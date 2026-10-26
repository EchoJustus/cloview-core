;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
;; cloview static check — dependency-free (clojure.core only).
;;
;;   clojure -M verify/static_check.clj            (or: bb static-check), in cloview-core
;;   clojure -M ../cloview-core/verify/static_check.clj --core ../cloview-core --app apps
;;                                                 (an app tree checked against the core,
;;                                                  e.g. cloview-examples' clojure -M:static-check)
;;
;; For each platform (:clj :cljr :cljd) it reads every source file visible to that
;; platform (.clj/.cljr/.cljd + .cljc with the platform's reader features) and checks,
;; for calls into the repository's own namespaces (cloview.*, greeter.*, and with --app
;; the first segments of the app's namespaces, e.g. fitness.*):
;;   - the referenced var exists (catches typos in alias/name),
;;   - the argument count matches one of the defn's arities.
;; It exists because ClojureDart (.cljd) and WPF (.cljr) code cannot be compiled outside
;; their toolchains; JVM and CLR code is additionally compiled by the test suites.
;; Architecture rules checked on top (docs/STAGE_2_1_DESIGN.md):
;;   - Material shim: under modules/, only cloview.flutter.material may import Flutter UI
;;     libraries (package:flutter/*, package:material_ui/*, package:cupertino_ui/*);
;;   - pure core: modules/core/src may only require clojure.* and cloview.core.*;
;;   - portable CAS: no (compare-and-set! a <boolean/number/char literal> ...) anywhere —
;;     ClojureCLR 1.12.x compares boxed value types by reference, so such a CAS never
;;     succeeds there (use swap-vals!; see cloview.core.store/try-acquire!);
;;   - ClojureCLR struct locals: no type hint on a let/loop local whose type is a .NET value
;;     type (Point, Rect, Size, Thickness, ...). ClojureCLR treats every value type as a
;;     primitive and rejects it ("Can't type hint a local with a primitive initializer"),
;;     so the namespace fails to load; leave the local unhinted (the type is inferred);
;;   - ClojureDart host pass: in .cljc files under modules/ and examples/ (and every .cljc
;;     file of an --app tree), a reader
;;     conditional with a :clj branch must list :cljd BEFORE it. ClojureDart's first pass
;;     reads .cljc with the features #{:cljd :cljd/clj-host :clj}, so a :clj branch that
;;     comes first is selected there; at top level that breaks the build (e.g. a def
;;     hinted with a JVM type: "Can't resolve type ..."). Top-level conditionals need a
;;     :cljd branch even when it is just `:cljd nil`.
;;   - ClojureDart coverage (`cljd-coverage`): in the code ClojureDart compiles (portable
;;     module sources, shared app sources and tests, Flutter entry points), every symbol
;;     must be defined by the pinned ClojureDart (verify/cljd/cljd-publics.edn of the core), by the
;;     file itself or by a local binding. The Stage 2-2 Windows run stopped on
;;     `with-redefs`, which ClojureDart does not have; this rule reports that kind of
;;     mistake without a Flutter SDK. Local bindings are collected file-wide (an
;;     over-approximation), so a shadowed name can hide a miss, but nothing valid is
;;     reported.
;; With --app DIR (repeatable) the core's files are read for the index only: problems are
;; reported for the app's files, and the core is checked in its own repository.
;; Exit code: 0 clean, 1 problems found, 2 bad arguments.

(ns cloview.static-check
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def platform-exts
  {:clj #{"clj" "cljc"}
   :cljr #{"cljr" "cljc"}
   :cljd #{"cljd" "cljc"}})

(def core-roots
  "Source folders of cloview-core, relative to its root."
  ["modules" "examples" "starter" "verify/src" "verify/cljd/src"])

(def ^:dynamic *config*
  "{:core <cloview-core root> :apps [<app tree> ...]}: set by -main from the arguments."
  {:core "." :apps []})

(def threading-heads
  '#{-> ->> cond-> cond->> some-> some->> doto as-> clojure.core/-> clojure.core/->>})

(defn ext [^java.io.File f]
  (let [n (.getName f) i (.lastIndexOf n ".")] (when (pos? i) (subs n (inc i)))))

(defn- tree-files
  "Files under `dir` (skipping target/ folders) as {:file File :rel <path relative to
  `base`, with forward slashes>}."
  [base dir]
  (let [b (.toPath (.getCanonicalFile (io/file base)))]
    (for [^java.io.File f (file-seq (io/file dir))
          :when (.isFile f)
          :when (not (str/includes? (str f) (str java.io.File/separator "target" java.io.File/separator)))]
      {:file f :rel (str/replace (str (.relativize b (.toPath (.getCanonicalFile f)))) "\\" "/")})))

(defn- core-dir
  "A folder of the core (`sub` relative to its root): plain `sub` when the core is the
  working directory, so reported paths stay short (modules/..., not ./modules/...)."
  [sub]
  (let [core (:core *config*)]
    (if (= "." core) (io/file sub) (io/file core sub))))

(defn source-entries
  "Every source file visible to `platform`: the core's (:app? false, :rel relative to the
  core root) and the --app trees' (:app? true, :rel relative to the app tree)."
  [platform]
  (let [{:keys [core apps]} *config*
        visible? (fn [{:keys [file]}] (contains? (platform-exts platform) (ext file)))]
    (concat
     (for [r core-roots e (tree-files core (core-dir r)) :when (visible? e)] (assoc e :app? false))
     (for [a apps e (tree-files a a) :when (visible? e)] (assoc e :app? true)))))

(defn source-files [platform]
  (map :file (source-entries platform)))

(defn read-forms
  "All top-level forms of `f` read with `platform` features. ClojureDart's #/(T args)
  generic notation is rewritten to (T args) first (syntax-equivalent for this check)."
  [f platform]
  (let [text (cond-> (slurp f) (= platform :cljd) (str/replace "#/(" "("))
        rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))
        opts {:eof ::eof :read-cond :allow :features #{platform}}]
    (binding [*default-data-reader-fn* (fn [_tag v] v)
              *read-eval* false
              ;; resolve ::alias/kw and ::kw without loading the namespaces
              *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                  (currentNS [_] 'cloview.static-check.file)
                                  (resolveClass [_ s] s)
                                  (resolveAlias [_ s] s)
                                  (resolveVar [_ s] s))]
      (loop [acc []]
        (let [form (read opts rdr)]
          (if (= form ::eof) acc (recur (conj acc form))))))))

(defn- arglists-of [[_ _name & more]]
  (let [more (drop-while #(or (string? %) (map? %)) more)]
    (cond
      (vector? (first more)) [(first more)]
      :else (keep #(when (and (seq? %) (vector? (first %))) (first %)) more))))

(defn- arity-spec [arglists]
  (for [al arglists]
    (let [[fixed rest] (split-with #(not= '& %) al)]
      {:n (count fixed) :variadic? (boolean (seq rest))})))

(defn- flatten-do
  "Top-level forms with (do ...) blocks spliced in: platform blocks such as
  #?(:cljr (do (defn a ...) (defn b ...))) define top-level vars too."
  [forms]
  (mapcat (fn [f] (if (and (seq? f) (= 'do (first f))) (flatten-do (rest f)) [f])) forms))

(defn ns-info [forms]
  (let [[_ ns-sym & clauses] (first (filter #(and (seq? %) (= 'ns (first %))) forms))
        requires (mapcat rest (filter #(and (seq? %) (= :require (first %))) clauses))
        aliases (into {}
                      (keep (fn [spec]
                              (when (and (vector? spec) (symbol? (first spec)))
                                (let [{:keys [as]} (apply hash-map (rest spec))]
                                  (when as [as (first spec)])))))
                      requires)
        defs (into {}
                   (mapcat
                    (fn [form]
                      (when (seq? form)
                        (case (first form)
                          (defn defn- defmacro) [[(second form) {:arities (arity-spec (arglists-of form))}]]
                          (def defonce) [[(second form) {:arities nil}]]
                          declare (map (fn [s] [s {:arities nil}]) (rest form))
                          defmulti [[(second form) {:arities nil}]]
                          defprotocol (for [sig (drop 2 form) :when (seq? sig)]
                                        [(first sig) {:arities (arity-spec (filter vector? (rest sig)))}])
                          (defrecord deftype) [[(symbol (str "->" (second form))) {:arities nil}]
                                               [(symbol (str "map->" (second form))) {:arities nil}]]
                          nil))))
                   (flatten-do forms))]
    {:ns ns-sym :aliases aliases :defs defs
     :libs (keep (fn [spec] (cond (vector? spec) (first spec)
                                  (or (symbol? spec) (string? spec)) spec))
                 requires)}))

(def ^:private flutter-ui-lib #"^package:(flutter|material_ui|cupertino_ui)/")

(defn check-architecture
  "Problems for the architecture rules (Material shim, pure core) in one parsed core file
  (`rel` is its path relative to the core root)."
  [platform {:keys [file rel app? info]}]
  (let [path (if app? "" rel)
        libs (:libs info)
        where (str (name platform) " " file ": ")]
    (concat
     (when (and (str/starts-with? path "modules/")
                (not (str/ends-with? path "cloview/flutter/material.cljd")))
       (for [l libs :when (and (string? l) (re-find flutter-ui-lib l))]
         (str where "imports \"" l "\" - only cloview.flutter.material may import Flutter UI libraries (Material shim)")))
     (when (str/starts-with? path "modules/core/src/")
       (for [l libs :when (not (and (symbol? l) (re-find #"^(clojure|cloview\.core)\." (str l))))]
         (str where "requires " (pr-str l) " - core namespaces may only require clojure.* and cloview.core.*"))))))

(def ^:dynamic *local-prefixes*
  "First namespace segments whose calls are checked: the core's, plus those of the --app
  trees (computed by check-platform)."
  #{"cloview" "greeter"})

(defn- local-ns? [sym]
  (let [s (str sym) i (.indexOf s ".")]
    (and (pos? i) (contains? *local-prefixes* (subs s 0 i)))))

(def clr-value-types
  "Simple names of .NET / WPF value types met in cloview code (see the struct-locals rule)."
  '#{Point Rect Size Vector Thickness CornerRadius GridLength Color Matrix Int32Rect Duration
     TimeSpan DateTime DateTimeOffset Guid Decimal KeyTime FontWeight FontStyle FontStretch
     Point3D Vector3D Rect3D Size3D Quaternion Matrix3D})

(def ^:private binding-heads
  '#{let let* loop loop* when-let if-let when-some if-some
     clojure.core/let clojure.core/loop clojure.core/when-let clojure.core/if-let
     clojure.core/when-some clojure.core/if-some})

(defn- struct-hinted-locals
  "Binding symbols of a let-like form hinted with a .NET value type."
  [bindings]
  (for [[b _init] (partition 2 bindings)
        :when (symbol? b)
        :let [tag (:tag (meta b))]
        :when (and (symbol? tag)
                   (contains? clr-value-types (symbol (last (str/split (str tag) #"\.")))))]
    [b tag]))

(defn check-calls [platform file forms info index]
  (let [problems (atom [])]
    (letfn [(walk [form threaded?]
              (cond
                (seq? form)
                (let [head (first form)
                      args (rest form)]
                  (when (and (contains? '#{compare-and-set! clojure.core/compare-and-set!} head)
                             (let [expected (second args)]
                               (or (boolean? expected) (number? expected) (char? expected))))
                    (swap! problems conj
                           (str "(compare-and-set! _ " (pr-str (second args)) " _) is not portable: ClojureCLR 1.12 "
                                "compares boxed values by reference; use swap-vals! (cloview.core.store/try-acquire!)")))
                  (when (and (= platform :cljr) (contains? binding-heads head) (vector? (first args)))
                    (doseq [[b tag] (struct-hinted-locals (first args))]
                      (swap! problems conj
                             (str "local ^" tag " " b ": ClojureCLR rejects a type hint on a local whose "
                                  "initializer is a value type (\"Can't type hint a local with a primitive "
                                  "initializer\"); drop the hint"))))
                  (when (and (symbol? head) (namespace head))
                    (let [target-ns (get (:aliases info) (symbol (namespace head)))
                          target (get index target-ns)]
                      (when (and target-ns (local-ns? target-ns))
                        (if-not target
                          (swap! problems conj (str "unknown namespace " target-ns " (" head ")"))
                          (if-some [d (get-in target [:defs (symbol (name head))])]
                            (when-let [arities (seq (:arities d))]
                              (let [n (+ (count args) (if threaded? 1 0))]
                                (when-not (or threaded?
                                              (some (fn [{an :n v :variadic?}] (if v (>= n an) (= n an))) arities))
                                  (swap! problems conj
                                         (str head " called with " (count args) " args; arities "
                                              (pr-str (map (fn [{an :n v :variadic?}] (if v (str an "+") an)) arities)))))))
                            (swap! problems conj (str "undefined var " head)))))))
                  (let [thread? (contains? threading-heads head)]
                    (doseq [[i a] (map-indexed vector args)]
                      ;; a threading form that is itself threaded receives x as its
                      ;; first arg, so all of its forms are threaded
                      (walk a (and thread? (or threaded? (pos? i)))))))
                (coll? form) (doseq [x form] (walk x false))
                :else nil))]
      (doseq [f forms] (walk f false)))
    (map #(str (name platform) " " file ": " %) @problems)))

;; ---------------------------------------------------------------------------
;; ClojureDart host pass: :cljd before :clj in reader conditionals

(defn- read-preserving
  "Top-level forms of `f` with reader conditionals preserved as ReaderConditional objects."
  [f]
  (let [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (slurp f)))]
    (binding [*default-data-reader-fn* (fn [_tag v] v)
              *read-eval* false
              *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                  (currentNS [_] 'cloview.static-check.file)
                                  (resolveClass [_ s] s)
                                  (resolveAlias [_ s] s)
                                  (resolveVar [_ s] s))]
      (loop [acc []]
        (let [form (read {:eof ::eof :read-cond :preserve} rdr)]
          (if (= form ::eof) acc (recur (conj acc form))))))))

(defn- rc-features [^clojure.lang.ReaderConditional rc]
  (vec (take-nth 2 (.-form rc))))

(defn check-cljd-host-pass
  "Problems for reader conditionals that ClojureDart's host pass would resolve to :clj."
  []
  (let [{:keys [core apps]} *config*
        cljc? (fn [{:keys [file]}] (= "cljc" (ext file)))
        files (map :file
                   (if (seq apps)
                     (for [a apps e (tree-files a a) :when (cljc? e)] e)
                     (for [r ["modules" "examples" "starter/templates"]
                           e (tree-files core (core-dir r)) :when (cljc? e)]
                       e)))
        problems (atom [])]
    (doseq [f files]
      (let [report! (fn [msg] (swap! problems conj (str "cljc " f ": " msg)))]
        (letfn [(walk [x top?]
                  (cond
                    (instance? clojure.lang.ReaderConditional x)
                    (let [feats (rc-features x)
                          i-clj (.indexOf ^java.util.List feats :clj)
                          i-cljd (.indexOf ^java.util.List feats :cljd)]
                      (when (and (>= i-clj 0) (>= i-cljd 0) (> i-cljd i-clj))
                        (report! (str "#?" (pr-str feats) " lists :clj before :cljd (ClojureDart's host pass would pick :clj)")))
                      (when (and top? (>= i-clj 0) (neg? i-cljd))
                        (report! (str "top-level #?" (pr-str feats) " has :clj but no :cljd branch (add :cljd nil first)")))
                      (doseq [y (take-nth 2 (rest (.-form x)))] (walk y false)))
                    (map? x) (doseq [[k v] x] (walk k false) (walk v false))
                    (coll? x) (doseq [y x] (walk y false))
                    :else nil))]
          (try
            (doseq [form (read-preserving f)] (walk form true))
            (catch Exception e (report! (str "READ ERROR " (.getMessage e))))))))
    {:files (count files) :problems @problems}))

;; ---------------------------------------------------------------------------
;; ClojureDart coverage

(defn- cljd-publics-file [] (io/file (:core *config*) "verify" "cljd" "cljd-publics.edn"))

(defn- dart-compiled?
  "Files ClojureDart compiles: portable module sources, shared app sources and tests
  (the harness copies an example's shared/test into its Flutter project), Flutter entry
  points and tests, and the app templates of the project initializer. For an --app tree
  (`app?`), `rel` is relative to the tree: <app>/shared/src, <app>/shared/test, <app>/flutter."
  [rel app?]
  (if app?
    (re-find #"^[^/]+/(shared/src|shared/test|flutter)/" rel)
    (or (re-find #"^modules/(core|fx|flutter|bridge|licensing)/src/" rel)
        (re-find #"^examples/[^/]+/(shared/src|shared/test|flutter)/" rel)
        (re-find #"^starter/templates/[^/]+/(shared/src|shared/test|flutter)/" rel))))

(def ^:private cljd-specials
  ;; cljd.compiler special forms (emit dispatch) and macros it expands itself
  '#{. set! throw new ns try catch finally case* quote do var let* loop* recur if fn* letfn def
     reify* deftype* defprotocol* extend-type-protocol* defmulti* defmethod* defcontrib* contribute* &
     defprotocol extend-type defmulti defmethod
     ;; handled inside cljd.test's `is`
     thrown? thrown-with-msg?})

(def ^:private dart-type-names
  '#{int double num bool void dynamic String Object Never Null Function Type Symbol Iterable List Map Set
     Future Stream FutureOr Record})

(defn- destructured-names [b]
  (cond
    (symbol? b) [b]
    (vector? b) (mapcat destructured-names (remove #{'&} b))
    (map? b) (mapcat (fn [[k v]]
                       (cond
                         (#{:keys :syms :strs} k) (map #(symbol (name %)) v)
                         (= :as k) [v]
                         (= :or k) (keys v)
                         (keyword? k) (when (vector? v) (map #(symbol (name %)) v))
                         :else (destructured-names k)))
                     b)
    :else nil))

(def ^:private let-like
  '#{let let* loop loop* when-let if-let when-some if-some when-first for doseq dotimes with-open
     binding areduce amap})

(defn- local-names
  "Every name the forms bind anywhere (let-like vectors and their :let modifiers, fn
  params and names, letfn, catch, as->, reify/deftype methods, and the keyword options
  that bind in cljd.flutter's f/widget and cljd.test's :runner)."
  [forms]
  (let [acc (transient #{})]
    (letfn [(add! [xs] (doseq [x xs] (when (symbol? x) (conj! acc x))))
            (arglists [body]
              (when (symbol? (first body)) (add! [(first body)]))
              (let [body (drop-while #(or (symbol? %) (string? %) (map? %)) body)]
                (if (vector? (first body))
                  (add! (destructured-names (first body)))
                  (doseq [b body :when (and (seq? b) (vector? (first b)))]
                    (add! (destructured-names (first b)))))))
            (walk [x]
              (cond
                (seq? x)
                (let [[h & args] x]
                  (cond
                    (let-like h) (when (vector? (first args))
                                   (doseq [[b v] (partition 2 (first args))]
                                     (if (= :let b)
                                       (when (vector? v) (doseq [[b2 _] (partition 2 v)] (add! (destructured-names b2))))
                                       (add! (destructured-names b)))))
                    ('#{fn fn* defn defn- defmacro} h) (arglists args)
                    (= 'letfn h) (doseq [f (first args) :when (seq? f)] (add! [(first f)]) (arglists (rest f)))
                    (= 'catch h) (add! [(second args)])
                    (= 'as-> h) (add! [(second args)])
                    ('#{reify reify* deftype defrecord extend-type extend-protocol} h)
                    (doseq [a args]
                      (cond (vector? a) (add! (destructured-names a))
                            (and (seq? a) (vector? (second a))) (add! (destructured-names (second a)))))
                    :else nil)
                  (doseq [[k v] (partition 2 1 args)]
                    (when (keyword? k)
                      (cond
                        (#{:vsync :context :ticker :as} k) (when (symbol? v) (add! [v]))
                        (#{:let :managed :watch :bind} k)
                        (when (vector? v) (doseq [[b _] (partition 2 v)] (add! (destructured-names b))))
                        (= :runner k) (when (seq? v) (doseq [a v :when (vector? a)] (add! (destructured-names a))))
                        :else nil)))
                  (doseq [a x] (walk a)))
                (coll? x) (doseq [a x] (walk a))
                :else nil))]
      (doseq [f forms] (walk f)))
    (persistent! acc)))

(defn- defined-names [forms]
  (set (mapcat (fn [f]
                 (when (seq? f)
                   (case (first f)
                     (defn defn- defmacro def defonce defmulti deftest deftest-) [(second f)]
                     declare (rest f)
                     defprotocol (cons (second f) (keep #(when (seq? %) (first %)) (drop 2 f)))
                     (deftype defrecord) [(second f) (symbol (str "->" (second f))) (symbol (str "map->" (second f)))]
                     nil)))
               (flatten-do forms))))

(defn- dart-interop? [s]
  (let [n (name s)]
    (or (str/starts-with? n ".") (str/ends-with? n ".") (str/includes? n ".")
        (re-find #"^[A-Z]" n) (contains? dart-type-names s)
        ;; gensyms of #(...) and syntax-quote
        (re-find #"__\d+(__auto__)?#?$|^p\d+__|^%" n))))

(defn check-cljd-coverage
  "Problems for symbols the pinned ClojureDart does not define, in the files it compiles."
  []
  (let [{:keys [namespaces]} (read-string (slurp (cljd-publics-file)))
        lib-publics (fn [lib]
                      (let [n (str lib)
                            n (if (str/starts-with? n "clojure.") (str "cljd." (subs n 8)) n)]
                        (some-> (get namespaces n) set)))
        core (into (set (map symbol (get namespaces "cljd.core"))) cljd-specials)
        apps? (boolean (seq (:apps *config*)))
        files (for [{:keys [file rel app?]} (source-entries :cljd)
                    ;; with --app, only the app's files (the core is checked in its repository)
                    :when (and (= app? apps?) (dart-compiled? rel app?))]
                file)
        problems (atom [])]
    (doseq [f files]
      (let [forms (read-forms f :cljd)
            [_ _ & clauses] (first (filter #(and (seq? %) (= 'ns (first %))) forms))
            reqs (for [spec (mapcat rest (filter #(and (seq? %) (= :require (first %))) clauses))
                       :when (vector? spec)]
                   (let [opts (apply hash-map (rest spec))] {:lib (first spec) :as (:as opts) :refer (:refer opts)}))
            aliases (into {} (keep (fn [{:keys [lib as]}] (when as [as lib]))) reqs)
            referred (set (mapcat (fn [{:keys [refer]}] (when (sequential? refer) refer)) reqs))
            known (-> core (into referred) (into (defined-names forms)) (into (local-names forms)))
            report! (fn [s why] (swap! problems conj (str "cljd " f ": " s " - " why)))]
        (letfn [(check-sym [s]
                  (if-some [nsp (namespace s)]
                    (let [lib (get aliases (symbol nsp) (symbol nsp))]
                      (when (and (symbol? lib) (re-find #"^(clojure|cljd)\." (str lib)))
                        (if-some [pubs (lib-publics lib)]
                          (when-not (contains? pubs (name s))
                            (report! s (str "not defined by ClojureDart's "
                                            (str/replace (str lib) #"^clojure\." "cljd."))))
                          (report! s (str "ClojureDart has no namespace " lib)))))
                    (when-not (or (contains? known s) (dart-interop? s))
                      (report! s "not defined by ClojureDart (cljd.core); use a reader conditional"))))
                (walk [x]
                  (cond
                    (symbol? x) (check-sym x)
                    (seq? x)
                    (let [[h & args] x]
                      (cond
                        ('#{ns quote comment defprotocol definterface} h) nil
                        (= 'case h) (do (walk (first args))
                                        (let [clauses (rest args)]
                                          (doseq [[i a] (map-indexed vector clauses)]
                                            ;; results and the default, not the test constants
                                            (when (or (odd? i) (and (even? i) (= i (dec (count clauses))))) (walk a)))))
                        (= '. h) (do (walk (first args)) (doseq [a (drop 2 args)] (walk a)))
                        (= 'new h) (doseq [a (rest args)] (walk a))
                        (= 'catch h) (doseq [a (drop 2 args)] (walk a))
                        ('#{reify reify* deftype defrecord extend-type extend-protocol} h)
                        (doseq [a args]
                          (if (and (seq? a) (symbol? (first a)) (vector? (second a)))
                            (doseq [b (drop 2 a)] (walk b))
                            (when-not (symbol? a) (walk a))))
                        :else (doseq [a x] (walk a))))
                    (map? x) (doseq [[k v] x] (walk k) (walk v))
                    (coll? x) (doseq [a x] (walk a))
                    :else nil))]
          (doseq [form forms] (walk form)))))
    {:files (count files) :problems (distinct @problems)}))

(defn check-platform
  "Read every file visible to `platform` and check calls and architecture rules. With
  --app trees, every file goes into the index but only the app's files are checked;
  :files counts the checked files."
  [platform]
  (let [entries (source-entries platform)
        apps? (boolean (seq (:apps *config*)))
        parsed (for [{:keys [file rel app?]} entries]
                 (try
                   (let [forms (read-forms file platform)]
                     {:file (str file) :rel rel :app? app? :forms forms :info (ns-info forms)})
                   (catch Exception e
                     {:file (str file) :rel rel :app? app?
                      :read-error (str (.getMessage e) (some->> (ex-cause e) .getMessage (str " / ")))})))
        index (into {} (keep (fn [p] (when-let [n (get-in p [:info :ns])] [n (:info p)]))) parsed)
        checked (filter #(= apps? (:app? %)) parsed)
        prefixes (into *local-prefixes*
                       (keep (fn [p] (when-let [n (and (:app? p) (get-in p [:info :ns]))]
                                       (first (str/split (str n) #"\.")))))
                       parsed)]
    (binding [*local-prefixes* prefixes]
      {:files (count checked)
       :problems (doall
                  (concat
                   (keep (fn [p] (when (:read-error p) (str (name platform) " " (:file p) ": READ ERROR " (:read-error p)))) checked)
                   (mapcat (fn [p] (when-not (:read-error p)
                                     (concat (check-calls platform (:file p) (:forms p) (:info p) index)
                                             (check-architecture platform p))))
                           checked)))})))

(defn parse-args
  "{:core <dir> :apps [<dir> ...]} from --core DIR and --app DIR (repeatable), or
  {:error <message>}."
  [args]
  (loop [args args cfg {:core "." :apps []}]
    (if-some [[k v & more] (seq args)]
      (cond
        (and (= k "--core") v) (recur more (assoc cfg :core v))
        (and (= k "--app") v) (recur more (update cfg :apps conj v))
        :else {:error (str "unknown or incomplete argument " (pr-str k)
                           " (usage: static_check.clj [--core DIR] [--app DIR]...)")})
      cfg)))

(defn -main [& args]
  (let [cfg (parse-args args)
        missing (remove #(.isDirectory (io/file %)) (cons (:core cfg) (:apps cfg)))]
    (cond
      (:error cfg) (do (println (:error cfg)) (System/exit 2))
      (seq missing) (do (println "no such folder:" (str/join ", " missing)) (System/exit 2))
      (not (.isFile (io/file (:core cfg) "modules" "core" "src" "cloview" "core" "store.cljc")))
      (do (println "not a cloview-core checkout:" (:core cfg) "(pass --core DIR)") (System/exit 2)))
    (binding [*config* cfg]
      (let [results (assoc (into (sorted-map) (map (fn [p] [p (check-platform p)]) [:clj :cljr :cljd]))
                           :cljd-host-pass (check-cljd-host-pass)
                           :cljd-coverage (check-cljd-coverage))
            problems (mapcat :problems (vals results))]
        (when (seq (:apps cfg))
          (println (str "app trees " (str/join ", " (:apps cfg)) " against cloview-core at " (:core cfg)
                        " (core files are read for the index only)")))
        (doseq [[p {:keys [files problems]}] results]
          (println (format "%-14s %3d files  %s" (name p) files
                           (if (seq problems) (str (count problems) " problem(s)") "OK"))))
        (doseq [pr problems] (println "  -" pr))
        (System/exit (if (seq problems) 1 0))))))

(apply -main *command-line-args*)
