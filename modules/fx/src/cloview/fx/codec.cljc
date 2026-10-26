;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.fx.codec
  "Portable text codecs for imported files: CSV (RFC 4180) and JSON (RFC 8259) readers
  in pure .cljc, identical on Clojure/JVM, ClojureCLR and ClojureDart.

  Effects deliver file contents as text (`:file/read`); handlers parse them with these
  functions and keep every rule in shared code — no host JSON library, so numbers,
  escapes and errors behave the same everywhere. Number syntax is validated before
  conversion: the host parsers disagree on inputs such as \"1,000\" (.NET accepts it),
  \"Infinity\" (Dart accepts it) or surrounding whitespace.

    (read-csv \"a,b\\n1,\\\"x,y\\\"\\n\")  ;=> [[\"a\" \"b\"] [\"1\" \"x,y\"]]
    (read-json \"[{\\\"bpm\\\": 72}]\")   ;=> [{\"bpm\" 72}]
    (write-json {:bpm 72 :ok true})     ;=> \"{\\\"bpm\\\":72,\\\"ok\\\":true}\"

  Errors throw ex-info with {:reason :csv|:json :pos n :message ...}."
  (:require [clojure.string :as str]
            [cloview.core.order :as order]
            [cloview.core.text :as text]))

;; ---------------------------------------------------------------------------
;; Numbers

(def ^:private json-number-re #"-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?")
(def ^:private integer-re #"-?(0|[1-9][0-9]*)")

(defn parse-number
  "Number for a string in JSON number syntax (no surrounding spaces, no leading +),
  else nil. Integers within 15 digits become longs, everything else doubles."
  [s]
  (when (and (string? s) (re-matches json-number-re s))
    (if (and (re-matches integer-re s) (<= (count s) 16))
      (parse-long s)
      (parse-double s))))

(defn parse-decimal
  "Like parse-number but tolerant of a leading + and surrounding whitespace (typical of
  CSV exports): \" +72.5 \" -> 72.5."
  [s]
  (when (string? s)
    (let [t (str/trim s)
          t (if (and (pos? (count t)) (= "+" (subs t 0 1))) (subs t 1) t)]
      (parse-number t))))

;; ---------------------------------------------------------------------------
;; CSV

(defn- strip-bom [s]
  (if (and (pos? (count s)) (= "\uFEFF" (subs s 0 1))) (subs s 1) s))

(defn read-csv
  "Rows (vectors of strings) of RFC 4180 CSV text: `,` separators, double-quoted fields
  with \"\" escapes, LF or CRLF line ends, optional final newline, optional UTF-8 BOM.
  Blank lines are skipped. Options: :separator (a one-character string, default \",\")."
  ([text] (read-csv text {}))
  ([text {:keys [separator] :or {separator ","}}]
   (let [s (strip-bom (str text))
         n (count s)]
     (loop [i 0, field-start 0, quoted? false, pieces nil, row [], rows []]
       (if (>= i n)
         (let [row (if (or quoted? pieces (< field-start n) (seq row))
                     (conj row (if pieces (apply str pieces) (subs s field-start n)))
                     row)]
           (when quoted?
             (throw (ex-info "Unterminated quoted CSV field" {:reason :csv :pos n})))
           (if (and (seq row) (not= row [""])) (conj rows row) rows))
         (let [c (subs s i (inc i))]
           (cond
             quoted?
             (if (= c "\"")
               (if (and (< (inc i) n) (= "\"" (subs s (inc i) (+ i 2))))
                 (recur (+ i 2) (+ i 2) true (conj pieces (subs s field-start (inc i))) row rows)
                 (recur (inc i) (inc i) false (conj pieces (subs s field-start i)) row rows))
               (recur (inc i) field-start true pieces row rows))

             (and (= c "\"") (= i field-start) (nil? pieces))
             (recur (inc i) (inc i) true [] row rows)

             (= c separator)
             (recur (inc i) (inc i) false nil
                    (conj row (if pieces (apply str pieces) (subs s field-start i))) rows)

             (or (= c "\n") (= c "\r"))
             (let [row (conj row (if pieces (apply str pieces) (subs s field-start i)))
                   next-i (if (and (= c "\r") (< (inc i) n) (= "\n" (subs s (inc i) (+ i 2))))
                            (+ i 2) (inc i))
                   rows (if (= row [""]) rows (conj rows row))]
               (recur next-i next-i false nil [] rows))

             pieces ; characters after a closing quote: keep them (lenient)
             (recur (inc i) field-start false (conj pieces c) row rows)

             :else
             (recur (inc i) field-start false nil row rows))))))))

(defn csv-maps
  "CSV text -> {:header [names] :rows [{:line n :values {name value}}]}; header names are
  trimmed and lower-cased, :line is the 1-based line of the row."
  [text]
  (let [[header & rows] (read-csv text)
        names (mapv #(text/ascii-lower (str/trim %)) header)]
    {:header names
     :rows (vec (map-indexed (fn [i row]
                               {:line (+ i 2)
                                :values (zipmap names (concat row (repeat nil)))})
                             rows))}))

;; ---------------------------------------------------------------------------
;; JSON

(defn- json-error [message pos]
  (throw (ex-info (str "Malformed JSON: " message " at " pos) {:reason :json :pos pos :message message})))

(def ^:private ws #{" " "\t" "\n" "\r"})

(defn- skip-ws [s i]
  (let [n (count s)]
    (loop [i i]
      (if (and (< i n) (contains? ws (subs s i (inc i)))) (recur (inc i)) i))))

(def ^:private hex-digit
  {"0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7 "8" 8 "9" 9
   "a" 10 "b" 11 "c" 12 "d" 13 "e" 14 "f" 15 "A" 10 "B" 11 "C" 12 "D" 13 "E" 14 "F" 15})

(defn- code-unit->str [code]
  #?(:cljd (String/fromCharCode code)
     :clj (str (char code))
     :cljr (str (char code))))

(defn- read-string-at
  "[string next-index] for the JSON string starting at the quote at `i`."
  [s i]
  (let [n (count s)]
    (loop [j (inc i), start (inc i), pieces []]
      (if (>= j n)
        (json-error "unterminated string" i)
        (let [c (subs s j (inc j))]
          (cond
            (= c "\"") [(apply str (conj pieces (subs s start j))) (inc j)]
            (= c "\\")
            (if (>= (inc j) n)
              (json-error "bad escape" j)
              (let [e (subs s (inc j) (+ j 2))
                    pieces (conj pieces (subs s start j))]
                (case e
                  "\"" (recur (+ j 2) (+ j 2) (conj pieces "\""))
                  "\\" (recur (+ j 2) (+ j 2) (conj pieces "\\"))
                  "/" (recur (+ j 2) (+ j 2) (conj pieces "/"))
                  "b" (recur (+ j 2) (+ j 2) (conj pieces (code-unit->str 8)))
                  "f" (recur (+ j 2) (+ j 2) (conj pieces (code-unit->str 12)))
                  "n" (recur (+ j 2) (+ j 2) (conj pieces "\n"))
                  "r" (recur (+ j 2) (+ j 2) (conj pieces "\r"))
                  "t" (recur (+ j 2) (+ j 2) (conj pieces "\t"))
                  "u" (if (> (+ j 6) n)
                        (json-error "bad unicode escape" j)
                        (let [digits (map #(get hex-digit (subs s % (inc %))) (range (+ j 2) (+ j 6)))]
                          (if (some nil? digits)
                            (json-error "bad unicode escape" j)
                            (recur (+ j 6) (+ j 6)
                                   (conj pieces (code-unit->str (reduce (fn [a d] (+ (* 16 a) d)) 0 digits)))))))
                  (json-error "bad escape" j))))
            (< (int (nth s j)) 32) (json-error "control character in string" j)
            :else (recur (inc j) start pieces)))))))

(def ^:private number-chars (set (map str "+-0123456789.eE")))

(def default-max-depth
  "Nesting limit of read-json. The reader recurses per array/object, and a deep enough
  input overflows the stack, which .NET cannot catch (the process dies): ClojureCLR
  overflows a 1 MB thread somewhere between 600 and 800 levels."
  128)

(declare read-value)

(defn- check-depth! [opts depth i]
  (when (>= depth (or (:max-depth opts) default-max-depth))
    (json-error "nested too deeply" i)))

(defn- read-array [s i opts depth]
  (let [i (skip-ws s (inc i))]
    (if (and (< i (count s)) (= "]" (subs s i (inc i))))
      [[] (inc i)]
      (loop [i i, out []]
        (let [[v i] (read-value s i opts (inc depth))
              i (skip-ws s i)
              c (when (< i (count s)) (subs s i (inc i)))]
          (case c
            "," (recur (skip-ws s (inc i)) (conj out v))
            "]" [(conj out v) (inc i)]
            (json-error "expected , or ]" i)))))))

(defn- read-object [s i {:keys [keywordize?] :as opts} depth]
  (let [i (skip-ws s (inc i))]
    (if (and (< i (count s)) (= "}" (subs s i (inc i))))
      [{} (inc i)]
      (loop [i i, out {}]
        (when-not (and (< i (count s)) (= "\"" (subs s i (inc i))))
          (json-error "expected a string key" i))
        (let [[k i] (read-string-at s i)
              i (skip-ws s i)
              _ (when-not (and (< i (count s)) (= ":" (subs s i (inc i))))
                  (json-error "expected :" i))
              [v i] (read-value s (skip-ws s (inc i)) opts (inc depth))
              out (assoc out (if keywordize? (keyword k) k) v)
              i (skip-ws s i)
              c (when (< i (count s)) (subs s i (inc i)))]
          (case c
            "," (recur (skip-ws s (inc i)) out)
            "}" [out (inc i)]
            (json-error "expected , or }" i)))))))

(defn- read-literal [s i word value]
  (if (and (<= (+ i (count word)) (count s)) (= word (subs s i (+ i (count word)))))
    [value (+ i (count word))]
    (json-error "unexpected token" i)))

(defn- read-value
  "[value next-index]; `depth` = arrays/objects already open around position `i`."
  [s i opts depth]
  (let [i (skip-ws s i)]
    (when (>= i (count s)) (json-error "unexpected end of input" i))
    (let [c (subs s i (inc i))]
      (cond
        (= c "{") (do (check-depth! opts depth i) (read-object s i opts depth))
        (= c "[") (do (check-depth! opts depth i) (read-array s i opts depth))
        (= c "\"") (read-string-at s i)
        (= c "t") (read-literal s i "true" true)
        (= c "f") (read-literal s i "false" false)
        (= c "n") (read-literal s i "null" nil)
        (contains? number-chars c)
        (let [end (loop [j i] (if (and (< j (count s)) (contains? number-chars (subs s j (inc j))))
                                (recur (inc j)) j))
              text (subs s i end)]
          (if-some [x (parse-number text)]
            [x end]
            (json-error (str "bad number " (pr-str text)) i)))
        :else (json-error "unexpected character" i)))))

(defn read-json
  "Clojure data for JSON text: objects -> maps (string keys, or keywords with
  {:keywordize? true}), arrays -> vectors, numbers -> long/double (see parse-number),
  true/false/null. A leading UTF-8 BOM is ignored; trailing content is an error, and so
  is nesting deeper than :max-depth arrays/objects (default `default-max-depth`)."
  ([text] (read-json text {}))
  ([text opts]
   (let [s (strip-bom (str text))
         [v i] (read-value s 0 opts 0)
         i (skip-ws s i)]
     (when (< i (count s)) (json-error "trailing content" i))
     v)))

;; ---------------------------------------------------------------------------
;; JSON writer (deterministic, culture-invariant; used by cloview.bridge and
;; cloview.licensing for wire messages and signed payloads)

(defn- hex4 [n]
  (let [digits "0123456789abcdef"]
    (apply str (map (fn [shift]
                      (let [d (bit-and (bit-shift-right n shift) 15)]
                        (subs digits d (inc d))))
                    [12 8 4 0]))))

(def ^:private json-escapes
  (merge (into {} (map (fn [n] [(str (char n)) (str "\\u" (hex4 n))])) (range 32))
         {"\"" "\\\"" "\\" "\\\\" "\n" "\\n" "\r" "\\r" "\t" "\\t"}))

(defn- json-string [s]
  (str "\"" (str/escape s (fn [c] (get json-escapes (str c)))) "\""))

(defn- json-double [x]
  (let [d (double x)]
    (when #?(:cljd (or (.-isNaN d) (.-isInfinite d))
             :clj (or (Double/isNaN d) (Double/isInfinite d))
             :cljr (or (Double/IsNaN d) (Double/IsInfinity d)))
      (throw (ex-info "JSON cannot represent NaN or Infinity" {:reason :json :value (str d)})))
    ;; always with a fraction or an exponent, so the reader gets a double back
    #?(:cljd (.toString d)
       :clj (str d)
       :cljr (let [s (.ToString d "R" System.Globalization.CultureInfo/InvariantCulture)]
               (if (re-find #"[.eE]" s) s (str s ".0"))))))

(defn json-key
  "The JSON object key for a Clojure map key: keywords lose the colon (namespace kept as
  \"ns/name\"), strings stay, anything else goes through str."
  [k]
  (cond (keyword? k) (if-some [n (namespace k)] (str n "/" (name k)) (name k))
        (string? k) k
        :else (str k)))

(defn write-json
  "Compact JSON (RFC 8259) for Clojure data, identical on every host: maps -> objects
  with keys in ordinal order (see json-key), vectors/lists -> arrays, sets -> arrays
  sorted by their JSON text, keywords and symbols -> strings, integers as written,
  other numbers as culture-invariant doubles with a fraction or exponent, nil -> null.
  Control characters are escaped (\\uXXXX); everything else is written as is (UTF-8).
  NaN, Infinity and other values throw ex-info {:reason :json}."
  [v]
  (cond
    (nil? v) "null"
    (true? v) "true"
    (false? v) "false"
    (string? v) (json-string v)
    (keyword? v) (json-string (json-key v))
    (symbol? v) (json-string (str v))
    (integer? v) (str v)
    (number? v) (json-double v)
    (map? v) (str "{"
                  (str/join "," (map (fn [[k x]] (str (json-string (json-key k)) ":" (write-json x)))
                                     (order/sort-by-str (fn [[k _]] (json-key k)) v)))
                  "}")
    (set? v) (str "[" (str/join "," (order/sort-strings (map write-json v))) "]")
    (sequential? v) (str "[" (str/join "," (map write-json v)) "]")
    :else (throw (ex-info (str "write-json: unsupported value " (pr-str v)) {:reason :json}))))
