;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.starter
  "cloview's project initializer.

    bb init <dir> [--target javafx|wpf|flutter] [--logic same-host|backend]
                  [--backend java|csharp|dart] [--name my-app] [--title \"My App\"]
                  [--with licensing] [--cloview <checkout>] [--force]

  writes a new app with ONE primary GUI target, the logic mode of your choice and a
  cloview.edn manifest. Inside the app, `bb target:add <target>` adds a release target
  later (cloview.starter/add-target!). Daily work stays on the primary target.

  Templates live in starter/templates: real Clojure sources whose namespace prefix
  `cloview-app`, file prefix `cloview_app` and title `Cloview App` are replaced, so the
  repository's static checks and tests cover them. Pure clojure.java.io: runs under
  babashka and on the JVM alike (`clojure -M:init <dir> ...`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def targets [:javafx :wpf :flutter])
(def modes [:same-host :backend])
(def backend-langs [:java :csharp :dart])
(def extras #{:licensing})

(def ^:private name-re #"[a-z][a-z0-9]*(-[a-z0-9]+)*")

(def reserved-names
  "App names that cannot work: the app's namespaces are <name>.core etc., and these
  first segments belong to the JVM (java.* is even refused by the class loader), the
  Clojure dialects, the Dart SDK or cloview itself."
  #{"java" "javax" "jdk" "sun" "clojure" "cljd" "cljs" "cloview" "dart" "flutter"})

(defn title-problem
  "Why `title` cannot be used, or nil. The title is written into Clojure strings and
  docstrings, JSON and comments, so it must not hold \" or \\ or control characters."
  [title]
  (cond
    (str/blank? title) "the title cannot be blank"
    (re-find #"[\"\\]" title) "the title cannot contain \" or \\ (it is written into source files)"
    (some #(or (< (int %) 32) (= (int %) 127)) title) "the title cannot contain control characters such as line breaks"
    :else nil))

(def clojuredart-dep
  "The ClojureDart release the Flutter target is pinned to (same as the examples)."
  '{:git/url "https://github.com/tensegritics/ClojureDart.git"
    :git/tag "0.9.20261002"
    :git/sha "247b3c2c5f2af8378e49a8f1271fb4e45e4a6982"})

(def cljfx-git
  '{cljfx/cljfx {:git/url "https://github.com/cljfx/cljfx"
                 :git/tag "1.10.363"
                 :git/sha "6bee510722fbb12d4f7ac447d413a77894bd7e33"}})

;; ---------------------------------------------------------------------------
;; paths

(defn- file ^java.io.File [& parts] (apply io/file (map str parts)))

(defn- abs-path ^java.nio.file.Path [x]
  (-> (io/file (str x)) .getAbsoluteFile .toPath .normalize))

(defn- slash [s] (str/replace (str s) "\\" "/"))

(defn rel-path
  "Path of `to` relative to the directory `from`, with forward slashes (absolute when
  there is no relative path, e.g. another drive on Windows)."
  [from to]
  (let [f (abs-path from) t (abs-path to)]
    (slash (try (let [r (str (.relativize f t))] (if (str/blank? r) "." r))
                (catch IllegalArgumentException _ (str t))))))

(defn- link-path
  "How a generated file names the cloview-core checkout: relative when it is nearby (the
  usual ../cloview-core), absolute when the path would climb more than three folders."
  [from to]
  (let [r (rel-path from to)]
    (if (re-find #"^(\.\./){4}" r) (slash (abs-path to)) r)))

(defn- checkout? [root]
  (and root (.isFile (file root "modules" "core" "src" "cloview" "core" "store.cljc"))
       (.isDirectory (file root "starter" "templates"))))

(defn url-file
  "The File of a file: URL. The JVM's class loaders give escaped URLs (%20); babashka
  builds its resource URLs with File.toURL, which leaves spaces and other characters as
  they are, so URL.toURI would throw on a path such as C:/Users/Jane Doe/..."
  ^java.io.File [^java.net.URL u]
  (try (io/file u)
       ;; io/file decodes %XX escapes; an unescaped path holding a literal % cannot be decoded
       (catch IllegalArgumentException _ (io/file (.getPath u)))))

(defn root-of-resource
  "The checkout that holds the resource URL of cloview/starter.clj
  (<root>/starter/src/cloview/starter.clj), or nil for a non-file URL (a jar)."
  [^java.net.URL u]
  (when (and u (= "file" (.getProtocol u)))
    (some-> (url-file u) .getAbsoluteFile .getParentFile .getParentFile .getParentFile .getParentFile)))

(defn default-root
  "The cloview-core checkout this namespace was loaded from (else the working directory,
  where `bb init` normally runs), or nil."
  []
  (or (root-of-resource (io/resource "cloview/starter.clj"))
      (let [cwd (io/file (System/getProperty "user.dir"))]
        (when (checkout? cwd) cwd))))

;; ---------------------------------------------------------------------------
;; names and substitution

(defn munged [n] (str/replace n "-" "_"))

(defn pascal [n] (apply str (map str/capitalize (str/split n #"-"))))

(defn default-title [n] (str/join " " (map str/capitalize (str/split n #"-"))))

(defn render
  "Template text with the placeholder names replaced."
  [text {:keys [name title]}]
  (-> text
      (str/replace "cloview_app" (munged name))
      (str/replace "cloview-app" name)
      (str/replace "Cloview App" title)))

(defn- template-files
  "{relative-path File} of starter/templates/<sub>."
  [root sub]
  (let [base (file root "starter" "templates" sub)]
    (into (sorted-map)
          (for [^java.io.File f (file-seq base) :when (.isFile f)]
            [(rel-path base f) f]))))

;; ---------------------------------------------------------------------------
;; options

(defn- fail [msg & [data]] (throw (ex-info msg (merge {:usage true} data))))

(defn normalize-options
  "Validated options with defaults. Throws ex-info {:usage true} with a message."
  [{:keys [dir name title target logic backend with root force?]}]
  (when (str/blank? (str dir)) (fail "the project folder is required: bb init <dir>"))
  (let [name (or name (.getName (io/file (str dir))))
        target (keyword (or target :javafx))
        logic (keyword (or logic :same-host))
        backend (when (= logic :backend) (keyword (or backend :java)))
        with (set (map keyword with))
        root (or root (default-root))]
    (when-not (re-matches name-re (str name))
      (fail (str "the app name \"" name "\" must be lower-case words joined by dashes (my-app); "
                 "pass --name when the folder name is not")))
    (when (contains? reserved-names name)
      (fail (str "the app name \"" name "\" is reserved: its namespaces (" name ".core, ...) would clash with "
                 name ".*; pass another --name")))
    (when-some [problem (some-> title title-problem)] (fail problem))
    (when-not (some #{target} targets) (fail (str "--target must be one of " (str/join ", " (map clojure.core/name targets)))))
    (when-not (some #{logic} modes) (fail "--logic must be same-host or backend"))
    (when (and backend (not (some #{backend} backend-langs)))
      (fail (str "--backend must be one of " (str/join ", " (map clojure.core/name backend-langs)))))
    (when (and (= logic :backend) (= target :flutter))
      (fail "--logic backend is not available with the flutter target yet (the bridge's Dart process transport is Stage 3); choose javafx or wpf"))
    (when-some [bad (seq (remove extras with))] (fail (str "unknown --with " (str/join ", " (map clojure.core/name bad)))))
    (when-not (checkout? root) (fail (str "no cloview-core checkout at " root " (pass --cloview <folder>)")))
    {:dir (str dir) :name name :title (if title (str/trim title) (default-title name)) :target target :logic logic
     :backend backend :with with :root (str (abs-path root)) :force? (boolean force?)}))

(defn modules-of
  "The cloview modules of a configuration: an explicit :modules (cloview.edn, where users
  may add e.g. :bridge for R plots) or the ones the mode and --with imply."
  [{:keys [logic with modules]}]
  (if (seq modules)
    (vec modules)
    (cond-> [:core :fx]
      (= logic :backend) (conj :bridge)
      (contains? with :licensing) (conj :licensing))))

;; ---------------------------------------------------------------------------
;; generated files

(defn- cloview-rel
  "The checkout relative to the folder `sub` of the project (\"\" = the project root)."
  [{:keys [dir root]} sub]
  (link-path (if (str/blank? sub) dir (file dir sub)) root))

(defn manifest-text
  "cloview.edn of a project configuration."
  [{:keys [name title logic backend] :as cfg} enabled primary root-rel]
  (str ";; cloview project manifest (written by bb init, kept up to date by bb target:add).\n"
       ";; :targets :primary is what `bb dev` runs; develop on it and add the other targets\n"
       ";; only for releases (bb target:add <target>).\n"
       "{:cloview/manifest 1\n"
       " :app {:name " (pr-str name) " :title " (pr-str title) "}\n"
       " :targets {:primary " primary "\n"
       "           :enabled " (pr-str (vec enabled)) "}\n"
       " :logic " (pr-str (cond-> {:mode logic} backend (assoc :backend {:lang backend :id "engine"}))) "\n"
       " :modules " (pr-str (modules-of cfg)) "\n"
       " ;; the cloview-core checkout behind the :local/root deps (and bb target:add). To drop\n"
       " ;; it, switch each deps.edn to git coordinates of the same modules, e.g.\n"
       " ;;   cloview/javafx {:git/url \"https://github.com/echojustus/cloview-core.git\"\n"
       " ;;                   :git/sha \"<commit>\" :deps/root \"modules/javafx\"}\n"
       " :cloview {:root " (pr-str root-rel) "}}\n"))

(defn- module-deps [cfg sub]
  (let [r (cloview-rel cfg sub)]
    (into (sorted-map)
          (for [m (modules-of cfg)]
            [(symbol "cloview" (name m)) {:local/root (str r "/modules/" (name m))}]))))

(defn pp
  "EDN text of `v` starting at column `col`: a map that holds collections gets one entry
  per line, aligned after its brace; everything else is printed on one line, without
  commas."
  [v col]
  (cond
    (map? v)
    (let [entries (for [[k x] v] [(pr-str k) x])
          multiline? (and (> (count v) 1) (some (fn [[_ x]] (and (coll? x) (seq x))) entries))]
      (if multiline?
        (str "{" (str/join (str "\n" (apply str (repeat (inc col) " ")))
                           (for [[ks x] entries] (str ks " " (pp x (+ col 1 (count ks) 1)))))
             "}")
        (str "{" (str/join " " (for [[ks x] entries] (str ks " " (pp x 0)))) "}")))
    (vector? v) (str "[" (str/join " " (map #(pp % 0) v)) "]")
    :else (pr-str v)))

(defn- edn-block
  "Comment lines, then the map `m` (see `pp`)."
  [comment-lines m]
  (str (str/join "" (map #(str ";; " % "\n") comment-lines)) (pp m 0) "\n"))

(defn- app-dep [{:keys [name]} part] (symbol name part))

(defn shared-deps [cfg]
  (edn-block [(str (:title cfg) ": the shared .cljc code (state, events, effects, views).")]
             (array-map :paths ["src"] :deps (module-deps cfg "shared"))))

(defn shared-deps-clr [cfg]
  (edn-block ["For cljr -Spath and editors; the WPF host loads the staged sources (bb wpf:sync)."]
             (array-map :paths ["src"] :deps (module-deps cfg "shared"))))

(defn root-deps [{:keys [name title] :as cfg}]
  (edn-block [(str title ": the shared code for a REPL at the project root, and `bb test`.")]
             (array-map
              :paths []
              :deps {(app-dep cfg "shared") {:local/root "shared"}}
              :aliases {:test {:extra-paths ["shared/test"]
                               :extra-deps {'cloview/verify {:local/root (str (cloview-rel cfg "") "/verify")}}
                               :main-opts ["-m" "cloview.verify.test-runner" (str name ".core-test")]}})))

(defn javafx-deps [{:keys [name title logic] :as cfg}]
  (edn-block (cond-> [(str title " on JavaFX: bb run:javafx (bb dev when javafx is the primary target).")
                      "If Clojars cannot serve cljfx, add the :cljfx-git alias (same release from GitHub)."]
               (= logic :backend)
               (conj ":run passes -Dcloview.app.dir=.. (bb runs it from javafx/): the folder that holds backend/."))
             (array-map
              :paths ["src"]
              :deps (array-map 'org.clojure/clojure {:mvn/version "1.12.6"}
                               'cloview/javafx {:local/root (str (cloview-rel cfg "javafx") "/modules/javafx")}
                               (app-dep cfg "shared") {:local/root "../shared"})
              :aliases {:run (cond-> {:main-opts ["-m" (str name ".javafx.main")]}
                               (= logic :backend) (assoc :jvm-opts ["-Dcloview.app.dir=.."]))
                        :cljfx-git {:override-deps cljfx-git}})))

(defn wpf-deps-clr [{:keys [title] :as cfg}]
  (edn-block [(str title " on WPF (ClojureCLR). The runnable app is the host in wpf/host:")
              "bb run:wpf stages the sources (target/clr-src), builds the host and runs it."
              "This file serves cljr -Spath and editors."]
             (array-map
              :paths ["src"]
              :deps (array-map 'cloview/wpf {:local/root (str (cloview-rel cfg "wpf") "/modules/wpf")}
                               (app-dep cfg "shared") {:local/root "../shared"}))))

(defn flutter-deps [{:keys [name title] :as cfg}]
  (str (edn-block [(str title " on Flutter (ClojureDart): bb run:flutter, bb test:flutter.")
                   "bb copies this folder and shared/test to target/flutter_<app> and runs"
                   "`clj -M:cljd init` there once. Requires Flutter >= 3.35 (3.47.x recommended)."]
                  (array-map
                   :paths ["src"]
                   :deps (array-map 'tensegritics/clojuredart clojuredart-dep
                                    'cloview/flutter {:local/root (str (cloview-rel cfg "flutter") "/modules/flutter")}
                                    (app-dep cfg "shared") {:local/root "../shared"})
                   :aliases {:cljd {:main-opts ["-m" "cljd.build"]}
                             :test {:extra-paths ["test"]}}
                   :cljd/opts {:kind :flutter :main (symbol (str name ".flutter.main"))}))))

(defn- host-files
  "wpf/host: cloview's CloViewHost template, renamed for the app."
  [{:keys [root name]}]
  (let [dir (file root "modules" "wpf" "host-template")
        csproj (-> (slurp (file dir "CloViewHost.csproj") :encoding "UTF-8")
                   (str/replace "<AssemblyName>CloViewHost</AssemblyName>"
                                (str "<AssemblyName>" (pascal name) "</AssemblyName>"))
                   (str/replace "$(MSBuildThisFileDirectory)..\\..\\..\\target\\clr-src"
                                "$(MSBuildThisFileDirectory)..\\..\\target\\clr-src"))
        program (-> (slurp (file dir "Program.cs") :encoding "UTF-8")
                    (str/replace "public string Ns = \"greeter.wpf.main\";"
                                 (str "public string Ns = \"" name ".wpf.main\";")))]
    (when-not (and (str/includes? program (str "\"" name ".wpf.main\""))
                   (str/includes? csproj (str "<AssemblyName>" (pascal name) "</AssemblyName>"))
                   (str/includes? csproj "$(MSBuildThisFileDirectory)..\\..\\target\\clr-src"))
      (throw (ex-info "the CloViewHost template changed: update cloview.starter/host-files" {})))
    {(str "wpf/host/" (pascal name) ".csproj") csproj
     "wpf/host/Program.cs" program}))

(defn- target-files
  "Files of one target folder (from the mode's templates)."
  [{:keys [root logic] :as cfg} target]
  (let [sub (str (name logic) "/" (name target))
        templates (template-files root sub)]
    (merge (into {} (for [[rel f] templates]
                      [(str (name target) "/" (render rel cfg)) (render (slurp f :encoding "UTF-8") cfg)]))
           (case target
             :javafx {"javafx/deps.edn" (javafx-deps cfg)}
             :wpf (merge {"wpf/deps-clr.edn" (wpf-deps-clr cfg)
                          "shared/deps-clr.edn" (shared-deps-clr cfg)}
                         (host-files cfg))
             :flutter {"flutter/deps.edn" (flutter-deps cfg)}))))

(def ^:private backend-sources
  {:java {"backend/java/Backend.java" "java/Backend.java"}
   :csharp {"backend/csharp/Backend.csproj" "csharp/Backend.csproj"
            "backend/csharp/Program.cs" "csharp/Program.cs"}
   :dart {"backend/dart/bin/backend.dart" "dart/bin/backend.dart"
          "backend/dart/pubspec.yaml" "dart/pubspec.yaml"}})

(defn- json-str
  "A JSON string literal."
  [s]
  (str "\""
       (str/escape (str s) (fn [c]
                             (case c
                               \" "\\\""
                               \\ "\\\\"
                               (when (< (int c) 32) (format "\\u%04x" (int c))))))
       "\""))

(defn backend-manifest [{:keys [title backend]}]
  (let [cmd (case backend
              :java ["java" "${appDir}/backend/java/Backend.java"]
              :csharp ["dotnet" "${appDir}/backend/csharp/out/backend.dll"]
              :dart ["dart" "${appDir}/backend/dart/bin/backend.dart"])
        ;; Flutter's dart on Windows is dart.bat, which neither the JVM nor .NET starts
        ;; without a shell; cmd /c finds dart.exe and dart.bat alike
        windows (when (= backend :dart) (into ["cmd" "/c"] cmd))
        arr #(str "[" (str/join ", " (map json-str %)) "]")]
    (str "{\n"
         "  \"manifest_version\": 1,\n"
         "  \"id\": \"engine\",\n"
         "  \"name\": " (json-str (str title " backend")) ",\n"
         "  \"protocol\": \"cloview-bridge/1\",\n"
         "  \"command\": {" (when windows (str "\"windows\": " (arr windows) ", "))
         "\"default\": " (arr cmd) "},\n"
         "  \"start\": \"on-launch\",\n"
         "  \"startup_timeout_ms\": 30000,\n"
         "  \"request_timeout_ms\": 60000\n"
         "}\n")))

(defn- backend-files [{:keys [root backend] :as cfg}]
  (merge {"backend/backend-manifest.json" (backend-manifest cfg)}
         (into {} (for [[to from] (get backend-sources backend)]
                    [to (slurp (file root "modules" "bridge" "backends" from) :encoding "UTF-8")]))))

(defn bb-edn [{:keys [title logic] :as cfg}]
  (str ";; " title " tasks (babashka): `bb tasks` lists them. Targets and mode: cloview.edn.\n"
       "{:paths [\"bb\" " (pr-str (str (cloview-rel cfg "") "/starter/src")) "]\n"
       " :tasks\n"
       " {:requires ([cloview.starter.tasks :as t])\n\n"
       "  dev {:doc \"Run the app on the primary target (cloview.edn :targets :primary)\"\n"
       "       :task (t/dev! *command-line-args*)}\n"
       "  test {:doc \"The shared tests on the JVM (fast, no GUI)\" :task (t/test! *command-line-args*)}\n"
       "  targets {:doc \"Show the primary and the enabled targets\" :task (t/targets!)}\n"
       "  target:add {:doc \"Add a release target: bb target:add javafx|wpf|flutter\"\n"
       "              :task (t/target-add! *command-line-args*)}\n\n"
       "  run:javafx {:doc \"Run on JavaFX\" :task (t/run-javafx! *command-line-args*)}\n\n"
       "  wpf:sync {:doc \"Stage the sources for the WPF host (target/clr-src)\" :task (t/wpf-sync!)}\n"
       "  wpf:build {:doc \"Stage and build the WPF host (target/clr-host)\" :task (t/wpf-build!)}\n"
       "  run:wpf {:doc \"Run on WPF; args go to the host (e.g. --repl 5555)\"\n"
       "           :task (do (t/wpf-build!) (apply t/wpf-run! *command-line-args*))}\n"
       "  test:clr {:doc \"The shared tests on ClojureCLR, inside the WPF host\"\n"
       "            :task (do (t/wpf-build!) (t/wpf-run! \"--run-tests\" \"--out\" \"target/clr-tests.json\"))}\n"
       "  smoke:wpf {:doc \"Mount the view off-screen in the WPF host and check it\"\n"
       "             :task (do (t/wpf-build!) (t/wpf-run! \"--headless-smoke\" \"--out\" \"target/wpf-smoke.json\"))}\n\n"
       "  flutter:prepare {:doc \"Create or refresh the Flutter project in target/\" :task (t/flutter-prepare!)}\n"
       "  run:flutter {:doc \"Run on Flutter with hot reload; args go to flutter run\"\n"
       "               :task (t/run-flutter! *command-line-args*)}\n"
       "  test:flutter {:doc \"ClojureDart compile + widget tests + the shared tests on Dart\"\n"
       "                :task (t/test-flutter!)}\n\n"
       (when (= logic :backend)
         "  backend:build {:doc \"Build the backend when its language needs it (C#)\" :task (t/backend-build!)}\n\n")
       "  clean {:doc \"Remove target/\" :task (babashka.fs/delete-tree \"target\")}}}\n"))

(def gitignore
  (str "target/\n.cpcache/\n.clj-kondo/.cache/\n.lsp/.cache/\n.nrepl-port\n"
       ".clojuredart/\n.dart_tool/\n.vs/\n"
       "# .NET build output (not a global bin/: Dart keeps its sources in bin/)\n"
       "wpf/host/bin/\nwpf/host/obj/\nbackend/csharp/bin/\nbackend/csharp/obj/\nbackend/csharp/out/\n"
       "# licence signing keys (cloview.licensing.issue)\n*.private.edn\nlicense-keys/\n"))

(defn- md-text
  "Text for Markdown: < > & written as entities, so a title such as \"Notes <beta>\" is
  shown, not taken for an HTML tag."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;"}))

(defn readme [{:keys [name title target logic backend] :as cfg}]
  (let [t (clojure.core/name target)]
    (str "# " (md-text title) "\n\n"
         "A [cloview](" (cloview-rel cfg "") ") app: one declarative `.cljc` UI, rendered natively.\n\n"
         "## Daily work\n\n"
         "```bash\n"
         "bb dev     # run on the primary target (" t ")\n"
         "bb test    # the shared tests on the JVM: fast, no GUI\n"
         "bb tasks   # everything else\n"
         "```\n\n"
         "Develop on **one** target. `cloview.edn` names it (`:targets :primary`); "
         "build and test the others only for a release:\n\n"
         "```bash\n"
         "bb target:add wpf        # writes wpf/ and enables it\n"
         "bb test:clr              # the shared tests on ClojureCLR\n"
         "bb run:wpf\n"
         "```\n\n"
         "## Layout\n\n"
         "| Folder | What goes there |\n"
         "|---|---|\n"
         "| `shared/` | almost everything: state, events, effects and views (`" name ".core`), and their tests |\n"
         "| `javafx/` `wpf/` `flutter/` | the entry point of each target (about 15 to 100 lines; the WPF one also holds the test and smoke entry points), plus optional `:ui/native` widgets |\n"
         (when (= logic :backend)
           (str "| `backend/` | the " (clojure.core/name backend) " backend process and `backend-manifest.json` |\n"))
         "| `bb/` | the project's tasks (yours to edit) |\n\n"
         (if (= logic :backend)
           (str "## Mode B: decoupled backend\n\n"
                "The GUI asks the backend with `:backend/call`; cloview starts it from "
                "`backend/backend-manifest.json`, restarts it if it crashes and stops it on exit. "
                "`" name ".core/backend-contract` is an in-process stand-in used by the tests"
                (when (= backend :csharp) "; run `bb backend:build` once before `bb dev`")
                ".\n\n")
           (str "## Mode A: same-host logic\n\n"
                "Logic runs in the GUI process, in `.cljc`. Effects (`:storage/*`, `:http`, "
                "`:file/*`, timers) are run by the host.\n\n"))
         "## Requirements\n\n"
         "- JavaFX: a JDK (21 recommended; cljfx picks the JavaFX version that matches it) and the Clojure CLI.\n"
         "- WPF: Windows, the .NET 10 SDK (8 and 9 work).\n"
         "- Flutter: Flutter >= 3.35 (3.47.x recommended) and the Clojure CLI.\n"
         "- [babashka](https://babashka.org) for the `bb` tasks.\n\n"
         "The `deps.edn` files point at the cloview-core checkout in `cloview.edn` (`:cloview :root`) "
         "with `:local/root`. To drop the checkout, switch them to git coordinates of the same "
         "modules, for example `cloview/javafx {:git/url \"https://github.com/echojustus/cloview-core.git\" "
         ":git/sha \"<commit>\" :deps/root \"modules/javafx\"}`.\n")))

(defn plan
  "{relative-path content} of a new project for normalized options."
  [{:keys [root logic target] :as cfg}]
  (merge
   (into {} (for [[rel f] (template-files root (str (name logic) "/shared"))]
              [(str "shared/" (render rel cfg)) (render (slurp f :encoding "UTF-8") cfg)]))
   (into {} (for [[rel f] (template-files root "project")]
              [rel (slurp f :encoding "UTF-8")]))
   {"cloview.edn" (manifest-text cfg [target] target (cloview-rel cfg ""))
    "bb.edn" (bb-edn cfg)
    "deps.edn" (root-deps cfg)
    "shared/deps.edn" (shared-deps cfg)
    "README.md" (readme cfg)
    ".gitignore" gitignore}
   (target-files cfg target)
   (when (= logic :backend) (backend-files cfg))))

(defn- write-files!
  "Write {relative-path content} under `dir`. Every path is checked before the first
  write, so a conflict leaves nothing half-written."
  [dir files {:keys [overwrite?]}]
  (when-not overwrite?
    (when-some [taken (seq (filter #(.exists (file dir %)) (sort (keys files))))]
      (fail (str (str/join ", " (map #(slash (file dir %)) taken))
                 (if (next taken) " exist" " exists") "; not overwriting anything (nothing was written)"))))
  (doseq [[rel content] (sort-by key files)]
    (let [f (file dir rel)]
      (io/make-parents f)
      (spit f content :encoding "UTF-8")))
  (count files))

(defn init!
  "Create the project; returns {:dir :files n :cfg cfg}."
  [opts]
  (let [{:keys [dir force?] :as cfg} (normalize-options opts)
        d (io/file dir)]
    (when (and (.exists d) (seq (.list d)) (not force?))
      (fail (str dir " exists and is not empty (use --force to write into it)")))
    {:dir dir :files (write-files! dir (plan cfg) {:overwrite? force?}) :cfg cfg}))

;; ---------------------------------------------------------------------------
;; bb target:add (inside a project)

(defn read-manifest [project-dir]
  (let [f (file project-dir "cloview.edn")]
    (when-not (.isFile f) (fail (str "no cloview.edn in " (slash (abs-path project-dir)))))
    (edn/read-string (slurp f :encoding "UTF-8"))))

(defn set-enabled-text
  "cloview.edn `text` with :targets :enabled set to `enabled`. Only that vector changes:
  comments, layout and every other key (e.g. :modules entries added by hand) stay as
  they are. nil when the vector is not written in one recognizable piece, or when the
  result would differ in anything else; then the file must not be rewritten."
  [text enabled]
  ;; one line, no comment, discard (#_) or string inside: anything else is left alone
  (let [re #"(:enabled\s+)\[[^\[\];#\"\r\n]*\]"
        m (try (edn/read-string text) (catch Exception _ nil))]
    (when (and (map? m) (= 1 (count (re-seq re text))))
      (let [patched (str/replace-first text re (fn [[_ head]] (str head (pr-str (vec enabled)))))
            parsed (try (edn/read-string patched) (catch Exception _ nil))]
        (when (= parsed (assoc-in m [:targets :enabled] (vec enabled)))
          patched)))))

(defn add-target!
  "Write the folder of `target` into the project and enable it in cloview.edn (only its
  :targets :enabled vector is edited). Nothing is written when a file of the target
  exists or cloview.edn cannot be edited safely. Returns lines to print."
  [project-dir target _opts]
  (let [mf (file project-dir "cloview.edn")
        m (read-manifest project-dir)
        target (keyword target)
        {:keys [name title]} (:app m)
        root-ref (io/file (str (get-in m [:cloview :root])))
        root (str (abs-path (if (.isAbsolute root-ref) root-ref (file project-dir root-ref))))
        cfg {:dir (str (abs-path project-dir)) :name name :title title :root root
             :logic (get-in m [:logic :mode]) :backend (get-in m [:logic :backend :lang])
             :with (set (filter extras (:modules m)))
             :modules (:modules m)}
        enabled (vec (get-in m [:targets :enabled]))
        enabled? (boolean (some #{target} enabled))]
    (when-not (some #{target} targets)
      (fail (str "the target must be one of " (str/join ", " (map clojure.core/name targets)))))
    (when (and enabled? (.exists (file project-dir (clojure.core/name target))))
      (fail (str (clojure.core/name target) " is already a target")))
    (when (and (= :backend (:logic cfg)) (= target :flutter))
      (fail "the flutter target cannot run Mode B (backend) apps yet (Stage 3)"))
    (when-not (checkout? root) (fail (str "no cloview-core checkout at " root " (cloview.edn :cloview :root)")))
    (let [files (target-files cfg target)
          ;; shared/deps-clr.edn may exist already (it is harmless to keep the user's copy)
          files (into {} (remove (fn [[rel _]] (and (= rel "shared/deps-clr.edn") (.exists (file project-dir rel))))) files)
          new-enabled (conj enabled target)
          ;; computed before anything is written
          new-text (when-not enabled?
                     (or (set-enabled-text (slurp mf :encoding "UTF-8") new-enabled)
                         (fail (str "could not update :targets :enabled in cloview.edn automatically (it is not "
                                    "written as one vector); nothing was written. Set it to " (pr-str new-enabled)
                                    " by hand and run bb target:add " (clojure.core/name target) " again"))))]
      (write-files! project-dir files {:overwrite? false})
      (when new-text (spit mf new-text :encoding "UTF-8"))
      (concat (for [rel (sort (keys files))] (str "wrote " rel))
              [(if enabled?
                 (str (clojure.core/name target) " was already enabled in cloview.edn")
                 (str "enabled " (clojure.core/name target) " in cloview.edn (the primary target stays "
                      (clojure.core/name (get-in m [:targets :primary])) ")"))]))))

;; ---------------------------------------------------------------------------
;; command line

(def usage
  (str "usage: bb init <dir> [--target javafx|wpf|flutter] [--logic same-host|backend]\n"
       "                     [--backend java|csharp|dart] [--name my-app] [--title \"My App\"]\n"
       "                     [--with licensing] [--cloview <checkout>] [--force]"))

(defn parse-args [args]
  (loop [args (seq args) opts {}]
    (if-some [[a & more] args]
      (case a
        ("-h" "--help") (recur more (assoc opts :help? true))
        "--force" (recur more (assoc opts :force? true))
        ("--target" "--logic" "--backend" "--name" "--title" "--cloview" "--with")
        (do (when (or (empty? more) (str/starts-with? (first more) "--")) (fail (str a " needs a value")))
            (let [v (first more)
                  opts (case a
                         "--cloview" (assoc opts :root v)
                         "--with" (update opts :with (fnil into []) (str/split v #","))
                         (assoc opts (keyword (subs a 2)) v))]
              (recur (next more) opts)))
        (if (and (not (str/starts-with? a "-")) (nil? (:dir opts)))
          (recur more (assoc opts :dir a))
          (fail (str "unknown argument " a))))
      opts)))

(defn next-steps [{:keys [dir target logic backend]}]
  (concat [(str "cd " dir)
           "bb test        # the shared tests (JVM)"]
          (when (= backend :csharp) ["bb backend:build"])
          [(str "bb dev         # run on " (name target)
                (case target :wpf " (Windows)" :flutter " (needs Flutter >= 3.35)" ""))]
          (when (= logic :backend) ["(the backend starts with the app; see backend/backend-manifest.json)"])))

(defn -main
  "bb init <dir> [options] (see `usage`)."
  [& args]
  (try
    (let [opts (parse-args args)]
      (if (or (:help? opts) (nil? (:dir opts)))
        (println usage)
        (let [{:keys [dir files cfg]} (init! opts)]
          (println (str "created " dir " (" files " files): " (:name cfg) ", target " (name (:target cfg))
                        ", logic " (name (:logic cfg)) (when (:backend cfg) (str " (" (name (:backend cfg)) ")"))))
          (doseq [s (next-steps cfg)] (println "  " s)))))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println "error:" (ex-message e))
        (when (:usage (ex-data e)) (println usage)))
      (System/exit 2))))
