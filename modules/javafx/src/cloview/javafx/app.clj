;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.javafx.app
  "JavaFX host for cloview apps (cljfx renderer + FX-thread executor + host effects).

    (app/start! {:store store :view root :title \"My app\" :width 480 :height 360})

  - the store's executor is switched to the JavaFX Application Thread, so dispatch! from
    any thread is marshalled with Platform/runLater;
  - every db change re-renders through cljfx (which diffs descriptions and only mutates
    what changed);
  - host effects are installed: :http :dispatch-later :timer/* :storage/* :file/*
    :lan/* and the JavaFX-specific :file/pick."
  (:require [cljfx.api :as fx]
            [cloview.core.store :as st]
            [cloview.core.tree :as t]
            [cloview.fx.file :as file]
            [cloview.fx.http :as http]
            [cloview.fx.lan-sync :as lan-sync]
            [cloview.fx.storage :as storage]
            [cloview.fx.timer :as timer]
            [cloview.javafx.cljfx :as cv]
            [cloview.javafx.lifecycles :as lc])
  (:import [javafx.application Platform]
           [javafx.event Event]
           [javafx.scene.control TextInputControl]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]))

(set! *warn-on-reflection* true)

(def fx-executor
  {:ui-thread? (fn [] (Platform/isFxApplicationThread))
   :post! (fn [f] (Platform/runLater ^Runnable f))})

(defn- payload [kind fx-event extra]
  (case kind
    :none nil
    :string (str fx-event)
    :boolean (boolean fx-event)
    :ratio (double fx-event)
    :select (get (::cv/options extra) fx-event)
    :tab-index (get (::cv/options extra) (int fx-event))
    :submit-text (.getText ^TextInputControl (.getSource ^Event fx-event))
    fx-event))

(defn map-event-handler
  "cljfx :fx.opt/map-event-handler translating JavaFX events to canonical payloads."
  [dispatch!]
  (fn [m]
    (let [kind (::cv/kind m)
          e (:fx/event m)]
      (when (and (::cv/consume? m) (instance? Event e))
        (.consume ^Event e))
      (t/emit! dispatch! (::cv/event m) (if (= kind :none) :none :value) (payload kind e m)))))

(defn- pick-files! [{:keys [title extensions multiple? on-picked on-cancel]} {:keys [dispatch!]}]
  (let [fc (FileChooser.)]
    (.setTitle fc (str (or title "Open")))
    (when (seq extensions)
      (.add (.getExtensionFilters fc)
            (FileChooser$ExtensionFilter. "Supported files"
                                          ^java.util.List (mapv #(str "*." %) extensions))))
    (let [files (if multiple?
                  (seq (.showOpenMultipleDialog fc nil))
                  (some-> (.showOpenDialog fc nil) list))]
      (if (seq files)
        (when on-picked (dispatch! (conj on-picked (mapv #(.getAbsolutePath ^java.io.File %) files))))
        (when on-cancel (dispatch! on-cancel))))))

(defn install-effects!
  "Install every host effect for the JVM."
  [{:keys [app-id] :or {app-id "cloview"}}]
  (timer/install!)
  (http/install!)
  (storage/install! {:app-id app-id})
  (file/install! {:app-id app-id})
  (lan-sync/install!)
  (st/reg-fx :file/pick pick-files!)
  :installed)

(defn stage-desc
  "Wrap a root description into a stage."
  [{:keys [title width height showing on-close x y]
    :or {title "cloview" width 480 height 360 showing true}}
   root-desc]
  (cond-> {:fx/type :stage
           :showing showing
           :title title
           :width (double width)
           :height (double height)
           :scene {:fx/type :scene
                   :root (or root-desc {:fx/type :region})}}
    on-close (assoc :on-close-request (fn [_] (on-close)))
    x (assoc :x (double x))
    y (assoc :y (double y))))

(defn create-renderer
  "cljfx renderer for `view` (db -> hiccup). `wrap-desc` (optional) post-processes the
  stage description (smoke tests use it to observe instances)."
  [{:keys [store view wrap-desc error-handler] :as opts}]
  (fx/create-renderer
   :middleware (fx/wrap-map-desc
                (fn [db]
                  (let [desc (stage-desc opts (cv/translate (view db)))]
                    (if wrap-desc (wrap-desc desc) desc))))
   :opts {:fx.opt/map-event-handler (map-event-handler #(st/dispatch! store %))
          :fx.opt/type->lifecycle lc/type->lifecycle}
   :error-handler (or error-handler (fn [^Throwable e] (.printStackTrace e)))))

(defn start!
  "Mount `view` for `store` in a JavaFX stage. Options: :store :view :title :width
  :height :app-id :exit-on-close? :install-effects? (default true). Returns
  {:renderer r :store s :stop! (fn [])}; deref (:mounted result) to wait for the first
  render."
  [{:keys [store view exit-on-close? install-effects? app-id]
    :or {install-effects? true}
    :as opts}]
  (when install-effects? (install-effects! {:app-id (or app-id "cloview")}))
  (st/set-executor! store fx-executor)
  (let [stop! (atom nil)
        renderer (create-renderer
                  (cond-> opts
                    exit-on-close? (assoc :on-close (fn [] (@stop!) (Platform/exit) (shutdown-agents)))))
        mounted (fx/mount-renderer (st/app-db store) renderer)]
    (reset! stop! (fn [] (fx/unmount-renderer (st/app-db store) renderer)))
    {:renderer renderer :store store :stop! @stop! :mounted mounted}))
