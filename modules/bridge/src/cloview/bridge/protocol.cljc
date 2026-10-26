;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.protocol
  "Wire format of the cloview backend bridge (docs/POLYGLOT_BACKEND_ARCHITECTURE.md §3).

  JSON-RPC 2.0 messages, one per line (newline-delimited JSON, UTF-8) on the backend's
  stdin (GUI -> backend) and stdout (backend -> GUI). stderr is free-form log text.
  JSON escapes newlines inside strings, so a message is always exactly one line.

  Reserved methods (prefix `$/`):
    $/initialize  request   GUI -> backend   {:protocol :app {...}} -> {:protocol :name :version :methods}
    $/shutdown    request   GUI -> backend   finish the requests in flight, reply, exit
                                             (stdin EOF also means exit)
    $/ping        request   GUI -> backend   -> \"pong\" (optional health check)
    $/cancel      notif.    GUI -> backend   {:id <request id>} best effort
    $/progress    notif.    backend -> GUI   {:id <request id> :value <any>}
    $/stream      notif.    backend -> GUI   {:id <request id> :item <any>}
    $/log         notif.    backend -> GUI   {:level \"info\" :message \"...\"}

  Pure functions, identical on every host."
  (:require [clojure.string :as str]
            [cloview.fx.codec :as codec]))

(def protocol-version "cloview-bridge/1")

(def initialize-method "$/initialize")
(def shutdown-method "$/shutdown")
(def ping-method "$/ping")
(def cancel-method "$/cancel")
(def progress-method "$/progress")
(def stream-method "$/stream")
(def log-method "$/log")

(def error-codes
  "JSON-RPC 2.0 codes, plus the bridge's own in the implementation-defined range."
  {:parse-error -32700
   :invalid-request -32600
   :method-not-found -32601
   :invalid-params -32602
   :internal-error -32603
   ;; cloview-bridge (-32000..-32099)
   :cancelled -32001
   :timeout -32002
   :not-running -32003
   :crashed -32004
   :too-large -32005
   :unsupported -32006})

(def ^:private code->reason
  (into {} (map (fn [[k v]] [v k])) error-codes))

(defn reason-for-code
  "The reason keyword for a JSON-RPC error code (:rpc for codes the bridge does not know,
  e.g. an application error of the backend)."
  [code]
  (get code->reason code :rpc))

;; ---------------------------------------------------------------------------
;; Building messages (Clojure maps; `encode` turns them into one line)

(defn request [id method params]
  (cond-> {:jsonrpc "2.0" :id id :method method}
    (some? params) (assoc :params params)))

(defn notification [method params]
  (cond-> {:jsonrpc "2.0" :method method}
    (some? params) (assoc :params params)))

(defn result-response [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn error-response
  ([id code message] (error-response id code message nil))
  ([id code message data]
   {:jsonrpc "2.0" :id id
    :error (cond-> {:code code :message message} (some? data) (assoc :data data))}))

(defn encode
  "One line of JSON (no trailing newline) for a message map."
  [msg]
  (codec/write-json msg))

;; ---------------------------------------------------------------------------
;; Reading messages

(defn- valid-id? [id] (or (string? id) (integer? id)))

(defn classify
  "Kind of a decoded message map: :request, :notification, :result, :error or :invalid."
  [m]
  (cond
    (not (map? m)) :invalid
    (not= "2.0" (:jsonrpc m)) :invalid
    (and (string? (:method m)) (contains? m :id) (valid-id? (:id m))) :request
    (and (string? (:method m)) (not (contains? m :id))) :notification
    (and (contains? m :result) (valid-id? (:id m))) :result
    (and (map? (:error m)) (integer? (get-in m [:error :code]))
         (or (nil? (:id m)) (valid-id? (:id m)))) :error
    :else :invalid))

(defn decode
  "Parse one line. Returns {:kind k :message m} or {:kind :invalid :problem \"...\"}.
  Keys of the envelope are always keywords; `:keywordize?` (default true) also
  keywordizes the keys inside params/result/error data."
  ([line] (decode line {}))
  ([line {:keys [keywordize?] :or {keywordize? true}}]
   (if (str/blank? line)
     {:kind :invalid :problem "empty line"}
     (let [parsed (try (codec/read-json line {:keywordize? true})
                       (catch #?(:cljd dynamic :clj Throwable :cljr System.Exception) e
                         {::problem (str "not JSON: " (or (ex-message e) (str e)))}))]
       (if-some [problem (::problem parsed)]
         {:kind :invalid :problem problem}
         (let [m (if (or keywordize? (not (map? parsed)))
                   parsed
                   ;; envelope keywordized, payloads with string keys
                   (let [raw (codec/read-json line)]
                     (cond-> {:jsonrpc (get raw "jsonrpc")}
                       (contains? raw "id") (assoc :id (get raw "id"))
                       (contains? raw "method") (assoc :method (get raw "method"))
                       (contains? raw "params") (assoc :params (get raw "params"))
                       (contains? raw "result") (assoc :result (get raw "result"))
                       (contains? raw "error") (assoc :error (let [e (get raw "error")]
                                                               (cond-> {:code (get e "code")
                                                                        :message (get e "message")}
                                                                 (contains? e "data") (assoc :data (get e "data"))))))))
               kind (classify m)]
           (if (= :invalid kind)
             {:kind :invalid :problem "not a JSON-RPC 2.0 message" :message m}
             {:kind kind :message m})))))))

(defn failure
  "The failure value delivered to :on-failure callbacks:
  {:error message :code n :reason kw :data d}."
  ([reason message] (failure reason message nil))
  ([reason message data]
   (cond-> {:error message :code (get error-codes reason (get error-codes :internal-error)) :reason reason}
     (some? data) (assoc :data data))))

(defn error->failure
  "Failure value for a JSON-RPC error object received from the backend."
  [{:keys [code message data]}]
  (cond-> {:error (or message "backend error") :code code :reason (reason-for-code code)}
    (some? data) (assoc :data data)))
