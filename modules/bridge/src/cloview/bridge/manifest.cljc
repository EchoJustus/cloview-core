;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.manifest
  "backend-manifest.json — the contract a backend (Java, C#, Dart, ... anything that can
  read stdin and write stdout) follows to be started by the cloview launcher
  (docs/POLYGLOT_BACKEND_ARCHITECTURE.md §4).

    {
      \"manifest_version\": 1,
      \"id\": \"engine\",
      \"name\": \"Watermark engine\",
      \"protocol\": \"cloview-bridge/1\",
      \"command\": {
        \"windows\": [\"${appDir}/backend/engine.exe\"],
        \"default\": [\"java\", \"-jar\", \"${appDir}/backend/engine.jar\"]
      },
      \"cwd\": \"${appDir}/backend\",
      \"env\": {\"ENGINE_LOG\": \"${dataDir}/engine.log\"},
      \"start\": \"on-launch\",
      \"startup_timeout_ms\": 15000,
      \"request_timeout_ms\": 60000,
      \"shutdown_timeout_ms\": 3000,
      \"restart\": {\"policy\": \"on-crash\", \"max_restarts\": 3, \"window_ms\": 60000},
      \"max_message_bytes\": 16777216
    }

  Placeholders in command, cwd and env values: ${appDir} ${dataDir} ${tempDir}
  ${platform} (windows | macos | linux | android | ios), plus any :vars passed by the
  app. `command` is chosen by platform key, falling back to \"default\".
  Pure functions, identical on every host."
  (:require [clojure.string :as str]
            [cloview.fx.codec :as codec]))

(def manifest-version 1)

(def defaults
  {:transport "stdio"
   :start "on-launch"
   :startup_timeout_ms 15000
   :request_timeout_ms 60000
   :shutdown_timeout_ms 3000
   :restart {:policy "on-crash" :max_restarts 3 :window_ms 60000}
   :max_message_bytes 16777216
   :env {}})

(def platforms #{"windows" "macos" "linux" "android" "ios"})

(defn- positive-int? [x] (and (integer? x) (pos? x)))
(defn- string-vec? [x] (and (sequential? x) (seq x) (every? string? x)))

(defn problems
  "Human-readable problems of a manifest map (keyword keys, defaults applied); empty
  when valid."
  [m]
  (let [{:keys [command restart env]} m]
    (cond-> []
      (not= manifest-version (:manifest_version m))
      (conj (str "manifest_version must be " manifest-version))
      (not (and (string? (:id m)) (re-matches #"[a-z0-9][a-z0-9-]*" (:id m))))
      (conj "id must be lower-case letters, digits and dashes")
      (not= "cloview-bridge/1" (:protocol m))
      (conj "protocol must be \"cloview-bridge/1\"")
      (not (and (map? command) (seq command)))
      (conj "command must be an object of platform -> [program, args...]")
      (and (map? command)
           (not (every? (fn [[k v]] (and (contains? (conj platforms "default") (name k)) (string-vec? v))) command)))
      (conj (str "command keys must be one of " (str/join ", " (sort (conj platforms "default")))
                 ", each a non-empty array of strings"))
      (not= "stdio" (:transport m))
      (conj "transport must be \"stdio\" (\"tcp\" is reserved for a later version)")
      (not (contains? #{"on-launch" "on-demand"} (:start m)))
      (conj "start must be \"on-launch\" or \"on-demand\"")
      (not (every? #(positive-int? (get m %)) [:startup_timeout_ms :request_timeout_ms :shutdown_timeout_ms :max_message_bytes]))
      (conj "timeouts and max_message_bytes must be positive integers")
      (not (contains? #{"never" "on-crash"} (:policy restart)))
      (conj "restart.policy must be \"never\" or \"on-crash\"")
      (not (and (integer? (:max_restarts restart)) (>= (:max_restarts restart) 0) (positive-int? (:window_ms restart))))
      (conj "restart.max_restarts must be >= 0 and restart.window_ms > 0")
      (not (and (map? env) (every? (fn [[k v]] (and (or (keyword? k) (string? k)) (string? v))) env)))
      (conj "env must be an object of string values")
      (and (some? (:cwd m)) (not (string? (:cwd m))))
      (conj "cwd must be a string"))))

(defn normalize
  "A manifest map (keyword keys, as read-json {:keywordize? true} returns it) with the
  defaults applied. Throws ex-info {:reason :manifest :problems [...]} when invalid."
  [m]
  (let [m (-> (merge defaults m)
              (update :restart #(merge (:restart defaults) %))
              (update :env #(or % {})))
        ps (problems m)]
    (when (seq ps)
      (throw (ex-info (str "Invalid backend manifest: " (str/join "; " ps))
                      {:reason :manifest :problems ps :id (:id m)})))
    m))

(defn parse
  "Manifest map for backend-manifest.json text (see `normalize`)."
  [json-text]
  (normalize (codec/read-json json-text {:keywordize? true})))

(defn substitute
  "Replace ${name} placeholders in `s` with (get vars :name); unknown names are left
  untouched (and reported by `unresolved`)."
  [s vars]
  (str/replace s #"\$\{([A-Za-z][A-Za-z0-9_]*)\}"
               (fn [[whole k]] (if-some [v (get vars (keyword k))] (str v) whole))))

(defn unresolved
  "Placeholder names left in the strings of a resolved launch spec."
  [{:keys [command cwd env]}]
  (->> (concat command (when cwd [cwd]) (vals env))
       (mapcat #(map second (re-seq #"\$\{([A-Za-z][A-Za-z0-9_]*)\}" %)))
       distinct vec))

(defn launch-spec
  "What the launcher runs on `platform` (\"windows\" ...): {:id :command [..] :cwd :env {..}}
  with placeholders replaced from `vars` ({:appDir .. :dataDir .. :tempDir ..}); :platform
  is added to vars. Throws ex-info {:reason :manifest} when the manifest has no command
  for the platform or a placeholder stays unresolved."
  [m platform vars]
  (let [vars (assoc vars :platform platform)
        cmd (or (get-in m [:command (keyword platform)]) (get-in m [:command :default]))
        _ (when-not cmd
            (throw (ex-info (str "Backend " (:id m) " has no command for " platform)
                            {:reason :manifest :id (:id m) :platform platform})))
        spec {:id (:id m)
              :command (mapv #(substitute % vars) cmd)
              :cwd (some-> (:cwd m) (substitute vars))
              :env (into {} (map (fn [[k v]] [(name k) (substitute v vars)])) (:env m))}
        missing (unresolved spec)]
    (when (seq missing)
      (throw (ex-info (str "Backend " (:id m) ": unresolved placeholders " (str/join ", " missing))
                      {:reason :manifest :id (:id m) :unresolved missing})))
    spec))
