;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.memo-test
  (:require [clojure.test :refer [deftest is]]
            [cloview.core.memo :as memo]))

(deftest memo-last-recomputes-only-when-arguments-change
  (let [calls (atom 0)
        f (memo/memo-last (memo/counting calls (fn [xs opts] (reduce + (map #(* % (:k opts)) xs)))))
        xs [1 2 3]]
    (is (= 12 (f xs {:k 2})))
    (is (= 12 (f xs {:k 2})))
    (is (= 1 @calls) "identical slice + equal options: cached")
    (is (= 12 (f [1 2 3] {:k 2})))
    (is (= 1 @calls) "equal (not identical) arguments: still cached")
    (is (= 18 (f xs {:k 3})))
    (is (= 2 @calls))
    (is (= 12 (f xs {:k 2})))
    (is (= 3 @calls) "single entry: the previous result was replaced")))
