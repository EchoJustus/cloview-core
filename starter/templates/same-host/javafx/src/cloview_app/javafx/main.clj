(ns cloview-app.javafx.main
  "Cloview App on JavaFX.  bb dev (when javafx is the primary target) or bb run:javafx."
  (:require [cloview.core.store :as st]
            [cloview.javafx.app :as app]
            [cloview-app.core :as core]))

(defn -main [& _args]
  (core/register!)
  (app/start! {:store core/store
               :view core/root
               :title "Cloview App"
               :width 480
               :height 420
               :app-id "cloview-app"          ; storage file name
               :exit-on-close? true})
  (st/dispatch! core/store [:notes/load]))
