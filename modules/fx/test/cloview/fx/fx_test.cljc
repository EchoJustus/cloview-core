;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.fx.fx-test
  "Effect-runner tests for the desktop hosts (Clojure/JVM and ClojureCLR)."
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloview.core.store :as st]
            [cloview.fx.file :as file]
            [cloview.fx.http :as http]
            [cloview.fx.lan-sync :as sync]
            [cloview.fx.storage :as storage]
            [cloview.fx.timer :as timer]))

(defn- sleep! [ms]
  #?(:clj (Thread/sleep (long ms))
     :cljr (System.Threading.Thread/Sleep (int ms))))

(defn- wait-until
  "Poll `pred` every 10 ms; true when it held before `timeout-ms`."
  [pred timeout-ms]
  (loop [waited 0]
    (cond
      (pred) true
      (>= waited timeout-ms) false
      :else (do (sleep! 10) (recur (+ waited 10))))))

(defn- temp-dir []
  #?(:clj (str (java.nio.file.Files/createTempDirectory "cloview-fx"
                                                        (make-array java.nio.file.attribute.FileAttribute 0)))
     :cljr (let [p (System.IO.Path/Combine (System.IO.Path/GetTempPath)
                                           (str "cloview-fx-" (System.Guid/NewGuid)))]
             (System.IO.Directory/CreateDirectory p)
             p)))

(defn- path-join [dir f]
  #?(:clj (str dir java.io.File/separator f)
     :cljr (System.IO.Path/Combine dir f)))

;; collect every dispatched event of interest in a store
(st/reg-event-db :fx-test/got (fn [db [_ k v]] (update db k (fnil conj []) v)))
(st/reg-event-fx :fx-test/run (fn [_ [_ effects]] effects))

(defn- new-store [] (st/create-store {:db {}}))

(defn- run-fx! [s effects] (st/dispatch! s [:fx-test/run effects]))

(deftest timer-effects
  (timer/install!)
  (is (> (timer/now-ms) 1700000000000))
  (testing ":now coeffect"
    (st/reg-event-fx :fx-test/stamp [(st/inject-cofx :now)] (fn [{:keys [db now]} _] {:db (assoc db :now now)}))
    (let [s (new-store)]
      (st/dispatch! s [:fx-test/stamp])
      (is (number? (:now (st/db s))))))
  (testing ":dispatch-later"
    (let [s (new-store)]
      (run-fx! s {:dispatch-later {:ms 20 :dispatch [:fx-test/got :later :ok]}})
      (is (wait-until #(= [:ok] (:later (st/db s))) 3000))))
  (testing ":timer/start repeats until :timer/stop"
    (let [s (new-store)]
      (run-fx! s {:timer/start {:id :fx-test/clock :ms 15 :dispatch [:fx-test/got :ticks 1]}})
      (is (wait-until #(>= (count (:ticks (st/db s))) 3) 3000))
      (is (contains? (timer/running-ids) :fx-test/clock))
      (run-fx! s {:timer/stop :fx-test/clock})
      (is (not (contains? (timer/running-ids) :fx-test/clock))))))

(deftest http-request-validation
  (is (= {:method :get :method-name "GET" :url "http://x/y" :headers {} :body nil
          :timeout-ms http/default-timeout-ms :on-success [:ok] :on-failure nil}
         (http/normalize-request {:url "http://x/y" :on-success [:ok]})))
  (is (thrown? #?(:clj Exception :cljr System.Exception) (http/normalize-request {:url "ftp://x"})))
  (is (thrown? #?(:clj Exception :cljr System.Exception)
               (http/normalize-request {:url "http://x" :method :teleport})))
  (is (http/success? 204))
  (is (not (http/success? 404))))

#?(:cljd nil
   :clj
   (defn- start-local-server!
     "Tiny local HTTP server answering every request with 'pong:<body>'."
     []
     (let [server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
       (.createContext server "/"
                       (reify com.sun.net.httpserver.HttpHandler
                         (handle [_ ex]
                           (let [body (slurp (.getRequestBody ex))
                                 bytes (.getBytes (str "pong:" body) "UTF-8")]
                             (.sendResponseHeaders ex 200 (alength bytes))
                             (with-open [out (.getResponseBody ex)] (.write out bytes))))))
       (.start server)
       {:port (.getPort (.getAddress server)) :stop #(.stop server 0)})))

#?(:cljr
   (defn- start-local-server!
     "Tiny local HTTP server (HttpListener on localhost) answering 'pong:<body>'."
     []
     (let [port (+ 49000 (rand-int 5000))
           listener (System.Net.HttpListener.)]
       (.Add (.Prefixes listener) (str "http://localhost:" port "/"))
       (.Start listener)
       (future
         (try
           (loop []
             (let [ctx (.GetContext listener)
                   body (.ReadToEnd (System.IO.StreamReader. (.InputStream (.Request ctx))))
                   bytes (.GetBytes System.Text.Encoding/UTF8 (str "pong:" body))
                   resp (.Response ctx)]
               (set! (.StatusCode resp) (int 200))
               (.Write (.OutputStream resp) bytes 0 (alength bytes))
               (.Close resp)
               (recur)))
           (catch System.Exception _ nil)))
       {:port port :stop #(.Stop listener)})))

(deftest http-round-trip-against-local-server
  (http/install!)
  (let [server (try (start-local-server!)
                    (catch #?(:clj Throwable :cljr System.Exception) ex
                      (println "SKIP http round-trip: cannot start local server:" (str ex))
                      nil))]
    (when server
      (try
        (let [s (new-store)
              base (str "http://" #?(:clj "127.0.0.1" :cljr "localhost") ":" (:port server) "/")]
          (run-fx! s {:http {:method :post :url (str base "sync") :body "hello"
                          :headers {"Content-Type" "application/edn"}
                          :on-success [:fx-test/got :http-ok]
                          :on-failure [:fx-test/got :http-fail]}})
          (is (wait-until #(seq (:http-ok (st/db s))) 10000)
              (str "no http response; failures: " (pr-str (:http-fail (st/db s)))))
          (is (= {:status 200 :body "pong:hello"}
                 (select-keys (first (:http-ok (st/db s))) [:status :body]))))
        (finally ((:stop server)))))))

(deftest storage-round-trip
  (let [dir (temp-dir)
        s (new-store)]
    (storage/install! {:app-id "fx-test" :dir dir})
    (run-fx! s {:storage/put {:key :settings :value {:units :metric :goal 500}
                              :on-success [:fx-test/got :saved] :on-failure [:fx-test/got :err]}})
    (is (= [:settings] (:saved (st/db s))) "on-success follows the durable write")
    (run-fx! s {:storage/get {:key :settings :on-loaded [:fx-test/got :loaded]}})
    (is (= [{:units :metric :goal 500}] (:loaded (st/db s))))
    (testing "values survive a fresh install (re-read from disk)"
      (storage/install! {:app-id "fx-test" :dir dir})
      (run-fx! s {:storage/get {:key :settings :on-loaded [:fx-test/got :reloaded]}})
      (is (= [{:units :metric :goal 500}] (:reloaded (st/db s))))
      (is (= :ok (storage/status))))
    (run-fx! s {:storage/remove {:key :settings :on-success [:fx-test/got :removed]}})
    (is (= [:settings] (:removed (st/db s))))
    (run-fx! s {:storage/get {:key :settings :on-loaded [:fx-test/got :after-remove]}})
    (is (= [nil] (:after-remove (st/db s))))
    (is (nil? (:err (st/db s))))))

(defn- write-text! [path text]
  #?(:clj (spit path text :encoding "UTF-8") :cljr (System.IO.File/WriteAllText path text)))

(defn- read-text [path]
  #?(:clj (slurp path :encoding "UTF-8") :cljr (System.IO.File/ReadAllText path)))

(defn- files-in [dir]
  #?(:clj (sort (map #(.getName ^java.io.File %) (.listFiles (java.io.File. ^String dir))))
     :cljr (sort (map #(System.IO.Path/GetFileName ^String %) (System.IO.Directory/GetFiles dir)))))

(deftest storage-backup-and-corruption
  (testing "every write keeps the previous file as the backup"
    (let [dir (temp-dir)
          s (new-store)
          primary (path-join dir "fx-backup.edn")]
      (storage/install! {:app-id "fx-backup" :dir dir})
      (run-fx! s {:storage/put {:key :doc :value 1}})
      (run-fx! s {:storage/put {:key :doc :value 2}})
      (is (= {"doc" 2} (clojure.edn/read-string (read-text primary))))
      (is (= {"doc" 1} (clojure.edn/read-string (read-text (str primary ".bak")))))
      (testing "an unreadable primary: start from the backup, keep the bad file"
        (write-text! primary "{:doc 3 ;; torn")
        (storage/install! {:app-id "fx-backup" :dir dir})
        (run-fx! s {:storage/get {:key :doc :on-loaded [:fx-test/got :recovered] :on-failure [:fx-test/got :err]}})
        (is (= [1] (:recovered (st/db s))))
        (is (= :recovered (storage/status)))
        (is (some #(str/starts-with? % "fx-backup.edn.corrupt-") (files-in dir)) "the unreadable file is preserved")
        (run-fx! s {:storage/put {:key :doc :value 4 :on-success [:fx-test/got :saved-after-recovery]}})
        (is (= [:doc] (:saved-after-recovery (st/db s))))
        (is (= {"doc" 4} (clojure.edn/read-string (read-text primary))))
        (is (nil? (:err (st/db s)))))))
  (testing "primary and backup unreadable: reads and writes fail, nothing is overwritten"
    (let [dir (temp-dir)
          s (new-store)
          primary (path-join dir "fx-corrupt.edn")]
      (write-text! primary "#{:not-a-map")
      (write-text! (str primary ".bak") "[:not :a :map]")
      (storage/install! {:app-id "fx-corrupt" :dir dir})
      (run-fx! s {:storage/get {:key :doc :on-loaded [:fx-test/got :loaded] :on-failure [:fx-test/got :get-failed]}})
      (is (nil? (:loaded (st/db s))))
      (is (= [:store-corrupt] (mapv :reason (:get-failed (st/db s)))))
      (is (= :corrupt (storage/status)))
      (run-fx! s {:storage/put {:key :doc :value 5 :on-success [:fx-test/got :saved] :on-failure [:fx-test/got :put-failed]}})
      (is (nil? (:saved (st/db s))))
      (is (= [[:store-corrupt :doc]] (mapv (juxt :reason :key) (:put-failed (st/db s)))))
      (is (= "#{:not-a-map" (read-text primary)) "the primary is untouched")
      (is (= "[:not :a :map]" (read-text (str primary ".bak"))) "the backup is untouched")))
  (testing "without :on-failure a failed write still surfaces (throws), never silent"
    (let [dir (temp-dir)
          primary (path-join dir "fx-throw.edn")]
      (write-text! primary "{")
      (write-text! (str primary ".bak") "{")
      (storage/install! {:app-id "fx-throw" :dir dir})
      (let [s (new-store)]                       ; the default on-error rethrows
        (is (thrown? #?(:clj Exception :cljr System.Exception)
                     (run-fx! s {:storage/put {:key :doc :value 1}})))))))

(deftest file-effects
  (file/install! {:app-id "fx-test"})
  (let [dir (temp-dir)
        a (path-join dir "plan.edn")
        b (path-join dir "clips/squat.GIF")
        s (new-store)]
    (run-fx! s {:file/write {:path a :content "{:plan 1}"
                          :on-success [:fx-test/got :written] :on-failure [:fx-test/got :err]}})
    (is (wait-until #(seq (:written (st/db s))) 5000))
    (run-fx! s {:file/read {:path a :on-success [:fx-test/got :read] :on-failure [:fx-test/got :err]}})
    (is (wait-until #(seq (:read (st/db s))) 5000))
    (is (= ["{:plan 1}"] (:read (st/db s))))
    (run-fx! s {:file/copy {:from a :to b :on-success [:fx-test/got :copied] :on-failure [:fx-test/got :err]}})
    (is (wait-until #(seq (:copied (st/db s))) 5000))
    (run-fx! s {:file/list {:dir (path-join dir "clips") :extensions ["gif" "mp4"]
                         :on-success [:fx-test/got :listed] :on-failure [:fx-test/got :err]}})
    (is (wait-until #(seq (:listed (st/db s))) 5000))
    (is (= 1 (count (first (:listed (st/db s))))))
    (is (str/ends-with? (str/lower-case (first (first (:listed (st/db s))))) "squat.gif"))
    (run-fx! s {:file/read {:path (path-join dir "missing.txt")
                         :on-success [:fx-test/got :read2] :on-failure [:fx-test/got :missing]}})
    (is (wait-until #(seq (:missing (st/db s))) 5000))
    (is (nil? (:err (st/db s))))))

(deftest file-extension-helpers
  (is (= "gif" (file/extension "C:\\clips\\Squat.GIF")))
  (is (= "" (file/extension "/home/a.b/noext")))
  (is (file/matches-extensions? "x.MP4" ["mp4"]))
  (is (file/matches-extensions? "anything" [])))

(deftest lan-sync-protocol
  (let [dev {:id "win-1" :name "Desk" :platform :windows :secret "not sent"}
        env (sync/envelope :push dev 1000 {:records {:workouts [{:id "w1" :updated-at 5}]}})]
    (is (= {:id "win-1" :name "Desk" :platform :windows} (:device env)))
    (is (= env (sync/decode (sync/encode env))) "EDN round trip")
    (is (thrown? #?(:clj Exception :cljr System.Exception) (sync/decode "{:cloview.sync/v 99 :type :push}")))
    (is (thrown? #?(:clj Exception :cljr System.Exception) (sync/decode "not edn {")))
    (is (thrown? #?(:clj Exception :cljr System.Exception) (sync/envelope :bogus dev 0 nil))))
  (testing "last-writer-wins merge is deterministic and keeps tombstones"
    (let [local [{:id "a" :updated-at 10 :device "phone" :v 1}
                 {:id "b" :updated-at 20 :device "phone" :v 1}]
          remote [{:id "a" :updated-at 15 :device "desk" :v 2}
                  {:id "b" :updated-at 20 :device "desk" :v 2}
                  {:id "c" :updated-at 1 :device "desk" :deleted? true}]
          merged (sync/merge-records local remote)]
      (is (= [["a" 2] ["b" 1] ["c" nil]] (mapv (juxt :id :v) merged))
          "a: newer remote wins; b: tie broken by device id (phone > desk)")
      (is (= merged (sync/merge-records remote local)) "commutative")
      (is (= ["a" "b"] (mapv :id (sync/live-records merged))))))
  (is (= {:w [{:id 2 :updated-at 9}]}
         (sync/changes-since {:w [{:id 1 :updated-at 3} {:id 2 :updated-at 9}] :h [{:id 3 :updated-at 1}]} 5)))
  (is (= "000042" (sync/pairing-code 42)))
  (is (= "654321" (sync/pairing-code 7654321)))
  (testing "network effects already honour the contract shape"
    (sync/install!)
    (let [s (new-store)]
      (run-fx! s {:lan/discover {:timeout-ms 10 :on-failure [:fx-test/got :lan]}})
      (is (= [{:error :not-implemented :effect :lan/discover}] (:lan (st/db s)))))))
