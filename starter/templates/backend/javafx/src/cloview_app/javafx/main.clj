(ns cloview-app.javafx.main
  "Cloview App on JavaFX, with its backend process.  bb dev (when javafx is the primary
  target) or bb run:javafx, from the project folder."
  (:require [cloview.bridge :as bridge]
            [cloview.bridge.manifest :as manifest]
            [cloview.core.store :as st]
            [cloview.javafx.app :as app]
            [cloview-app.core :as core]))

(defn app-dir
  "appDir, the folder that holds backend/: the cloview.app.dir property when set (the
  :run alias of javafx/deps.edn passes the project folder; a packaged app passes its
  install folder, e.g. jpackage --java-options -Dcloview.app.dir=$APPDIR), else the
  working directory, or its parent when the app was started from javafx/."
  [prop cwd]
  (let [has-backend? (fn [d] (.isFile (java.io.File. (str d "/backend/backend-manifest.json"))))
        dir (cond
              (seq prop) prop
              (has-backend? cwd) cwd
              (has-backend? (str cwd "/..")) (str cwd "/..")
              :else cwd)]
    (.getCanonicalPath (java.io.File. (str dir)))))

(defn -main [& _args]
  (core/register!)
  (let [app-dir (app-dir (System/getProperty "cloview.app.dir") (System/getProperty "user.dir"))]
    (app/start! {:store core/store
                 :view core/root
                 :title "Cloview App"
                 :width 480
                 :height 300
                 :app-id "cloview-app"
                 :exit-on-close? true})
    (bridge/install! {:manifests [(manifest/parse (slurp (str app-dir "/backend/backend-manifest.json") :encoding "UTF-8"))]
                      :vars {:appDir app-dir
                             :dataDir (str (System/getProperty "user.home") "/.cloview-app")
                             :tempDir (System/getProperty "java.io.tmpdir")}
                      :dispatch! (fn [ev] (st/dispatch! core/store ev))
                      :on-status [:app/backend-status]
                      :on-log (fn [id {:keys [level message]}] (println (str "[" (name id) " " level "] " message)))
                      :app-info {:name "cloview-app"}})))
