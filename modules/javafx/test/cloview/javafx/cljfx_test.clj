;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.javafx.cljfx-test
  "cljfx description snapshots — pure data, no JavaFX toolkit required."
  (:require [clojure.test :refer [deftest is testing]]
            [cloview.core.tree :as t]
            [cloview.javafx.cljfx :as cv]))

(defn greeter [{:keys [name greeting]}]
  [:ui/column {:gap 8 :style {:padding 16} :test-id "root"}
   [:ui/text {:test-id "title" :text "Greeter"
              :style {:font-size 20 :font-weight :bold :color "#3366CC"}}]
   [:ui/text-input {:test-id "name" :value name :placeholder "Your name"
                    :on-change [:app/set-name]}]
   [:ui/button {:test-id "greet" :label "Greet" :on-press [:app/greet]}]
   (when greeting [:ui/text {:key :greeting :test-id "greeting" :text greeting}])])

(deftest greeter-snapshot
  (is (= {:fx/type :v-box
          :id "root"
          :spacing 8.0
          :alignment :top-left
          :fill-width true
          :padding {:top 16.0 :right 16.0 :bottom 16.0 :left 16.0}
          :children [{:fx/type :label
                      :id "title"
                      :text "Greeter"
                      :alignment :center-left
                      :style "-fx-text-fill: #3366cc; -fx-font-size: 20px; -fx-font-weight: bold;"
                      :max-width Double/MAX_VALUE}
                     {:fx/type :cloview/text-field
                      :id "name"
                      :text "Ada"
                      :prompt-text "Your name"
                      :cloview/on-text-edited {::cv/event [:app/set-name] ::cv/kind :string}}
                     {:fx/type :button
                      :id "greet"
                      :text "Greet"
                      :on-action {::cv/event [:app/greet] ::cv/kind :none}
                      :max-width Double/MAX_VALUE}
                     {:fx/type :label
                      :fx/key :greeting
                      :id "greeting"
                      :text "Hello, Ada!"
                      :alignment :center-left
                      :max-width Double/MAX_VALUE}]}
         (cv/translate (greeter {:name "Ada" :greeting "Hello, Ada!"})))))

(deftest keyed-children-carry-fx-key
  (let [d (cv/translate [:ui/column
                         [:ui/text {:key :a :text "a"}]
                         [:ui/text {:text "b"}]
                         [:ui/grid [:ui/text {:key "cell" :text "c"}]]])]
    (is (= [:a nil] (mapv :fx/key (take 2 (:children d)))))
    (is (not (contains? (second (:children d)) :fx/key)) "unkeyed children stay positional")
    (is (= "cell" (:fx/key (first (:children (nth (:children d) 2)))))))
  (testing ":fx/key never appears in single-child props"
    (is (not (contains? (:content (cv/translate [:ui/scroll [:ui/text {:key :x :text "x"}]])) :fx/key)))))

(deftest every-v1-tag-has-an-adapter
  (doseq [tag (keys t/v1-tags)
          :when (not= tag :ui/tab)]
    (is (contains? (methods cv/->desc) tag) (str "missing cljfx adapter for " tag))))

(deftest grid-children-fill-their-cell-width
  (let [d (cv/translate [:ui/grid {:columns [{:fr 1} {:fr 1}]}
                         [:ui/button {:grid/row 0 :grid/col 0 :label "a"}]
                         [:ui/button {:grid/row 0 :grid/col 1 :label "b" :style {:width 40}}]])]
    (is (= cv/max-size (:max-width (first (:children d)))))
    (is (= 40.0 (double (:max-width (second (:children d))))) "an explicit width is kept")))

(deftest toggle-and-wrap
  (let [d (cv/translate [:ui/wrap {:gap 6 :run-gap 4 :test-id "chips"}
                         [:ui/toggle {:key "a" :label "Biceps" :checked true :on-toggle [:t "a"]}]
                         [:ui/toggle {:key "b" :label "Triceps"}]])
        [a b] (:children d)]
    (is (= :flow-pane (:fx/type d)))
    (is (= [6.0 4.0] [(:hgap d) (:vgap d)]))
    (is (= "chips" (:id d)))
    (is (= [:toggle-button :toggle-button] [(:fx/type a) (:fx/type b)]))
    (is (= ["a" "b"] [(:fx/key a) (:fx/key b)]) "chips keep their identity in the flow")
    (is (= [true false] [(:selected a) (:selected b)]))
    (is (= "Biceps" (:text a)))
    (is (= [:t "a"] (get-in a [:on-selected-changed ::cv/event])))
    (is (= :boolean (get-in a [:on-selected-changed ::cv/kind])))
    (is (not (contains? b :on-selected-changed)) "no handler, no listener"))
  (testing "a wrap grows inside a row (it needs a bounded width to break its runs)"
    (is (= :always (:h-box/hgrow (first (:children (cv/translate [:ui/row [:ui/wrap]])))))))
  (testing "toggle buttons stretch in a stretching column, like buttons"
    (is (= Double/MAX_VALUE (:max-width (first (:children (cv/translate [:ui/column [:ui/toggle {:label "x"}]]))))))))

(deftest row-layout-grow-and-margins
  (let [d (cv/translate [:ui/row {:gap 4 :align :center}
                         [:ui/text "Name"]
                         [:ui/text-input {:value ""}]
                         [:ui/spacer]
                         [:ui/button {:label "Go" :style {:margin [0 0 0 8]}}]])]
    (is (= :h-box (:fx/type d)))
    (is (= :center-left (:alignment d)))
    (is (= [nil :always :always nil] (mapv :h-box/hgrow (:children d)))
        "inputs and spacers grow inside rows")
    (is (= {:top 0.0 :right 0.0 :bottom 0.0 :left 8.0} (:h-box/margin (nth (:children d) 3))))))

(deftest column-justify-and-align
  (let [d (cv/translate [:ui/column {:align :center :justify :end} [:ui/text "x"]])]
    (is (= :bottom-center (:alignment d)))
    (is (false? (:fill-width d)))
    (is (nil? (:max-width (first (:children d)))) "no stretch unless :align :stretch")))

(deftest grid-tracks-and-cells
  (let [d (cv/translate [:ui/grid {:columns [120 {:fr 1}] :gap 6}
                         [:ui/text {:grid/row 0 :grid/col 0 :text "a"}]
                         [:ui/text-input {:grid/row 0 :grid/col 1 :grid/col-span 2 :value ""}]])]
    (is (= :grid-pane (:fx/type d)))
    (is (= [{:fx/type :column-constraints :pref-width 120.0 :min-width 120.0}
            {:fx/type :column-constraints :hgrow :always}]
           (:column-constraints d)))
    (is (= [0 1 2] ((juxt :grid-pane/row :grid-pane/column :grid-pane/column-span) (second (:children d)))))))

(deftest box-style-css
  (let [d (cv/translate [:ui/box {:style {:background "#eef" :border {:width 1 :color "#99a"}
                                          :radius 6 :padding 4 :opacity 0.5}}
                         [:ui/text "x"]])]
    (is (= :stack-pane (:fx/type d)))
    (is (= (str "-fx-background-color: #eeeeff; -fx-background-radius: 6px; "
                "-fx-border-width: 1px; -fx-border-color: #9999aa; -fx-border-radius: 6px; "
                "-fx-opacity: 0.5;")
           (:style d)))
    (is (= {:top 4.0 :right 4.0 :bottom 4.0 :left 4.0} (:padding d)))))

(deftest controls
  (testing "checkbox and switch"
    (let [c (cv/translate [:ui/checkbox {:label "ok" :checked true :on-toggle [:a]}])]
      (is (= {:fx/type :check-box :text "ok" :selected true
              :on-selected-changed {::cv/event [:a] ::cv/kind :boolean}}
             c)))
    (is (= ["check-box" "cloview-switch"]
           (:style-class (cv/translate [:ui/switch {:checked false}])))))
  (testing "select maps labels back to option values"
    (let [s (cv/translate [:ui/select {:options [{:value :kg :label "Kilograms"} {:value :lb :label "Pounds"}]
                                       :value :lb :on-select [:units/set]}])]
      (is (= ["Kilograms" "Pounds"] (:items s)))
      (is (= "Pounds" (:value s)))
      (is (= {"Kilograms" :kg "Pounds" :lb} (get-in s [:on-value-changed ::cv/options])))))
  (testing "password inputs use the stock password-field"
    (is (= :password-field (:fx/type (cv/translate [:ui/text-input {:value "" :password? true}])))))
  (testing "image sources"
    (is (= "https://example.com/a.png" (cv/image-url {:url "https://example.com/a.png"})))
    (is (re-find #"^file:" (cv/image-url {:file "clip.gif"})))
    (is (nil? (cv/image-url nil)))))

(deftest composites
  (testing "split view"
    (let [d (cv/translate [:ui/split-view {:ratio 0.3 :on-resize [:split/set]}
                           [:ui/text "left"] [:ui/text "right"]])]
      (is (= :cloview/split-pane (:fx/type d)))
      (is (= [0.3] (:divider-positions d)))
      (is (= {::cv/event [:split/set] ::cv/kind :ratio} (:cloview/on-divider-moved d)))
      (is (= 2 (count (:items d))))))
  (testing "tabs: selected id -> index, index -> id on change"
    (let [d (cv/translate [:ui/tabs {:selected :two :on-select [:tab/set]}
                           [:ui/tab {:id :one :label "One"} [:ui/text "1"]]
                           [:ui/tab {:id :two :label "Two"} [:ui/text "2"]]])]
      (is (= :cloview/tab-pane (:fx/type d)))
      (is (= 1 (:cloview/selected-index d)))
      (is (= ["One" "Two"] (mapv :text (:tabs d))))
      (is (= [:one :two] (get-in d [:cloview/on-selected-index-changed ::cv/options])))))
  (testing "modal lives off-tree in let-refs"
    (let [d (cv/translate [:ui/modal {:open? true :title "Confirm" :on-dismiss [:modal/close]}
                           [:ui/text "Sure?"]])
          stage (get-in d [:refs ::cv/modal])]
      (is (= :cloview/let-refs (:fx/type d)))
      (is (= {:fx/type :region :managed false :visible false} (:desc d)))
      (is (= [:stage true :application-modal "Confirm"]
             ((juxt :fx/type :showing :modality :title) stage)))
      (is (true? (get-in stage [:on-close-request ::cv/consume?])))))
  (testing "native escape hatch"
    (let [create (fn [] :node)
          d (cv/translate [:ui/native {:props {:n 1} :host {:create create}}])]
      (is (= {:fx/type :cloview/native :props {:n 1} :create create :update nil :dispose nil} d)))))

(deftest unknown-tags-fail-loudly
  (is (thrown-with-msg? Exception #"No cljfx adapter for :app/unknown"
                        (cv/translate [:app/unknown {}]))))
