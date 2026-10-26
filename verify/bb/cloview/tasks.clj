;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.tasks
  "Babashka task implementations for bb.edn (run from the repo root).

  The PowerShell harness (verify/run-windows-checks.ps1) implements the same steps
  without requiring babashka; keep the two in sync (source dirs, TFM choice, paths).

  cloview-examples loads this namespace from its sibling cloview-core checkout and calls
  the functions with its own folders: (wpf-sync! dirs), (wpf-build! project & props),
  wpf-run!, check-flutter!, (flutter-prepare! {:example .. :dir .. :extra-tests [..]})
  and (python), and reads clr-source-dirs, clr-src and host-out. Keep those stable."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [babashka.tasks :refer [clojure]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def windows? (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows"))

;; ---------------------------------------------------------------------------
;; CLR: staging

(def clr-source-dirs
  "Everything CloViewHost.exe needs on CLOJURE_LOAD_PATH (merged into one tree), relative
  to the cloview-core root. Keep in sync with $sourceDirs in verify/run-windows-checks.ps1."
  ["modules/core/src" "modules/core/test"
   "modules/fx/src" "modules/fx/test"
   "modules/bridge/src" "modules/bridge/test"
   "modules/licensing/src" "modules/licensing/test"
   "modules/wpf/src"
   "verify/src"
   "examples/greeter/shared/src"
   "examples/greeter/wpf/src"])

(def clr-src "target/clr-src")

(defn wpf-sync!
  "Stage every .clj/.cljc/.cljr file of `dirs` (default `clr-source-dirs`) into
  target/clr-src; later folders win on equal paths."
  ([] (wpf-sync! clr-source-dirs))
  ([dirs]
   (fs/delete-tree clr-src)
   (fs/create-dirs clr-src)
   (let [n (atom 0)]
     (doseq [dir dirs
             f (fs/glob dir "**.{clj,cljc,cljr}")]
       (let [dest (fs/path clr-src (fs/relativize dir f))]
         (fs/create-dirs (fs/parent dest))
         (fs/copy f dest {:replace-existing true})
         (swap! n inc)))
     (println (str "staged " @n " files into " clr-src)))))

;; ---------------------------------------------------------------------------
;; CLR: target framework + build

(defn- majors [lines pattern]
  (into #{} (keep #(some-> (re-find pattern %) second parse-long)) lines))

(defn pick-tfm
  "net10.0-windows when possible, else net9.0-windows / net8.0-windows: the highest
  major N that the newest SDK can build (SDK >= N) and that an installed
  Microsoft.WindowsDesktop.App runtime can run (major >= N; the csproj sets
  RollForward=Major). Same rule as Select-ClrTfm in run-windows-checks.ps1.
  CLOVIEW_TFM overrides."
  []
  (or (System/getenv "CLOVIEW_TFM")
      (let [sdks (str/split-lines (:out (p/shell {:out :string :continue true} "dotnet --list-sdks")))
            rts (str/split-lines (:out (p/shell {:out :string :continue true} "dotnet --list-runtimes")))
            sdk-max (reduce max 0 (majors sdks #"^(\d+)\."))
            desktop-max (reduce max 0 (majors rts #"^Microsoft\.WindowsDesktop\.App (\d+)\."))
            pick (first (filter #(and (>= sdk-max %) (or (not windows?) (>= desktop-max %)))
                                [10 9 8]))]
        (if pick
          (str "net" pick ".0-windows")
          (throw (ex-info "No usable .NET SDK (8, 9 or 10) found — install the .NET 10 SDK"
                          {:sdks sdks :runtimes rts}))))))

(def host-project "modules/wpf/host-template/CloViewHost.csproj")
(def host-out "target/clr-host")

(defn wpf-build!
  "Build CloViewHost (`project`, default `host-project`) into target/clr-host. `props` are
  extra MSBuild arguments, e.g. \"-p:CloViewSrcDir=<staged sources>\" for a copy of the
  template outside this repository."
  ([] (wpf-build! host-project))
  ([project & props]
   (let [tfm (pick-tfm)]
     (println "building CloViewHost for" tfm)
     (apply p/shell "dotnet" "build" (str project) "-c" "Release"
            (str "-p:CloViewTargetFramework=" tfm) (concat props ["-o" host-out])))))

(defn wpf-run!
  "Run CloViewHost with the staged sources; extra args are passed through
  (--headless-smoke, --run-tests, --out <file>, --repl <port>)."
  [& args]
  (let [exe (str (fs/path host-out (if windows? "CloViewHost.exe" "CloViewHost")))
        cmd (if (fs/exists? exe) [exe] ["dotnet" (str (fs/path host-out "CloViewHost.dll"))])]
    (apply p/shell (concat cmd ["--src" (str (fs/absolutize clr-src))] args))))

;; ---------------------------------------------------------------------------
;; Flutter: version check + prepare target/flutter-<example>

(def flutter-examples
  "Flutter example apps: source folder, generated project folder, and extra test source
  folders copied into the project's test/ (cljd test only discovers namespaces inside the
  project folder). cloview-examples passes such a map to flutter-prepare! directly."
  {"greeter" {:example "examples/greeter/flutter" :dir "target/flutter-greeter" :extra-tests []}})

(def min-flutter
  "ClojureDart 0.9.20261002 (cljd.flutter) uses AlignmentGeometry.topLeft & co., which
  appeared in Flutter 3.35.0."
  [3 35 0])

(def dart-packages
  "Dart packages of the cloview.fx.* :cljd branches (http, storage, file effects)."
  ["http" "shared_preferences" "path_provider"])

(defn flutter-version
  "[major minor patch] of the flutter on PATH, or nil."
  []
  (let [{:keys [out exit]} (p/shell {:out :string :err :string :continue true} "flutter" "--version")]
    (when (zero? exit)
      (some->> (re-find #"Flutter (\d+)\.(\d+)\.(\d+)" out) rest (mapv parse-long)))))

(defn check-flutter!
  "Throw with upgrade instructions unless Flutter >= min-flutter is on PATH."
  []
  (let [v (flutter-version)]
    (cond
      (nil? v) (throw (ex-info "flutter not found on PATH (install Flutter >= 3.35, 3.47.x recommended)" {}))
      (neg? (compare v min-flutter))
      (throw (ex-info (str "Flutter " (str/join "." v) " is too old: ClojureDart 0.9.20261002 needs Flutter >= "
                           (str/join "." min-flutter) ". Run `flutter upgrade` (3.47.x stable recommended), "
                           "then `clj -M:cljd clean` in the target/flutter-* folders.")
                      {:flutter v}))
      :else (println "flutter" (str/join "." v) "(>= 3.35: ok)"))))

(defn- absolutize-local-roots
  "deps.edn text with every :local/root made absolute (relative to `base`)."
  [text base]
  (str/replace text #":local/root\s+\"([^\"]+)\""
               (fn [[_ rel]]
                 (str ":local/root \""
                      (str/replace (str (fs/normalize (fs/absolutize (fs/path base rel)))) "\\" "/")
                      "\""))))

(defn flutter-prepare!
  "Create/refresh the Flutter project of an example: `spec` is a name of
  `flutter-examples` (\"greeter\") or a map {:example <folder> :dir <project folder>
  :extra-tests [<folders>]}. Copies src/ and test/ (+ extra test folders), makes the
  :local/root paths absolute, runs `clj -M:cljd init` once (flutter create), then adds
  the Dart packages."
  ([] (flutter-prepare! "greeter"))
  ([spec]
   (let [{:keys [example dir extra-tests]} (if (map? spec)
                                             spec
                                             (or (get flutter-examples spec)
                                                 (throw (ex-info (str "unknown Flutter example " spec) {}))))]
     (check-flutter!)
     (fs/create-dirs dir)
     (doseq [d ["src" "test"]]
       (fs/delete-tree (fs/path dir d))
       (fs/copy-tree (fs/path example d) (fs/path dir d)))
     (doseq [t extra-tests]
       (fs/copy-tree t (fs/path dir "test") {:replace-existing true}))
     (spit (str (fs/path dir "deps.edn"))
           (absolutize-local-roots (slurp (str (fs/path example "deps.edn"))) example))
     (edn/read-string (slurp (str (fs/path dir "deps.edn")))) ; sanity: still valid EDN
     (when-not (fs/exists? (fs/path dir "pubspec.yaml"))
       ;; babashka.tasks/clojure runs the Clojure CLI via deps.clj (works on Windows,
       ;; where clj/clojure are PowerShell functions rather than executables)
       (clojure {:dir dir} "-M:cljd" "init" "--platforms=windows,android,linux,macos")
       ;; flutter create's sample test references MyApp, which the cljd entry point replaces
       (fs/delete-if-exists (fs/path dir "test" "widget_test.dart")))
     ;; a Flutter upgrade invalidates ClojureDart's output built with the previous SDK
     (let [stamp (fs/path dir ".cloview-flutter-version")
           now (str/join "." (flutter-version))
           prev (when (fs/exists? stamp) (str/trim (slurp (str stamp))))]
       (when (and (fs/exists? (fs/path dir ".clojuredart")) (not= prev now))
         (println "Flutter changed (" (or prev "unknown") "->" now "): clj -M:cljd clean")
         (clojure {:dir dir} "-M:cljd" "clean"))
       (spit (str stamp) now))
     (let [missing-packages (fn []
                              (let [pubspec (slurp (str (fs/path dir "pubspec.yaml")))]
                                (remove #(re-find (re-pattern (str "(?m)^\\s+" % ":")) pubspec) dart-packages)))
           missing (missing-packages)]
       (when (seq missing)
         (let [{:keys [exit]} (apply p/shell {:dir dir :continue true} "flutter" "pub" "add" missing)]
           (when-not (zero? exit)
             (if (empty? (missing-packages))
               ;; packages added and resolved; Flutter's plugin symlink step failed
               (println "warning: `flutter pub add` exited" exit "after adding the packages. Usually this is"
                        "\"Building with plugins requires symlink support\": enable Windows Developer Mode"
                        "(start ms-settings:developers). Compiling and tests work without it; running the"
                        "Windows app needs it.")
               (throw (ex-info (str "flutter pub add failed (exit " exit ")") {:exit exit})))))))
     (println "prepared" dir))))

;; ---------------------------------------------------------------------------
;; Python (cloview-examples runs its benchmark checkers with it)

(defn python
  "Command vector of a Python 3.7+ that really runs (python3, python or `py -3`), or nil.
  Each candidate is tried with --version: on Windows python3.exe/python.exe may be the
  Microsoft Store alias, which is on PATH but only opens the Store."
  []
  (some (fn [cmd]
          (when (fs/which (first cmd))
            (let [{:keys [exit out err]} (try (apply p/shell {:out :string :err :string :continue true}
                                                     (conj cmd "--version"))
                                              (catch Exception _ {:exit -1}))]
              (when-let [[_ minor] (and (zero? exit) (re-find #"Python 3\.(\d+)" (str out err)))]
                (when (>= (parse-long minor) 7) cmd)))))
        [["python3"] ["python"] ["py" "-3"]]))
