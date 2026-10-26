;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.device
  "Device binding (docs/LICENSING.md §5): a short code that identifies this machine to
  the seller without revealing anything about it.

    device-code = \"D1-\" + the first 64 bits of HMAC-SHA256(\"cloview-device:\" + product,
                  machine-id), as four groups of 4 upper-case hex digits

  The raw machine id never leaves this namespace: it is keyed with the product id (as
  systemd's machine-id(5) asks), so two products see unrelated codes and the code
  cannot be turned back into the id. Sources:
    Windows  HKLM\\SOFTWARE\\Microsoft\\Cryptography\\MachineGuid (64-bit view; reset by
             Sysprep /generalize, copied by images cloned without it)
    macOS    IOPlatformUUID (ioreg)
    Linux    /etc/machine-id, else /var/lib/dbus/machine-id
  Implemented on Clojure/JVM and ClojureCLR; nil on ClojureDart for now (Stage 3:
  Android/iOS need vendor-scoped ids from platform channels, never hardware ids)."
  (:require [clojure.string :as str]
            [cloview.core.text :as text]
            [cloview.licensing.crypto :as crypto]))

(defn- clean [s]
  (when (string? s)
    (let [t (str/trim s)]
      (when-not (str/blank? t) t))))

#?(:cljd nil
   :clj
   (do
     (defn- run [cmd]
       (try
         (let [p (.start (doto (ProcessBuilder. ^java.util.List cmd) (.redirectErrorStream true)))
               out (slurp (.getInputStream p))]
           (.waitFor p)
           out)
         (catch Exception _ nil)))

     (defn- read-file [path]
       (let [f (java.io.File. ^String path)]
         (when (.isFile f) (try (slurp f) (catch Exception _ nil)))))))

#?(:cljr
   (do
     (defn- run [cmd]
       (try
         (let [psi (System.Diagnostics.ProcessStartInfo. (first cmd))]
           (doseq [a (rest cmd)] (.Add (.ArgumentList psi) a))
           (.set_UseShellExecute psi false)
           (.set_RedirectStandardOutput psi true)
           (let [p (System.Diagnostics.Process/Start psi)
                 out (.ReadToEnd (.StandardOutput p))]
             (.WaitForExit p)
             out))
         (catch System.Exception _ nil)))

     (defn- read-file [path]
       (when (System.IO.File/Exists path)
         (try (System.IO.File/ReadAllText path) (catch System.Exception _ nil))))))

(defn machine-id
  "This machine's id, or nil when it cannot be read (then device-bound keys cannot be
  activated; unbound keys and trials still work)."
  []
  #?(:cljd nil
     :default
     (let [os (text/ascii-lower (str #?(:clj (System/getProperty "os.name")
                                        ;; Environment.OSVersion says "Unix" on Linux and macOS
                                        :cljr (cond (System.OperatingSystem/IsWindows) "windows"
                                                    (System.OperatingSystem/IsMacOS) "macos"
                                                    :else "linux"))))]
       (cond
         (str/includes? os "win")
         ;; reg.exe on both runtimes: no registry assembly to load on ClojureCLR, and
         ;; /reg:64 reads the 64-bit view from a 32-bit process too
         (some->> (run ["reg" "query" "HKLM\\SOFTWARE\\Microsoft\\Cryptography" "/v" "MachineGuid" "/reg:64"])
                  (re-find #"MachineGuid\s+REG_SZ\s+(\S+)") second clean)
         (or (str/includes? os "mac") (str/includes? os "darwin"))
         (some->> (run ["ioreg" "-rd1" "-c" "IOPlatformExpertDevice"])
                  (re-find #"\"IOPlatformUUID\"\s*=\s*\"([^\"]+)\"") second clean)
         :else
         (or (clean (read-file "/etc/machine-id")) (clean (read-file "/var/lib/dbus/machine-id")))))))

(defn device-code
  "The device code of `machine-id` for `product` (\"D1-4F2A-9C11-77B0-1E3D\"), or nil."
  [product machine-id]
  (when (and (string? product) (string? machine-id))
    (let [h (crypto/hex (crypto/hmac-sha256 (crypto/utf8 (str "cloview-device:" product)) (crypto/utf8 machine-id)))
          d (text/ascii-upper (subs h 0 16))]
      (str "D1-" (subs d 0 4) "-" (subs d 4 8) "-" (subs d 8 12) "-" (subs d 12 16)))))
