;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.issue
  "The seller's side of cloview.licensing (Clojure/JVM). Never ship this namespace or a
  private key file inside an app: the app only needs the public key.

    clojure -M:license keygen --kid 2026-1 [--out license-keys]
        writes license-keys/2026-1.private.edn and prints the public key to paste into
        the app's (licensing/install! {:public-keys {\"2026-1\" \"...\"}})
    clojure -M:license issue --key license-keys/2026-1.private.edn --product wmark
                             --features pro/remove-watermark,pro/batch
                             [--id L-0001] [--licensee \"Ada Lovelace\"]
                             [--device D1-XXXX-XXXX-XXXX-XXXX] [--expires 2027-10-24]
                             [--issued 2026-10-25]
        prints the licence key (CLV1...)
    clojure -M:license inspect <licence-key> [--public <base64url> --kid 2026-1
                                              --product wmark --device D1-...]
        prints the payload, and the verification result when --public is given
    clojure -M:license device-code --product wmark
        prints this machine's device code (what a customer sends for a bound key)

  Keep *.private.edn out of version control (the repository .gitignore excludes them);
  losing it means issuing new keys under a new --kid."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloview.licensing.crypto :as crypto]
            [cloview.licensing.device :as device]
            [cloview.licensing.token :as token]))

(def ^:private date-re #"\d{4}-\d{2}-\d{2}")
(def ^:private device-re #"D1-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}")
(def ^:private kid-re #"[A-Za-z0-9._-]{1,32}")

(defn- fail [msg] (throw (ex-info msg {:usage true})))

(defn- today [] (str (java.time.LocalDate/now java.time.ZoneOffset/UTC)))

(defn- check-date!
  "A real calendar date in the form YYYY-MM-DD (2027-02-30 is refused)."
  [flag v]
  (when (some? v)
    (when-not (and (string? v)
                   (re-matches date-re v)
                   ;; ISO_LOCAL_DATE resolves strictly
                   (try (java.time.LocalDate/parse v) true
                        (catch java.time.format.DateTimeParseException _ false)))
      (fail (str flag " must be a real date written YYYY-MM-DD")))))

(defn- owner-only-attrs
  "File attributes that make a new file or folder private to its owner on POSIX systems
  (none elsewhere: on Windows the user profile's ACLs apply)."
  [perms]
  (if (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix")
    (into-array java.nio.file.attribute.FileAttribute
                [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                  (java.nio.file.attribute.PosixFilePermissions/fromString perms))])
    (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-secret!
  "Create `f` (never replacing one) private to its owner from the start, then write `text`."
  [^java.io.File f ^String text]
  (when-some [dir (.getParentFile (.getAbsoluteFile f))]
    (when-not (.exists dir)
      (java.nio.file.Files/createDirectories (.toPath dir) (owner-only-attrs "rwx------"))))
  (try
    (java.nio.file.Files/createFile (.toPath f) (owner-only-attrs "rw-------"))
    (catch java.nio.file.FileAlreadyExistsException _
      (fail (str f " exists; choose a new --kid instead of replacing a key"))))
  (spit f text))

(defn keygen!
  "Create a key pair, write it to <out>/<kid>.private.edn (refusing to overwrite) and
  return {:kid :file :public}."
  [{:keys [kid out] :or {out "license-keys"}}]
  (when-not (and (string? kid) (re-matches kid-re kid))
    (fail "--kid is required: 1-32 characters of A-Z a-z 0-9 . _ - (for example 2026-1)"))
  (let [f (io/file out (str kid ".private.edn"))]
    (when (.exists f) (fail (str f " exists; choose a new --kid instead of replacing a key")))
    (let [kp (crypto/es256-keypair)
          pub (crypto/b64url-encode (:public kp))]
      (write-secret! f (str ";; cloview licence signing key - SECRET. Anyone with this file can issue keys.\n"
                            (pr-str {:kid kid :alg "ES256" :public pub
                                     :private (crypto/b64url-encode (:private kp))
                                     :created (today)})
                            "\n"))
      {:kid kid :file (str f) :public pub})))

(defn read-key
  "{:kid :public :private} (bytes for the keys) of a .private.edn file."
  [path]
  (let [{:keys [kid alg public private]} (edn/read-string (slurp path))]
    (when-not (and (= "ES256" alg) (string? kid) (string? public) (string? private))
      (fail (str path " is not a cloview licence signing key")))
    {:kid kid :public (crypto/b64url-decode public) :private (crypto/b64url-decode private)
     :public-b64 public}))

(defn issue
  "A licence key for the options (see the namespace docstring). Signs, then verifies the
  result with the public key before returning it."
  [{:keys [key product features id licensee device expires issued]}]
  (when-not key (fail "--key <file.private.edn> is required"))
  (when (str/blank? product) (fail "--product is required"))
  ;; the app compares the product id exactly: "wmark " would make every key :wrong-product
  (when (not= product (str/trim product)) (fail "--product has leading or trailing spaces"))
  (when (and (some? id) (str/blank? id)) (fail "--id is empty"))
  (let [features (if (string? features) (remove str/blank? (map str/trim (str/split features #","))) features)
        issued (or issued (today))]
    (when (empty? features) (fail "--features is required (comma-separated, or * for everything)"))
    (when (and device (not (re-matches device-re device)))
      (fail "--device must look like D1-XXXX-XXXX-XXXX-XXXX (upper-case hex)"))
    (check-date! "--issued" issued)
    (check-date! "--expires" expires)
    (when (and expires (neg? (compare expires issued))) (fail "--expires is before --issued"))
    (let [{:keys [kid public-b64] :as k} (read-key key)
          payload (cond-> {:kid kid
                           :id (or id (str "L-" (subs (str (java.util.UUID/randomUUID)) 0 8)))
                           :product product
                           :features (vec features)
                           :issued issued}
                    licensee (assoc :licensee licensee)
                    device (assoc :device device)
                    expires (assoc :expires expires))
          tok (token/sign payload k)
          check (token/verify tok {:product product :public-keys {kid public-b64} :device-code device :today issued})]
      (when-not (:valid? check)
        (throw (ex-info (str "the new key does not verify: " (:reason check)) check)))
      tok)))

(defn inspect
  "{:payload ... [:verification ...]} of a licence key."
  [tok {:keys [public kid product device today]}]
  (let [{:keys [payload error]} (token/parse tok)]
    (if error
      {:error :malformed}
      (cond-> {:payload payload}
        public (assoc :verification
                      (token/verify tok {:product (or product (:product payload))
                                         :public-keys {(or kid (:kid payload)) public}
                                         :device-code device
                                         :today (or today (str (java.time.LocalDate/now java.time.ZoneOffset/UTC)))}))))))

(defn- parse-args [args]
  (loop [args (seq args) opts {} positional []]
    (if-some [[a & more] args]
      (if (str/starts-with? a "--")
        (let [k (keyword (subs a 2))]
          (cond
            (or (empty? more) (str/starts-with? (first more) "--")) (fail (str a " needs a value"))
            (contains? opts k) (fail (str a " is given twice"))
            :else (recur (next more) (assoc opts k (first more)) positional)))
        (recur more opts (conj positional a)))
      {:opts opts :positional positional})))

(def ^:private options
  "The options each command takes. Anything else is an error: a misspelt --expires
  must not issue a perpetual key."
  {"keygen" #{:kid :out}
   "issue" #{:key :product :features :id :licensee :device :expires :issued}
   "inspect" #{:public :kid :product :device :today}
   "device-code" #{:product}})

(def ^:private usage
  "usage: keygen --kid K [--out DIR] | issue --key F --product P --features A,B [...] | inspect TOKEN [--public PUB] | device-code --product P")

(defn run
  "Run a command line; returns the exit code (0 ok, 1 failed, 2 usage error)."
  [args]
  (try
    (let [{:keys [opts positional]} (parse-args args)
          [cmd tok extra] positional]
      (when-some [allowed (get options cmd)]
        (when-some [unknown (seq (remove allowed (keys opts)))]
          (fail (str "unknown option" (when (next unknown) "s") " for " cmd ": "
                     (str/join ", " (map #(str "--" (name %)) unknown)))))
        (when (or extra (and tok (not= "inspect" cmd)))
          (fail (str "unexpected argument: " (or extra tok)))))
      (check-date! "--today" (:today opts))
      (case cmd
        "keygen" (let [{:keys [kid file public]} (keygen! opts)]
                   (println "private key written to" file "(keep it secret, keep a backup)")
                   (println "public key for the app:")
                   (println (str "  :public-keys {\"" kid "\" \"" public "\"}")))
        "issue" (println (issue opts))
        "inspect" (do (when-not tok (fail "inspect needs the licence key"))
                      (prn (inspect tok opts)))
        "device-code" (do (when (str/blank? (:product opts)) (fail "--product is required"))
                          (println (or (device/device-code (:product opts) (device/machine-id))
                                       "no machine id readable on this computer")))
        (fail usage))
      0)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println "error:" (ex-message e))
        (when (:usage (ex-data e)) (println usage)))
      (if (:usage (ex-data e)) 2 1))))

(defn -main [& args]
  (let [code (run args)]
    (shutdown-agents)
    (System/exit code)))
