;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.rplot
  "R plots in cloview views (docs/R_VISUALIZATION_INTEGRATION.md). A long-lived R
  process (modules/bridge/backends/r/backend.R) speaks the bridge protocol and renders
  ggplot2 or base-R figures to PNG/SVG files; a canonical :ui/image shows them.

    ;; once, after cloview.bridge/install! with the R backend's manifest (id \"r\"):
    (rplot/install! {:backend :r})

    ;; from any event handler (data as columns):
    {:r/plot {:id :weight-trend :plot \"weight_trend\"
              :data {:date [\"2026-10-01\" \"2026-10-08\"] :kg [71.2 70.8]}
              :width 640 :height 360}}          ; logical px; :scale 2 (default) renders 1280x720

    ;; in the view:
    (rplot/plot-view db :weight-trend {})

  :r/plot keys: :id (keyword, the slot), :plot (name of an R function plot_<name>),
  :data, :options (passed to the R function), :width :height (logical px, default
  640x400), :scale (default 2), :format \"png\"|\"svg\" (default \"png\"), :timeout-ms.

  State: [:cloview/r-plots id] =
    {:status :rendering|:ready|:failed :seq n :spec spec :pending spec
     :image {:path :width :height :width-px :height-px :format :ms} :error \"...\"}
  One request per slot is in flight at a time. A new :r/plot for a slot that is still
  rendering replaces the waiting one (:pending) and is sent when the current render
  ends, so a window resize that asks for twenty sizes renders at most two. R works
  through requests one by one, so this matters more than cancelling would.
  While a new image renders, the previous one stays on screen.

  Every host that can start processes can use it (JVM, CLR; ClojureDart once the
  bridge's Dart process transport lands, Stage 3)."
  (:require [clojure.string :as str]
            [cloview.core.store :as st]
            [cloview.fx.codec :as codec]))

(defonce ^:private cfg (atom {:backend :r}))

(defn plot-state
  "The slot of plot `id` in `db` (see the ns doc), or nil."
  [db id]
  (get-in db [:cloview/r-plots id]))

(defn- tag-of [id n]
  (str (str/replace (codec/json-key id) #"[^A-Za-z0-9_-]" "_") "-" n))

(defn- check-spec [{:keys [id plot format] :as spec}]
  (when-not (keyword? id)
    (throw (ex-info ":r/plot needs :id, a keyword naming the plot slot" {:spec spec})))
  (when-not (and (string? plot) (seq plot))
    (throw (ex-info ":r/plot needs :plot, the name of an R plot function" {:spec spec})))
  (when-not (contains? #{nil "png" "svg"} format)
    (throw (ex-info ":r/plot :format must be \"png\" or \"svg\"" {:spec spec}))))

(defn- send-fx
  "Effects that send `spec` as request number n of its slot."
  [db {:keys [id plot data options width height scale format timeout-ms] :as spec}]
  (let [n (inc (or (:seq (plot-state db id)) 0))
        params {:plot plot
                :data (or data {})
                :options (or options {})
                :width (or width 640)
                :height (or height 400)
                :scale (or scale 2)
                :format (or format "png")
                :tag (tag-of id n)}]
    {:db (update-in db [:cloview/r-plots id] merge {:status :rendering :seq n :spec spec :pending nil})
     :backend/call (cond-> {:backend (:backend @cfg)
                            :method "plot/render"
                            :id [:r/plot id n]
                            :params params
                            :on-success [:r/plot-done id n]
                            :on-failure [:r/plot-failed id n]}
                     timeout-ms (assoc :timeout-ms timeout-ms))}))

(defn request-fx
  "The effects of asking for `spec` (what the :r/plot effect does), for handlers that
  prefer to return them directly."
  [db spec]
  (check-spec spec)
  (if (= :rendering (:status (plot-state db (:id spec))))
    {:db (assoc-in db [:cloview/r-plots (:id spec) :pending] spec)}
    (send-fx db spec)))

(defn- finish
  "Effects after request n of `id` ended with `changes`; sends the waiting request."
  [db id n changes]
  (let [slot (plot-state db id)]
    (if (not= n (:seq slot))
      {}
      (let [db' (update-in db [:cloview/r-plots id] merge changes)]
        (if-some [next-spec (:pending slot)]
          (send-fx db' next-spec)
          {:db db'})))))

(def handlers
  {:events-fx
   {:r/plot-start
    (fn [{:keys [db]} [_ spec]] (request-fx db spec))

    :r/plot-done
    (fn [{:keys [db]} [_ id n result]]
      (let [{:keys [width height]} (:spec (plot-state db id))]
        (finish db id n {:status :ready
                         :error nil
                         :image {:path (:path result)
                                 :width (or width 640)
                                 :height (or height 400)
                                 :width-px (:width_px result)
                                 :height-px (:height_px result)
                                 :format (:format result)
                                 :ms (:ms result)}})))

    :r/plot-failed
    (fn [{:keys [db]} [_ id n failure]]
      (finish db id n {:status :failed :error (or (:error failure) (str (:reason failure)))}))}})

(defn install!
  "Register the :r/plot effect and its events. Options: :backend, the bridge backend
  id of the R process (default :r). Call after cloview.bridge/install!."
  ([] (install! {}))
  ([{:keys [backend]}]
   (swap! cfg assoc :backend (or backend :r))
   (st/register-handlers! handlers)
   (st/reg-fx :r/plot (fn [v {:keys [dispatch!]}]
                        (doseq [spec (if (map? v) [v] v)] (dispatch! [:r/plot-start spec]))))
   :installed))

(def ^:private note-style {:font-size 11 :color "#5F6368"})

(defn plot-view
  "The view of plot `id`: its image at the logical size it was rendered for, the
  previous image (with a short note) while a new one renders, or a one-line text when
  there is nothing to show. Options: :style (merged into the image style),
  :empty-text."
  [db id {:keys [style empty-text]}]
  (let [{:keys [status image error]} (plot-state db id)
        test-id (str "r-plot-" (str/replace (codec/json-key id) "/" "-"))]
    (cond
      image
      [:ui/column {:key [:cloview/r-plot id] :gap 4}
       [:ui/image {:test-id test-id
                   :src {:file (:path image)}
                   :fit :contain
                   :style (merge {:width (:width image) :height (:height image)} style)}]
       (case status
         :rendering [:ui/text {:test-id (str test-id "-note") :style note-style :text "Updating the plot..."}]
         :failed [:ui/text {:test-id (str test-id "-note") :style (assoc note-style :color "#B3261E") :wrap? true
                            :text (str "The plot could not be updated: " error)}]
         nil)]

      (= :failed status)
      [:ui/text {:key [:cloview/r-plot id] :test-id (str test-id "-note") :wrap? true
                 :style (assoc note-style :color "#B3261E") :text (str "The plot could not be drawn: " error)}]

      (= :rendering status)
      [:ui/text {:key [:cloview/r-plot id] :test-id (str test-id "-note") :style note-style :text "Drawing the plot..."}]

      :else
      [:ui/text {:key [:cloview/r-plot id] :test-id (str test-id "-note") :style note-style
                 :text (or empty-text "No plot yet")}])))
