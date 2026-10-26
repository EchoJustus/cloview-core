;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.starter-test
  "The project initializer (JVM): every target x logic mode generates a complete, parseable
  project with no placeholder left; options are validated; target:add extends a project;
  and a generated project's own tests pass in a child JVM."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloview.bridge.manifest :as manifest]
            [cloview.starter :as starter]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory "cloview-starter-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [dir]
  (doseq [^java.io.File f (reverse (file-seq (io/file dir)))] (.delete f)))

(defn- abs-slash
  "The absolute, normalized path of `p` with forward slashes (C:/... on Windows)."
  [p]
  (str/replace (str (.normalize (.toPath (.getAbsoluteFile (io/file (str p)))))) "\\" "/"))

(defn- form-named
  "The top-level (defn name ...) form of Clojure source `text`."
  [text fname]
  (let [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))]
    (binding [*read-eval* false]
      (loop []
        (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} rdr)]
          (cond (= form ::eof) nil
                (and (seq? form) (= 'defn (first form)) (= fname (second form))) form
                :else (recur)))))))

(defn- eval-fn
  "Evaluate a (defn ...) form in a scratch namespace; returns the function."
  [form]
  (binding [*ns* (create-ns (gensym "starter-test-scratch"))]
    (refer-clojure)
    (eval form)
    @(ns-resolve *ns* (second form))))

(defn- files-of [dir]
  (into (sorted-map)
        (for [^java.io.File f (file-seq (io/file dir)) :when (.isFile f)]
          [(starter/rel-path dir f) (slurp f :encoding "UTF-8")])))

(defn- read-all
  "Every form of Clojure source `text` (reader conditionals allowed), or throws."
  [text platform]
  (let [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (str/replace text "#/(" "(")))]
    (binding [*read-eval* false
              *default-data-reader-fn* (fn [_ v] v)
              *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                  (currentNS [_] 'user) (resolveClass [_ s] s)
                                  (resolveAlias [_ s] s) (resolveVar [_ s] s))]
      (loop [acc []]
        (let [form (read {:eof ::eof :read-cond :allow :features #{platform}} rdr)]
          (if (= form ::eof) acc (recur (conj acc form))))))))

(defn- check-project
  "Assertions every generated project must pass; returns its files."
  [dir {:keys [name target enabled title]}]
  (let [files (files-of dir)
        m (edn/read-string (get files "cloview.edn"))]
    (is (= {:name name :title (or title (starter/default-title name))} (:app m)))
    (is (= {:primary target :enabled (or enabled [target])} (:targets m)))
    (doseq [[rel text] files]
      (is (not (re-find #"cloview-app|cloview_app|Cloview App" text)) (str "placeholder left in " rel))
      (is (not (re-find #"cloview_app|cloview-app" rel)) (str "placeholder left in the path " rel))
      (cond
        (re-find #"\.edn$" rel) (is (some? (edn/read-string text)) rel)
        (re-find #"\.json$" rel) (is (str/includes? text "\"protocol\": \"cloview-bridge/1\"") rel)
        (re-find #"\.(clj|cljc|cljr|cljd)$" rel)
        (doseq [p (case (last (str/split rel #"\.")) "clj" [:clj] "cljr" [:cljr] "cljd" [:cljd] "cljc" [:clj :cljr :cljd])]
          (is (seq (read-all text p)) (str rel " reads with " p)))
        :else nil))
    ;; the deps point at a real checkout
    (doseq [[rel text] files :when (str/ends-with? rel "deps.edn")
            root (map second (re-seq #":local/root \"([^\"]+)\"" text))]
      (is (.exists (if (.isAbsolute (io/file root)) (io/file root) (io/file dir (str/replace rel #"[^/]*$" "") root)))
          (str rel " -> " root)))
    files))

(def combos
  [{:target :javafx :logic :same-host}
   {:target :wpf :logic :same-host}
   {:target :flutter :logic :same-host}
   {:target :javafx :logic :backend :backend :java}
   {:target :wpf :logic :backend :backend :csharp}
   {:target :javafx :logic :backend :backend :dart :with ["licensing"]}])

(deftest every-target-and-mode
  (doseq [{:keys [target logic backend with] :as combo} combos]
    (testing (pr-str combo)
      (let [base (temp-dir)
            dir (str base "/notes-app")]
        (try
          (let [{:keys [files cfg]} (starter/init! (assoc combo :dir dir))
                fs (check-project dir {:name "notes-app" :target target})]
            (is (= files (count fs)))
            (is (= (cond-> [:core :fx] (= logic :backend) (conj :bridge) (seq with) (conj :licensing))
                   (:modules (edn/read-string (get fs "cloview.edn")))))
            (is (contains? fs "shared/src/notes_app/core.cljc"))
            (is (contains? fs "shared/test/notes_app/core_test.cljc"))
            (is (contains? fs "bb/cloview/starter/tasks.clj"))
            (is (not (re-find #"(?m)^(bin|obj)/$" (get fs ".gitignore")))
                "no global bin/ ignore: the Dart backend keeps its source in bin/")
            (is (str/includes? (get fs "shared/src/notes_app/core.cljc") "(ns notes-app.core"))
            (is (= (set (filter #(re-find #"^(javafx|wpf|flutter)/" %) (keys fs)))
                   (set (filter #(str/starts-with? % (str (name target) "/")) (keys fs))))
                "only the primary target's folder")
            (case target
              :javafx (is (contains? fs "javafx/src/notes_app/javafx/main.clj"))
              :wpf (do (is (contains? fs "wpf/host/NotesApp.csproj"))
                       (is (str/includes? (get fs "wpf/host/NotesApp.csproj") "<AssemblyName>NotesApp</AssemblyName>"))
                       (is (str/includes? (get fs "wpf/host/Program.cs") "\"notes-app.wpf.main\""))
                       (is (contains? fs "shared/deps-clr.edn")))
              :flutter (do (is (contains? fs "flutter/src/notes_app/flutter/main.cljd"))
                           (is (= 'notes-app.flutter.main
                                  (get-in (edn/read-string (get fs "flutter/deps.edn")) [:cljd/opts :main])))))
            (if (= logic :backend)
              (let [mf (get fs "backend/backend-manifest.json")]
                (is (str/includes? (get fs "bb.edn") "backend:build"))
                (is (str/includes? mf (case backend :java "Backend.java" :csharp "backend.dll" :dart "backend.dart")))
                (is (some #(str/starts-with? % (str "backend/" (name backend) "/")) (keys fs))))
              (is (not-any? #(str/starts-with? % "backend/") (keys fs)))))
          (finally (delete-tree! base)))))))

(deftest checkout-paths
  ;; absolute paths built by the platform (C:/... on Windows, /... elsewhere)
  (let [link @#'starter/link-path
        w (abs-slash (io/file (System/getProperty "java.io.tmpdir") "w"))]
    (is (= "../cloview-core" (link (str w "/apps/notes-app") (str w "/apps/cloview-core"))) "the usual layout")
    (is (= "../../../cloview-core" (link (str w "/a/b/notes-app/shared") (str w "/a/cloview-core"))) "from a subfolder")
    (is (= (str w "/cloview-core") (link (str w "/a/b/c/d/notes-app") (str w "/cloview-core"))) "far away: absolute")))

(deftest resource-urls-with-spaces
  ;; babashka builds resource URLs with File.toURL: spaces stay unescaped, and
  ;; URL.toURI throws on them (C:/Users/Jane Doe/...)
  (let [base (temp-dir)
        root (io/file base "Jane Doe" "100% cloview")
        f (io/file root "starter" "src" "cloview" "starter.clj")
        unescaped (.toURL f)
        escaped (.toURL (.toURI f))]
    (try
      (is (str/includes? (str unescaped) "Jane Doe") "the URL is unescaped, as babashka makes it")
      (is (= (abs-slash root) (abs-slash (starter/root-of-resource unescaped))))
      (is (= (abs-slash root) (abs-slash (starter/root-of-resource escaped))) "an escaped JVM URL as well")
      (is (nil? (starter/root-of-resource (java.net.URL. "jar:file:/x.jar!/cloview/starter.clj"))))
      (finally (delete-tree! base)))))

(deftest options-are-validated
  (let [base (temp-dir)
        bad (fn [opts] (try (starter/init! (merge {:dir (str base "/ok-app")} opts)) nil
                            (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (try
      (is (re-find #"lower-case" (bad {:name "MyApp"})))
      (is (re-find #"lower-case" (bad {:dir (str base "/My_App")})))
      (is (re-find #"clash" (bad {:name "cloview"})))
      (doseq [n ["java" "javax" "jdk" "sun" "clojure" "cljd" "cljs" "dart" "flutter"]]
        (is (re-find #"reserved" (str (bad {:name n}))) n))
      (is (re-find #"cannot contain" (str (bad {:title "Say \"Hi\""}))))
      (is (re-find #"cannot contain" (str (bad {:title "C:\\apps"}))))
      (is (re-find #"control characters" (str (bad {:title "two\nlines"}))))
      (is (re-find #"blank" (str (bad {:title "  "}))))
      (is (re-find #"--target" (bad {:target "web"})))
      (is (re-find #"--logic" (bad {:logic "remote"})))
      (is (re-find #"--backend" (bad {:logic "backend" :backend "cobol"})))
      (is (re-find #"flutter" (bad {:logic "backend" :target "flutter"})))
      (is (re-find #"unknown --with" (bad {:with ["telemetry"]})))
      (is (re-find #"no cloview-core checkout" (bad {:root base})))
      (is (nil? (bad {})) "valid")
      (is (re-find #"not empty" (bad {})) "never writes into a non-empty folder")
      (is (= {:dir "x" :target "wpf" :logic "backend" :backend "csharp" :with ["licensing"] :force? true}
             (starter/parse-args ["x" "--target" "wpf" "--logic" "backend" "--backend" "csharp"
                                  "--with" "licensing" "--force"])))
      (is (thrown? clojure.lang.ExceptionInfo (starter/parse-args ["x" "--target"])))
      (finally (delete-tree! base)))))

(deftest adding-release-targets
  (let [base (temp-dir)
        dir (str base "/notes-app")]
    (try
      (starter/init! {:dir dir})
      (let [lines (starter/add-target! dir :wpf {})]
        (is (some #(str/includes? % "wpf/host/NotesApp.csproj") lines))
        (is (= {:primary :javafx :enabled [:javafx :wpf]} (:targets (starter/read-manifest dir)))))
      (starter/add-target! dir :flutter {})
      (is (= [:javafx :wpf :flutter] (get-in (starter/read-manifest dir) [:targets :enabled])))
      (check-project dir {:name "notes-app" :target :javafx :enabled [:javafx :wpf :flutter]})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already" (starter/add-target! dir :wpf {})))
      (testing "a checkout named by an absolute path (C:/... on Windows)"
        (let [adir (str base "/abs-app")
              f (io/file adir "cloview.edn")]
          (starter/init! {:dir adir})
          (spit f (str/replace (slurp f :encoding "UTF-8") #":root \"[^\"]+\"" (str ":root " (pr-str (abs-slash (starter/default-root))))) :encoding "UTF-8")
          (is (.isAbsolute (io/file (get-in (starter/read-manifest adir) [:cloview :root]))))
          (is (seq (starter/add-target! adir :wpf {})))))
      (testing "cloview.edn is edited in place: comments and hand-added keys survive"
        (let [edir (str base "/edit-app")
              f (io/file edir "cloview.edn")]
          (starter/init! {:dir edir})
          (spit f (-> (slurp f :encoding "UTF-8")
                      (str/replace " :modules [:core :fx]" " :modules [:core :fx :bridge] ; R plots")
                      (str "\n;; my own note\n")) :encoding "UTF-8")
          (starter/add-target! edir :wpf {})
          (let [text (slurp f :encoding "UTF-8")]
            (is (str/includes? text ":modules [:core :fx :bridge] ; R plots"))
            (is (str/includes? text ";; my own note"))
            (is (= [:javafx :wpf] (get-in (edn/read-string text) [:targets :enabled]))))
          (is (str/includes? (slurp (io/file edir "shared/deps-clr.edn")) "cloview/bridge")
              "the new target's deps follow the hand-edited :modules")))
      (testing "a conflict writes nothing"
        (let [cdir (str base "/conflict-app")
              ;; the last file in write order, so a one-pass writer would have written the others
              taken (io/file cdir "flutter" "test" "conflict_app" "flutter" "app_test.cljd")]
          (starter/init! {:dir cdir})
          (io/make-parents taken)
          (spit taken "(ns mine)")
          (let [before (files-of cdir)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing was written" (starter/add-target! cdir :flutter {})))
            (is (= before (files-of cdir)) "no file added or changed, cloview.edn included"))))
      (testing "a cloview.edn that cannot be edited safely is left alone"
        (let [udir (str base "/odd-app")
              f (io/file udir "cloview.edn")]
          (starter/init! {:dir udir})
          ;; the vector split over two lines with a comment inside: not one recognizable piece
          (spit f (str/replace (slurp f :encoding "UTF-8") ":enabled [:javafx]" ":enabled [:javafx ; the primary\n                     ]") :encoding "UTF-8")
          (let [before (files-of udir)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"by hand" (starter/add-target! udir :wpf {})))
            (is (= before (files-of udir)) "nothing written"))
          (testing "after the hand edit, target:add writes the folder"
            (spit f (str/replace (slurp f :encoding "UTF-8") ":enabled [:javafx ; the primary\n                     ]" ":enabled [:javafx :wpf]") :encoding "UTF-8")
            (is (some #(str/includes? % "already enabled") (starter/add-target! udir :wpf {})))
            (is (.isFile (io/file udir "wpf/src/odd_app/wpf/main.cljr"))))))
      (let [bdir (str base "/engine-app")]
        (starter/init! {:dir bdir :logic :backend})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"flutter" (starter/add-target! bdir :flutter {})))
        (starter/add-target! bdir :wpf {})
        (is (.isFile (io/file bdir "wpf/src/engine_app/wpf/main.cljr")))
        (is (str/includes? (slurp (io/file bdir "wpf/src/engine_app/wpf/main.cljr")) "bridge/install!")
            "the mode's entry point"))
      (finally (delete-tree! base)))))

(deftest mode-b-finds-its-backend
  (let [base (temp-dir)
        dir (str base "/engine-app")]
    (try
      (starter/init! {:dir dir :logic :backend})
      (let [text (slurp (io/file dir "javafx/src/engine_app/javafx/main.clj"))
            app-dir (eval-fn (form-named text 'app-dir))
            canonical #(.getCanonicalPath (io/file %))]
        (is (= ["-Dcloview.app.dir=.."]
               (get-in (edn/read-string (slurp (io/file dir "javafx/deps.edn"))) [:aliases :run :jvm-opts]))
            "bb run:javafx works in javafx/ and passes the project folder")
        (is (= (canonical dir) (app-dir nil (str dir "/javafx"))) "started from javafx/ without the option")
        (is (= (canonical dir) (app-dir nil dir)) "started from the project folder")
        (is (= (canonical dir) (app-dir (str dir "/javafx/..") (str base))) "the option wins")
        (is (= (canonical base) (app-dir nil base)) "no backend/ anywhere: the working directory"))
      (testing "the Windows command of a Dart backend goes through cmd (Flutter's dart is dart.bat)"
        (let [ddir (str base "/dart-app")]
          (starter/init! {:dir ddir :logic :backend :backend :dart})
          (let [m (manifest/parse (slurp (io/file ddir "backend/backend-manifest.json")))]
            (is (= ["cmd" "/c" "dart" "${appDir}/backend/dart/bin/backend.dart"] (get-in m [:command :windows])))
            (is (= ["dart" "${appDir}/backend/dart/bin/backend.dart"] (get-in m [:command :default]))))))
      (finally (delete-tree! base)))))

(deftest titles-are-written-safely
  (let [base (temp-dir)
        dir (str base "/notes-app")
        title "Jane's Notes & Co <beta> ünïcode"]
    (try
      (starter/init! {:dir dir :logic :backend :title (str "  " title " ")})
      (let [fs (files-of dir)]
        (is (= title (get-in (edn/read-string (get fs "cloview.edn")) [:app :title])) "trimmed, kept as given")
        (is (= (str title " backend") (:name (manifest/parse (get fs "backend/backend-manifest.json")))))
        (is (str/starts-with? (get fs "README.md") "# Jane's Notes &amp; Co &lt;beta&gt; ünïcode\n"))
        (is (str/includes? (get fs "shared/src/notes_app/core.cljc") (str ":text " (pr-str title))))
        (check-project dir {:name "notes-app" :target :javafx :title title}))
      (finally (delete-tree! base)))))

(deftest generated-tests-pass
  ;; the generated shared tests, in a child JVM with the module sources on the class path
  (let [base (temp-dir)
        root (starter/default-root)
        sep java.io.File/pathSeparator
        jars (filter #(str/ends-with? % ".jar")
                     (str/split (System/getProperty "java.class.path") (re-pattern (java.util.regex.Pattern/quote sep))))
        exe (io/file (System/getProperty "java.home") "bin" "java.exe")
        java (str (if (.isFile exe) exe (io/file (System/getProperty "java.home") "bin" "java")))]
    (try
      (doseq [{:keys [logic name title]} [{:logic :same-host :name "notes-app" :title "Jane's Notes & Co <beta>"}
                                          {:logic :backend :name "engine-app"}]]
        (let [dir (str base "/" name)
              _ (starter/init! (cond-> {:dir dir :logic logic} title (assoc :title title)))
              modules (:modules (starter/read-manifest dir))
              cp (str/join sep (concat jars
                                       (for [m modules] (str root "/modules/" (clojure.core/name m) "/src"))
                                       [(str root "/verify/src") (str dir "/shared/src") (str dir "/shared/test")]))
              p (-> (ProcessBuilder. ^java.util.List [java "-cp" cp "clojure.main" "-m" "cloview.verify.test-runner"
                                                      (str name ".core-test")])
                    (.redirectErrorStream true)
                    (.start))
              out (slurp (.getInputStream p))
              exit (.waitFor p)]
          (is (= 0 exit) out)
          (is (re-find #"TOTAL tests=2 assertions=\d+ fail=0 error=0" out) out)))
      (finally (delete-tree! base)))))
