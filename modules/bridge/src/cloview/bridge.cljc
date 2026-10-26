;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge
  "Opt-in module (Mode B in docs/POLYGLOT_BACKEND_ARCHITECTURE.md): a cloview GUI talking
  to a backend process written in Java, C#, Dart — any language that can read stdin and
  write stdout — through effects. Default apps (Mode A, logic in .cljc in the same
  process) never load this module.

    (bridge/install! {:manifests [(manifest/parse (slurp \"backend/backend-manifest.json\"))]
                      :vars {:appDir \".\"}
                      :dispatch! (fn [ev] (st/dispatch! store ev))
                      :on-status [:backend/status]})

  Effects
    :backend/call   {:backend :engine :method \"wm/remove\" :params {..}
                     :id :job-42            ; optional, to cancel it
                     :timeout-ms 600000     ; optional, default: manifest request_timeout_ms
                     :on-success [:job/done]       ; (conj ev result)
                     :on-failure [:job/failed]     ; (conj ev failure)
                     :on-progress [:job/progress]} ; (conj ev value) for each $/progress
    :backend/stream like :backend/call, plus :on-item [:scan/item] for each $/stream item;
                    :on-success receives the final result
    :backend/cancel {:backend :engine :id :job-42}
    :backend/start  {:backend :engine}      ; for manifests with \"start\": \"on-demand\"
    :backend/stop   {:backend :engine}
  :backend defaults to the only (or first) installed backend. Without :on-success /
  :on-failure, results go to [:backend/response {:backend :method :id :result}] and
  failures to [:backend/error {.. failure ..}] — register handlers for those if used.

  failure = {:error \"message\" :code n :reason r :data d}; r is :rpc (an error the
  backend returned), :timeout, :cancelled, :crashed, :not-running, :too-large (reserved),
  :unsupported (no process transport on this host yet) or :manifest.

  Status events: with :on-status, every change is dispatched as
  (conj ev backend-id status info), status one of :starting :ready :stopping :stopped
  :crashed :restarting."
  (:require [cloview.bridge.client :as client]
            [cloview.bridge.manifest :as manifest]
            [cloview.bridge.process :as process]
            [cloview.bridge.protocol :as p]
            [cloview.core.store :as st]
            [cloview.fx.timer :as timer]))

;; backend id (keyword) -> {:manifest m :opts install-opts :client c :transport t :restarts [ms ..]}
(defonce ^:private registry (atom {}))

(defn- backend-key [id] (if (keyword? id) id (keyword (str id))))

;; manifest ids in install order: map key order is not insertion order on every host
;; (ClojureDart has no array maps; JVM maps above 8 entries hash too)
(defonce ^:private backend-order (atom []))

(defn- default-backend []
  (first @backend-order))

(defn status
  "Status of a backend (:idle :starting :ready :stopping :stopped :crashed), nil if unknown."
  ([] (status (default-backend)))
  ([id] (some-> (get-in @registry [(backend-key id) :client]) client/status)))

(defn backend-info
  "What the backend answered to $/initialize (name, version, methods), or nil."
  ([] (backend-info (default-backend)))
  ([id] (some-> (get-in @registry [(backend-key id) :client]) client/backend-info)))

(defn- dispatch-status! [id status info]
  (let [{:keys [dispatch! on-status]} (get-in @registry [id :opts])]
    (when (and dispatch! on-status)
      (dispatch! (conj on-status id status info)))))

(declare start-backend!)

(defn- take-kill-check!
  "Remove and return the cancel fn of the pending stop! kill check of `id`, if it was
  scheduled for client `c` (a late exit of an earlier process leaves a newer check alone)."
  [id c]
  (let [mine? (fn [r] (identical? c (get-in r [id :kill-check :client])))
        [old _] (swap-vals! registry (fn [r] (if (mine? r) (update r id dissoc :kill-check) r)))]
    (when (mine? old) (get-in old [id :kill-check :cancel]))))

(defn- take-restart!
  "Remove and return the cancel fn of a scheduled restart of `id`, if any."
  [id]
  (let [[old _] (swap-vals! registry (fn [r] (if (get-in r [id :restart-timer]) (update r id dissoc :restart-timer) r)))]
    (get-in old [id :restart-timer])))

(defn- maybe-restart! [id info]
  (let [{:keys [manifest]} (get @registry id)
        {:keys [policy max_restarts window_ms]} (:restart manifest)
        now (timer/now-ms)
        recent (filterv #(< (- now %) window_ms) (get-in @registry [id :restarts]))]
    (when (and (= "on-crash" policy) (< (count recent) max_restarts))
      (swap! registry assoc-in [id :restarts] (conj recent now))
      (dispatch-status! id :restarting (assoc info :attempt (inc (count recent))))
      ;; 0.5 s, 1 s, 2 s, ... capped at 10 s; stop! cancels it
      (let [cancel (timer/schedule! (min 10000 (* 500 (bit-shift-left 1 (count recent))))
                                    (fn []
                                      (take-restart! id)
                                      (when (= :crashed (status id)) (start-backend! id))))]
        (swap! registry (fn [r] (if (contains? r id) (assoc-in r [id :restart-timer] cancel) r)))))))

(defn- start-backend!
  "(Re)start backend `id`: spawn its process, create the client, send $/initialize."
  [id]
  (let [{:keys [manifest opts]} (get @registry id)
        {:keys [vars platform transport-fn app-info keywordize? on-log]} opts
        t-atom (atom nil)
        c (client/create {:send! (fn [line] (when-some [t @t-atom] ((:send! t) line)))
                          :close! (fn [] (when-some [t @t-atom] ((:close! t))))}
                         {:schedule! timer/schedule!
                          :request-timeout-ms (:request_timeout_ms manifest)
                          :startup-timeout-ms (:startup_timeout_ms manifest)
                          :shutdown-timeout-ms (:shutdown_timeout_ms manifest)
                          :keywordize? (if (some? keywordize?) keywordize? true)
                          :on-status (fn [s info] (dispatch-status! id s info))
                          :on-notification (fn [method params]
                                             (when (and on-log (= method p/log-method)) (on-log id params)))
                          :on-invalid (fn [problem line]
                                        (when on-log (on-log id {:level "warn" :message (str problem ": " line)})))})]
    (swap! registry update id assoc :client c :transport nil)
    (try
      (let [spec (manifest/launch-spec manifest (or platform (process/platform)) (or vars {}))
            t ((or transport-fn process/spawn)
               spec
               {:on-line (fn [line] (client/receive! c line))
                :on-stderr (fn [line] (when on-log (on-log id {:level "stderr" :message line})))
                :on-exit (fn [info]
                           ;; a pending kill check of stop! is moot once the process is gone
                           (when-some [cancel (take-kill-check! id c)] (cancel))
                           (let [crashed? (not (contains? #{:stopping :stopped} (client/status c)))]
                             (client/closed! c info)
                             (when crashed? (maybe-restart! id info))))
                :max-line-chars (:max_message_bytes manifest)
                :on-too-large (fn [n] (when on-log (on-log id {:level "warn" :message (str "skipped a " n "-character line")})))})]
        (reset! t-atom t)
        (swap! registry assoc-in [id :transport] t)
        (client/start! c (merge {:platform (or platform (process/platform))} app-info)))
      (catch #?(:cljd dynamic :clj Throwable :cljr System.Exception) e
        (client/closed! c (merge {:error (or (ex-message e) (str e))}
                                 (select-keys (ex-data e) [:reason :command])))))
    c))

(defn- ensure-started! [id]
  (when (contains? #{nil :idle :stopped} (status id))
    (start-backend! id)))

(defn- failure-event [ev id method app-id failure]
  (if ev
    (conj ev failure)
    [:backend/error (assoc failure :backend id :method method :id app-id)]))

(defn- call!
  [{:keys [backend method params id timeout-ms on-success on-failure on-progress on-item] :as fx} dispatch!]
  (let [bid (if (some? backend) (backend-key backend) (default-backend))
        entry (get @registry bid)]
    (if (nil? entry)
      (dispatch! (failure-event on-failure bid method id
                                (p/failure :not-running (str "no backend " (pr-str backend) " installed"))))
      (do
        (when (= "on-demand" (get-in entry [:manifest :start])) (ensure-started! bid))
        (client/request! (get-in @registry [bid :client]) method params
                         (cond-> {:app-id id
                                  :on-result (fn [r]
                                               (dispatch! (if on-success
                                                            (conj on-success r)
                                                            [:backend/response {:backend bid :method method :id id :result r}])))
                                  :on-error (fn [f] (dispatch! (failure-event on-failure bid method id f)))
                                  :on-notify (fn [m ps]
                                               (cond
                                                 (and on-progress (= m p/progress-method)) (dispatch! (conj on-progress (:value ps)))
                                                 (and on-item (= m p/stream-method)) (dispatch! (conj on-item (:item ps)))
                                                 :else nil))}
                           (contains? fx :timeout-ms) (assoc :timeout-ms timeout-ms)))))))

(defn stop!
  "Ask backend `id` to shut down ($/shutdown, then stdin EOF); kills it if it is still
  alive after the manifest's shutdown_timeout_ms. `on-done` (optional) runs afterwards."
  ([id] (stop! id nil))
  ([id on-done]
   (let [id (backend-key id)
         {:keys [client transport manifest]} (get @registry id)]
     ;; a crashed backend waiting for its restart stays down
     (when-some [cancel (take-restart! id)] (cancel))
     (if client
       (do (when-some [kill! (when (contains? #{:starting :ready} (client/status client)) (:kill! transport))]
             ;; only when a shutdown is actually sent, and once per process. Registered
             ;; before the stop, so an exit during it (synchronous transports) cancels the
             ;; check; it is cancelled on exit in any case (no stray timers)
             (let [cancel (timer/schedule! (+ 500 (:shutdown_timeout_ms manifest 3000))
                                           (fn []
                                             (take-kill-check! id client)
                                             (when-some [alive? (:alive? transport)] (when (alive?) (kill!)))))
                   [old new] (swap-vals! registry
                                         (fn [r] (if (and (identical? client (get-in r [id :client]))
                                                          (nil? (get-in r [id :kill-check])))
                                                   (assoc-in r [id :kill-check] {:client client :cancel cancel})
                                                   r)))]
               (when (identical? old new) (cancel))))
           (client/stop! client on-done))
       (when on-done (on-done))))))

(defn shutdown-all!
  "Stop every backend; with `wait-ms` (JVM/CLR) block up to that long for them to finish.
  Called on exit (JVM shutdown hook / CLR ProcessExit, installed by install!); app shells
  may call it when the main window closes."
  ([] (shutdown-all! 0))
  ([wait-ms]
   (let [ids (keys @registry)
         remaining (atom (count ids))
         done #?(:cljd nil :default (promise))]
     (doseq [id ids]
       (stop! id (fn []
                   (when (zero? (swap! remaining dec))
                     #?(:cljd nil :default (deliver done true))))))
     #?(:cljd nil
        :default (when (and (pos? wait-ms) (seq ids))
                   (deref done wait-ms nil)
                   ;; whatever is still alive now dies with us
                   (doseq [id ids]
                     (when-some [kill! (get-in @registry [id :transport :kill!])]
                       (when-some [alive? (get-in @registry [id :transport :alive?])]
                         (when (alive?) (kill!))))))))))

(defonce ^:private exit-hook-installed (atom false))

(defn- install-exit-hook! []
  (let [[was _] (swap-vals! exit-hook-installed (constantly true))]
    (when-not was
      #?(:cljd nil
         :clj (.addShutdownHook (Runtime/getRuntime)
                                (Thread. ^Runnable (fn [] (shutdown-all! 3000)) "cloview-bridge-exit"))
         :cljr (.add_ProcessExit System.AppDomain/CurrentDomain
                                 (gen-delegate System.EventHandler [_ _] (shutdown-all! 3000)))))))

(defn install!
  "Register the :backend/* effects and start the backends whose manifest says
  \"start\": \"on-launch\". Options:
    :manifests     [manifest ..] (cloview.bridge.manifest/parse or normalize results)
    :vars          placeholder values {:appDir .. :dataDir .. :tempDir ..}
    :dispatch!     (fn [event]) for status events (and failures of unknown backends)
    :on-status     event vector, see the ns doc
    :on-log        (fn [backend-id {:level :message}]) backend stderr, $/log and protocol warnings
    :app-info      map sent with $/initialize (e.g. {:name \"wmark\" :version \"1.2.0\"})
    :platform      override (\"windows\" ...); default: this machine
    :transport-fn  (fn [launch-spec callbacks] transport); default cloview.bridge.process/spawn
                   (cloview.bridge.loopback/transport-fn for in-process backends and tests)
    :keywordize?   keywordize keys of results (default true)
  Re-installing replaces the backends (running ones are stopped first)."
  [{:keys [manifests] :as opts}]
  (when (seq @registry) (shutdown-all! 0))
  (let [ms (mapv manifest/normalize manifests)]
    (reset! registry (into {} (map (fn [m] [(backend-key (:id m)) {:manifest m :opts opts :restarts []}])) ms))
    (reset! backend-order (mapv #(backend-key (:id %)) ms)))
  (st/reg-fx :backend/call (fn [v {:keys [dispatch!]}] (doseq [c (if (map? v) [v] v)] (call! c dispatch!))))
  (st/reg-fx :backend/stream (fn [v {:keys [dispatch!]}] (call! v dispatch!)))
  (st/reg-fx :backend/cancel (fn [{:keys [backend id]} _]
                               (when-some [c (get-in @registry [(if backend (backend-key backend) (default-backend)) :client])]
                                 (client/cancel! c id))))
  (st/reg-fx :backend/start (fn [{:keys [backend]} _]
                              ;; an explicit start also revives a crashed backend
                              (let [id (if backend (backend-key backend) (default-backend))]
                                (when (and (contains? @registry id)
                                           (contains? #{nil :idle :stopped :crashed} (status id)))
                                  (start-backend! id)))))
  (st/reg-fx :backend/stop (fn [{:keys [backend]} _] (stop! (if backend (backend-key backend) (default-backend)))))
  (install-exit-hook!)
  (doseq [[id {:keys [manifest]}] @registry
          :when (= "on-launch" (:start manifest))]
    (start-backend! id))
  :installed)
