;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.fx.file
  "File-system effects (desktop + mobile; not available on Flutter web).

    :file/read    {:path p :on-success [:ev] :on-failure [:ev]}            payload: text
    :file/write   {:path p :content s :on-success [:ev] :on-failure [:ev]} payload: path
    :file/copy    {:from p :to p :on-success [:ev] :on-failure [:ev]}      payload: dest path
    :file/list    {:dir p :extensions [\"gif\" \"mp4\"] :on-success [:ev] ...}
                  payload: vector of the absolute paths of the files (not directories) in
                  the directory, in ordinal order (extension match is case-insensitive)
    :file/app-dir {:on-success [:ev]}   payload: per-app data directory (created if needed)
  Failures dispatch (conj on-failure {:error message :path p}).

  :file/pick is part of the same contract but is registered by the UI host module
  (cloview.javafx.app → JavaFX FileChooser, cloview.wpf.app → WPF OpenFileDialog,
  cloview.flutter.app → reports :unsupported until the file_picker plugin is added):
    :file/pick    {:title \"Import heart-rate export\" :extensions [\"csv\" \"json\"]
                   :multiple? false :on-picked [:hr/picked] :on-cancel [:hr/cancelled]}
                  payload: vector of absolute paths

  JVM/CLR run the I/O on a background thread and dispatch back (dispatch! marshals to
  the UI thread); Dart uses dart:io futures."
  (:require [clojure.string :as str]
            [cloview.core.order :as order]
            [cloview.core.store :as st]
            [cloview.core.text :as text]
            #?@(:cljd [["dart:io" :as io]
                       ["package:path_provider/path_provider.dart" :as pp]])))

(defonce ^:private config (atom {:app-id "cloview"}))

(defn extension
  "Lower-cased extension of `path` without the dot (\"\" when none)."
  [path]
  (let [p (str path)
        slash (max (or (str/last-index-of p "/") -1) (or (str/last-index-of p "\\") -1))
        dot (str/last-index-of p ".")]
    (if (and dot (> dot slash))
      (text/ascii-lower (subs p (inc dot)))
      "")))

(defn matches-extensions?
  [path extensions]
  (or (empty? extensions)
      (contains? (into #{} (map text/ascii-lower) extensions) (extension path))))

(defn- fail! [dispatch! on-failure path ex]
  (when on-failure (dispatch! (conj on-failure {:error (str ex) :path path}))))

(defn- ok! [dispatch! on-success v]
  (when on-success (dispatch! (conj on-success v))))

#?(:cljd nil
   :default
   (defn- background
     "Run (f) on a background thread; deliver its value or failure."
     [dispatch! on-success on-failure path f]
     (future
       (try
         (ok! dispatch! on-success (f))
         (catch #?(:clj Throwable :cljr System.Exception) ex
           (fail! dispatch! on-failure path ex))))
     nil))

(defn app-dir-path
  "Per-app data directory on desktop hosts (Dart resolves it asynchronously)."
  []
  #?(:cljd nil
     :clj (let [local (System/getenv "LOCALAPPDATA")
                base (or local (str (System/getProperty "user.home") java.io.File/separator ".cloview"))]
            (str base java.io.File/separator (if local (str "cloview" java.io.File/separator) "")
                 (:app-id @config)))
     :cljr (let [local (System.Environment/GetEnvironmentVariable "LOCALAPPDATA")
                 home (or (System.Environment/GetEnvironmentVariable "HOME")
                          (System.Environment/GetEnvironmentVariable "USERPROFILE"))]
             (System.IO.Path/Combine (if local (System.IO.Path/Combine local "cloview")
                                         (System.IO.Path/Combine home ".cloview"))
                                     (str (:app-id @config))))))

(defn- read! [{:keys [path on-success on-failure]} dispatch!]
  #?(:cljd (-> (.readAsString (io/File path))
               (.then (fn [s] (ok! dispatch! on-success s) nil))
               (.catchError (fn [e] (fail! dispatch! on-failure path e) nil)))
     :clj (background dispatch! on-success on-failure path #(slurp path))
     :cljr (background dispatch! on-success on-failure path #(System.IO.File/ReadAllText path))))

(defn- write! [{:keys [path content on-success on-failure]} dispatch!]
  #?(:cljd (-> (.create (.-parent (io/File path)) .recursive true)
               (.then (fn [_] (.writeAsString (io/File path) (str content))))
               (.then (fn [_] (ok! dispatch! on-success path) nil))
               (.catchError (fn [e] (fail! dispatch! on-failure path e) nil)))
     :clj (background dispatch! on-success on-failure path
                      #(let [f (java.io.File. ^String path)]
                         (some-> (.getParentFile f) (.mkdirs))
                         (spit f (str content))
                         path))
     :cljr (background dispatch! on-success on-failure path
                       #(let [dir (System.IO.Path/GetDirectoryName (System.IO.Path/GetFullPath path))]
                          (when-not (str/blank? dir) (System.IO.Directory/CreateDirectory dir))
                          (System.IO.File/WriteAllText path (str content))
                          path))))

(defn- copy! [{:keys [from to on-success on-failure]} dispatch!]
  #?(:cljd (-> (.create (.-parent (io/File to)) .recursive true)
               (.then (fn [_] (.copy (io/File from) to)))
               (.then (fn [_] (ok! dispatch! on-success to) nil))
               (.catchError (fn [e] (fail! dispatch! on-failure from e) nil)))
     :clj (background dispatch! on-success on-failure from
                      #(let [dest (java.io.File. ^String to)]
                         (some-> (.getParentFile dest) (.mkdirs))
                         (java.nio.file.Files/copy (.toPath (java.io.File. ^String from)) (.toPath dest)
                                                   ^"[Ljava.nio.file.CopyOption;"
                                                   (into-array java.nio.file.CopyOption
                                                               [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                         to))
     :cljr (background dispatch! on-success on-failure from
                       #(let [dir (System.IO.Path/GetDirectoryName (System.IO.Path/GetFullPath to))]
                          (when-not (str/blank? dir) (System.IO.Directory/CreateDirectory dir))
                          (System.IO.File/Copy from to true)
                          to))))

(defn- list! [{:keys [dir extensions on-success on-failure]} dispatch!]
  #?(:cljd (-> (.toList (.list (io/Directory dir)))
               (.then (fn [entries]
                        (ok! dispatch! on-success
                             (->> entries
                                  (filter (fn [e] (instance? io/File e)))
                                  (map (fn [^io/FileSystemEntity e] (.-path (.-absolute e))))
                                  (filter (fn [p] (matches-extensions? p extensions)))
                                  order/sort-strings))
                        nil))
               (.catchError (fn [e] (fail! dispatch! on-failure dir e) nil)))
     :clj (background dispatch! on-success on-failure dir
                      #(->> (.listFiles (java.io.File. ^String dir))
                            (filter (fn [^java.io.File f] (.isFile f)))
                            (map (fn [^java.io.File f] (.getAbsolutePath f)))
                            (filter (fn [p] (matches-extensions? p extensions)))
                            order/sort-strings))
     :cljr (background dispatch! on-success on-failure dir
                       #(->> (System.IO.Directory/GetFiles dir)
                             (map (fn [p] (System.IO.Path/GetFullPath p)))
                             (filter (fn [p] (matches-extensions? p extensions)))
                             order/sort-strings))))

(defn- app-dir! [{:keys [on-success on-failure]} dispatch!]
  #?(:cljd (-> (pp/getApplicationSupportDirectory)
               (.then (fn [^io/Directory d] (ok! dispatch! on-success (.-path d)) nil))
               (.catchError (fn [e] (fail! dispatch! on-failure nil e) nil)))
     :default (let [p (app-dir-path)]
                (background dispatch! on-success on-failure p
                            #(do #?(:clj (.mkdirs (java.io.File. ^String p))
                                    :cljr (System.IO.Directory/CreateDirectory p))
                                 p)))))

(defn install!
  "Register :file/read :file/write :file/copy :file/list :file/app-dir.
  Options: :app-id (used by :file/app-dir). Idempotent."
  ([] (install! {}))
  ([{:keys [app-id]}]
   (when app-id (swap! config assoc :app-id app-id))
   (st/reg-fx :file/read (fn [v {:keys [dispatch!]}] (read! v dispatch!)))
   (st/reg-fx :file/write (fn [v {:keys [dispatch!]}] (write! v dispatch!)))
   (st/reg-fx :file/copy (fn [v {:keys [dispatch!]}] (copy! v dispatch!)))
   (st/reg-fx :file/list (fn [v {:keys [dispatch!]}] (list! v dispatch!)))
   (st/reg-fx :file/app-dir (fn [v {:keys [dispatch!]}] (app-dir! v dispatch!)))
   :installed))
