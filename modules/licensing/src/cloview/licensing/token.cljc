;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.token
  "Offline licence keys (docs/LICENSING.md §3).

    CLV1.<payload>.<signature>

  payload    base64url (no padding) of the UTF-8 JSON object below, keys in ordinal
             order (cloview.fx.codec/write-json, so every host writes the same bytes)
  signature  base64url of the 64-byte ES256 signature (r || s) of the ASCII text
             \"CLV1.<payload>\" — the signed input includes the prefix, so a token can
             never be reinterpreted under another format

  Payload fields:
    v         1
    alg       \"ES256\"
    kid       key id: selects the public key embedded in the app (key rotation)
    id        licence id (support, refunds, revocation lists)
    product   must equal the app's product id
    features  [\"pro/remove-watermark\" ...] or [\"*\"] for everything
    licensee  display name (optional)
    device    \"D1-XXXX-XXXX-XXXX-XXXX\" binds the key to one machine (optional)
    issued    \"YYYY-MM-DD\"
    expires   \"YYYY-MM-DD\", last valid day (optional: perpetual)

  The seller signs with a private key that never ships; the app verifies with the
  public key. Characters outside the key's alphabet in a pasted key (line wraps,
  spaces, quotes) are ignored, the same way on every host.

  A token is not a unique string: other encodings of the same signature also verify.
  Identify licences by the payload `id`, never by the token text."
  (:require [clojure.string :as str]
            [cloview.fx.codec :as codec]
            [cloview.licensing.crypto :as crypto]))

(def prefix "CLV1")

(defn- encode-payload [payload]
  (crypto/b64url-encode (crypto/utf8 (codec/write-json payload))))

(defn sign
  "A token for `payload` (map with the fields above; :v and :alg are filled in), signed
  with `keypair` ({:public :private} from cloview.licensing.crypto/es256-keypair)."
  [payload keypair]
  (let [input (str prefix "." (encode-payload (merge {:v 1 :alg "ES256"} payload)))
        sig (crypto/es256-sign keypair (crypto/utf8 input))]
    (str input "." (crypto/b64url-encode sig))))

(def ^:private token-re
  ;; one strict shape on every host (str/split drops trailing empty parts on the JVM but
  ;; not on Dart, so "CLV1.p.s." must not depend on it)
  #"CLV1\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)")

(def max-length
  "Longest accepted key, in characters (real keys are a few hundred)."
  4096)

(defn normalize
  "`token` without the characters that cannot be part of a key (line wraps, spaces,
  quotes...). An explicit character class, because \\s means different things on the
  JVM, .NET and Dart."
  [token]
  (str/replace (str token) #"[^A-Za-z0-9_.-]" ""))

(defn- canonical-b64url
  "Bytes of `part` when it is the canonical base64url encoding of them (no padding, no
  stray bits in the last character), else nil."
  [part]
  (when-some [b (crypto/b64url-decode part)]
    (when (= part (crypto/b64url-encode b)) b)))

(defn parse
  "{:payload map :input signed-text :signature bytes} for a token, or {:error :malformed}."
  [token]
  (let [t (normalize token)]
    (or (when (<= (count t) max-length)
          (when-some [[_ payload-part sig-part] (re-matches token-re t)]
            (let [payload-bytes (canonical-b64url payload-part)
                  sig (canonical-b64url sig-part)
                  ;; a payload is a flat object (features is the only array); the depth
                  ;; limit also keeps a crafted key from exhausting the stack before the
                  ;; signature is checked
                  payload (when payload-bytes
                            (try (codec/read-json (crypto/utf8-str payload-bytes) {:keywordize? true :max-depth 4})
                                 (catch #?(:cljd dynamic :clj Throwable :cljr System.Exception) _ nil)))]
              (when (and (map? payload) sig)
                {:payload payload :input (str prefix "." payload-part) :signature sig}))))
        {:error :malformed})))

(defn verify
  "Check `token` for this app. Options:
    :product      the app's product id
    :public-keys  {kid public-key} (base64url of the 65-byte point, or bytes)
    :device-code  this machine's code (cloview.licensing.device/device-code)
    :today        \"YYYY-MM-DD\"
  Returns {:valid? true :license payload} or {:valid? false :reason r} with r one of
  :malformed :unsupported :unknown-key :bad-signature :wrong-product :wrong-device
  :expired (the last three also carry :license, for messages)."
  [token {:keys [product public-keys device-code today]}]
  (let [{:keys [payload input signature error]} (parse token)]
    (cond
      error {:valid? false :reason :malformed}
      (not (and (= 1 (:v payload)) (= "ES256" (:alg payload)))) {:valid? false :reason :unsupported}
      :else
      (let [k (get public-keys (:kid payload))
            pub (if (string? k) (crypto/b64url-decode k) k)]
        (cond
          (nil? pub) {:valid? false :reason :unknown-key}
          (not (crypto/es256-verify pub (crypto/utf8 input) signature)) {:valid? false :reason :bad-signature}
          (not= product (:product payload)) {:valid? false :reason :wrong-product :license payload}
          (and (some? (:device payload)) (not= device-code (:device payload)))
          {:valid? false :reason :wrong-device :license payload}
          (and (string? (:expires payload)) (string? today) (pos? (compare today (:expires payload))))
          {:valid? false :reason :expired :license payload}
          :else {:valid? true :license payload})))))

(defn unlocks?
  "True when `license` (a verified payload) unlocks `feature` (a string)."
  [license feature]
  (let [fs (set (:features license))]
    (or (contains? fs "*") (contains? fs feature))))
