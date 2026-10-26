;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.fx.storage
  "Small key-value persistence (settings, documents, state snapshots) with EDN values.

  Effects
    :storage/put    {:key k :value v :on-success [:ev] :on-failure [:ev]}  (or a vector of puts)
    :storage/remove {:key k :on-success [:ev] :on-failure [:ev]}
    :storage/get    {:key k :on-loaded [:ev] :on-failure [:ev]}

    on-success  (conj ev k)            once the write is durable (desktop: the file was
                                       replaced; Dart: shared_preferences confirmed it)
    on-loaded   (conj ev value-or-nil)
    on-failure  (conj ev {:error \"message\" :key k :reason r}), r one of
                :write          the write failed; nothing was replaced
                :read           the store could not be opened (Dart: shared_preferences)
                :store-corrupt  the stored data cannot be read (see below)
    Callbacks are optional. Without :on-failure a failed write throws from the runner, as
    before, so it is never silently lost.

  Desktop backend (JVM / CLR): one EDN file <dir>/<app-id>.edn
    (dir defaults to %LOCALAPPDATA%\\cloview on Windows, ~/.cloview elsewhere)
    - Writes are serial, in effect order. Each write goes to <file>.tmp first; the current
      file becomes <file>.bak and the temp file replaces it in one atomic step
      (JVM Files/move ATOMIC_MOVE, .NET File.Replace). A crash leaves either the old or
      the new document, never a torn one.
    - Reads: the primary file; if it is unreadable, the backup. A recovered start keeps
      the unreadable primary as <file>.corrupt-<epoch-ms> (preserved, never overwritten)
      and continues from the backup.
    - If the primary and the backup are both unreadable, the store is :corrupt: every get
      and write fails with :reason :store-corrupt and neither file is touched, so nothing
      overwrites data that might still be repaired by hand. (benchmark EFFECT-002)
  Dart backend: shared_preferences, one EDN string per key (\"cloview.<app-id>.<key>\"); a
    value that cannot be read fails that get with :store-corrupt. No backup file.

  Values must be EDN-printable. Large or binary data (video clips, imports) belongs in
  cloview.fx.file."
  (:require [clojure.edn :as edn]
            [cloview.core.store :as st]
            #?(:cljd ["package:shared_preferences/shared_preferences.dart" :as sp])))

(defonce ^:private config (atom {:app-id "cloview" :dir nil}))

(defn default-dir
  "Default storage directory for desktop hosts."
  []
  #?(:cljd nil
     :clj (let [local (System/getenv "LOCALAPPDATA")]
            (if local
              (str local java.io.File/separator "cloview")
              (str (System/getProperty "user.home") java.io.File/separator ".cloview")))
     :cljr (let [local (System.Environment/GetEnvironmentVariable "LOCALAPPDATA")
                 home (or (System.Environment/GetEnvironmentVariable "HOME")
                          (System.Environment/GetEnvironmentVariable "USERPROFILE"))]
             (if local
               (System.IO.Path/Combine local "cloview")
               (System.IO.Path/Combine home ".cloview")))))

(defn storage-key
  "Stable string form of a storage key."
  [k]
  (if (keyword? k)
    (if (namespace k) (str (namespace k) "/" (name k)) (name k))
    (str k)))

(defn- fail-value [k reason ex]
  {:error (str ex) :key k :reason reason})

;; ---------------------------------------------------------------------------
;; Desktop backend (JVM / CLR)

#?(:cljd nil
   :default
   (do
     ;; nil until first use, then {:data {key value} :status :ok|:recovered|:corrupt}
     (defonce ^:private cache (atom nil))

     (defn file-path
       "The primary storage file of the current configuration."
       []
       (let [{:keys [app-id dir]} @config
             dir (or dir (default-dir))]
         #?(:clj (str dir java.io.File/separator app-id ".edn")
            :cljr (System.IO.Path/Combine dir (str app-id ".edn")))))

     (defn- exists? [p]
       #?(:clj (.isFile (java.io.File. ^String p)) :cljr (System.IO.File/Exists p)))

     (defn- read-text [p]
       #?(:clj (slurp p :encoding "UTF-8") :cljr (System.IO.File/ReadAllText p)))

     (defn- now-ms []
       #?(:clj (System/currentTimeMillis)
          :cljr (.ToUnixTimeMilliseconds System.DateTimeOffset/UtcNow)))

     (defn- read-map
       "The EDN map stored at `p`: nil when the file is missing, ::corrupt when it cannot
       be read as a map."
       [p]
       (when (exists? p)
         (try
           (let [v (edn/read-string (read-text p))]
             (if (map? v) v ::corrupt))
           (catch #?(:clj Throwable :cljr System.Exception) _ ::corrupt))))

     (defn- move! [from to]
       #?(:clj (java.nio.file.Files/move (.toPath (java.io.File. ^String from)) (.toPath (java.io.File. ^String to))
                                         (into-array java.nio.file.CopyOption
                                                     [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          :cljr (System.IO.File/Move from to)))

     (defn- load-store
       "Read the primary file (or its backup) into {:data :status}."
       []
       (let [p (file-path)
             bak (str p ".bak")
             primary (read-map p)]
         (cond
           (map? primary) {:data primary :status :ok}
           (nil? primary) (let [b (read-map bak)]
                            (if (map? b) {:data b :status :recovered} {:data {} :status :ok}))
           :else (let [b (read-map bak)]
                   (if (map? b)
                     ;; keep the unreadable primary for inspection, continue from the backup;
                     ;; if it cannot be moved aside, refuse to touch anything
                     (try
                       (move! p (str p ".corrupt-" (now-ms)))
                       {:data b :status :recovered}
                       (catch #?(:clj Throwable :cljr System.Exception) _
                         {:data nil :status :corrupt}))
                     {:data nil :status :corrupt})))))

     (defn- ensure-loaded! []
       (when (nil? @cache) (reset! cache (load-store)))
       @cache)

     (defn- persist!
       "Write `m` durably: temp file, then an atomic replace that keeps the previous file
       as the backup."
       [m]
       (let [p (file-path)
             tmp (str p ".tmp")
             bak (str p ".bak")
             text (pr-str m)]
         #?(:clj (let [f (java.io.File. ^String p)
                       src (.toPath (java.io.File. ^String tmp))
                       dst (.toPath f)]
                   (.mkdirs (.getParentFile f))
                   (spit tmp text :encoding "UTF-8")
                   (when (.isFile f)
                     (java.nio.file.Files/copy dst (.toPath (java.io.File. ^String bak))
                                               (into-array java.nio.file.CopyOption
                                                           [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))
                   (try
                     (java.nio.file.Files/move src dst (into-array java.nio.file.CopyOption
                                                                   [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
                     (catch java.nio.file.AtomicMoveNotSupportedException _
                       (java.nio.file.Files/move src dst (into-array java.nio.file.CopyOption
                                                                     [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))))
            :cljr (do (System.IO.Directory/CreateDirectory (System.IO.Path/GetDirectoryName p))
                      (System.IO.File/WriteAllText tmp text)
                      (if (System.IO.File/Exists p)
                        (System.IO.File/Replace tmp p bak)
                        (System.IO.File/Move tmp p))))))

     (defn- write!
       "Apply `f` to the stored map and persist it. Returns nil, or the failure value."
       [k f]
       (let [{:keys [data status]} (ensure-loaded!)]
         (if (= status :corrupt)
           (fail-value k :store-corrupt "stored data is unreadable (primary and backup); not overwritten")
           (let [m (f data)]
             (try
               (persist! m)
               (reset! cache {:data m :status :ok})
               nil
               (catch #?(:clj Throwable :cljr System.Exception) ex
                 (fail-value k :write ex)))))))))

;; ---------------------------------------------------------------------------
;; Runners

(defn- report!
  "Dispatch the outcome of a write. Without an :on-failure event a failure throws."
  [dispatch! {:keys [key on-success on-failure]} failure]
  (if failure
    (if on-failure
      (dispatch! (conj on-failure failure))
      (throw (ex-info (str "storage write failed: " (:error failure)) failure)))
    (when on-success (dispatch! (conj on-success key)))))

#?(:cljd
   (defn- prefs-key [key] (str "cloview." (:app-id @config) "." (storage-key key))))

(defn- put! [{:keys [key value] :as fx} dispatch!]
  ;; Dart: `.then f .onError g` on the last step routes errors of getInstance/setString to
  ;; g, while an exception thrown by the app's on-success handler is not a failed write
  #?(:cljd (-> (sp/SharedPreferences.getInstance)
               (.then (fn [^sp/SharedPreferences prefs] (.setString prefs (prefs-key key) (pr-str value))))
               (.then (fn [ok?]
                        (report! dispatch! fx (when-not ok? (fail-value key :write "setString returned false")))
                        nil)
                      .onError (fn [e] (report! dispatch! fx (fail-value key :write e)) nil)))
     :default (report! dispatch! fx (write! key #(assoc % (storage-key key) value)))))

(defn- remove! [{:keys [key] :as fx} dispatch!]
  #?(:cljd (-> (sp/SharedPreferences.getInstance)
               (.then (fn [^sp/SharedPreferences prefs] (.remove prefs (prefs-key key))))
               (.then (fn [_] (report! dispatch! fx nil) nil)
                      .onError (fn [e] (report! dispatch! fx (fail-value key :write e)) nil)))
     :default (report! dispatch! fx (write! key #(dissoc % (storage-key key))))))

(defn- get! [{:keys [key on-loaded on-failure]} dispatch!]
  #?(:cljd (-> (sp/SharedPreferences.getInstance)
               (.then (fn [^sp/SharedPreferences prefs]
                        (let [s (.getString prefs (prefs-key key))
                              v (if (nil? s)
                                  nil
                                  (try (edn/read-string s) (catch dynamic e ::unreadable)))]
                          (if (= v ::unreadable)
                            (when on-failure
                              (dispatch! (conj on-failure (fail-value key :store-corrupt "stored value is unreadable"))))
                            (when on-loaded (dispatch! (conj on-loaded v))))
                          nil))
                      .onError (fn [e]
                                 (when on-failure (dispatch! (conj on-failure (fail-value key :read e))))
                                 nil)))
     :default (let [{:keys [data status]} (ensure-loaded!)]
                (if (= status :corrupt)
                  (when on-failure
                    (dispatch! (conj on-failure (fail-value key :store-corrupt
                                                            "stored data is unreadable (primary and backup)"))))
                  (when on-loaded (dispatch! (conj on-loaded (get data (storage-key key)))))))))

(defn status
  "Desktop hosts: :ok, :recovered (started from the backup) or :corrupt, after the first
  access; nil before it (and always nil on Dart)."
  []
  #?(:cljd nil :default (:status @cache)))

(defn install!
  "Register the :storage/* effects. Options: :app-id (default \"cloview\"), :dir
  (desktop hosts only). Idempotent; re-installing resets the cache (re-reads the files)."
  ([] (install! {}))
  ([{:keys [app-id dir]}]
   (swap! config assoc :app-id (or app-id (:app-id @config)) :dir dir)
   #?(:cljd nil :default (reset! cache nil))
   (st/reg-fx :storage/put (fn [v {:keys [dispatch!]}] (doseq [p (if (map? v) [v] v)] (put! p dispatch!))))
   (st/reg-fx :storage/remove (fn [v {:keys [dispatch!]}] (remove! v dispatch!)))
   (st/reg-fx :storage/get (fn [v {:keys [dispatch!]}] (get! v dispatch!)))
   :installed))
