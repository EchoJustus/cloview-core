(ns cloview.starter.tasks
  "Tasks of a project made by cloview's `bb init` (see bb.edn and README.md). They read
  cloview.edn, so switching the primary target is a one-word edit there.

  Generated once and owned by the project from then on: edit freely. Requires babashka."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [babashka.tasks :refer [clojure]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def windows? (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows"))

(defn config [] (edn/read-string (slurp "cloview.edn")))

(defn app-name [] (get-in (config) [:app :name]))

(defn- munged [] (str/replace (app-name) "-" "_"))

(defn- exe-name [] (apply str (map str/capitalize (str/split (app-name) #"-"))))

(defn cloview-root
  "The cloview-core checkout the project's :local/root deps point at (cloview.edn :cloview :root)."
  []
  (str (fs/normalize (fs/absolutize (get-in (config) [:cloview :root])))))

(defn enabled? [target] (contains? (set (get-in (config) [:targets :enabled])) target))

(defn- require-target! [target]
  (when-not (enabled? target)
    (throw (ex-info (str (name target) " is not a target of this project yet; add it with: bb target:add "
                         (name target))
                    {:target target}))))

(defn targets!
  "Print the targets of cloview.edn."
  []
  (let [{:keys [primary enabled]} (:targets (config))]
    (println "primary target (bb dev):" (name primary))
    (println "enabled targets:" (str/join ", " (map name enabled)))
    (println "add a release target with: bb target:add javafx|wpf|flutter")))

;; ---------------------------------------------------------------------------
;; tests on the JVM

(defn test!
  "The shared tests on the JVM (fast; no GUI)."
  [args]
  (apply clojure "-M:test" args))

;; ---------------------------------------------------------------------------
;; JavaFX

(defn run-javafx! [args]
  (require-target! :javafx)
  (apply clojure {:dir "javafx"} "-M:run" args))

;; ---------------------------------------------------------------------------
;; WPF: stage the sources, build the host, run it

(def clr-src "target/clr-src")
(def clr-host "target/clr-host")

(defn- clr-source-dirs []
  (let [root (cloview-root)
        modules (distinct (concat (map name (get (config) :modules [:core :fx])) ["wpf"]))]
    (concat (for [m modules] (str root "/modules/" m "/src"))
            [(str root "/verify/src")       ; test runner + JSON report for --run-tests / --headless-smoke
             "shared/src" "shared/test" "wpf/src"])))

(defn wpf-sync!
  "Copy every .clj/.cljc/.cljr source the host needs into target/clr-src."
  []
  (fs/delete-tree clr-src)
  (fs/create-dirs clr-src)
  (let [n (atom 0)]
    (doseq [dir (clr-source-dirs)
            :when (fs/exists? dir)
            f (fs/glob dir "**.{clj,cljc,cljr}")]
      (let [dest (fs/path clr-src (fs/relativize dir f))]
        (fs/create-dirs (fs/parent dest))
        (fs/copy f dest {:replace-existing true})
        (swap! n inc)))
    (println (str "staged " @n " files into " clr-src))))

(defn- majors [lines pattern]
  (into #{} (keep #(some-> (re-find pattern %) second parse-long)) lines))

(defn pick-tfm
  "net10.0-windows when possible, else net9/net8: the highest major that the newest SDK
  can build and an installed Windows Desktop runtime can run. CLOVIEW_TFM overrides."
  []
  (or (System/getenv "CLOVIEW_TFM")
      (let [sdks (str/split-lines (:out (p/shell {:out :string :continue true} "dotnet --list-sdks")))
            rts (str/split-lines (:out (p/shell {:out :string :continue true} "dotnet --list-runtimes")))
            sdk-max (reduce max 0 (majors sdks #"^(\d+)\."))
            desktop-max (reduce max 0 (majors rts #"^Microsoft\.WindowsDesktop\.App (\d+)\."))
            pick (first (filter #(and (>= sdk-max %) (or (not windows?) (>= desktop-max %))) [10 9 8]))]
        (if pick
          (str "net" pick ".0-windows")
          (throw (ex-info "No usable .NET SDK (8, 9 or 10) found: install the .NET 10 SDK" {}))))))

(defn wpf-build!
  "Stage the sources and build the WPF host (wpf/host) into target/clr-host."
  []
  (require-target! :wpf)
  (wpf-sync!)
  (p/shell "dotnet" "build" (str "wpf/host/" (exe-name) ".csproj") "-c" "Release"
           (str "-p:CloViewTargetFramework=" (pick-tfm)) "-o" clr-host))

(defn wpf-run!
  "Run the built host with the staged sources; extra args go to it (--run-tests,
  --headless-smoke, --out <file>, --repl <port>)."
  [& args]
  (let [exe (str (fs/path clr-host (str (exe-name) (when windows? ".exe"))))
        cmd (if (fs/exists? exe) [exe] ["dotnet" (str (fs/path clr-host (str (exe-name) ".dll")))])]
    (apply p/shell (concat cmd ["--src" (str (fs/absolutize clr-src)) "--ns" (str (app-name) ".wpf.main")] args))))

;; ---------------------------------------------------------------------------
;; Flutter: a generated Flutter project in target/, from flutter/ and shared/test

(defn- flutter-dir [] (str "target/flutter_" (munged)))

(def dart-packages
  "Dart packages of cloview's effect runners (http, storage, files)."
  ["http" "shared_preferences" "path_provider"])

(defn- absolutize-local-roots [text base]
  (str/replace text #":local/root\s+\"([^\"]+)\""
               (fn [[_ rel]]
                 (str ":local/root \""
                      (str/replace (str (fs/normalize (fs/absolutize (fs/path base rel)))) "\\" "/")
                      "\""))))

(defn flutter-prepare!
  "Create or refresh the Flutter project: copy flutter/src, flutter/test and shared/test,
  make the :local/root paths absolute, run `clj -M:cljd init` once and add the Dart
  packages. Needs Flutter >= 3.35 on PATH (3.47.x recommended)."
  []
  (require-target! :flutter)
  (let [dir (flutter-dir)]
    (fs/create-dirs dir)
    (doseq [d ["src" "test"]]
      (fs/delete-tree (fs/path dir d))
      (when (fs/exists? (fs/path "flutter" d)) (fs/copy-tree (fs/path "flutter" d) (fs/path dir d))))
    (fs/copy-tree "shared/test" (fs/path dir "test") {:replace-existing true})
    (spit (str (fs/path dir "deps.edn")) (absolutize-local-roots (slurp "flutter/deps.edn") "flutter"))
    (when-not (fs/exists? (fs/path dir "pubspec.yaml"))
      (clojure {:dir dir} "-M:cljd" "init")
      (fs/delete-if-exists (fs/path dir "test" "widget_test.dart")))
    (let [pubspec (slurp (str (fs/path dir "pubspec.yaml")))
          missing (remove #(re-find (re-pattern (str "(?m)^\\s+" % ":")) pubspec) dart-packages)]
      (when (seq missing)
        (apply p/shell {:dir dir :continue true} "flutter" "pub" "add" missing)))
    (println "prepared" dir)
    dir))

(defn run-flutter!
  "Run on Flutter with hot reload; args go to `flutter run` (default: this desktop)."
  [args]
  (let [dir (flutter-prepare!)]
    (apply clojure {:dir dir} "-M:cljd" "flutter"
           (or (seq args) ["-d" (cond windows? "windows"
                                      (str/includes? (str/lower-case (System/getProperty "os.name")) "mac") "macos"
                                      :else "linux")]))))

(defn test-flutter!
  "ClojureDart compile + the Flutter widget tests + the shared tests on Dart."
  []
  (let [dir (flutter-prepare!)]
    (clojure {:dir dir} "-M:test:cljd" "test")))

;; ---------------------------------------------------------------------------
;; the primary target

(defn dev!
  "Run the app on the primary target of cloview.edn."
  [args]
  (case (get-in (config) [:targets :primary])
    :javafx (run-javafx! args)
    :wpf (do (wpf-build!) (apply wpf-run! args))
    :flutter (run-flutter! args)))

;; ---------------------------------------------------------------------------
;; more targets (needs the cloview-core checkout of cloview.edn on bb's :paths)

(defn target-add!
  "bb target:add <javafx|wpf|flutter>: write the target folder and enable it."
  [[target & _]]
  (when-not target (throw (ex-info "usage: bb target:add javafx|wpf|flutter" {})))
  (require 'cloview.starter)
  (let [add! (resolve 'cloview.starter/add-target!)]
    (doseq [line (add! "." (keyword target) {})] (println line))))

;; ---------------------------------------------------------------------------
;; Mode B: backends that need a build step

(defn backend-build!
  "Build the backend when its language needs it (C#: dotnet build into backend/csharp/out)."
  []
  (case (get-in (config) [:logic :backend :lang])
    :csharp (p/shell {:dir "backend/csharp"} "dotnet" "build" "-c" "Release" "-o" "out")
    (println "nothing to build for this backend")))
