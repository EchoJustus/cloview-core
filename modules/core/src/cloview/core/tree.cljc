;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.tree
  "Canonical view trees.

  A view is a plain function `state -> hiccup`. `normalize` turns hiccup into the
  canonical node map that every host adapter consumes:

      {:tag      :ui/column   ; namespaced keyword (see `v1-tags`)
       :key      nil          ; sibling identity, lifted out of the props map
       :props    {...}        ; every other prop (events are data vectors)
       :children [...]}       ; normalized child nodes

  Rules (identical on Clojure/JVM, ClojureDart and ClojureCLR):
  - `nil`/`false`/`true` children are skipped (so `(when x ...)` works);
  - seqs (`for`, `map`, lists) are flattened into the parent's children;
  - strings and numbers become `:ui/text` nodes;
  - keyword heads registered with `reg-component!` are expanded here, so hosts never
    see composite components;
  - function heads `[f arg1 arg2]` are called with the args (Reagent style); their key
    comes from vector metadata `^{:key k}`.

  This namespace is pure .cljc: no reader conditionals, no host interop."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; V1 vocabulary (data, so adapters and tests can check coverage)

(def v1-tags
  "The canonical tags (V1 of Stage 2-1, plus :ui/wrap and :ui/toggle added in Stage 2-2
  for multi-select filter chips).

  :children   — child arity contract (:none :one :two :many)
  :events     — event prop -> payload kind appended to the event vector
                (:none appends nothing; :string, :boolean, :value, :ratio append one value)
  :controlled — the {:prop :event :default} pair of a controlled widget: the host shows
                `prop`, reports user edits through `event`, and adapters must never
                write `prop` back when it already equals what the host displays."
  {:ui/column     {:kind :layout :children :many}
   :ui/row        {:kind :layout :children :many}
   :ui/grid       {:kind :layout :children :many}
   :ui/wrap       {:kind :layout :children :many}   ; flow layout: :gap, :run-gap
   :ui/scroll     {:kind :layout :children :one}
   :ui/box        {:kind :layout :children :one}
   :ui/align      {:kind :layout :children :one}
   :ui/spacer     {:kind :layout :children :none}
   :ui/text       {:kind :control :children :none}
   :ui/button     {:kind :control :children :none
                   :events {:on-press :none}}
   :ui/text-input {:kind :control :children :none
                   :events {:on-change :string :on-submit :string}
                   :controlled {:prop :value :event :on-change :default ""}}
   :ui/checkbox   {:kind :control :children :none
                   :events {:on-toggle :boolean}
                   :controlled {:prop :checked :event :on-toggle :default false}}
   :ui/switch     {:kind :control :children :none
                   :events {:on-toggle :boolean}
                   :controlled {:prop :checked :event :on-toggle :default false}}
   :ui/toggle     {:kind :control :children :none        ; toggle button / filter chip
                   :events {:on-toggle :boolean}
                   :controlled {:prop :checked :event :on-toggle :default false}}
   :ui/select     {:kind :control :children :none
                   :events {:on-select :value}
                   :controlled {:prop :value :event :on-select :default nil}}
   :ui/image      {:kind :control :children :none}
   :ui/split-view {:kind :composite :children :two
                   :events {:on-resize :ratio}
                   :controlled {:prop :ratio :event :on-resize :default 0.5}}
   :ui/tabs       {:kind :composite :children :many
                   :events {:on-select :value}
                   :controlled {:prop :selected :event :on-select :default nil}}
   :ui/tab        {:kind :composite :children :one}
   :ui/modal      {:kind :composite :children :one
                   :events {:on-dismiss :none}}
   :ui/native     {:kind :native :children :none}})

(def layout-keys
  "Child props that describe the child's slot in its parent (applied by the parent's
  adapter, never by the child itself)."
  #{:grow :grid/row :grid/col :grid/row-span :grid/col-span})

(defn spec
  "V1 spec map for `tag` (nil for host-specific or unknown tags)."
  [tag]
  (get v1-tags tag))

(defn controlled
  "{:prop :event :default} for controlled tags, else nil."
  [tag]
  (:controlled (spec tag)))

(defn event-key?
  "True for event props: `:on-*` keywords."
  [k]
  (and (keyword? k) (str/starts-with? (name k) "on-")))

(defn payload-kind
  "Payload kind for `event-key` on `tag`. Unknown events default to :value."
  [tag event-key]
  (get-in v1-tags [tag :events event-key] :value))

;; ---------------------------------------------------------------------------
;; Events

(defn event-vector
  "Event vector `ev` with the host payload appended according to `kind`."
  [ev kind payload]
  (if (= kind :none) ev (conj ev payload)))

(defn emit!
  "Deliver a host event. `handler` is normally a data vector (dispatched with the
  payload appended); a fn is accepted as an escape hatch for host-specific code."
  [dispatch! handler kind payload]
  (cond
    (nil? handler) nil
    (vector? handler) (dispatch! (event-vector handler kind payload))
    (fn? handler) (if (= kind :none) (handler) (handler payload))
    :else (throw (ex-info "Event handlers must be data vectors (or fns)"
                          {:handler handler}))))

;; ---------------------------------------------------------------------------
;; Composite components

(defonce ^:private components (atom {}))

(defn reg-component!
  "Register a composite component under keyword `tag`: (f props children) -> hiccup.
  `props` excludes :key; `children` is the raw (un-normalized) child hiccup. The key
  given at the use site is carried over to the expansion's root node."
  [tag f]
  (swap! components assoc tag f)
  tag)

(defn component
  "Registered composite component fn for `tag`, or nil."
  [tag]
  (get @components tag))

;; ---------------------------------------------------------------------------
;; Normalization

(declare normalize)

(defn node?
  "True for an already-normalized node map."
  [x]
  (and (map? x) (keyword? (:tag x)) (contains? x :children)))

(defn- props-map? [x]
  (and (map? x) (not (node? x))))

(defn text-node
  "Canonical text node for a string/number."
  [x]
  {:tag :ui/text :key nil :props {:text (str x)} :children []})

(defn- with-key [node k]
  (if (and node (some? k)) (assoc node :key k) node))

(defn- flatten-children [xs]
  (persistent!
   (reduce (fn add [acc x]
             (cond
               (or (nil? x) (true? x) (false? x)) acc
               (node? x) (conj! acc x)
               (vector? x) (if-some [n (normalize x)] (conj! acc n) acc)
               (or (string? x) (number? x)) (conj! acc (text-node x))
               (seq? x) (reduce add acc x)
               :else (throw (ex-info "Invalid child in view tree" {:child x}))))
           (transient [])
           xs)))

(defn- inline-text [children]
  ;; [:ui/text {:style ...} "Hello, " name] -> :text "Hello, Ada"
  (let [parts (reduce (fn add [acc x]
                        (cond
                          (or (string? x) (number? x)) (conj acc (str x))
                          (seq? x) (reduce add acc x)
                          :else acc))
                      []
                      children)]
    (when (seq parts) (apply str parts))))

(defn- normalize-element [v]
  (let [head (first v)
        has-props? (props-map? (second v))
        props (if has-props? (second v) {})
        children (if has-props? (subvec v 2) (subvec v 1))]
    (cond
      (keyword? head)
      (if-some [f (component head)]
        (with-key (normalize (f (dissoc props :key) children)) (:key props))
        (let [props (if (and (= head :ui/text) (not (contains? props :text)))
                      (if-some [s (inline-text children)] (assoc props :text s) props)
                      props)
              children (if (= head :ui/text) [] (flatten-children children))]
          {:tag head
           :key (:key props)
           :props (dissoc props :key)
           :children children}))

      (fn? head)
      (with-key (normalize (apply head (rest v))) (:key (meta v)))

      :else
      (throw (ex-info "Hiccup element must start with a keyword or a fn" {:element v})))))

(defn normalize
  "Hiccup (or an already-normalized node) -> canonical node map, or nil."
  [x]
  (cond
    (nil? x) nil
    (node? x) x
    (vector? x) (if (empty? x)
                  (throw (ex-info "Empty hiccup vector" {}))
                  (normalize-element x))
    (or (string? x) (number? x)) (text-node x)
    :else (throw (ex-info "A view must return hiccup (vector), a string or nil"
                          {:value x}))))

;; ---------------------------------------------------------------------------
;; Accessors and helpers shared by adapters

(defn tag [node] (:tag node))
(defn props [node] (:props node))
(defn children [node] (:children node))
(defn node-key [node] (:key node))

(defn event-props
  "Map of event-key -> handler for `node`'s props."
  [props]
  (reduce-kv (fn [m k v] (if (event-key? k) (assoc m k v) m)) {} props))

(defn diffable-props
  "Props a host element must reflect, i.e. without event handlers (delivered through
  stable trampolines), parent-slot layout keys and, for :ui/native, the :host fns
  (closures are recreated on every render and must not trigger updates)."
  [tag props]
  (let [p (reduce-kv (fn [m k v]
                       (if (or (event-key? k) (contains? layout-keys k)) m (assoc m k v)))
                     {}
                     props)]
    (if (= tag :ui/native) (dissoc p :host) p)))

(def ^:private width-hungry
  ;; children that need a bounded width when placed in a row (a wrap must know where
  ;; to break its runs)
  #{:ui/text-input :ui/select :ui/column :ui/grid :ui/wrap :ui/scroll :ui/tabs :ui/split-view})

(def ^:private height-hungry
  ;; children that need a bounded height when placed in a column (tabs host pages, which
  ;; are usually scroll views or split views themselves)
  #{:ui/scroll :ui/split-view :ui/tabs})

(def ^:private decorator-tags
  ;; single-child wrappers whose layout needs are those of their content
  #{:ui/box :ui/align})

(defn- layout-chain
  "`node`, followed by the content of each decorator (:ui/box, :ui/align) it wraps."
  [node]
  (take-while some? (iterate (fn [n] (when (contains? decorator-tags (:tag n)) (first (:children n))))
                             node)))

(defn effective-grow
  "Main-axis grow factor of `child` inside a parent with tag `parent-tag` (0 = keep the
  intrinsic size). Applied identically on every host so layouts stay equivalent:
  - explicit :grow wins (true = 1);
  - :ui/spacer grows by 1;
  - inside a :ui/row, inputs, selects and containers (column, grid, wrap, scroll,
    tabs, split-view) without a :style :width grow by 1;
  - inside a :ui/column, vertical scroll, split-view and tabs without a :style :height
    grow by 1 (a horizontal scroll keeps its content's height);
  - a :ui/box or :ui/align counts as its content (a padded box around a column grows
    like the column), unless one of them sets the size.
  The hungry rules exist because Flutter requires bounded constraints for those widgets
  (an unbounded TextField, scroll view or stretching column is a hard layout error);
  JavaFX and WPF follow the same rule so the three hosts agree."
  [parent-tag child]
  (let [g (get-in child [:props :grow])
        chain (layout-chain child)
        inner (last chain)
        tag (:tag inner)
        sized? (fn [dim] (some #(some? (get-in % [:props :style dim])) chain))]
    (cond
      (number? g) g
      (true? g) 1
      (false? g) 0
      (= :ui/spacer (:tag child)) 1
      (and (= parent-tag :ui/row)
           (contains? width-hungry tag)
           (not (sized? :width))) 1
      (and (= parent-tag :ui/column)
           (contains? height-hungry tag)
           ;; a horizontal scroll view takes its content's height: it needs no bounded
           ;; height (and must not flex inside a column that is itself in a vertical scroll)
           (not (and (= tag :ui/scroll) (= :horizontal (get-in inner [:props :direction]))))
           (not (sized? :height))) 1
      :else 0)))

(defn default-align
  "Cross-axis alignment when :align is absent: columns stretch their children to the
  full width; rows center them vertically (a stretching row inside a column would need
  an unbounded height on Flutter)."
  [tag]
  (if (= tag :ui/row) :center :stretch))

(defn node-seq
  "Pre-order seq of all nodes in `node`."
  [node]
  (when node
    (tree-seq (fn [n] (seq (:children n))) :children node)))

(defn find-by-test-id
  "First node whose :test-id prop equals `id`, or nil."
  [node id]
  (some (fn [n] (when (= id (get-in n [:props :test-id])) n)) (node-seq node)))

;; ---------------------------------------------------------------------------
;; Static checks (dev/test aid; adapters never call this on the hot path)

(defn- arity-problem [{:keys [tag children]}]
  (let [n (count children)]
    (case (:children (spec tag))
      :none (when (pos? n) (str tag " takes no children, got " n))
      :one (when-not (= 1 n) (str tag " takes exactly one child, got " n))
      :two (when-not (= 2 n) (str tag " takes exactly two children, got " n))
      nil)))

(defn problems
  "Static checks on a normalized tree. Returns a vector of {:path :problem} maps:
  child arity per `v1-tags`, duplicate sibling keys, malformed event handlers and
  :ui/tab outside :ui/tabs."
  [root]
  (let [out (volatile! [])
        report! (fn [path msg] (vswap! out conj {:path path :problem msg}))]
    (letfn [(check [node path parent-tag]
              (when-some [msg (arity-problem node)] (report! path msg))
              (when (and (= :ui/tab (:tag node)) (not= :ui/tabs parent-tag))
                (report! path ":ui/tab must be a direct child of :ui/tabs"))
              (doseq [[k h] (event-props (:props node))]
                (when-not (or (nil? h) (vector? h) (fn? h))
                  (report! path (str "event " k " must be a data vector, got " (pr-str h)))))
              (let [ks (keep :key (:children node))
                    dups (keep (fn [[k c]] (when (> c 1) k)) (frequencies ks))]
                (doseq [k dups]
                  (report! path (str "duplicate sibling key " (pr-str k)))))
              (doseq [[i c] (map-indexed vector (:children node))]
                (check c (conj path i) (:tag node))))]
      (when root (check root [] nil)))
    @out))
