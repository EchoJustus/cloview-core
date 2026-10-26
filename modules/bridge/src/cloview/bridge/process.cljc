;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.process
  "Child-process transport of the backend bridge: starts a backend from a launch spec
  (cloview.bridge.manifest/launch-spec) with pipes for stdin, stdout and stderr.

  Implemented for Clojure/JVM and ClojureCLR. On ClojureDart, `spawn` throws
  ex-info {:reason :unsupported} for now (Stage 3: dart:io Process.start; the contract
  below is the specification).

  Contract of `spawn`:
    - lines written with :send! go to the backend's stdin, UTF-8, each ending in \\n;
    - every stdout line (UTF-8, \\n or \\r\\n) is passed to :on-line, in order, from a
      reader thread; a line longer than :max-line-chars is skipped and reported to
      :on-too-large with its length;
    - stderr lines go to :on-stderr (they are always drained, so a chatty backend never
      blocks on a full pipe);
    - :on-exit gets {:exit code} once, after stdout ended and the process exited;
    - :close! closes stdin (the backend must treat EOF as \"exit now\": that is also
      what happens when the GUI process dies, so backends never outlive their GUI);
    - :kill! kills the process and its descendants."
  (:require [clojure.string :as str]
            [cloview.core.text :as text]
            #?@(:cljd [["dart:io" :as io]])))

(defn platform
  "This machine's platform key for manifest commands: windows, macos, linux, android, ios."
  []
  (let [os #?(:cljd io/Platform.operatingSystem
              :clj (System/getProperty "os.name")
              ;; Environment.OSVersion says "Unix" on both Linux and macOS
              :cljr (cond (System.OperatingSystem/IsWindows) "windows"
                          (System.OperatingSystem/IsMacOS) "macos"
                          (System.OperatingSystem/IsAndroid) "android"
                          (System.OperatingSystem/IsIOS) "ios"
                          :else "linux"))
        os (text/ascii-lower (str os))]
    (cond
      (str/includes? os "android") "android"
      (str/includes? os "ios") "ios"
      (str/includes? os "win") "windows"
      (or (str/includes? os "mac") (str/includes? os "darwin")) "macos"
      :else "linux")))

(def default-max-line-chars 16777216)

;; ---------------------------------------------------------------------------
;; JVM

#?(:cljd nil
   :clj
   (do
     (defn- read-lines!
       "Read `rdr` line by line until EOF, calling (on-line s); lines longer than `max`
       characters are skipped and reported with (on-too-large n)."
       [^java.io.Reader rdr max on-line on-too-large]
       (let [sb (StringBuilder.)]
         (loop [skipping? false n 0]
           (let [c (.read rdr)]
             (cond
               (neg? c) (when (and (pos? (.length sb)) (not skipping?)) (on-line (str sb)))
               (= c 10) (do (if skipping?
                              (on-too-large n)
                              (let [len (.length sb)
                                    s (if (and (pos? len) (= \return (.charAt sb (dec len))))
                                        (.substring sb 0 (dec len))
                                        (str sb))]
                                (on-line s)))
                            (.setLength sb 0)
                            (recur false 0))
               skipping? (recur true (inc n))
               (>= (.length sb) max) (do (.setLength sb 0) (recur true (inc max)))
               :else (do (.append sb (char c)) (recur false (inc n))))))))

     (defn- daemon! [^String thread-name f]
       (doto (Thread. ^Runnable f thread-name) (.setDaemon true) (.start)))

     (defn spawn
       [{:keys [command cwd env]} {:keys [on-line on-stderr on-exit max-line-chars on-too-large]}]
       (let [pb (ProcessBuilder. ^java.util.List (vec command))
             _ (when cwd (.directory pb (java.io.File. ^String cwd)))
             _ (doseq [[k v] env] (.put (.environment pb) (str k) (str v)))
             ^Process proc (try (.start pb)
                                (catch java.io.IOException e
                                  (throw (ex-info (str "Cannot start backend: " (.getMessage e))
                                                  {:reason :spawn :command command} e))))
             utf8 java.nio.charset.StandardCharsets/UTF_8
             w (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream proc) utf8))
             max (or max-line-chars default-max-line-chars)
             noop (fn [& _] nil)]
         (daemon! "cloview-bridge-stderr"
                  (fn []
                    (with-open [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getErrorStream proc) utf8))]
                      (loop [] (when-some [l (try (.readLine r) (catch java.io.IOException _ nil))]
                                 ((or on-stderr noop) l)
                                 (recur))))))
         (daemon! "cloview-bridge-stdout"
                  (fn []
                    (try
                      (with-open [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream proc) utf8))]
                        (read-lines! r max on-line (or on-too-large noop)))
                      (catch java.io.IOException _ nil))
                    (on-exit {:exit (.waitFor proc)})))
         {:send! (fn [^String line]
                   (locking w
                     (.write w line)
                     (.write w "\n")
                     (.flush w)))
          :close! (fn [] (try (locking w (.close w)) (catch java.io.IOException _ nil)))
          :kill! (fn []
                   (doseq [^java.lang.ProcessHandle h (iterator-seq (.iterator (.descendants (.toHandle proc))))]
                     (.destroyForcibly h))
                   (.destroyForcibly proc))
          :alive? (fn [] (.isAlive proc))
          :pid (.pid proc)}))))

;; ---------------------------------------------------------------------------
;; ClojureCLR

#?(:cljr
   (do
     (defn- read-lines!
       [^System.IO.TextReader rdr max on-line on-too-large]
       (let [sb (System.Text.StringBuilder.)]
         (loop [skipping? false n 0]
           (let [c (.Read rdr)]
             (cond
               (neg? c) (when (and (pos? (.Length sb)) (not skipping?)) (on-line (.ToString sb)))
               (= c 10) (do (if skipping?
                              (on-too-large n)
                              (let [s (.ToString sb)
                                    s (if (str/ends-with? s "\r") (subs s 0 (dec (count s))) s)]
                                (on-line s)))
                            (.set_Length sb 0)
                            (recur false 0))
               skipping? (recur true (inc n))
               (>= (.Length sb) max) (do (.set_Length sb 0) (recur true (inc max)))
               :else (do (.Append sb (char c)) (recur false (inc n))))))))

     (defn- background! [f]
       (doto (System.Threading.Thread. (gen-delegate System.Threading.ThreadStart [] (f)))
         (.set_IsBackground true)
         (.Start)))

     (defn spawn
       [{:keys [command cwd env]} {:keys [on-line on-stderr on-exit max-line-chars on-too-large]}]
       (let [utf8 (System.Text.UTF8Encoding. false)
             psi (System.Diagnostics.ProcessStartInfo. (str (first command)))
             _ (doseq [a (rest command)] (.Add (.ArgumentList psi) (str a)))
             _ (doto psi
                 (.set_UseShellExecute false)
                 (.set_CreateNoWindow true)
                 (.set_RedirectStandardInput true)
                 (.set_RedirectStandardOutput true)
                 (.set_RedirectStandardError true)
                 (.set_StandardInputEncoding utf8)
                 (.set_StandardOutputEncoding utf8)
                 (.set_StandardErrorEncoding utf8))
             _ (when cwd (.set_WorkingDirectory psi cwd))
             _ (doseq [[k v] env]
                 (let [e (.Environment psi)]
                   (.Remove e (str k))
                   (.Add e (str k) (str v))))
             proc (try (System.Diagnostics.Process/Start psi)
                       (catch System.Exception e
                         (throw (ex-info (str "Cannot start backend: " (.Message e))
                                         {:reason :spawn :command command}))))
             w (.StandardInput proc)
             lock (Object.)
             max (or max-line-chars default-max-line-chars)
             noop (fn [& _] nil)]
         (background! (fn []
                        (try
                          (loop [] (when-some [l (.ReadLine (.StandardError proc))]
                                     ((or on-stderr noop) l)
                                     (recur)))
                          (catch System.Exception _ nil))))
         (background! (fn []
                        (try (read-lines! (.StandardOutput proc) max on-line (or on-too-large noop))
                             (catch System.Exception _ nil))
                        (.WaitForExit proc)
                        (on-exit {:exit (.ExitCode proc)})))
         {:send! (fn [line]
                   (locking lock
                     (.Write w (str line "\n"))
                     (.Flush w)))
          :close! (fn [] (try (locking lock (.Close w)) (catch System.Exception _ nil)))
          :kill! (fn [] (try (.Kill proc true) (catch System.Exception _ nil)))
          :alive? (fn [] (not (.HasExited proc)))
          :pid (.Id proc)}))))

;; ---------------------------------------------------------------------------
;; ClojureDart (Stage 3)

#?(:cljd
   (defn spawn [{:keys [command]} _callbacks]
     (throw (ex-info "cloview.bridge.process/spawn is not implemented on ClojureDart yet (Stage 3)"
                     {:reason :unsupported :command command}))))
