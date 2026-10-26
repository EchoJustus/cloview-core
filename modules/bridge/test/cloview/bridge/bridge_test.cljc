;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.bridge-test
  "The backend bridge on every host, with the in-process loopback transport: wire
  format, manifest contract, client state machine, and the :backend/* effects."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloview.bridge :as bridge]
            [cloview.bridge.client :as client]
            [cloview.bridge.loopback :as loopback]
            [cloview.bridge.manifest :as manifest]
            [cloview.bridge.protocol :as p]
            [cloview.core.store :as st]
            [cloview.fx.codec :as codec]))

;; ---------------------------------------------------------------------------
;; helpers

(defn- fake-scheduler
  "A :schedule! that records tasks; (fire!) runs the ones not cancelled."
  []
  (let [tasks (atom [])]
    {:schedule! (fn [ms f]
                  (let [t (atom {:ms ms :f f :cancelled? false})]
                    (swap! tasks conj t)
                    (fn [] (swap! t assoc :cancelled? true))))
     :fire! (fn [] (doseq [t @tasks :when (not (:cancelled? @t))]
                     (swap! t assoc :cancelled? true)
                     ((:f @t))))
     :tasks tasks}))

(defn- connect
  "A started client over a loopback backend `handler`; returns [client log]."
  ([handler] (connect handler {}))
  ([handler opts]
   (let [log (atom [])
         c-atom (atom nil)
         t ((loopback/transport-fn handler {:log log :methods ["math/add"]})
            {} {:on-line (fn [line] (client/receive! @c-atom line))
                :on-exit (fn [info] (client/closed! @c-atom info))})
         c (client/create t opts)]
     (reset! c-atom c)
     (client/start! c {:name "test"})
     [c log])))

(defn- calc-backend
  "math/add answers at once, slow/never never answers, boom fails, steps notifies."
  [{:keys [id method params]} {:keys [respond! fail! notify!]}]
  (case method
    "math/add" (respond! (+ (:a params) (:b params)))
    "boom" (fail! -32050 "it broke" {:hint "check input"})
    "steps" (do (notify! p/progress-method {:id 99 :value 0})   ; no such request
                (notify! p/progress-method {:id id :value 1})
                (notify! p/stream-method {:id id :item "a"})
                (respond! :done))
    "slow/never" nil
    "$/cancel" nil
    nil))

;; ---------------------------------------------------------------------------
;; wire format

(deftest protocol-messages
  (is (= "{\"id\":7,\"jsonrpc\":\"2.0\",\"method\":\"m\",\"params\":{\"a\":1}}"
         (p/encode (p/request 7 "m" {:a 1}))))
  (is (not (str/includes? (p/encode (p/result-response 1 "two\nlines")) "\n"))
      "a message is always one line")
  (is (= {:kind :result :message {:jsonrpc "2.0" :id 3 :result {:sum 5}}}
         (p/decode "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"sum\":5}}")))
  (is (= {"Sum" 5} (get-in (p/decode "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"Sum\":5}}" {:keywordize? false})
                           [:message :result]))
      "payload keys can stay strings; the envelope is keywordized either way")
  (is (= :notification (:kind (p/decode "{\"jsonrpc\":\"2.0\",\"method\":\"$/log\",\"params\":{}}"))))
  (is (= :error (:kind (p/decode "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"x\"}}"))))
  (is (= :invalid (:kind (p/decode "{\"id\":1,\"result\":2}"))) "jsonrpc 2.0 is required")
  (is (= :invalid (:kind (p/decode "not json"))))
  (is (= :invalid (:kind (p/decode ""))))
  (is (= :invalid (:kind (p/decode (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                                        (apply str (repeat 2000 "[")) (apply str (repeat 2000 "]")) "}"))))
      "a deeply nested line is rejected, not a stack overflow")
  (is (= {:error "it broke" :code -32050 :reason :rpc :data {:hint "x"}}
         (p/error->failure {:code -32050 :message "it broke" :data {:hint "x"}})))
  (is (= :timeout (:reason (p/error->failure {:code -32002 :message "t"})))))

(deftest json-writer-is-deterministic
  (is (= "{\"a\":[1,2.5,\"x\"],\"b\":null,\"c\":true}" (codec/write-json {:c true :b nil :a [1 2.5 "x"]})))
  (is (= "\"tab\\tquote\\\"nl\\nctl\\u0001\"" (codec/write-json (str "tab\tquote\"nl\nctl" (char 1)))))
  (is (= "20.0" (codec/write-json 20.0)) "doubles keep a fraction")
  (is (= {"k" [1 2.5 "é"]} (codec/read-json (codec/write-json {:k [1 2.5 "é"]}))) "round trip")
  (is (= "[\"a\",\"b\"]" (codec/write-json #{"b" "a"})) "sets sorted")
  (is (thrown? #?(:cljd cljd.core/ExceptionInfo :default clojure.lang.ExceptionInfo)
               (codec/write-json (/ 0.0 0.0)))))

;; ---------------------------------------------------------------------------
;; manifest

(def manifest-json
  "{\"manifest_version\":1,\"id\":\"engine\",\"protocol\":\"cloview-bridge/1\",
    \"command\":{\"windows\":[\"${appDir}/engine.exe\"],\"default\":[\"java\",\"-jar\",\"${appDir}/engine.jar\"]},
    \"cwd\":\"${appDir}\",\"env\":{\"LOG\":\"${dataDir}/e.log\"},\"start\":\"on-demand\"}")

(deftest manifest-contract
  (let [m (manifest/parse manifest-json)]
    (is (= "stdio" (:transport m)) "defaults applied")
    (is (= 60000 (:request_timeout_ms m)))
    (is (= "on-crash" (get-in m [:restart :policy])))
    (is (= {:id "engine" :command ["C:/App/engine.exe"] :cwd "C:/App" :env {"LOG" "D:/data/e.log"}}
           (manifest/launch-spec m "windows" {:appDir "C:/App" :dataDir "D:/data"})))
    (is (= ["java" "-jar" "/opt/app/engine.jar"]
           (:command (manifest/launch-spec m "linux" {:appDir "/opt/app" :dataDir "/d"})))
        "default command for other platforms")
    (is (thrown? #?(:cljd cljd.core/ExceptionInfo :default clojure.lang.ExceptionInfo)
                 (manifest/launch-spec m "linux" {:appDir "/opt/app"}))
        "an unresolved placeholder is an error"))
  (is (= ["id must be lower-case letters, digits and dashes" "protocol must be \"cloview-bridge/1\""]
         (manifest/problems (merge manifest/defaults {:manifest_version 1 :id "Bad Id" :protocol "x"
                                                      :command {:default ["a"]}}))))
  (is (some #(str/includes? % "command keys") (manifest/problems (merge manifest/defaults
                                                                        {:manifest_version 1 :id "a" :protocol p/protocol-version
                                                                         :command {:beos ["a"]}}))))
  (is (thrown? #?(:cljd cljd.core/ExceptionInfo :default clojure.lang.ExceptionInfo)
               (manifest/parse "{\"manifest_version\":2}"))))

;; ---------------------------------------------------------------------------
;; client state machine

(deftest handshake-call-and-errors
  (let [[c log] (connect calc-backend)
        got (atom [])]
    (is (= :ready (client/status c)))
    (is (= "loopback" (:name (client/backend-info c))))
    (client/request! c "math/add" {:a 2 :b 40} {:on-result #(swap! got conj [:ok %]) :on-error #(swap! got conj [:err %])})
    (client/request! c "boom" nil {:on-result #(swap! got conj [:ok %]) :on-error #(swap! got conj [:err %])})
    (is (= [[:ok 42] [:err {:error "it broke" :code -32050 :reason :rpc :data {:hint "check input"}}]] @got))
    (is (zero? (client/pending-count c)))
    (is (str/includes? (second (first @log)) "\"method\":\"$/initialize\"") "first line sent is the handshake")))

(deftest requests-before-ready-are-queued
  (let [log (atom [])
        c-atom (atom nil)
        t ((loopback/transport-fn calc-backend {:log log}) {} {:on-line (fn [l] (client/receive! @c-atom l))
                                                              :on-exit (fn [i] (client/closed! @c-atom i))})
        c (client/create t {})
        got (atom nil)]
    (reset! c-atom c)
    (client/request! c "math/add" {:a 1 :b 1} {:on-result #(reset! got %)})
    (is (= [] @log) "nothing is sent before start!")
    (client/start! c {})
    (is (= 2 @got))
    (is (= ["$/initialize" "math/add"]
           (map #(get-in (p/decode (second %)) [:message :method]) (filter #(= :out (first %)) @log))))))

(deftest progress-stream-and-unrelated-notifications
  (let [other (atom [])
        [c _] (connect calc-backend {:on-notification (fn [m ps] (swap! other conj [m ps]))})
        seen (atom [])
        id (atom nil)]
    (reset! id (client/request! c "steps" nil {:on-notify (fn [m ps] (swap! seen conj [m ps]))
                                                      :on-result (fn [r] (swap! seen conj [:result r]))}))
    (is (= 2 @id) "ids start after the handshake (1)")
    (is (= [[p/progress-method {:id 2 :value 1}] [p/stream-method {:id 2 :item "a"}] [:result "done"]] @seen))
    (is (= [[p/progress-method {:id 99 :value 0}]] @other) "a notification for no pending request goes to :on-notification")))

(deftest timeout-cancel-and-late-responses
  (let [{:keys [schedule! fire!]} (fake-scheduler)
        [c log] (connect calc-backend {:schedule! schedule! :request-timeout-ms 1000})
        got (atom [])]
    (client/request! c "slow/never" nil {:on-result #(swap! got conj [:ok %]) :on-error #(swap! got conj (:reason %))})
    (client/request! c "slow/never" nil {:app-id :job-1 :on-error #(swap! got conj (:reason %))})
    (is (true? (client/cancel! c :job-1)))
    (is (nil? (client/cancel! c :job-1)) "cancelling twice does nothing")
    (fire!)
    (is (= [:cancelled :timeout] @got))
    (is (= 2 (count (filter #(str/includes? (second %) "$/cancel") @log)))
        "the backend hears about the cancel and the timeout")
    ;; a response arriving after the timeout is dropped
    (client/receive! c (p/encode (p/result-response 2 "late")))
    (is (= [:cancelled :timeout] @got))))

(deftest crash-fails-pending-and-blocks-new-requests
  (let [statuses (atom [])
        [c _] (connect calc-backend {:on-status (fn [s _] (swap! statuses conj s))})
        got (atom [])]
    (client/request! c "slow/never" nil {:on-error #(swap! got conj (:reason %))})
    (client/closed! c {:exit 3})
    (is (= :crashed (client/status c)))
    (client/request! c "math/add" {:a 1 :b 2} {:on-error #(swap! got conj (:reason %))})
    (is (= [:crashed :not-running] @got))
    (is (= [:ready :crashed] (vec (remove #{:starting} @statuses))))))

(deftest orderly-stop
  (let [[c _] (connect calc-backend)
        done (atom false)]
    (client/stop! c #(reset! done true))
    (is @done)
    (is (= :stopped (client/status c)))))

(deftest invalid-lines-are-reported-not-fatal
  (let [bad (atom [])
        [c _] (connect calc-backend {:on-invalid (fn [problem _] (swap! bad conj problem))})]
    (client/receive! c "garbage")
    (client/receive! c (p/encode (p/request 5 "gui/whatever" nil)))
    (is (= 2 (count @bad)))
    (is (= :ready (client/status c)))))

;; ---------------------------------------------------------------------------
;; effects (cloview.bridge) through a store

(deftest backend-effects-through-the-store
  (let [store (st/create-store {:db {}})
        manifest {:manifest_version 1 :id "calc" :protocol p/protocol-version
                  :command {:default ["unused"]} :start "on-launch"}]
    (st/register-handlers!
     {:events-db {:calc/ok (fn [db [_ r]] (assoc db :ok r))
                  :calc/failed (fn [db [_ f]] (assoc db :failed (:reason f)))
                  :calc/progress (fn [db [_ v]] (update db :progress (fnil conj []) v))
                  :calc/item (fn [db [_ v]] (update db :items (fnil conj []) v))
                  :calc/status (fn [db [_ id s]] (update db :status (fnil conj []) [id s]))}
      :events-fx {:calc/add (fn [_ [_ a b]] {:backend/call {:method "math/add" :params {:a a :b b}
                                                             :on-success [:calc/ok] :on-failure [:calc/failed]}})
                  :calc/steps (fn [_ _] {:backend/stream {:backend :calc :method "steps"
                                                          :on-progress [:calc/progress] :on-item [:calc/item]
                                                          :on-success [:calc/ok]}})
                  :calc/unknown (fn [_ _] {:backend/call {:backend :nope :method "x" :on-failure [:calc/failed]}})}})
    (bridge/install! {:manifests [manifest]
                      :transport-fn (loopback/transport-fn calc-backend)
                      :dispatch! (fn [ev] (st/dispatch! store ev))
                      :on-status [:calc/status]})
    (is (= :ready (bridge/status :calc)))
    (st/dispatch! store [:calc/add 20 22])
    (is (= 42 (:ok (st/db store))))
    (st/dispatch! store [:calc/steps])
    (is (= [1] (:progress (st/db store))))
    (is (= ["a"] (:items (st/db store))))
    (is (= "done" (:ok (st/db store))))
    (st/dispatch! store [:calc/unknown])
    (is (= :not-running (:failed (st/db store))))
    (is (= [[:calc :starting] [:calc :ready]] (:status (st/db store))))
    (bridge/stop! :calc)
    (is (= :stopped (bridge/status :calc)))
    (st/dispatch! store [:calc/add 1 1])
    (is (= :not-running (:failed (st/db store))) "a stopped on-launch backend is not restarted by calls")))

(deftest start-failures-keep-their-reason
  (let [store (st/create-store {:db {}})
        failed (fn [] (:failed (st/db store)))]
    (st/register-handlers!
     {:events-db {:calc/failed (fn [db [_ f]] (assoc db :failed (select-keys f [:reason :error])))}
      :events-fx {:calc/add (fn [_ _] {:backend/call {:method "math/add" :params {:a 1 :b 2}
                                                      :on-failure [:calc/failed]}})}})
    (testing "no process transport on this host"
      (bridge/install! {:manifests [{:manifest_version 1 :id "calc" :protocol p/protocol-version
                                     :command {:default ["x"]} :start "on-demand"}]
                        :transport-fn (fn [_ _] (throw (ex-info "spawn is not implemented here" {:reason :unsupported})))
                        :dispatch! (fn [ev] (st/dispatch! store ev))})
      (st/dispatch! store [:calc/add])
      (is (= :unsupported (:reason (failed))))
      (is (str/includes? (:error (failed)) "not implemented")))
    (testing "an unresolved placeholder in the manifest"
      (bridge/install! {:manifests [{:manifest_version 1 :id "calc" :protocol p/protocol-version
                                     :command {:default ["${toolDir}/calc"]} :start "on-demand"}]
                        :transport-fn (loopback/transport-fn calc-backend)
                        :dispatch! (fn [ev] (st/dispatch! store ev))})
      (st/dispatch! store [:calc/add])
      (is (= :manifest (:reason (failed))))
      (is (str/includes? (:error (failed)) "toolDir")))
    (testing "a call after a start that crashed for another reason: not running"
      (bridge/install! {:manifests [{:manifest_version 1 :id "calc" :protocol p/protocol-version
                                     :command {:default ["x"]} :start "on-demand"
                                     :restart {:policy "never" :max_restarts 0 :window_ms 1000}}]
                        :transport-fn (fn [_ _] (throw (ex-info "No such file" {})))
                        :dispatch! (fn [ev] (st/dispatch! store ev))})
      (st/dispatch! store [:calc/add])
      (is (= :not-running (:reason (failed))))
      (is (= :crashed (bridge/status :calc))))
    (testing "a program that is not installed (the launcher's own :spawn reason stays internal)"
      (bridge/install! {:manifests [{:manifest_version 1 :id "calc" :protocol p/protocol-version
                                     :command {:default ["no-such-program"]} :start "on-demand"
                                     :restart {:policy "never" :max_restarts 0 :window_ms 1000}}]
                        :transport-fn (fn [_ _] (throw (ex-info "Cannot run program \"no-such-program\""
                                                                {:reason :spawn :command ["no-such-program"]})))
                        :dispatch! (fn [ev] (st/dispatch! store ev))})
      (st/dispatch! store [:calc/add])
      (is (= :not-running (:reason (failed))))
      (is (str/includes? (:error (failed)) "could not start: Cannot run program")))
    (bridge/stop! :calc)))

(deftest the-default-backend-is-the-first-manifest
  ;; more than 8 entries: a JVM map hashes its keys, and ClojureDart maps always do
  (let [ids (mapv #(str "b" %) (range 12))
        named (fn [spec cbs] ((loopback/transport-fn calc-backend {:name (:id spec)}) spec cbs))]
    (bridge/install! {:manifests (for [id (reverse ids)]
                                   {:manifest_version 1 :id id :protocol p/protocol-version
                                    :command {:default ["x"]} :start "on-launch"})
                      :transport-fn named
                      :dispatch! (fn [_])})
    (is (= "b11" (:name (bridge/backend-info))) "the first manifest given, whatever the map order")
    (is (= :ready (bridge/status)))
    (bridge/shutdown-all! 0)))

#?(:cljd nil
   :default
   (deftest stop-leaves-no-pending-timer
     (let [scheduled (atom 0) cancelled (atom 0)]
       (with-redefs [cloview.fx.timer/schedule! (fn [_ms _f] (swap! scheduled inc) (fn [] (swap! cancelled inc)))]
         (bridge/install! {:manifests [{:manifest_version 1 :id "calc" :protocol p/protocol-version
                                        :command {:default ["x"]} :start "on-launch"}]
                           :transport-fn (loopback/transport-fn calc-backend)
                           :dispatch! (fn [_])})
         (bridge/stop! :calc)
         (is (= :stopped (bridge/status :calc)))
         (is (pos? @scheduled) "stop! scheduled its kill check")
         (is (= @scheduled @cancelled) "every timer was cancelled once the backend exited")))))

#?(:cljd nil
   :default
   (deftest stop-cancels-a-pending-restart
     (let [tasks (atom []) exits (atom [])]
       (with-redefs [cloview.fx.timer/schedule! (fn [ms f]
                                                  (let [t (atom {:ms ms :f f :cancelled? false})]
                                                    (swap! tasks conj t)
                                                    (fn [] (swap! t assoc :cancelled? true))))]
         (bridge/install! {:manifests [{:manifest_version 1 :id "calc" :protocol p/protocol-version
                                        :command {:default ["x"]} :start "on-launch"}]
                           :transport-fn (fn [spec cbs]
                                           (swap! exits conj (:on-exit cbs))
                                           ((loopback/transport-fn calc-backend) spec cbs))
                           :dispatch! (fn [_])})
         (is (= :ready (bridge/status :calc)))
         ((peek @exits) {:exit 1})                       ; the backend dies
         (is (= :crashed (bridge/status :calc)))
         (let [restart (peek @tasks)]
           (is (= 500 (:ms @restart)) "the first restart is due after 0.5 s")
           (bridge/stop! :calc)
           (is (:cancelled? @restart) "stop! cancels it")
           ((:f @restart))                               ; a timer that fires anyway
           (is (= :stopped (bridge/status :calc)))
           (is (= 1 (count @exits)) "no new process was started")
           (is (every? #(:cancelled? (deref %)) (remove #{restart} @tasks)) "no other timer left"))))))
