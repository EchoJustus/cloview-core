;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.text
  "Culture-independent text helpers for identifiers (file extensions, header and column
  names, keys, HTTP methods).

  clojure.string/lower-case and upper-case follow the current culture on ClojureCLR
  (String.ToLower/ToUpper): under a Turkish culture \"GIF\" lower-cases to \"gıf\" and
  \"options\" upper-cases to \"OPTİONS\". These helpers change only ASCII letters, the same
  way on every host. Keep clojure.string for text shown to (or typed by) people."
  (:require [clojure.string :as str]))

(def ^:private upper "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
(def ^:private lower "abcdefghijklmnopqrstuvwxyz")

(def ^:private to-lower (zipmap (map str upper) (map str lower)))
(def ^:private to-upper (zipmap (map str lower) (map str upper)))

(defn ascii-lower
  "`s` with A-Z mapped to a-z; every other character unchanged."
  [s]
  (str/replace (str s) #"[A-Z]" (fn [m] (get to-lower m m))))

(defn ascii-upper
  "`s` with a-z mapped to A-Z; every other character unchanged."
  [s]
  (str/replace (str s) #"[a-z]" (fn [m] (get to-upper m m))))
