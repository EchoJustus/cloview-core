;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.style-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloview.core.color :as color]
            [cloview.core.style :as s]))

(deftest color-parsing
  (is (= {:r 51 :g 102 :b 204 :a 255} (color/parse "#3366CC")))
  (is (= {:r 51 :g 102 :b 204 :a 255} (color/parse "#36c")))
  (testing "CSS order: alpha LAST in 4/8-digit hex"
    (is (= {:r 51 :g 102 :b 204 :a 128} (color/parse "#3366cc80")))
    (is (= {:r 255 :g 0 :b 0 :a 136} (color/parse "#f008"))))
  (is (= {:r 0 :g 0 :b 0 :a 0} (color/parse :transparent)))
  (is (= {:r 255 :g 255 :b 255 :a 255} (color/parse "white")))
  (is (= {:r 1 :g 2 :b 3 :a 255} (color/parse {:r 1 :g 2 :b 3})))
  (is (= {:r 1 :g 2 :b 3 :a 128} (color/parse {:r 1 :g 2 :b 3 :a 0.5})) "fractional alpha")
  (is (= {:r 1 :g 2 :b 3 :a 64} (color/parse {:r 1 :g 2 :b 3 :a 64})) "integer alpha is a byte")
  (is (= {:r 10 :g 20 :b 30 :a 255} (color/parse [:rgb 10 20 30])))
  (is (= {:r 10 :g 20 :b 30 :a 51} (color/parse [:rgba 10 20 30 0.2])))
  (is (= {:r 255 :g 0 :b 0 :a 255} (color/parse {:r 300 :g -4 :b 0})) "channels are clamped")
  (is (nil? (color/parse "#12345")))
  (is (nil? (color/parse "#zzzzzz")))
  (is (nil? (color/parse 42))))

(deftest color-host-encodings
  (let [c "#3366cc80"]
    (is (= "#3366cc80" (color/css-hex c)))
    (is (= "#803366cc" (color/argb-hex c)) "WPF/XAML: alpha FIRST")
    (is (= 2150852300 (color/argb-int c)) "Flutter Color(0x803366CC)")
    (is (= [51 102 204 128] (color/rgba-vec c)))
    (is (= "rgba(51,102,204,0.502)" (color/css-rgba c)) "JavaFX CSS with alpha")
    (is (= "#3366cc" (color/css-rgba "#3366CC")) "JavaFX CSS opaque")))

(deftest insets-shorthands
  (is (= {:top 4.0 :right 4.0 :bottom 4.0 :left 4.0} (s/insets 4)))
  (is (= {:top 1.0 :right 2.0 :bottom 1.0 :left 2.0} (s/insets [1 2])))
  (is (= {:top 1.0 :right 2.0 :bottom 3.0 :left 2.0} (s/insets [1 2 3])))
  (is (= {:top 1.0 :right 2.0 :bottom 3.0 :left 4.0} (s/insets [1 2 3 4])))
  (is (= {:top 9.0 :right 2.0 :bottom 1.0 :left 2.0}
         (s/insets {:vertical 1 :horizontal 2 :top 9}))))

(deftest font-weights
  (is (= 700 (s/font-weight :bold)))
  (is (= 400 (s/font-weight :normal)))
  (is (= 600 (s/font-weight 640)))
  (is (= 100 (s/font-weight 10)))
  (is (= 900 (s/font-weight 1200))))

(deftest normalize-style
  (is (= {} (s/normalize nil)))
  (is (= {:color {:r 51 :g 102 :b 204 :a 255}
          :font-size 20.0
          :font-weight 700
          :padding {:top 8.0 :right 8.0 :bottom 8.0 :left 8.0}
          :border-width 1.0
          :border-color {:r 0 :g 0 :b 0 :a 255}
          :radius 6.0
          :opacity 1.0
          :text-align :center}
         (s/normalize {:color "#3366CC" :font-size 20 :font-weight :bold :padding 8
                       :border 1 :radius 6 :opacity 1.4 :text-align :center
                       :shadow "ignored"})))
  (is (= {:border-width 2.0 :border-color {:r 255 :g 0 :b 0 :a 255}}
         (s/normalize {:border {:width 2 :color "#ff0000"}})))
  (is (= #{:shadow :bogus} (s/unknown-keys {:shadow 1 :bogus 2 :color "#fff"})))
  (is (= {:host-style "-fx-effect: dropshadow(gaussian, black, 4, 0, 0, 1);"}
         (s/normalize {:host-style "-fx-effect: dropshadow(gaussian, black, 4, 0, 0, 1);"})))
  (is (thrown? #?(:cljd dynamic :clj Exception :cljr System.Exception)
               (s/normalize {:font-size "20px"}))
      "lengths are unitless logical px numbers"))

(deftest style-subsets
  (let [n (s/normalize {:color "#000" :padding 2 :font-size 10 :background "#fff"})]
    (is (= #{:color :font-size} (set (keys (s/text-keys n)))))
    (is (= #{:padding :background} (set (keys (s/box-keys n)))))))
