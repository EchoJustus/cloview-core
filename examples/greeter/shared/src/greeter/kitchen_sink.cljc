;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns greeter.kitchen-sink
  "Every canonical tag in one tree. Host smoke tests mount it, patch it and report
  per-tag results (tag coverage). Not part of the Greeter UI."
  (:require [cloview.core.store :as st]))

(def initial-db {:text "" :checked false :switch true :sel :b :ratio 0.3 :tab :one
                 :modal? false :n 0 :items [:a :b :c] :chips #{:x}})

(def handlers
  {:events-db
   {:ks/text (fn [db [_ v]] (assoc db :text v))
    :ks/check (fn [db [_ v]] (assoc db :checked v))
    :ks/switch (fn [db [_ v]] (assoc db :switch v))
    :ks/select (fn [db [_ v]] (assoc db :sel v))
    :ks/ratio (fn [db [_ v]] (assoc db :ratio v))
    :ks/tab (fn [db [_ v]] (assoc db :tab v))
    :ks/open-modal (fn [db _] (assoc db :modal? true))
    :ks/close-modal (fn [db _] (assoc db :modal? false))
    :ks/bump (fn [db _] (update db :n inc))
    :ks/rotate (fn [db _] (update db :items #(vec (concat (rest %) [(first %)]))))
    :ks/press (fn [db _] (update db :presses (fnil inc 0)))
    :ks/chip (fn [db [_ id on?]] (update db :chips (if on? conj disj) id))}})

(defn register! [] (st/register-handlers! handlers))

(def tag->test-id
  "Which test-id carries each V1 tag in `view` (:ui/tab is checked through ks-tabs)."
  {:ui/column "ks-root" :ui/row "ks-row" :ui/grid "ks-grid" :ui/wrap "ks-wrap"
   :ui/toggle "ks-toggle" :ui/scroll "ks-scroll"
   :ui/box "ks-box" :ui/align "ks-align" :ui/spacer "ks-spacer" :ui/text "ks-text"
   :ui/button "ks-button" :ui/text-input "ks-input" :ui/checkbox "ks-checkbox"
   :ui/switch "ks-switch" :ui/select "ks-select" :ui/image "ks-image"
   :ui/split-view "ks-split" :ui/tabs "ks-tabs" :ui/tab "ks-tab-one" :ui/modal "ks-modal"
   :ui/native "ks-native"})

(def patch-events
  "Events the smokes dispatch after mounting, to exercise prop patches per tag."
  [[:ks/text "typed"] [:ks/check true] [:ks/switch false] [:ks/select :a] [:ks/ratio 0.6]
   [:ks/tab :two] [:ks/open-modal] [:ks/bump] [:ks/rotate] [:ks/close-modal]
   [:ks/chip :x false] [:ks/chip :y true]])

(defn view
  "`native-host` is the host-specific :host map for :ui/native (nil = omit the node)."
  [db native-host]
  [:ui/column {:gap 6 :style {:padding 8} :test-id "ks-root"}
   [:ui/row {:gap 4 :align :center :test-id "ks-row"}
    [:ui/text {:test-id "ks-text" :text (str "presses: " (:presses db 0))}]
    [:ui/spacer {:test-id "ks-spacer"}]
    [:ui/button {:test-id "ks-button" :label "Press" :on-press [:ks/press]}]]
   [:ui/grid {:test-id "ks-grid" :columns [:auto {:fr 1}] :gap 4}
    [:ui/text {:grid/row 0 :grid/col 0 :text "Input"}]
    [:ui/text-input {:grid/row 0 :grid/col 1 :test-id "ks-input" :value (:text db)
                     :placeholder "type" :on-change [:ks/text]}]
    [:ui/text {:grid/row 1 :grid/col 0 :grid/col-span 2 :text "spanning row"}]]
   [:ui/box {:test-id "ks-box"
             :style {:background "#eeeeff" :border {:width 1 :color "#9999aa"} :radius 6 :padding 6}}
    [:ui/text {:text "boxed" :style {:font-weight :semibold :italic? true}}]]
   [:ui/align {:test-id "ks-align" :align :center :style {:height 30}}
    [:ui/text "centered"]]
   [:ui/row {:gap 8}
    [:ui/checkbox {:test-id "ks-checkbox" :label "check" :checked (:checked db) :on-toggle [:ks/check]}]
    [:ui/switch {:test-id "ks-switch" :label "switch" :checked (:switch db) :on-toggle [:ks/switch]}]
    [:ui/select {:test-id "ks-select" :style {:width 140}
                 :options [{:value :a :label "Alpha"} {:value :b :label "Beta"}]
                 :value (:sel db) :on-select [:ks/select]}]]
   [:ui/wrap {:test-id "ks-wrap" :gap 4}
    [:ui/toggle {:key :x :test-id "ks-toggle" :label "chip x" :checked (contains? (:chips db) :x)
                 :on-toggle [:ks/chip :x]}]
    [:ui/toggle {:key :y :test-id "ks-toggle-y" :label "chip y" :checked (contains? (:chips db) :y)
                 :on-toggle [:ks/chip :y]}]]
   [:ui/image {:test-id "ks-image" :src nil :style {:width 24 :height 24}}]
   [:ui/scroll {:test-id "ks-scroll" :direction :vertical :style {:height 60}}
    (into [:ui/column {:gap 2}]
          (for [k (:items db)] [:ui/text {:key k :text (str "item " (name k))}]))]
   [:ui/split-view {:test-id "ks-split" :ratio (:ratio db) :on-resize [:ks/ratio] :style {:height 60}}
    [:ui/text "left pane"]
    [:ui/text "right pane"]]
   [:ui/tabs {:test-id "ks-tabs" :selected (:tab db) :on-select [:ks/tab] :style {:height 80}}
    [:ui/tab {:id :one :label "One" :test-id "ks-tab-one"} [:ui/text "first tab"]]
    [:ui/tab {:id :two :label "Two" :test-id "ks-tab-two"} [:ui/text "second tab"]]]
   [:ui/modal {:test-id "ks-modal" :open? (:modal? db) :title "Kitchen sink dialog"
               :on-dismiss [:ks/close-modal]}
    [:ui/column {:gap 4}
     [:ui/text "modal body"]
     [:ui/button {:label "Close" :on-press [:ks/close-modal]}]]]
   (when native-host
     [:ui/native {:key :ks-native :test-id "ks-native" :props {:n (:n db)} :host native-host}])])
