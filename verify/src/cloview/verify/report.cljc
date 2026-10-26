;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.verify.report
  "Tiny dependency-free helpers for verification reports (JVM + ClojureCLR):
  a JSON writer, a check accumulator and host info. Used by the JVM cljfx smoke, the CLR
  WPF smoke and the portable test runner, so every host produces uniform JSON files."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; JSON

(def ^:private hex "0123456789abcdef")

(defn- escape-char [c]
  (let [code (int c)]
    (case code
      34 "\\\""
      92 "\\\\"
      10 "\\n"
      13 "\\r"
      9 "\\t"
      8 "\\b"
      12 "\\f"
      (if (< code 32)
        (str "\\u00" (nth hex (quot code 16)) (nth hex (rem code 16)))
        (str c)))))

(defn- json-string [s]
  (str "\"" (apply str (map escape-char s)) "\""))

(defn- json-number
  "JSON number text. Doubles are written culture-invariantly with a fractional part
  (20.0, not 20): on ClojureCLR 1.12.6 `str` of a double drops \".0\" for integral
  values, and .NET's own ToString()/format follow the current culture (\"0,6\" on de-DE
  would corrupt the JSON), so the invariant \"R\" format is used explicitly."
  [x]
  (if (integer? x)
    (str x)
    (let [d (double x)]
      (if (or (not= d d)
              #?(:clj (Double/isInfinite d) :cljr (System.Double/IsInfinity d)))
        "null"
        #?(:clj (str d)
           :cljr (let [s (.ToString d "R" System.Globalization.CultureInfo/InvariantCulture)]
                   (if (re-find #"[.eE]" s) s (str s ".0"))))))))

(defn- json-key [k]
  (cond
    (keyword? k) (if (namespace k) (str (namespace k) "/" (name k)) (name k))
    (string? k) k
    :else (pr-str k)))

(defn ->json
  "Serialize Clojure data to a compact JSON string. Keywords become strings without the
  leading colon; unknown objects are stringified."
  [x]
  (cond
    (nil? x) "null"
    (true? x) "true"
    (false? x) "false"
    (string? x) (json-string x)
    (keyword? x) (json-string (json-key x))
    (symbol? x) (json-string (str x))
    (number? x) (json-number x)
    (map? x) (str "{" (str/join "," (map (fn [[k v]] (str (json-string (json-key k)) ":" (->json v))) x)) "}")
    (or (sequential? x) (set? x)) (str "[" (str/join "," (map ->json x)) "]")
    :else (json-string (str x))))

(defn ->pretty-json
  "Indented JSON (2 spaces) for human-readable report files."
  ([x] (->pretty-json x ""))
  ([x indent]
   (let [inner (str indent "  ")]
     (cond
       (and (map? x) (seq x))
       (str "{\n"
            (str/join ",\n" (map (fn [[k v]] (str inner (json-string (json-key k)) ": "
                                                  (->pretty-json v inner)))
                                 x))
            "\n" indent "}")
       (and (or (sequential? x) (set? x)) (seq x)
            (some #(or (map? %) (sequential? %)) x))
       (str "[\n" (str/join ",\n" (map #(str inner (->pretty-json % inner)) x)) "\n" indent "]")
       :else (->json x)))))

(defn write-json!
  "Write `data` as pretty JSON to `path`, creating parent directories."
  [path data]
  #?(:clj (let [f (java.io.File. (str path))]
            (when-some [p (.getParentFile f)] (.mkdirs p))
            (spit f (str (->pretty-json data) "\n")))
     :cljr (let [full (System.IO.Path/GetFullPath (str path))
                 dir (System.IO.Path/GetDirectoryName full)]
             (when-not (str/blank? dir) (System.IO.Directory/CreateDirectory dir))
             (System.IO.File/WriteAllText full (str (->pretty-json data) "\n"))))
  path)

;; ---------------------------------------------------------------------------
;; Check accumulation

(defn new-report
  "Mutable report accumulator for a smoke suite."
  [suite host]
  (atom {:suite suite
         :host host
         :started (#?(:clj (fn [] (str (java.time.Instant/now)))
                      :cljr (fn [] (.ToString System.DateTime/UtcNow "o"))))
         :checks []
         :errors []}))

(defn check!
  "Record a check. `critical?` checks decide the suite's exit code."
  ([report id expected actual] (check! report id expected actual {}))
  ([report id expected actual {:keys [critical? note] :or {critical? true}}]
   (let [ok (= expected actual)]
     (swap! report update :checks conj
            (cond-> {:id id :ok ok :critical critical?
                     :expected (if (keyword? expected) (str expected) expected)
                     :actual (if (keyword? actual) (str actual) actual)}
              note (assoc :note note)))
     ok)))

(defn error!
  "Record an unexpected exception (as text) under `where`."
  [report where ex]
  (swap! report update :errors conj {:where (str where) :error (str ex)})
  nil)

(defn finish
  "Close the report: adds :ok (all critical checks passed, no errors), counts and
  duration. Returns the final map."
  [report started-ms now-ms]
  (let [{:keys [checks errors]} @report
        critical (filter :critical checks)
        ok (and (every? :ok critical) (empty? errors) (seq critical))]
    (swap! report assoc
           :ok (boolean ok)
           :checks_passed (count (filter :ok checks))
           :checks_total (count checks)
           :duration_ms (- now-ms started-ms))
    @report))

(defn now-ms []
  #?(:clj (System/currentTimeMillis)
     :cljr (.ToUnixTimeMilliseconds System.DateTimeOffset/UtcNow)))

(defn host-info
  "Runtime facts for the report header."
  []
  #?(:clj {:clojure (clojure-version)
           :java (System/getProperty "java.version")
           :os (str (System/getProperty "os.name") " " (System/getProperty "os.version"))
           :javafx (System/getProperty "javafx.runtime.version")}
     :cljr {:clojure (clojure-version)
            :dotnet (str System.Environment/Version)
            :framework (str System.Runtime.InteropServices.RuntimeInformation/FrameworkDescription)
            :os (str System.Runtime.InteropServices.RuntimeInformation/OSDescription)
            :clojure_load_path (System.Environment/GetEnvironmentVariable "CLOJURE_LOAD_PATH")}))
