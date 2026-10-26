;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.text-test
  (:require [clojure.test :refer [deftest is]]
            [cloview.core.text :as text]))

(deftest ascii-case-changes-only-ascii-letters
  (is (= "squat.gif" (text/ascii-lower "Squat.GIF")))
  (is (= "content-type" (text/ascii-lower "Content-Type")))
  (is (= "OPTIONS" (text/ascii-upper "options")) "never a dotted capital I")
  (is (= "id" (text/ascii-lower "ID")) "never a dotless small i")
  (is (= "\u00C9t\u00C9 \u00E9 \u00DF 123" (text/ascii-lower "\u00C9T\u00C9 \u00E9 \u00DF 123"))
      "only the ASCII T changes; non-ASCII letters are left alone")
  (is (= "" (text/ascii-lower nil)))
  (is (= "" (text/ascii-upper ""))))
