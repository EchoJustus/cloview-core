;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.client
  "Transport-agnostic JSON-RPC client of the cloview backend bridge.

  A client wraps a transport — a map {:send! (fn [line]) :close! (fn [])} — and keeps the
  table of pending requests. The transport feeds every line it reads from the backend
  into `receive!` and reports its end with `closed!`. Callbacks are plain functions; the
  effect layer (cloview.bridge) turns them into store events.

  Life cycle:  :idle --start!--> :starting --initialize ok--> :ready --stop!--> :stopping
               any state --closed!--> :stopped (asked to stop) or :crashed
               :crashed --stop!--> :stopped
  Requests made before :ready are queued and sent once the backend answered
  $/initialize; requests after :stopped/:crashed fail at once with :not-running.

  Callbacks (all optional) of `request!`:
    :on-result  (fn [result])
    :on-error   (fn [failure])   failure = {:error msg :code n :reason kw :data d}, reason one
                                 of :rpc (backend error) :timeout :cancelled :crashed
                                 :not-running :unsupported :manifest (:too-large is reserved)
    :on-notify  (fn [method params])   $/progress and $/stream notifications of this request
  Exactly one of :on-result / :on-error is called per request, at most once (a response
  that loses the race against its timeout or a cancel is dropped).

  Thread safety: state lives in one atom; every transition is a swap, so the reader
  thread, timer threads and UI calls may interleave freely (JVM, CLR) — Dart is single
  threaded anyway."
  (:require [cloview.bridge.protocol :as p]))

(defn create
  "New client (status :idle). Options:
    :schedule!           (fn [ms f]) -> cancel fn; required for timeouts
    :request-timeout-ms  default timeout (default 60000; nil or 0 = none)
    :startup-timeout-ms  timeout of $/initialize (default 15000)
    :keywordize?         keywordize payload keys (default true)
    :on-status           (fn [status info]) on every status change
    :on-notification     (fn [method params]) notifications not tied to a request ($/log, app ones)
    :on-invalid          (fn [problem line]) unparseable or unexpected lines"
  [transport opts]
  (atom {:transport transport
         :opts (merge {:request-timeout-ms 60000 :startup-timeout-ms 15000 :keywordize? true} opts)
         :status :idle
         :next-id 1
         :pending {}     ; rpc id -> {:method :on-result :on-error :on-notify :cancel-timer :app-id}
         :app-ids {}     ; app id -> rpc id
         :queue []       ; lines waiting for :ready
         :backend nil})) ; $/initialize result

(def start-failure-reasons
  "Reasons a start failure passes on to the app's calls; every other cause (a program
  that is not installed, a crash during startup) reaches them as :not-running, with the
  details in :error and :data."
  #{:unsupported :manifest})

(defn status [client] (:status @client))
(defn backend-info [client] (:backend @client))
(defn pending-count [client] (count (:pending @client)))

(defn- set-status! [client status info]
  (let [[old _] (swap-vals! client assoc :status status)]
    (when (not= (:status old) status)
      (when-some [f (get-in old [:opts :on-status])] (f status info)))))

(defn- send-line! [client line]
  ((get-in @client [:transport :send!]) line))

(defn- take-pending!
  "Remove the pending entry of `id` atomically; returns it, or nil when another path
  (response, timeout, cancel, crash) already took it."
  [client id]
  (let [[old _] (swap-vals! client (fn [st]
                                     (if-some [e (get-in st [:pending id])]
                                       (-> st
                                           (update :pending dissoc id)
                                           (update :app-ids (fn [m] (if-some [a (:app-id e)] (dissoc m a) m))))
                                       st)))]
    (when-some [e (get-in old [:pending id])]
      (when-some [cancel (:cancel-timer e)] (cancel))
      e)))

(defn- fail! [client id failure]
  (when-some [e (take-pending! client id)]
    (when-some [f (:on-error e)] (f failure))
    true))

(defn request!
  "Send a request; returns its JSON-RPC id. See the ns doc for the callbacks; also
  :timeout-ms (overrides the default; 0 = none) and :app-id (a value the app uses to
  cancel the request, e.g. :job-42)."
  [client method params {:keys [on-result on-error on-notify timeout-ms app-id] :as cbs}]
  (let [st @client]
    (if (and (contains? #{:stopping :stopped :crashed} (:status st))
             (not= method p/shutdown-method))
      (do (when on-error
            ;; a backend that could not start says why
            (let [info (:closed-info st)
                  crashed? (= :crashed (:status st))
                  r (:reason info)]
              (on-error (cond
                          (and crashed? (contains? start-failure-reasons r))
                          (p/failure r (str "backend could not start: " (:error info)) info)
                          (and crashed? (:error info))
                          (p/failure :not-running (str "backend could not start: " (:error info)) info)
                          :else
                          (p/failure :not-running (str "backend is " (name (:status st))))))))
          nil)
      (let [[old _] (swap-vals! client update :next-id inc)
            id (:next-id old)
            timeout (if (contains? cbs :timeout-ms) timeout-ms (get-in st [:opts :request-timeout-ms]))
            schedule! (get-in st [:opts :schedule!])
            cancel-timer (when (and schedule! timeout (pos? timeout))
                           (schedule! timeout
                                      (fn []
                                        (when (fail! client id (p/failure :timeout (str method " timed out after " timeout " ms")))
                                          ;; tell the backend to stop working on it (best effort)
                                          (when (= :ready (status client))
                                            (send-line! client (p/encode (p/notification p/cancel-method {:id id}))))))))
            line (p/encode (p/request id method params))]
        (swap! client (fn [s]
                        (cond-> (assoc-in s [:pending id] {:method method :on-result on-result :on-error on-error
                                                           :on-notify on-notify :cancel-timer cancel-timer
                                                           :app-id app-id})
                          (some? app-id) (assoc-in [:app-ids app-id] id))))
        (if (or (= :ready (:status @client)) (= method p/initialize-method) (= method p/shutdown-method))
          (send-line! client line)
          (swap! client update :queue conj line))
        id))))

(defn notify!
  "Send a notification (no reply), queued like requests until :ready."
  [client method params]
  (let [line (p/encode (p/notification method params))]
    (case (status client)
      :ready (send-line! client line)
      (:idle :starting) (swap! client update :queue conj line)
      nil)))

(defn cancel!
  "Cancel a pending request by app id or JSON-RPC id: its :on-error gets :cancelled and
  the backend receives $/cancel. Returns true when something was pending."
  [client id-or-app-id]
  (let [id (or (get-in @client [:app-ids id-or-app-id]) id-or-app-id)]
    (when (fail! client id (p/failure :cancelled "cancelled by the app"))
      (when (= :ready (status client))
        (send-line! client (p/encode (p/notification p/cancel-method {:id id}))))
      true)))

(defn- flush-queue! [client]
  (let [[old _] (swap-vals! client assoc :queue [])]
    (doseq [line (:queue old)] (send-line! client line))))

(defn start!
  "Send $/initialize (status :starting). `app-info` goes to the backend as params :app.
  On success the status becomes :ready and queued requests are sent; on failure the
  transport is closed and the status becomes :crashed."
  [client app-info]
  (set-status! client :starting nil)
  (request! client p/initialize-method {:protocol p/protocol-version :app app-info}
            {:timeout-ms (get-in @client [:opts :startup-timeout-ms])
             :on-result (fn [result]
                          (swap! client assoc :backend result)
                          ;; a stop! during the handshake keeps :stopping (its $/shutdown is queued)
                          (when (= :starting (status client)) (set-status! client :ready result))
                          (flush-queue! client))
             :on-error (fn [failure]
                         (set-status! client :crashed failure)
                         ((get-in @client [:transport :close!])))}))

(defn closed!
  "The transport ended (backend exited, pipe broken) or could not start. Every pending
  request fails with :not-running when the app asked to stop, with the start failure's
  own reason when `info` carries one of start-failure-reasons, else with :crashed.
  `info` goes to :on-status."
  [client info]
  (let [stopping? (contains? #{:stopping :stopped} (status client))
        reason (cond stopping? :not-running
                     (contains? start-failure-reasons (:reason info)) (:reason info)
                     :else :crashed)
        message (cond stopping? "backend stopped"
                      (:error info) (str "backend could not start: " (:error info))
                      :else "backend exited unexpectedly")
        ids (keys (:pending @client))]
    (set-status! client (if stopping? :stopped :crashed) info)
    (doseq [id ids]
      (fail! client id (p/failure reason message info)))
    (swap! client assoc :queue [] :closed-info info)))

(defn stop!
  "Ask the backend to shut down: $/shutdown, then close stdin (EOF). `on-done` (optional)
  is called once the request finished or timed out; the launcher kills the process if
  it does not exit by itself."
  ([client] (stop! client nil))
  ([client on-done]
   (if (contains? #{:ready :starting} (status client))
     (let [finish (fn [_] ((get-in @client [:transport :close!])) (when on-done (on-done)))]
       ;; :stopping first: the backend may exit before request! returns, and its exit
       ;; must count as an orderly stop
       (set-status! client :stopping nil)
       (request! client p/shutdown-method nil {:on-result finish :on-error finish
                                               :timeout-ms (get-in @client [:opts :shutdown-timeout-ms] 3000)}))
     (do (when (= :crashed (status client))
           ;; the app's stop wins over a pending restart (which only revives :crashed).
           ;; Before close!: a transport that reports its end again must see :stopped
           (set-status! client :stopped nil))
         ((get-in @client [:transport :close!]))
         (when on-done (on-done))))))

(defn receive!
  "Handle one line read from the backend's stdout."
  [client line]
  (let [{:keys [kind message problem]} (p/decode line {:keywordize? (get-in @client [:opts :keywordize?])})
        opts (:opts @client)]
    (case kind
      :result (when-some [e (take-pending! client (:id message))]
                (when-some [f (:on-result e)] (f (:result message))))
      :error (if-some [e (when (some? (:id message)) (take-pending! client (:id message)))]
               (when-some [f (:on-error e)] (f (p/error->failure (:error message))))
               (when-some [f (:on-invalid opts)] (f "error response without a pending request" line)))
      :notification (let [{:keys [method params]} message
                          target (when (contains? #{p/progress-method p/stream-method} method)
                                   (get-in @client [:pending (:id params)]))]
                      (if target
                        (when-some [f (:on-notify target)] (f method params))
                        (when-some [f (:on-notification opts)] (f method params))))
      :request (do ;; backends do not call the GUI in protocol version 1
                 (send-line! client (p/encode (p/error-response (:id message) (p/error-codes :method-not-found)
                                                                "the GUI does not accept requests")))
                 (when-some [f (:on-invalid opts)] (f "request from backend" line)))
      (when-some [f (:on-invalid opts)] (f problem line)))))
