;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.order-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloview.core.order :as order]))

(deftest ordinal-string-order
  (testing "upper case before lower case, code unit by code unit (the JVM and Dart order)"
    (is (= ["Ab wheel" "B" "No equipment" "a" "b"]
           (order/sort-strings ["b" "B" "a" "Ab wheel" "No equipment"]))))
  (is (neg? (order/compare-str "Barbell" "bands")))
  (is (pos? (order/compare-str "kettlebell" "TRX")))
  (is (zero? (order/compare-str "same" "same")))
  (is (neg? (order/compare-str nil "a")))
  (is (pos? (order/compare-str "a" nil)))
  (is (zero? (order/compare-str nil nil)))
  (testing "hyphens and digits are ordinary characters (culture comparers may ignore hyphens)"
    (is (= ["co-op" "coop" "coop2"] (order/sort-strings ["coop2" "coop" "co-op"])))))

(deftest stable-sorting
  (let [rows (mapv (fn [i] {:id i :k (mod i 3)}) (range 40))
        sorted (order/stable-sort-by :k rows)]
    (testing "more than 16 elements with equal keys keep their input order on every host"
      (is (= (concat (filter #(= 0 (mod % 3)) (range 40))
                     (filter #(= 1 (mod % 3)) (range 40))
                     (filter #(= 2 (mod % 3)) (range 40)))
             (map :id sorted))))
    (is (vector? sorted)))
  (is (= [{:n "B"} {:n "a"} {:n "a" :second true}]
         (order/sort-by-str :n [{:n "a"} {:n "B"} {:n "a" :second true}])))
  (is (= [] (order/stable-sort-by :k []))))
