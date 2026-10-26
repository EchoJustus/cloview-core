;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.store
  "A small re-frame-inspired application core, shared verbatim by every host.

  Model
  - Handlers are code, so they live in one global registry (like re-frame):
      (reg-event-db :app/set-name (fn [db [_ s]] (assoc db :name s)))
      (reg-event-fx :app/load [(inject-cofx :now)] (fn [{:keys [db now]} [_ id]] {...}))
      (reg-fx :http (fn [value {:keys [dispatch!]}] ...))   ; host effect runners
      (reg-cofx :now (fn [cofx] (assoc cofx :now ...)))
      (reg-sub :greeting (fn [db] (:greeting db)))
  - State lives in a store value created per app (tests create their own):
      (def store (create-store {:db {:name \"\"}}))
      (dispatch! store [:app/set-name \"Ada\"])
  - Views stay pure functions of db; derived values use (sub db :id & args), memoized
    on db identity.

  Dispatch is synchronous and queued: an event dispatched on the UI thread is processed
  to completion before dispatch! returns, and events dispatched while processing
  (by the :dispatch effect or by effect runners) are appended to the same queue. That
  keeps controlled text inputs exact: a keystroke's state change is applied before the
  host renders again.

  Threading: dispatch! may be called from any thread. Off the UI thread it is
  marshalled with the store's executor {:ui-thread? (fn []) :post! (fn [f])}; the host
  app modules install JavaFX/WPF executors, ClojureDart keeps the default (one isolate).

  Pure .cljc; the only reader conditional is the catch-all exception class."
  )

;; ---------------------------------------------------------------------------
;; Registry

(def ^:private empty-registry {:event {} :fx {} :cofx {} :sub {}})

(defonce ^:private registry (atom empty-registry))

(defn- register! [kind id v]
  (swap! registry assoc-in [kind id] v)
  id)

(defn clear-registry!
  "Remove registered handlers (all kinds, or only `kind`: :event :fx :cofx :sub)."
  ([] (reset! registry empty-registry) nil)
  ([kind] (swap! registry assoc kind {}) nil))

(defn registered?
  [kind id]
  (contains? (get @registry kind) id))

(defn handler-ids
  "Registered ids of `kind`."
  [kind]
  (set (keys (get @registry kind))))

;; ---------------------------------------------------------------------------
;; Interceptors
;;
;; An interceptor is a map {:id kw :before (fn [ctx] ctx) :after (fn [ctx] ctx)}.
;; ctx = {:coeffects {:db db :event event ...} :effects {...}}

(defn ->interceptor
  [{:keys [id before after]}]
  {:id id :before before :after after})

(defn inject-cofx
  "Interceptor adding the coeffect registered under `id` (optionally with `arg`)."
  ([id]
   (->interceptor
    {:id id
     :before (fn [ctx]
               (if-some [f (get-in @registry [:cofx id])]
                 (update ctx :coeffects f)
                 (throw (ex-info (str "No coeffect registered for " id) {:cofx id}))))}))
  ([id arg]
   (->interceptor
    {:id id
     :before (fn [ctx]
               (if-some [f (get-in @registry [:cofx id])]
                 (update ctx :coeffects f arg)
                 (throw (ex-info (str "No coeffect registered for " id) {:cofx id}))))})))

(defn path
  "Interceptor focusing the handler's db on (get-in db ks); the handler's resulting
  :db is written back at that path."
  [& ks]
  (let [ks (vec ks)]
    (->interceptor
     {:id :path
      :before (fn [ctx]
                (let [db (get-in ctx [:coeffects :db])]
                  (-> ctx
                      (update ::db-stack (fnil conj []) db)
                      (assoc-in [:coeffects :db] (get-in db ks)))))
      :after (fn [ctx]
               (let [stack (::db-stack ctx)
                     original (peek stack)
                     ctx (-> ctx
                             (assoc ::db-stack (pop stack))
                             (assoc-in [:coeffects :db] original))]
                 (if (contains? (:effects ctx) :db)
                   (assoc-in ctx [:effects :db] (assoc-in original ks (get-in ctx [:effects :db])))
                   ctx)))})))

(defn after
  "Interceptor running (f db event) for side-effect-free checks/logging after the
  handler (its return value is ignored)."
  [f]
  (->interceptor
   {:id :after
    :after (fn [ctx]
             (let [db (if (contains? (:effects ctx) :db)
                        (get-in ctx [:effects :db])
                        (get-in ctx [:coeffects :db]))]
               (f db (get-in ctx [:coeffects :event]))
               ctx))}))

;; ---------------------------------------------------------------------------
;; Registration API

(defn reg-event-db
  "Register (handler db event) -> new-db."
  ([id handler] (reg-event-db id [] handler))
  ([id interceptors handler]
   (register! :event id {:kind :db :f handler :interceptors (vec interceptors)})))

(defn reg-event-fx
  "Register (handler coeffects event) -> effects map. coeffects contains :db, :event and
  anything added by `inject-cofx` interceptors."
  ([id handler] (reg-event-fx id [] handler))
  ([id interceptors handler]
   (register! :event id {:kind :fx :f handler :interceptors (vec interceptors)})))

(defn reg-fx
  "Register an effect runner (runner value ctx); ctx = {:dispatch! f :store store}.
  Runners own all side effects (I/O, timers, threads) and report back by dispatching
  events. ctx's :dispatch! is safe to call from any thread."
  [id runner]
  (register! :fx id runner))

(defn reg-cofx
  "Register a coeffect: (f coeffects) or (f coeffects arg) -> coeffects."
  [id f]
  (register! :cofx id f))

(defn reg-sub
  "Register a derived value: (f db & args)."
  [id f]
  (register! :sub id f))

(defn register-handlers!
  "Register a whole feature from data — the portable way to register handlers.

    (def handlers
      {:events-db {:app/set-name (fn [db [_ s]] (assoc db :name s))}
       :events-fx {:app/load {:interceptors [(inject-cofx :now)]
                              :handler (fn [cofx event] {...})}}
       :fx {...} :cofx {...} :subs {:greeting (fn [db] (:greeting db))}})
    (defn register! [] (st/register-handlers! handlers))   ; called by every entry point

  ClojureDart compiles definitions only — top-level side effects such as a bare
  (reg-event-db ...) form are not emitted (Dart has no top-level statements and
  initializes top-level fields lazily). Keeping handlers in a def'd map and registering
  them from an explicit function called at startup works identically on JVM, CLR and
  Dart. Event values may be a handler fn or {:interceptors [...] :handler fn}.
  Idempotent (re-registering replaces); returns the set of registered event ids."
  [{:keys [events-db events-fx fx cofx subs]}]
  (doseq [[id v] events-db]
    (if (map? v)
      (reg-event-db id (:interceptors v []) (:handler v))
      (reg-event-db id v)))
  (doseq [[id v] events-fx]
    (if (map? v)
      (reg-event-fx id (:interceptors v []) (:handler v))
      (reg-event-fx id v)))
  (doseq [[id f] fx] (reg-fx id f))
  (doseq [[id f] cofx] (reg-cofx id f))
  (doseq [[id f] subs] (reg-sub id f))
  (into #{} (concat (keys events-db) (keys events-fx))))

;; ---------------------------------------------------------------------------
;; Pure event processing

(defn- run-handler [ctx {:keys [kind f]}]
  (let [{:keys [db event]} (:coeffects ctx)]
    (case kind
      :db (assoc ctx :effects {:db (f db event)})
      :fx (assoc ctx :effects (or (f (:coeffects ctx) event) {})))))

(defn handle
  "Run the interceptors and the handler registered for `event` against `db` and return
  the effects map (with :db when the handler produced one). Nothing is executed, so this
  is the function unit tests call."
  [db event]
  (let [id (first event)
        h (get-in @registry [:event id])]
    (when-not h
      (throw (ex-info (str "No event handler registered for " id) {:event event})))
    (let [ics (:interceptors h)
          ctx {:coeffects {:db db :event event} :effects {}}
          ctx (reduce (fn [c ic] (if-some [b (:before ic)] (b c) c)) ctx ics)
          ctx (run-handler ctx h)
          ctx (reduce (fn [c ic] (if-some [a (:after ic)] (a c) c)) ctx (reverse ics))]
      (:effects ctx))))

;; ---------------------------------------------------------------------------
;; Stores

(def default-executor
  "Synchronous executor: every thread is the UI thread (tests, ClojureDart)."
  {:ui-thread? (fn [] true)
   :post! (fn [f] (f))})

(defn- default-on-error [ex _info]
  (throw ex))

(defn create-store
  "Create a store. Options:
    :db        initial db (default {})
    :executor  {:ui-thread? (fn []) :post! (fn [f])} (default: synchronous)
    :on-error  (fn [exception {:event e :effect id}]) — default rethrows
    :on-event  (fn [event effects]) — trace hook, called after each handler"
  ([] (create-store {}))
  ([{:keys [db executor on-error on-event]}]
   {::store true
    :app-db (atom (if (nil? db) {} db))
    :queue (atom [])
    :draining? (atom false)
    :executor (atom (or executor default-executor))
    :on-error (atom (or on-error default-on-error))
    :on-event (atom on-event)}))

(defn store? [x] (and (map? x) (true? (::store x))))

(defn app-db
  "The store's db atom (hosts watch it to re-render)."
  [store]
  (:app-db store))

(defn db
  "Current db value."
  [store]
  @(:app-db store))

(defn set-executor! [store executor] (reset! (:executor store) executor) store)
(defn set-on-error! [store f] (reset! (:on-error store) f) store)
(defn set-on-event! [store f] (reset! (:on-event store) f) store)

(declare dispatch!)

(defn- effect-ctx [store]
  {:store store
   :dispatch! (fn [event] (dispatch! store event))})

(defn- enqueue! [store event]
  (when-not (vector? event)
    (throw (ex-info "Events must be vectors like [:app/event arg]" {:event event})))
  (swap! (:queue store) conj event))

(defn- apply-db! [store new-db]
  (let [a (:app-db store)]
    (when-not (identical? new-db @a)
      (reset! a new-db))))

(defn- run-effect! [store id value]
  (case id
    :db (apply-db! store value)
    :fx (doseq [entry value
                :when (some? entry)]
          (run-effect! store (first entry) (second entry)))
    :dispatch (when (some? value) (enqueue! store value))
    :dispatch-n (doseq [e value :when (some? e)] (enqueue! store e))
    (if-some [runner (get-in @registry [:fx id])]
      (runner value (effect-ctx store))
      (throw (ex-info (str "No effect runner registered for " id)
                      {:effect id :value value})))))

(defn- report-error! [store ex info]
  (@(:on-error store) ex info))

(defn- process-event! [store event]
  (let [effects (try
                  (handle (db store) event)
                  (catch #?(:cljd dynamic :clj Throwable :cljr System.Exception) ex
                    (report-error! store ex {:event event})
                    nil))]
    (when (some? effects)
      (when-some [trace @(:on-event store)] (trace event effects))
      (when (contains? effects :db)
        (apply-db! store (:db effects)))
      (doseq [[id value] effects
              :when (not= id :db)]
        (try
          (run-effect! store id value)
          (catch #?(:cljd dynamic :clj Throwable :cljr System.Exception) ex
            (report-error! store ex {:event event :effect id})))))))

(defn- pop-event! [store]
  (let [[old _] (swap-vals! (:queue store) (fn [q] (if (pos? (count q)) (subvec q 1) q)))]
    (when (pos? (count old)) (nth old 0))))

(defn- try-acquire!
  "Test-and-set on a boolean flag atom: true when this call flipped it from false to true.

  Deliberately not (compare-and-set! flag false true): ClojureCLR 1.12.x compares the
  expected value by reference, and every boxed `false` is a distinct object there, so that
  CAS never succeeds and nothing would ever be processed (found by the Stage 2-1 Windows
  run; fixed upstream after 1.12.6). swap-vals! compares against the object it just read,
  which is portable to Clojure/JVM, ClojureCLR and ClojureDart."
  [flag]
  (let [[old _] (swap-vals! flag (fn [_] true))]
    (not old)))

(defn- drain!
  "Process queued events until the queue is empty. Only one thread drains at a time
  (test-and-set on :draining?); the re-check after releasing the flag closes the
  lost-wakeup window when another thread enqueued just before the flag was released."
  [store]
  (loop []
    (when (try-acquire! (:draining? store))
      (try
        (loop []
          (when-some [e (pop-event! store)]
            (process-event! store e)
            (recur)))
        (finally
          (reset! (:draining? store) false)))
      (when (pos? (count @(:queue store)))
        (recur)))))

(defn dispatch!
  "Process `event` (a vector) synchronously on the UI thread. From any other thread the
  call is posted to the UI thread with the store's executor. Returns nil."
  [store event]
  (let [{:keys [ui-thread? post!]} @(:executor store)]
    (if (ui-thread?)
      (do (enqueue! store event)
          (drain! store))
      (post! (fn [] (dispatch! store event))))
    nil))

;; ---------------------------------------------------------------------------
;; Subscriptions (derived values, memoized on db identity)

(defonce ^:private sub-cache (atom {}))

(defn clear-sub-cache! [] (reset! sub-cache {}) nil)

(defn sub
  "Derived value registered with `reg-sub`, recomputed only when `db` is not identical?
  to the db it was last computed from (per id+args)."
  [db id & args]
  (let [k (if args (into [id] args) [id])
        compute (fn []
                  (let [f (or (get-in @registry [:sub id])
                              (throw (ex-info (str "No subscription registered for " id)
                                              {:sub id})))
                        v (apply f db args)]
                    (swap! sub-cache assoc k [db v])
                    v))]
    (if-some [entry (get @sub-cache k)]
      (if (identical? (nth entry 0) db) (nth entry 1) (compute))
      (compute))))
