;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.reconcile
  "Keyed reconciler for imperative, mutable host UI trees (the CLR/WPF backend).

  cljfx and Flutter reconcile on their own; WPF does not, so cloview brings this small
  engine. It is host-agnostic: everything host-specific sits behind `HostOps`, and
  `mock-host` is a deterministic in-memory implementation so the whole diff algorithm
  is unit-tested headless on the JVM and on ClojureCLR.

  Per render pass (`render!`):
  1. identity — a child is reused when its sibling identity (:key, else position)
     and its tag match; otherwise the old element is destroyed and a new one created;
  2. props — only changed *diffable* props are sent to `patch-props!`
     (event handlers, parent-slot layout keys and :ui/native :host fns excluded);
  3. events — each element gets ONE stable `emit` fn at creation; new handler vectors
     are swapped into a side table, so changing a handler costs no host operation;
  4. controlled values — the controlled prop (text-input :value, checkbox :checked,
     select :value, tabs :selected, split-view :ratio) is written only when it differs
     from what the host currently shows (`:host-value`, updated by user events);
  5. re-entrancy — host events raised synchronously while the pass mutates the tree
     (e.g. WPF TextChanged after a programmatic Text write) are swallowed;
  6. children — after reconciling them, `replace-children!` is called only when the
     ordered child list changed, then `attach-child-slot!` for every child whose slot
     (index, grow factor, grid cell, parent gap/alignment) changed.

  The reconciler must be driven from the UI thread; the host app module coalesces
  db changes into at most one pass per dispatcher turn."
  (:require [cloview.core.tree :as t]))

;; ---------------------------------------------------------------------------
;; Host contract

(defprotocol HostOps
  (create-element [ops node emit]
    "Create the host element for canonical `node` ({:tag :key :props :children}).
    `emit` is a stable (fn [event-key payload]) the host must call from its native event
    handlers for the element's whole lifetime (attach native handlers here, once).
    Do not apply props here: `patch-props!` is called right after with all initial
    props. Returns an opaque host handle.")
  (patch-props! [ops handle tag changed prev]
    "Apply `changed` (prop -> new value; a removed prop maps to nil, meaning 'reset to
    the host default') to the element. `prev` is the previous diffable prop map. For
    :ui/native, `changed` also carries the latest :host fn map under :host.")
  (replace-children! [ops handle tag child-handles]
    "Make the element's ordered children exactly `child-handles`. Hosts must move
    existing children rather than recreate them (identity preserves focus, caret and
    scroll position).")
  (attach-child-slot! [ops parent-handle parent-tag child-handle slot]
    "Apply parent-relative layout to a child. slot = {:index :grow :layout :parent};
    :layout holds the child's layout keys (:grid/row ...), :parent the parent's layout
    props (:gap :align :justify :columns :rows :orientation ...). The child count is
    known from the preceding `replace-children!`.")
  (destroy-element! [ops handle tag]
    "Release host resources of an element that left the tree (called children-first,
    after it was detached by `replace-children!`)."))

(def parent-layout-keys
  "Parent props copied into every child slot."
  [:gap :align :justify :columns :rows :orientation :direction])

(defn preserve-caret
  "Caret position after an external (programmatic) value write: the previous caret,
  clamped to the new text length."
  [old-caret new-text]
  (min (or old-caret 0) (count (or new-text ""))))

;; ---------------------------------------------------------------------------
;; Reconciler state

(defn create-reconciler
  "Reconciler bound to `ops`. Options:
    :dispatch!  (fn [event-vector]) — usually (partial store/dispatch! store)"
  [ops {:keys [dispatch!]}]
  {::reconciler true
   :ops ops
   :dispatch! (or dispatch! (fn [_] nil))
   :applying? (atom false)
   :next-id (atom 0)
   :root (atom nil)
   :passes (atom 0)})

(defn- next-id! [rctx] (swap! (:next-id rctx) inc))

(defn- make-emitter [rctx cell tag]
  (let [ctl (t/controlled tag)]
    (fn emit [event-key payload]
      (let [c @cell]
        (when-not (:dead? c)
          (when (and ctl (= event-key (:event ctl)))
            (swap! cell assoc :host-value payload))
          (when-not @(:applying? rctx)
            (t/emit! (:dispatch! rctx)
                     (get-in c [:handlers event-key])
                     (t/payload-kind tag event-key)
                     payload)))))))

(defn- slot-of [parent-tag parent-props i node]
  {:index i
   :grow (t/effective-grow parent-tag node)
   :layout (select-keys (:props node) t/layout-keys)
   :parent (select-keys parent-props parent-layout-keys)})

(defn- child-identity [node i]
  (if (some? (:key node)) [::key (:key node)] [::index i]))

;; ---------------------------------------------------------------------------
;; Mount / update / unmount

(declare mount update-instance)

(defn- unmount [rctx inst]
  (doseq [c (:children inst)] (unmount rctx c))
  (swap! (:cell inst) assoc :dead? true)
  (destroy-element! (:ops rctx) (:handle inst) (:tag inst)))

(defn- reconcile-children [rctx parent-tag parent-props parent-handle old-kids new-nodes]
  (let [ops (:ops rctx)
        old-by-id (reduce (fn [m [i inst]]
                            (let [id (:identity inst)]
                              (if (contains? m id) m (assoc m id inst))))
                          {}
                          (map-indexed vector old-kids))
        used (atom #{})
        new-kids (vec (map-indexed
                       (fn [i node]
                         (let [id (child-identity node i)
                               old (get old-by-id id)]
                           (if (and old
                                    (= (:tag old) (:tag node))
                                    (not (contains? @used id)))
                             (do (swap! used conj id)
                                 (assoc (update-instance rctx old node) :identity id))
                             (assoc (mount rctx node) :identity id))))
                       new-nodes))
        kept (into #{} (map :id) new-kids)
        stale (remove #(contains? kept (:id %)) old-kids)]
    (when (not= (mapv :id old-kids) (mapv :id new-kids))
      (replace-children! ops parent-handle parent-tag (mapv :handle new-kids)))
    (doseq [s stale] (unmount rctx s))
    (vec (map-indexed
          (fn [i [kid node]]
            (let [slot (slot-of parent-tag parent-props i node)]
              (if (= slot (:slot kid))
                kid
                (do (attach-child-slot! ops parent-handle parent-tag (:handle kid) slot)
                    (assoc kid :slot slot)))))
          (map vector new-kids new-nodes)))))

(defn- mount [rctx node]
  (let [{:keys [tag props children]} node
        ops (:ops rctx)
        ctl (t/controlled tag)
        dprops (t/diffable-props tag props)
        cell (atom {:handlers (t/event-props props)
                    :host-value (if (and ctl (contains? dprops (:prop ctl)))
                                  (get dprops (:prop ctl))
                                  (:default ctl))})
        handle (create-element ops node (make-emitter rctx cell tag))
        initial (if (= tag :ui/native) (assoc dprops :host (:host props)) dprops)]
    (when (seq initial)
      (patch-props! ops handle tag initial {}))
    (let [kids (reconcile-children rctx tag props handle [] children)]
      {:id (next-id! rctx)
       :tag tag
       :key (:key node)
       :props dprops
       :handle handle
       :cell cell
       :children kids})))

(defn- diff-props
  "Changed diffable props, with the controlled-value guard applied."
  [tag old new cell]
  (let [cprop (:prop (t/controlled tag))
        changed (reduce-kv (fn [m k v]
                             (if (and (not= k cprop) (not= v (get old k ::absent)))
                               (assoc m k v)
                               m))
                           {}
                           new)
        changed (reduce-kv (fn [m k _]
                             (if (and (not= k cprop) (not (contains? new k)))
                               (assoc m k nil)
                               m))
                           changed
                           old)]
    (if (and cprop (contains? new cprop) (not= (get new cprop) (:host-value @cell)))
      (assoc changed cprop (get new cprop))
      changed)))

(defn- update-instance [rctx inst node]
  (let [{:keys [tag props children]} node
        ops (:ops rctx)
        cell (:cell inst)
        dprops (t/diffable-props tag props)
        changed (diff-props tag (:props inst) dprops cell)
        cprop (:prop (t/controlled tag))]
    ;; trampolines: new handler vectors cost no host operation
    (swap! cell assoc :handlers (t/event-props props))
    (when (seq changed)
      (patch-props! ops (:handle inst) tag
                    (if (= tag :ui/native) (assoc changed :host (:host props)) changed)
                    (:props inst))
      (when (and cprop (contains? changed cprop))
        (swap! cell assoc :host-value (get changed cprop))))
    (assoc inst
           :key (:key node)
           :props dprops
           :children (reconcile-children rctx tag props (:handle inst) (:children inst) children))))

;; ---------------------------------------------------------------------------
;; Public API

(defn render!
  "Reconcile the host tree with `view` (hiccup or a normalized node) and return the root
  host handle. The handle only changes when the root's tag or key changes; the caller
  must then re-attach it (e.g. set Window.Content). Passing nil unmounts everything."
  [rctx view]
  (let [node (t/normalize view)
        old @(:root rctx)
        applying? (:applying? rctx)]
    (reset! applying? true)
    (try
      (let [new (cond
                  (nil? node) (do (when old (unmount rctx old)) nil)
                  (nil? old) (mount rctx node)
                  (and (= (:tag old) (:tag node)) (= (:key old) (:key node)))
                  (update-instance rctx old node)
                  :else (let [m (mount rctx node)]
                          (unmount rctx old)
                          m))]
        (reset! (:root rctx) new)
        (swap! (:passes rctx) inc)
        (:handle new))
      (finally
        (reset! applying? false)))))

(defn unmount!
  "Destroy the whole host tree."
  [rctx]
  (render! rctx nil))

(defn root-handle [rctx] (:handle @(:root rctx)))

(defn passes
  "Number of completed render passes."
  [rctx]
  @(:passes rctx))

(defn instance-seq
  "Pre-order seq of live instances (maps with :tag :key :props :handle :children)."
  [rctx]
  (when-some [root @(:root rctx)]
    (tree-seq (fn [i] (seq (:children i))) :children root)))

(defn handle-by-test-id
  "Host handle of the element whose :test-id prop is `id`, or nil."
  [rctx id]
  (some (fn [i] (when (= id (get-in i [:props :test-id])) (:handle i))) (instance-seq rctx)))

(defn handles-by-tag
  "Host handles of all live elements with `tag`, in tree order."
  [rctx tag]
  (into [] (comp (filter #(= tag (:tag %))) (map :handle)) (instance-seq rctx)))

(defn counting-ops
  "Wrap `ops` so every call is counted into `counter` (an atom of {op-kw n})."
  [ops counter]
  (let [tick! (fn [k] (swap! counter update k (fnil inc 0)))]
    (reify HostOps
      (create-element [_ node emit] (tick! :create) (create-element ops node emit))
      (patch-props! [_ h tag changed prev] (tick! :patch) (patch-props! ops h tag changed prev))
      (replace-children! [_ h tag kids] (tick! :children) (replace-children! ops h tag kids))
      (attach-child-slot! [_ p ptag c slot] (tick! :slot) (attach-child-slot! ops p ptag c slot))
      (destroy-element! [_ h tag] (tick! :destroy) (destroy-element! ops h tag)))))

;; ---------------------------------------------------------------------------
;; MockHostOps: deterministic recorder for headless tests

(defn- loggable [changed]
  (dissoc changed :host))

(defn mock-host
  "In-memory host for headless tests. Returns {:ops HostOps :log (atom []) :elements
  (atom {})}. Elements are integers in creation order, so op logs are deterministic:

    [:create id tag] [:patch id changed] [:children id [child-ids]]
    [:slot parent-id child-id index] [:destroy id tag] [:native-update id props]

  Options:
    :echo-controlled? (default true) — like a WPF TextBox, the mock synchronously raises
      the controlled change event when the program writes the controlled prop, so tests
      can prove the reconciler's re-entrancy guard swallows it.
  Text inputs keep :text and :caret; external writes preserve the caret with
  `preserve-caret`. :ui/native elements call the :host :create/:update/:dispose fns."
  ([] (mock-host {}))
  ([{:keys [echo-controlled?] :or {echo-controlled? true}}]
   (let [log (atom [])
         elements (atom {})
         next-id (atom 0)
         log! (fn [entry] (swap! log conj entry))
         ops (reify HostOps
               (create-element [_ node emit]
                 (let [id (swap! next-id inc)
                       tag (:tag node)]
                   (swap! elements assoc id {:id id :tag tag :props {} :children []
                                             :emit emit :text "" :caret 0})
                   (log! [:create id tag])
                   (when (= :ui/native tag)
                     (when-some [create (get-in node [:props :host :create])]
                       (swap! elements assoc-in [id :native] (create))))
                   id))
               (patch-props! [_ id tag changed _prev]
                 (log! [:patch id (loggable changed)])
                 (swap! elements update-in [id :props]
                        (fn [p] (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
                                           p
                                           (loggable changed))))
                 (when (= :ui/native tag)
                   (when (contains? changed :host)
                     (swap! elements assoc-in [id :host] (:host changed)))
                   (when (contains? changed :props)
                     (let [e (get @elements id)]
                       (when-some [update (get-in e [:host :update])]
                         (update (:native e) (:props changed)))
                       (log! [:native-update id (:props changed)]))))
                 (when-some [{:keys [prop event]} (t/controlled tag)]
                   (when (contains? changed prop)
                     (let [v (get changed prop)]
                       (when (= tag :ui/text-input)
                         (swap! elements update id
                                (fn [e] (assoc e :text v :caret (preserve-caret (:caret e) v)))))
                       (when echo-controlled?
                         ((get-in @elements [id :emit]) event v))))))
               (replace-children! [_ id _tag kids]
                 (log! [:children id kids])
                 (swap! elements assoc-in [id :children] kids))
               (attach-child-slot! [_ pid _ptag cid slot]
                 (log! [:slot pid cid (:index slot)])
                 (swap! elements assoc-in [cid :slot] slot))
               (destroy-element! [_ id tag]
                 (log! [:destroy id tag])
                 (let [e (get @elements id)]
                   (when-some [dispose (get-in e [:host :dispose])]
                     (dispose (:native e))))
                 (swap! elements dissoc id)))]
     {:ops ops :log log :elements elements})))

(defn op-log [mock] @(:log mock))
(defn clear-log! [mock] (reset! (:log mock) []) nil)
(defn element [mock id] (get @(:elements mock) id))

(defn mock-type!
  "Simulate the user editing mock text input `id`: the host text and caret change, then
  the host raises :on-change with the new text."
  [mock id text caret]
  (swap! (:elements mock) update id assoc :text text :caret caret)
  ((:emit (element mock id)) :on-change text))

(defn mock-fire!
  "Simulate the host raising `event-key` with `payload` on element `id`."
  [mock id event-key payload]
  ((:emit (element mock id)) event-key payload))
