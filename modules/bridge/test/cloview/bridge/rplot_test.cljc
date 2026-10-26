;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.rplot-test
  "R plots (cloview.bridge.rplot): the :r/plot effect, request coalescing and the view
  against an in-process stand-in for backend.R, then the real backend.R through the
  launcher when an `Rscript` is on the PATH (skipped with a passing marker otherwise,
  and on ClojureDart)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloview.bridge :as bridge]
            [cloview.bridge.loopback :as loopback]
            [cloview.bridge.manifest :as manifest]
            [cloview.bridge.process :as process]
            [cloview.bridge.protocol :as p]
            [cloview.bridge.rplot :as rplot]
            [cloview.core.store :as st]
            [cloview.core.tree :as t]))

(def ^:private loopback-manifest
  {:manifest_version 1 :id "r" :protocol p/protocol-version :command {:default ["Rscript" "backend.R"]}
   :start "on-launch"})

;; ---------------------------------------------------------------------------
;; host helpers (files, PATH, sleeping)

#?(:cljd nil
   :default
   (do
     (defn- file-exists? [path]
       #?(:clj (.isFile (java.io.File. ^String path)) :cljr (System.IO.File/Exists path)))

     (defn- parent [path]
       #?(:clj (.getParent (java.io.File. ^String path))
          :cljr (some-> (System.IO.Directory/GetParent path) (.FullName))))

     (defn- getenv [k]
       #?(:clj (System/getenv k) :cljr (System.Environment/GetEnvironmentVariable k)))

     (defn- repo-file
       "`rel` in the repository above the working directory or a CLOJURE_LOAD_PATH entry."
       [rel]
       (let [cwd #?(:clj (System/getProperty "user.dir") :cljr System.Environment/CurrentDirectory)]
         (some (fn [start]
                 (loop [dir start]
                   (when dir
                     (let [f (str dir "/" rel)]
                       (if (file-exists? f) f (let [p (parent dir)] (when (not= p dir) (recur p))))))))
               (into [cwd] (remove str/blank?) (str/split (or (getenv "CLOJURE_LOAD_PATH") "") #"[;:](?![\\/])")))))

     (defn- slurp* [rel]
       (let [f (repo-file rel)]
         #?(:clj (slurp f) :cljr (System.IO.File/ReadAllText f))))

     (defn- rscript
       "Full path of Rscript on the PATH, or nil."
       []
       (let [win? (= "windows" (process/platform))
             names (if win? ["Rscript.exe"] ["Rscript"])]
         (some (fn [dir] (some (fn [n] (let [f (str dir "/" n)] (when (file-exists? f) f))) names))
               (remove str/blank? (str/split (or (getenv "PATH") "") (if win? #";" #":"))))))

     (defn- temp-dir []
       #?(:clj (System/getProperty "java.io.tmpdir") :cljr (System.IO.Path/GetTempPath)))

     (defn- sleep! [ms]
       #?(:clj (Thread/sleep (long ms)) :cljr (System.Threading.Thread/Sleep (int ms))))

     (defn- wait-for [pred]
       (loop [n 0]
         (cond (pred) true
               (> n 1200) false          ; 60 s: the first start loads ggplot2
               :else (do (sleep! 50) (recur (inc n))))))))

(defn- fake-r
  "Answers plot/render like backend.R; while `hold?` is true the answers wait in `held`.
  Every request's params are conj'ed to `seen`."
  [held hold? seen]
  (fn [{:keys [method params]} {:keys [respond! fail!]}]
    (when (= "plot/render" method)
      (swap! seen conj params)
      (let [answer (fn []
                     (if (= "broken" (:plot params))
                       (fail! -32050 "object 'kg' not found" {:plot "broken"})
                       (respond! {:path (str "/tmp/cloview-r/" (:tag params) "." (:format params))
                                  :width_px (* (:width params) (:scale params))
                                  :height_px (* (:height params) (:scale params))
                                  :format (:format params) :ms 12})))]
        (if @hold? (swap! held conj {:params params :answer answer}) (answer))))))

(defn- app-store []
  (let [store (st/create-store {:db {}})]
    (st/register-handlers!
     {:events-fx {:app/chart (fn [_ [_ w]] {:r/plot {:id :trend :plot "weight_trend"
                                                   :data {:date ["2026-10-01" "2026-10-08"] :kg [71.2 70.8]}
                                                   :width w :height 300}})
                  :app/broken (fn [_ _] {:r/plot {:id :charts/bad :plot "broken" :format "svg"}})
                  ;; non-ASCII text crosses the pipe in both directions (any locale)
                  :app/unknown-u (fn [_ _] {:r/plot {:id :u :plot "nope \u00fc\u4f53"}})
                  :app/hist (fn [_ _] {:r/plot {:id :hist :plot "histogram" :data {:v [1 2 2 3]}
                                               :options {:label "Gewicht \u2014 \u00fcmlaut \u4f53\u91cd"}
                                               :width 300 :height 200 :scale 1}})}})
    store))

(deftest plots-through-the-store
  (let [held (atom []) hold? (atom false) seen (atom [])
        store (app-store)
        state #(rplot/plot-state (st/db store) %)
        view (fn [id] (t/normalize (rplot/plot-view (st/db store) id {})))]
    (bridge/install! {:manifests [loopback-manifest]
                      :transport-fn (loopback/transport-fn (fake-r held hold? seen))
                      :dispatch! (fn [ev] (st/dispatch! store ev))})
    (rplot/install!)
    (is (= "No plot yet" (get-in (view :trend) [:props :text])))
    (testing "a render"
      (st/dispatch! store [:app/chart 600])
      (is (= :ready (:status (state :trend))))
      (is (= {:path "/tmp/cloview-r/trend-1.png" :width 600 :height 300 :width-px 1200 :height-px 600
              :format "png" :ms 12}
             (:image (state :trend)))))
    (testing "the view shows the image at its logical size"
      (let [tree (view :trend)
            img (t/find-by-test-id tree "r-plot-trend")]
        (is (empty? (t/problems tree)))
        (is (= {:file "/tmp/cloview-r/trend-1.png"} (get-in img [:props :src])))
        (is (= {:width 600 :height 300} (get-in img [:props :style])))
        (is (nil? (t/find-by-test-id tree "r-plot-trend-note")))))
    (testing "requests made while one renders coalesce to the latest"
      (reset! hold? true)
      (st/dispatch! store [:app/chart 700])
      (st/dispatch! store [:app/chart 800])
      (st/dispatch! store [:app/chart 900])
      (is (= 1 (count @held)) "one request in flight per plot")
      (is (= 700 (get-in (first @held) [:params :width])))
      (is (= :rendering (:status (state :trend))))
      (is (= 900 (get-in (state :trend) [:pending :width])))
      (is (= "/tmp/cloview-r/trend-1.png" (get-in (state :trend) [:image :path])) "the previous image stays")
      (is (= "Updating the plot..." (get-in (t/find-by-test-id (view :trend) "r-plot-trend-note") [:props :text])))
      ((:answer (first @held)))
      (is (= 2 (count @held)) "the end of a render sends the waiting request")
      (is (= 900 (get-in (second @held) [:params :width])) "800 was superseded and never rendered")
      (is (= "trend-3" (get-in (second @held) [:params :tag])))
      (is (= :rendering (:status (state :trend))))
      ((:answer (second @held)))
      (is (= :ready (:status (state :trend))))
      (is (= 900 (get-in (state :trend) [:image :width])))
      (is (nil? (:pending (state :trend)))))
    (testing "failures"
      (reset! hold? false)
      (st/dispatch! store [:app/broken])
      (is (= :failed (:status (state :charts/bad))))
      (is (= "object 'kg' not found" (:error (state :charts/bad))))
      (is (= "The plot could not be drawn: object 'kg' not found"
             (get-in (view :charts/bad) [:props :text])))
      (is (= {:tag "charts_bad-1" :format "svg" :width 640 :height 400 :scale 2 :options {} :data {}}
             (select-keys (last @seen) [:tag :format :width :height :scale :options :data]))
          "defaults, and a file-name-safe tag")
      (is (= :ready (:status (state :trend))) "other plots are unaffected"))
    (testing "a bad :r/plot is a programming error"
      (is (thrown? #?(:cljd dynamic :default clojure.lang.ExceptionInfo) (rplot/request-fx {} {:plot "x"})))
      (is (thrown? #?(:cljd dynamic :default clojure.lang.ExceptionInfo)
                   (rplot/request-fx {} {:id :x :plot "x" :format "gif"}))))
    (bridge/stop! :r)))

(deftest the-shipped-manifest-is-valid
  #?(:cljd (is true "reads files: JVM/CLR only")
     :default
     (let [m (manifest/parse (slurp* "modules/bridge/backends/r/backend-manifest.json"))]
       (is (= "on-demand" (:start m)))
       (is (= {:id "r"
               :command ["/opt/app/R/bin/Rscript.exe" "--vanilla" "/opt/app/backend/r/backend.R"]
               :cwd "/opt/app/backend/r"
               :env {"CLOVIEW_R_OUT" "/tmp/x/cloview-r"}}
              (manifest/launch-spec m "windows" {:appDir "/opt/app" :tempDir "/tmp/x"})))
       (is (= "Rscript" (first (:command (manifest/launch-spec m "linux" {:appDir "/opt/app" :tempDir "/tmp/x"}))))))))

(deftest r-backend-over-stdio
  #?(:cljd (is true "no process transport on ClojureDart yet")
     :default
     (let [r (rscript)
           script (repo-file "modules/bridge/backends/r/backend.R")]
       (if-not (and r script)
         (is true "Rscript or backend.R not found: skipped")
         (let [shipped (manifest/parse (slurp* "modules/bridge/backends/r/backend-manifest.json"))
               store (app-store)
               state #(rplot/plot-state (st/db store) %)]
           (st/register-handlers! {:events-db {:app/r-status (fn [db _] db)}})
           (bridge/install! {:manifests [(assoc shipped :command {:default [r "--vanilla" script]} :cwd nil)]
                             :vars {:appDir "unused" :tempDir (temp-dir)}
                             :dispatch! (fn [ev] (st/dispatch! store ev))
                             :on-status [:app/r-status]})
           (rplot/install! {:backend :r})
           (st/dispatch! store [:app/chart 640])
           (is (wait-for #(contains? #{:ready :failed} (:status (state :trend)))))
           (let [{:keys [status image error]} (state :trend)]
             (is (= :ready status) (str error))
             (is (= [1280 600 "png"] [(:width-px image) (:height-px image) (:format image)]))
             (is (file-exists? (:path image)))
             (is (str/includes? (str/replace (:path image) "\\" "/") "cloview-r") "written to CLOVIEW_R_OUT"))
           (testing "non-ASCII text in a request (the worker may run in the C locale)"
             (st/dispatch! store [:app/hist])
             (is (wait-for #(contains? #{:ready :failed} (:status (state :hist)))))
             (is (= :ready (:status (state :hist))) (str (:error (state :hist))))
             (is (= [300 200] [(get-in (state :hist) [:image :width-px]) (get-in (state :hist) [:image :height-px])]))
             (st/dispatch! store [:app/unknown-u])
             (is (wait-for #(contains? #{:ready :failed} (:status (state :u)))))
             (is (str/includes? (str (:error (state :u))) "nope \u00fc\u4f53")
                 "the name came back byte for byte in the error message"))
           (testing "an R error comes back as a failure"
             (st/dispatch! store [:app/broken])
             (is (wait-for #(contains? #{:ready :failed} (:status (state :charts/bad)))))
             (is (= :failed (:status (state :charts/bad))))
             (is (str/includes? (str (:error (state :charts/bad))) "unknown plot 'broken'")))
           (testing "malformed params from a direct :backend/call are refused, and the worker lives on"
             (st/register-handlers! {:events-fx {:app/raw (fn [_ [_ k params]]
                                                            {:backend/call {:backend :r :method "plot/render" :params params
                                                                            :on-success [:app/raw-done k]
                                                                            :on-failure [:app/raw-done k]}})}
                                     :events-db {:app/raw-done (fn [db [_ k v]] (assoc-in db [:raw k] v))}})
             (doseq [[k params] [[:array {:plot [1 2]}] [:object {:plot {:a 1}}] [:number {:plot 3}]
                                 [:width {:plot "histogram" :width "wide"}] [:scalar 7]]]
               (st/dispatch! store [:app/raw k params])
               (is (wait-for #(some? (get-in (st/db store) [:raw k]))) (str k))
               (is (= -32602 (:code (get-in (st/db store) [:raw k]))) (str k " " (get-in (st/db store) [:raw k]))))
             (st/dispatch! store [:app/hist])
             (is (wait-for #(= :ready (:status (state :hist)))))
             (is (= :ready (bridge/status :r)) "no crash, no restart"))
           (testing "orderly stop"
             (bridge/stop! :r)
             (is (wait-for #(= :stopped (bridge/status :r)))))
           (testing "a plots file that prints while it loads does not corrupt the protocol"
             (let [dir (temp-dir)
                   noisy (str dir "/noisy-plots.R")]
               #?(:clj (spit noisy "cat(\"loading my plots\")\nprint(1:3)\nplot_hello <- function(d, o) plot(1:3)\n")
                  :cljr (System.IO.File/WriteAllText noisy "cat(\"loading my plots\")\nprint(1:3)\nplot_hello <- function(d, o) plot(1:3)\n"))
               (bridge/install! {:manifests [(assoc shipped :command {:default [r "--vanilla" script noisy]} :cwd nil)]
                                 :vars {:appDir "unused" :tempDir dir}
                                 :dispatch! (fn [ev] (st/dispatch! store ev))
                                 :on-status [:app/r-status]})
               (st/dispatch! store [:app/raw :hello {:plot "hello" :width 320 :height 240}])
               (is (wait-for #(some? (get-in (st/db store) [:raw :hello]))))
               (is (= [320 240] [(get-in (st/db store) [:raw :hello :width_px]) (get-in (st/db store) [:raw :hello :height_px])])
                   (str (get-in (st/db store) [:raw :hello])))
               (bridge/stop! :r)
               (is (wait-for #(= :stopped (bridge/status :r)))))))))))
