;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.color
  "Portable color parsing and host encodings.

  Canonical color value: {:r 0-255 :g 0-255 :b 0-255 :a 0-255}.

  Accepted inputs:
  - CSS hex strings in CSS channel order (alpha LAST): \"#rgb\" \"#rgba\" \"#rrggbb\" \"#rrggbbaa\"
  - named colors as keywords or strings (see `named`)
  - maps {:r :g :b :a}; :a may be 0-255 (integer) or 0.0-1.0 (fraction)
  - vectors [:rgb r g b] and [:rgba r g b a] (a as 0.0-1.0 fraction, CSS style)

  Host encodings differ in alpha position — this namespace is the single place that
  knows it: JavaFX CSS gets `rgba(r,g,b,a)`, Flutter `Color(0xAARRGGBB)` (`argb-int`),
  WPF `Color.FromArgb(a,r,g,b)` (`rgba-vec`) or \"#AARRGGBB\" (`argb-hex`).

  Pure .cljc: hex parsing is done by hand (no Integer/parseInt, no format) so it runs
  unchanged on JVM, ClojureDart and ClojureCLR.")

(def named
  {"transparent" {:r 0 :g 0 :b 0 :a 0}
   "black" {:r 0 :g 0 :b 0 :a 255}
   "white" {:r 255 :g 255 :b 255 :a 255}
   "gray" {:r 128 :g 128 :b 128 :a 255}
   "grey" {:r 128 :g 128 :b 128 :a 255}
   "light-gray" {:r 211 :g 211 :b 211 :a 255}
   "dark-gray" {:r 64 :g 64 :b 64 :a 255}
   "red" {:r 229 :g 57 :b 53 :a 255}
   "green" {:r 67 :g 160 :b 71 :a 255}
   "blue" {:r 30 :g 136 :b 229 :a 255}
   "orange" {:r 251 :g 140 :b 0 :a 255}
   "purple" {:r 142 :g 36 :b 170 :a 255}
   "yellow" {:r 253 :g 216 :b 53 :a 255}})

(def ^:private hex-digits
  {"0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7 "8" 8 "9" 9
   "a" 10 "b" 11 "c" 12 "d" 13 "e" 14 "f" 15
   "A" 10 "B" 11 "C" 12 "D" 13 "E" 14 "F" 15})

(def ^:private hex-chars
  ["0" "1" "2" "3" "4" "5" "6" "7" "8" "9" "a" "b" "c" "d" "e" "f"])

(defn- hex-value
  "Integer value of the hex string `s`, or nil when it contains a non-hex char."
  [s]
  (reduce (fn [acc i]
            (if-some [d (get hex-digits (subs s i (inc i)))]
              (+ (* acc 16) d)
              (reduced nil)))
          0
          (range (count s))))

(defn- clamp-byte [x]
  (let [x (long x)] (cond (< x 0) 0 (> x 255) 255 :else x)))

(defn- frac->byte [f]
  (clamp-byte (long (+ (* (double f) 255.0) 0.5))))

(defn- parse-hex [^String s]
  (let [h (subs s 1)
        n (count h)
        ch (fn [i w] (hex-value (subs h (* i w) (* (inc i) w))))
        dbl (fn [v] (when v (+ (* v 16) v)))]
    (case n
      3 (let [[r g b] (map #(dbl (ch % 1)) [0 1 2])]
          (when (and r g b) {:r r :g g :b b :a 255}))
      4 (let [[r g b a] (map #(dbl (ch % 1)) [0 1 2 3])]
          (when (and r g b a) {:r r :g g :b b :a a}))
      6 (let [[r g b] (map #(ch % 2) [0 1 2])]
          (when (and r g b) {:r r :g g :b b :a 255}))
      8 (let [[r g b a] (map #(ch % 2) [0 1 2 3])]
          (when (and r g b a) {:r r :g g :b b :a a}))
      nil)))

(defn parse
  "Parse any accepted color input into {:r :g :b :a} (0-255 ints), or nil if invalid."
  [c]
  (cond
    (nil? c) nil
    (keyword? c) (get named (name c))
    (string? c) (if (and (pos? (count c)) (= "#" (subs c 0 1)))
                  (parse-hex c)
                  (get named c))
    (map? c) (when (and (number? (:r c)) (number? (:g c)) (number? (:b c)))
               (let [a (:a c 255)]
                 {:r (clamp-byte (:r c))
                  :g (clamp-byte (:g c))
                  :b (clamp-byte (:b c))
                  :a (if (and (number? a) (not (integer? a)) (<= a 1.0))
                       (frac->byte a)
                       (clamp-byte a))}))
    (vector? c) (let [[kind r g b a] c]
                  (case kind
                    :rgb {:r (clamp-byte r) :g (clamp-byte g) :b (clamp-byte b) :a 255}
                    :rgba {:r (clamp-byte r) :g (clamp-byte g) :b (clamp-byte b)
                           :a (frac->byte (if (nil? a) 1.0 a))}
                    nil))
    :else nil))

(defn parse!
  "Like `parse` but throws ex-info on invalid input."
  [c]
  (or (parse c) (throw (ex-info "Invalid color" {:color c}))))

(defn- hex2 [n]
  (str (get hex-chars (quot n 16)) (get hex-chars (rem n 16))))

(defn css-hex
  "#rrggbbaa (CSS order, alpha last)."
  [c]
  (let [{:keys [r g b a]} (parse! c)]
    (str "#" (hex2 r) (hex2 g) (hex2 b) (hex2 a))))

(defn argb-hex
  "#AARRGGBB (WPF/XAML order, alpha first)."
  [c]
  (let [{:keys [r g b a]} (parse! c)]
    (str "#" (hex2 a) (hex2 r) (hex2 g) (hex2 b))))

(defn argb-int
  "0xAARRGGBB as an integer (Flutter `Color(int)`)."
  [c]
  (let [{:keys [r g b a]} (parse! c)]
    (+ (* a 16777216) (* r 65536) (* g 256) b)))

(defn rgba-vec
  "[r g b a] with every channel 0-255."
  [c]
  (let [{:keys [r g b a]} (parse! c)] [r g b a]))

(defn alpha-fraction
  "Alpha as a 0.0-1.0 double."
  [c]
  (/ (double (:a (parse! c))) 255.0))

(defn css-rgba
  "CSS color usable in JavaFX stylesheets: #rrggbb when opaque, else rgba(r,g,b,a)."
  [c]
  (let [{:keys [r g b a]} (parse! c)]
    (if (= a 255)
      (str "#" (hex2 r) (hex2 g) (hex2 b))
      (let [frac (/ (double (long (+ (* (/ (double a) 255.0) 1000.0) 0.5))) 1000.0)]
        (str "rgba(" r "," g "," b "," frac ")")))))
