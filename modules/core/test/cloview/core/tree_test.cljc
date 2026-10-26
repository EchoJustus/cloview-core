;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloview.core.tree :as t]))

(deftest normalize-basic-element
  (is (= {:tag :ui/column :key nil :props {:gap 8} :children []}
         (t/normalize [:ui/column {:gap 8}])))
  (testing "props map is optional"
    (is (= {:tag :ui/column :key nil :props {} :children []}
           (t/normalize [:ui/column]))))
  (testing ":key is lifted out of props"
    (is (= :a (:key (t/normalize [:ui/button {:key :a :label "A"}]))))
    (is (= {:label "A"} (:props (t/normalize [:ui/button {:key :a :label "A"}]))))))

(deftest normalize-children
  (let [n (t/normalize [:ui/column {}
                        "plain string"
                        42
                        nil
                        false
                        (when false [:ui/text {:text "hidden"}])
                        (for [i (range 2)] [:ui/button {:key i :label (str i)}])
                        (list [:ui/spacer])])]
    (is (= [:ui/text :ui/text :ui/button :ui/button :ui/spacer] (mapv :tag (:children n))))
    (is (= "plain string" (get-in n [:children 0 :props :text])))
    (is (= "42" (get-in n [:children 1 :props :text])))
    (is (= [0 1] (mapv :key (subvec (:children n) 2 4))))))

(deftest text-children-are-inlined
  (is (= "Hello, Ada!" (get-in (t/normalize [:ui/text {:style {:font-size 12}} "Hello, " "Ada" "!"])
                               [:props :text])))
  (is (= [] (:children (t/normalize [:ui/text "x"]))))
  (testing "explicit :text wins over children"
    (is (= "a" (get-in (t/normalize [:ui/text {:text "a"} "b"]) [:props :text])))))

(deftest normalize-is-idempotent
  (let [n (t/normalize [:ui/row {:gap 4} [:ui/text "a"] [:ui/text "b"]])]
    (is (= n (t/normalize n)))
    (is (= n (t/normalize [:ui/row {:gap 4} (first (:children n)) (second (:children n))])))))

(deftest composite-components-expand
  (t/reg-component! :test/rating
                    (fn [{:keys [value on-change]} _children]
                      (into [:ui/row {:gap 2}]
                            (for [i (range 1 4)]
                              [:ui/button {:key i
                                           :label (if (<= i value) "*" "-")
                                           :on-press (conj on-change i)}]))))
  (let [n (t/normalize [:test/rating {:key :stars :value 2 :on-change [:app/rate]}])]
    (is (= :ui/row (:tag n)))
    (is (= :stars (:key n)) "use-site key carried to the expansion root")
    (is (= ["*" "*" "-"] (mapv #(get-in % [:props :label]) (:children n))))
    (is (= [:app/rate 3] (get-in n [:children 2 :props :on-press])))))

(deftest composite-components-receive-children
  (t/reg-component! :test/card
                    (fn [props children]
                      (into [:ui/box {:style (:style props)}] children)))
  (let [n (t/normalize [:test/card {:style {:padding 4}} [:ui/text "inside"]])]
    (is (= :ui/box (:tag n)))
    (is (= "inside" (get-in n [:children 0 :props :text])))))

(defn- greeting-view [name]
  [:ui/text {:text (str "Hi " name)}])

(deftest fn-components
  (let [n (t/normalize [:ui/column {} ^{:key :g} [greeting-view "Ada"]])]
    (is (= "Hi Ada" (get-in n [:children 0 :props :text])))
    (is (= :g (get-in n [:children 0 :key])))))

(deftest invalid-input-throws
  (is (thrown? #?(:cljd dynamic :clj Exception :cljr System.Exception) (t/normalize {:not "hiccup"})))
  (is (thrown? #?(:cljd dynamic :clj Exception :cljr System.Exception) (t/normalize [:ui/column {} :bad-child]))))

(deftest events
  (is (= [:app/set-name "Ada"] (t/event-vector [:app/set-name] :string "Ada")))
  (is (= [:app/greet] (t/event-vector [:app/greet] :none nil)))
  (testing "nil payloads are appended for value events (e.g. clearing a select)"
    (is (= [:app/pick nil] (t/event-vector [:app/pick] :value nil))))
  (is (= :string (t/payload-kind :ui/text-input :on-change)))
  (is (= :none (t/payload-kind :ui/button :on-press)))
  (let [seen (atom [])]
    (t/emit! #(swap! seen conj %) [:a] :boolean true)
    (t/emit! #(swap! seen conj %) nil :none nil)
    (t/emit! #(swap! seen conj %) (fn [x] (swap! seen conj [:fn x])) :value 7)
    (is (= [[:a true] [:fn 7]] @seen))))

(deftest diffable-props-excludes-events-layout-and-native-host
  (is (= {:label "x" :style {:color "#fff"}}
         (t/diffable-props :ui/button {:label "x" :on-press [:a] :grow 1 :grid/row 2
                                       :style {:color "#fff"}})))
  (is (= {:props {:v 1}}
         (t/diffable-props :ui/native {:props {:v 1} :host {:create (fn [] nil)}}))))

(deftest effective-grow-rule
  (let [row-input (t/normalize [:ui/text-input {:value ""}])
        sized-input (t/normalize [:ui/text-input {:value "" :style {:width 120}}])]
    (is (= 1 (t/effective-grow :ui/row row-input)) "inputs grow inside rows")
    (is (= 0 (t/effective-grow :ui/column row-input)) "but not inside columns")
    (is (= 0 (t/effective-grow :ui/row sized-input)) "explicit width disables implicit grow")
    (is (= 3 (t/effective-grow :ui/column (t/normalize [:ui/box {:grow 3} [:ui/text "x"]]))))
    (is (= 1 (t/effective-grow :ui/row (t/normalize [:ui/spacer]))))
    (is (= 1 (t/effective-grow :ui/row (t/normalize [:ui/column {} [:ui/text "x"]])))
        "containers in rows take the remaining width")
    (is (= 1 (t/effective-grow :ui/column (t/normalize [:ui/scroll {} [:ui/text "x"]])))
        "scroll views in columns take the remaining height")
    (is (= 0 (t/effective-grow :ui/column (t/normalize [:ui/scroll {:style {:height 60}} [:ui/text "x"]]))))
    (is (= 0 (t/effective-grow :ui/row (t/normalize [:ui/column {:grow false} [:ui/text "x"]])))
        ":grow false opts out")
    (is (= :center (t/default-align :ui/row)))
    (is (= :stretch (t/default-align :ui/column)))))

(deftest problems-are-reported
  (let [bad (t/normalize [:ui/column {}
                          [:ui/button {:key :a :label "1"}]
                          [:ui/button {:key :a :label "2"}]
                          [:ui/scroll {} [:ui/text "a"] [:ui/text "b"]]
                          [:ui/tab {:id :x} [:ui/text "t"]]
                          [:ui/button {:on-press :not-a-vector}]])
        msgs (mapv :problem (t/problems bad))]
    (is (some #(re-find #"duplicate sibling key" %) msgs))
    (is (some #(re-find #"exactly one child" %) msgs))
    (is (some #(re-find #"direct child of :ui/tabs" %) msgs))
    (is (some #(re-find #"must be a data vector" %) msgs)))
  (is (= [] (t/problems (t/normalize [:ui/column {} [:ui/text "fine"]])))))

(deftest find-by-test-id
  (let [n (t/normalize [:ui/column {} [:ui/row {} [:ui/text {:test-id "deep" :text "x"}]]])]
    (is (= "x" (get-in (t/find-by-test-id n "deep") [:props :text])))
    (is (nil? (t/find-by-test-id n "missing")))
    (is (= 3 (count (t/node-seq n))))))

(deftest v1-spec-is-complete
  (is (= #{:ui/column :ui/row :ui/grid :ui/wrap :ui/scroll :ui/box :ui/align :ui/spacer
           :ui/text :ui/button :ui/text-input :ui/checkbox :ui/switch :ui/toggle :ui/select
           :ui/image :ui/split-view :ui/tabs :ui/tab :ui/modal :ui/native}
         (set (keys t/v1-tags))))
  (is (= {:prop :value :event :on-change :default ""} (t/controlled :ui/text-input)))
  (is (= {:prop :checked :event :on-toggle :default false} (t/controlled :ui/toggle)))
  (is (= :boolean (t/payload-kind :ui/toggle :on-toggle))))

(deftest wrap-grows-inside-rows
  (is (= 1 (t/effective-grow :ui/row (t/normalize [:ui/wrap {} [:ui/text "a"]]))))
  (is (= 0 (t/effective-grow :ui/row (t/normalize [:ui/wrap {:style {:width 200}}]))))
  (is (= 0 (t/effective-grow :ui/column (t/normalize [:ui/wrap {}])))))

(deftest grow-looks-through-decorators
  (testing "a padded box around a column grows in a row like the column (Flutter: a stretching
            column needs a bounded width)"
    (is (= 1 (t/effective-grow :ui/row (t/normalize [:ui/box {:style {:padding 10}} [:ui/column {} [:ui/text "x"]]]))))
    (is (= 1 (t/effective-grow :ui/row (t/normalize [:ui/align {} [:ui/box {} [:ui/column {} [:ui/text "x"]]]]))))
    (is (= 0 (t/effective-grow :ui/row (t/normalize [:ui/box {:style {:width 200}} [:ui/column {} [:ui/text "x"]]])))
        "a size on the box keeps it intrinsic")
    (is (= 0 (t/effective-grow :ui/row (t/normalize [:ui/box {} [:ui/column {:style {:width 64}} [:ui/text "x"]]])))
        "so does a size on the content")
    (is (= 0 (t/effective-grow :ui/row (t/normalize [:ui/box {} [:ui/text "x"]]))) "boxes around text stay intrinsic")
    (is (= 0 (t/effective-grow :ui/row (t/normalize [:ui/box {}]))))
    (is (= 0 (t/effective-grow :ui/row (t/normalize [:ui/box {:grow false} [:ui/column {} [:ui/text "x"]]])))))
  (testing "a box around a scroll view grows in a column"
    (is (= 1 (t/effective-grow :ui/column (t/normalize [:ui/box {} [:ui/scroll {} [:ui/text "x"]]]))))
    (is (= 0 (t/effective-grow :ui/column (t/normalize [:ui/box {:style {:height 120}} [:ui/scroll {} [:ui/text "x"]]]))))))

(deftest horizontal-scroll-keeps-its-height-in-a-column
  ;; e.g. a chart strip inside a panel inside a vertical page scroll: flexing it there
  ;; would ask Flutter for a flex child under an unbounded height
  (is (= 0 (t/effective-grow :ui/column (t/normalize [:ui/scroll {:direction :horizontal} [:ui/row {} [:ui/text "x"]]]))))
  (is (= 1 (t/effective-grow :ui/column (t/normalize [:ui/scroll {:direction :vertical} [:ui/text "x"]]))))
  (is (= 1 (t/effective-grow :ui/row (t/normalize [:ui/scroll {:direction :horizontal} [:ui/text "x"]])))
      "it still needs a bounded width in a row"))

(deftest tabs-fill-a-column
  (let [tabs (fn [style] (t/normalize [:ui/tabs (cond-> {:selected :a} style (assoc :style style))
                                       [:ui/tab {:id :a :label "A"} [:ui/text "a"]]]))]
    (is (= 1 (t/effective-grow :ui/column (tabs nil))) "page containers need a bounded height")
    (is (= 0 (t/effective-grow :ui/column (tabs {:height 80}))) "an explicit height wins")
    (is (= 1 (t/effective-grow :ui/row (tabs nil))) "and a bounded width in rows, as before")))
