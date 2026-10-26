;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.process-test
  "A real child process: the Java reference backend (modules/bridge/backends/java),
  started through cloview.bridge.process and driven by the client, on Clojure/JVM and
  ClojureCLR. Skipped (with a passing marker) when Backend.java or `java` cannot be
  found, and on ClojureDart (no process transport there yet)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloview.bridge.client :as client]
            [cloview.bridge.process :as process]
            [cloview.fx.timer :as timer]))

#?(:cljd nil
   :default
   (do
     (defn- file-exists? [path]
       #?(:clj (.isFile (java.io.File. ^String path)) :cljr (System.IO.File/Exists path)))

     (defn- parent [path]
       #?(:clj (.getParent (java.io.File. ^String path))
          :cljr (some-> (System.IO.Directory/GetParent path) (.FullName))))

     (defn- start-dirs []
       (let [cwd #?(:clj (System/getProperty "user.dir") :cljr System.Environment/CurrentDirectory)
             load-path (or #?(:clj (System/getenv "CLOJURE_LOAD_PATH")
                              :cljr (System.Environment/GetEnvironmentVariable "CLOJURE_LOAD_PATH"))
                           "")]
         (into [cwd] (remove str/blank?) (str/split load-path #"[;:](?![\\/])"))))

     (defn- backend-source
       "modules/bridge/backends/java/Backend.java of the repository above a start dir."
       []
       (some (fn [start]
               (loop [dir start]
                 (when dir
                   (let [f (str dir "/modules/bridge/backends/java/Backend.java")]
                     (if (file-exists? f) f (let [p (parent dir)] (when (not= p dir) (recur p))))))))
             (start-dirs)))

     (defn- java-command []
       #?(:clj (let [home (System/getProperty "java.home")
                     exe (java.io.File. (str home "/bin/java.exe"))]
                 (if (.isFile exe) (str exe) (str home "/bin/java")))
          :cljr "java"))

     (defn- await! [p] (deref p 30000 ::timeout))))

(deftest java-reference-backend-over-stdio
  #?(:cljd (is true "no process transport on ClojureDart yet")
     :default
     (if-some [src (backend-source)]
       (let [ready (promise) exited (promise)
             statuses (atom [])
             c-atom (atom nil)
             t (try (process/spawn {:command [(java-command) src] :env {"JAVA_TOOL_OPTIONS" ""}}
                                   {:on-line (fn [l] (client/receive! @c-atom l))
                                    :on-exit (fn [info] (client/closed! @c-atom info) (deliver exited info))})
                    (catch #?(:clj Throwable :cljr System.Exception) _ nil))]
         (if (nil? t)
           (is true "java not found: skipped")
           (let [c (client/create t {:schedule! timer/schedule! :startup-timeout-ms 60000
                                     :on-status (fn [s info] (swap! statuses conj s)
                                                  (when (= s :ready) (deliver ready info)))})]
             (reset! c-atom c)
             (client/start! c {:name "process-test"})
             (is (= "java-reference" (:name (await! ready))))
             ;; a plain call
             (let [r (promise)]
               (client/request! c "math/add" {:a 2 :b 40} {:on-result #(deliver r %) :on-error #(deliver r %)})
               (is (= 42 (await! r))))
             ;; backend error
             (let [r (promise)]
               (client/request! c "no/such" nil {:on-result #(deliver r %) :on-error #(deliver r %)})
               (is (= :method-not-found (:reason (await! r)))))
             ;; progress and stream notifications, then the result
             (let [r (promise) seen (atom [])]
               (client/request! c "demo/count" {:to 3 :delay_ms 10}
                                {:on-notify (fn [m ps] (swap! seen conj [m ps]))
                                 :on-result #(deliver r %) :on-error #(deliver r %)})
               (is (= {:counted 3} (await! r)))
               (is (= [1 2 3] (keep #(get-in % [1 :item]) @seen)))
               (is (= 3 (count (filter #(= "$/progress" (first %)) @seen)))))
             ;; cancellation of a long request
             (let [r (promise) first-progress (promise)]
               (client/request! c "demo/count" {:to 100 :delay_ms 50}
                                {:app-id :long :on-notify (fn [_ _] (deliver first-progress true))
                                 :on-result #(deliver r [:ok %]) :on-error #(deliver r (:reason %))})
               (await! first-progress)
               (is (true? (client/cancel! c :long)))
               (is (= :cancelled (await! r))))
             ;; orderly shutdown: the process exits by itself with 0
             (let [done (promise)]
               (client/stop! c #(deliver done true))
               (is (true? (await! done)))
               (is (= 0 (:exit (await! exited))))
               (is (= :stopped (client/status c)))
               (is (= [:starting :ready :stopping :stopped] @statuses))))))
       (is true "modules/bridge/backends/java/Backend.java not found: skipped"))))
