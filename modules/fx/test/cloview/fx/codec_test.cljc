;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.fx.codec-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloview.fx.codec :as codec]))

(deftest csv-reading
  (is (= [["timestamp" "bpm" "end_time"]
          ["2026-10-13T12:00:00Z" "100" "2026-10-13T12:01:00Z"]]
         (codec/read-csv "timestamp,bpm,end_time\n2026-10-13T12:00:00Z,100,2026-10-13T12:01:00Z\n")))
  (testing "quotes, escaped quotes, separators and newlines inside quotes, CRLF, BOM"
    (is (= [["a" "b,c" "say \"hi\""] ["1" "line1\nline2" ""]]
           (codec/read-csv "\uFEFFa,\"b,c\",\"say \"\"hi\"\"\"\r\n1,\"line1\nline2\",\r\n"))))
  (testing "blank lines are skipped; a missing final newline is fine; empty fields kept"
    (is (= [["x" ""] ["" "y"]] (codec/read-csv "x,\n\n,y")))
    (is (= [] (codec/read-csv "")))
    (is (= [] (codec/read-csv "\n\n"))))
  (is (= [["a" "b"]] (codec/read-csv "a;b" {:separator ";"})))
  (is (thrown? #?(:cljd Object :clj Exception :cljr System.Exception) (codec/read-csv "\"open"))))

(deftest csv-maps
  (let [{:keys [header rows]} (codec/csv-maps " Timestamp ,BPM\n1,72\n2\n")]
    (is (= ["timestamp" "bpm"] header))
    (is (= [{:line 2 :values {"timestamp" "1" "bpm" "72"}}
            {:line 3 :values {"timestamp" "2" "bpm" nil}}]
           rows))))

(deftest numbers
  (is (= 72 (codec/parse-number "72")))
  (is (= -3 (codec/parse-number "-3")))
  (is (= 72.5 (codec/parse-number "72.5")))
  (is (= 1.0E3 (codec/parse-number "1e3")))
  (testing "syntax checked before the host parser sees it"
    (doseq [bad ["1,000" " 72" "72 " "+72" "Infinity" "NaN" "0x10" "01" "1." ".5" "" "1e"]]
      (is (nil? (codec/parse-number bad)) bad)))
  (is (= 72.5 (codec/parse-decimal " +72.5 ")))
  (is (nil? (codec/parse-decimal "72 bpm"))))

(deftest json-reading
  (is (= [{"timestamp" "2026-10-13T12:00:00Z" "bpm" 100}
          {"timestamp" 1791892890000 "bpm" 120.5 "ok" true "note" nil}]
         (codec/read-json "[{\"timestamp\": \"2026-10-13T12:00:00Z\", \"bpm\": 100},
                            {\"timestamp\": 1791892890000, \"bpm\": 120.5, \"ok\": true, \"note\": null}]")))
  (is (= {:a [1 2 {:b "c"}] :e {}} (codec/read-json "{\"a\":[1,2,{\"b\":\"c\"}],\"e\":{}}" {:keywordize? true})))
  (testing "escapes, including \\u and surrogate pairs"
    (is (= "q\"\\/\b\f\n\r\tA\u00e9\uD83D\uDE00" (codec/read-json "\"q\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\\u00e9\\uD83D\\uDE00\""))))
  (is (= [] (codec/read-json " [ ] ")))
  (testing "malformed input fails with position information"
    (doseq [bad ["[1,]" "{\"a\" 1}" "[1 2]" "\"open" "tru" "[01]" "{\"a\":1} x" "[\"a\nb\"]" ""]]
      (is (= :json (try (codec/read-json bad) nil
                        (catch #?(:cljd cljd.core/ExceptionInfo :clj clojure.lang.ExceptionInfo
                                  :cljr clojure.lang.ExceptionInfo) e
                          (:reason (ex-data e)))))
          bad)))
  (testing "nesting is limited before it can overflow the stack (fatal on .NET)"
    (let [nested (fn [n open close] (str (apply str (repeat n open)) (apply str (repeat n close))))
          reason (fn [text opts] (try (codec/read-json text opts) nil
                                      (catch #?(:cljd cljd.core/ExceptionInfo :clj clojure.lang.ExceptionInfo
                                                :cljr clojure.lang.ExceptionInfo) e
                                        (:message (ex-data e)))))]
      (is (= 128 codec/default-max-depth))
      (is (vector? (codec/read-json (nested 128 "[" "]"))) "128 levels are fine")
      (is (= "nested too deeply" (reason (nested 129 "[" "]") {})))
      (is (= "nested too deeply" (reason (nested 5000 "[" "]") {})) "fails fast, without recursing")
      (is (= "nested too deeply" (reason (str (apply str (repeat 129 "{\"a\":")) "1" (apply str (repeat 129 "}"))) {})))
      (is (= "nested too deeply" (reason "[[[1]]]" {:max-depth 2})))
      (is (= [[1]] (codec/read-json "[[1]]" {:max-depth 2}))))))
