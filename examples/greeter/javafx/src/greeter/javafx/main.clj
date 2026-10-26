;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns greeter.javafx.main
  "Greeter on JavaFX (cljfx).  Run: bb run:javafx   (or: cd examples/greeter/javafx && clj -M:run)"
  (:require [cloview.javafx.app :as app]
            [greeter.core :as g])
  (:gen-class))

(defn -main [& _args]
  (g/register!)
  (app/start! {:store g/store
               :view g/root
               :title "cloview greeter — JavaFX"
               :width 420
               :height 300
               :app-id "greeter"
               :exit-on-close? true}))
