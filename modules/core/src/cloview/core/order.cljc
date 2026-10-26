;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.order
  "Ordering that is identical on every host.

  Clojure's default `compare` and `sort` are NOT portable:

  - strings: Clojure/JVM and ClojureDart compare UTF-16 code units (ordinal order), but
    ClojureCLR 1.12.x compares with the current culture (String.CompareTo), so
    (sort [\"b\" \"B\" \"a\" \"Ab wheel\"]) is (\"Ab wheel\" \"B\" \"a\" \"b\") on the JVM and
    (\"a\" \"Ab wheel\" \"b\" \"B\") on ClojureCLR 1.12.6 — and on Windows the CLR order also
    depends on the user's culture. (ClojureCLR 1.13 switches the default to ordinal.)
  - stability: `sort`/`sort-by` are stable on the JVM, but ClojureCLR sorts with
    Array.Sort (introsort), which reorders equal keys once a collection has more than
    16 elements.

  Use these functions wherever an order must agree across hosts and devices: visible
  option lists, serialized maps (JSON keys), sync merge tie-breaks, report files."
  )

(defn compare-str
  "Ordinal (UTF-16 code unit) comparison of two strings, the order Clojure/JVM and Dart
  use for strings. nil sorts first."
  [a b]
  (cond
    (identical? a b) 0
    (nil? a) -1
    (nil? b) 1
    :else #?(:cljd (.compareTo ^String a b)
             :clj (.compareTo ^String a ^String b)
             :cljr (String/CompareOrdinal ^String a ^String b))))

(defn sort-strings
  "Strings in ordinal order (a vector)."
  [coll]
  (vec (sort compare-str coll)))

(defn stable-sort-by
  "Like `sort-by`, but stable on every host (equal keys keep their input order) and
  returning a vector. `cmp` defaults to `compare`; pass `compare-str` for string keys."
  ([keyfn coll] (stable-sort-by keyfn compare coll))
  ([keyfn cmp coll]
   (->> coll
        (map-indexed (fn [i x] [i (keyfn x) x]))
        (sort (fn [a b]
                (let [c (cmp (nth a 1) (nth b 1))]
                  (if (zero? c) (compare (nth a 0) (nth b 0)) c))))
        (mapv #(nth % 2)))))

(defn sort-by-str
  "Stable sort of `coll` by the string `(keyfn x)` in ordinal order (a vector)."
  [keyfn coll]
  (stable-sort-by keyfn compare-str coll))
