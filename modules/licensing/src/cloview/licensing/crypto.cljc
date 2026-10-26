;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.crypto
  "Host cryptography for offline licence keys (docs/LICENSING.md §4): UTF-8, base64url,
  SHA-256, HMAC-SHA256 and ECDSA P-256 with SHA-256 (\"ES256\").

  Byte values are the host's byte arrays (JVM byte[], CLR System.Byte[]); the token and
  trial namespaces only pass them between these functions.

  Formats (identical on every host):
    public key   65 bytes, uncompressed point 0x04 || X || Y   (base64url in app config)
    private key  32 bytes, the scalar d                         (issuing tool only)
    signature    64 bytes, IEEE P1363 r || s                    (base64url in the token)

  ES256 because both desktop runtimes verify it without extra packages: JDK 11+ (SunEC;
  a jlink image up to JDK 21 must include the jdk.crypto.ec module) and .NET (ECDsa on
  every OS). Ed25519 would need JDK 15+ and a NuGet package on .NET.
  Implemented on Clojure/JVM and ClojureCLR; on ClojureDart every function throws
  ex-info {:reason :unsupported} for now (Stage 3: package:pointycastle for ECDSA,
  package:crypto for SHA-256/HMAC)."
  (:require [clojure.string :as str]))

#?(:cljd
   (do
     (defn- unsupported [f]
       (throw (ex-info (str "cloview.licensing.crypto/" f " is not implemented on ClojureDart yet (Stage 3)")
                       {:reason :unsupported})))
     (defn utf8 [_] (unsupported "utf8"))
     (defn utf8-str [_] (unsupported "utf8-str"))
     (defn b64url-encode [_] (unsupported "b64url-encode"))
     (defn b64url-decode [_] (unsupported "b64url-decode"))
     (defn sha256 [_] (unsupported "sha256"))
     (defn hmac-sha256 [_ _] (unsupported "hmac-sha256"))
     (defn hex [_] (unsupported "hex"))
     (defn byte-count [_] (unsupported "byte-count"))
     (defn es256-verify [_ _ _] (unsupported "es256-verify"))
     (defn es256-keypair [] (unsupported "es256-keypair"))
     (defn es256-sign [_ _] (unsupported "es256-sign"))))

;; ---------------------------------------------------------------------------
;; Clojure/JVM

#?(:cljd nil
   :clj
   (do
     (defn utf8 ^bytes [^String s] (.getBytes s java.nio.charset.StandardCharsets/UTF_8))
     (defn utf8-str [^bytes b] (String. b java.nio.charset.StandardCharsets/UTF_8))
     (defn b64url-encode [^bytes b] (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) b))
     (defn b64url-decode
       "Bytes of a base64url string (padding optional), or nil when malformed."
       [s]
       (when (string? s)
         (try (.decode (java.util.Base64/getUrlDecoder) ^String s)
              (catch IllegalArgumentException _ nil))))
     (defn sha256 ^bytes [^bytes b] (.digest (java.security.MessageDigest/getInstance "SHA-256") b))
     (defn hmac-sha256 ^bytes [^bytes k ^bytes b]
       (let [m (javax.crypto.Mac/getInstance "HmacSHA256")]
         (.init m (javax.crypto.spec.SecretKeySpec. k "HmacSHA256"))
         (.doFinal m b)))
     (defn hex [^bytes b] (apply str (map #(format "%02x" (bit-and (long %) 0xff)) b)))
     (defn byte-count [^bytes b] (alength b))

     (def ^:private p256
       (delay (let [ps (java.security.AlgorithmParameters/getInstance "EC")]
                (.init ps (java.security.spec.ECGenParameterSpec. "secp256r1"))
                (.getParameterSpec ps java.security.spec.ECParameterSpec))))

     (defn- unsigned-bytes ^bytes [^bytes b from to] (java.util.Arrays/copyOfRange b (int from) (int to)))

     (defn- der-int
       "DER INTEGER of the unsigned big-endian bytes `b`."
       [^bytes b]
       (let [v (drop-while zero? (seq b))
             v (if (empty? v) [0] v)
             v (if (neg? (first v)) (cons 0 v) v)]   ; high bit set: prepend 0x00
         (concat [2 (count v)] v)))

     (defn- p1363->der ^bytes [^bytes sig]
       (let [body (concat (der-int (unsigned-bytes sig 0 32)) (der-int (unsigned-bytes sig 32 64)))]
         (byte-array (map unchecked-byte (concat [0x30 (count body)] body)))))

     (defn- der->p1363 ^bytes [^bytes der]
       (let [v (vec der)
             rlen (nth v 3)
             r (subvec v 4 (+ 4 rlen))
             slen (nth v (+ 5 rlen))
             s (subvec v (+ 6 rlen) (+ 6 rlen slen))
             pad32 (fn [x] (let [x (drop-while zero? x)] (concat (repeat (- 32 (count x)) 0) x)))]
         (byte-array (map unchecked-byte (concat (pad32 r) (pad32 s))))))

     (defn- public-key [^bytes pub]
       (when (and (= 65 (alength pub)) (= 4 (aget pub 0)))
         (let [x (BigInteger. 1 (unsigned-bytes pub 1 33))
               y (BigInteger. 1 (unsigned-bytes pub 33 65))]
           (.generatePublic (java.security.KeyFactory/getInstance "EC")
                            (java.security.spec.ECPublicKeySpec. (java.security.spec.ECPoint. x y) @p256)))))

     (defn es256-verify
       "True when `sig` (64-byte r || s) is a valid ES256 signature of `msg` by `pub`."
       [^bytes pub ^bytes msg ^bytes sig]
       (try
         (if-some [k (public-key pub)]
           (and (= 64 (alength sig))
                (let [v (java.security.Signature/getInstance "SHA256withECDSA")]
                  (.initVerify v ^java.security.PublicKey k)
                  (.update v msg)
                  (.verify v (p1363->der sig))))
           false)
         (catch java.security.GeneralSecurityException _ false)))

     (defn- fixed32 ^bytes [^BigInteger n]
       (let [b (.toByteArray n)
             b (if (> (alength b) 32) (unsigned-bytes b (- (alength b) 32) (alength b)) b)]
         (byte-array (map unchecked-byte (concat (repeat (- 32 (alength b)) 0) (seq b))))))

     (defn es256-keypair
       "{:public 65 bytes :private 32 bytes} — for the issuing tool and tests."
       []
       (let [g (java.security.KeyPairGenerator/getInstance "EC")
             _ (.initialize g (java.security.spec.ECGenParameterSpec. "secp256r1"))
             kp (.generateKeyPair g)
             ^java.security.interfaces.ECPublicKey pub (.getPublic kp)
             ^java.security.interfaces.ECPrivateKey priv (.getPrivate kp)
             w (.getW pub)]
         {:public (byte-array (map unchecked-byte (concat [4] (fixed32 (.getAffineX w)) (fixed32 (.getAffineY w)))))
          :private (fixed32 (.getS priv))}))

     (defn es256-sign
       "64-byte r || s signature of `msg` with keypair {:private 32 bytes} (the map
       es256-keypair returns; .NET also needs :public)."
       ^bytes [{:keys [^bytes private]} ^bytes msg]
       (let [k (.generatePrivate (java.security.KeyFactory/getInstance "EC")
                                 (java.security.spec.ECPrivateKeySpec. (BigInteger. 1 private) @p256))
             s (java.security.Signature/getInstance "SHA256withECDSA")]
         (.initSign s ^java.security.PrivateKey k)
         (.update s msg)
         (der->p1363 (.sign s))))))

;; ---------------------------------------------------------------------------
;; ClojureCLR

#?(:cljr
   (do
     (defn utf8 [s] (.GetBytes System.Text.Encoding/UTF8 ^String s))
     (defn utf8-str [b] (.GetString System.Text.Encoding/UTF8 b))
     (defn b64url-encode [b]
       (-> (System.Convert/ToBase64String b) (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" "")))
     (defn b64url-decode
       "Bytes of a base64url string (padding optional), or nil when malformed."
       [s]
       (when (and (string? s) (re-matches #"[A-Za-z0-9_-]*=*" s))
         (let [t (-> (str/replace s #"=+$" "") (str/replace "-" "+") (str/replace "_" "/"))
               t (str t (subs "===" 0 (mod (- 4 (mod (count t) 4)) 4)))]
           (try (System.Convert/FromBase64String t)
                (catch System.FormatException _ nil)))))
     (defn sha256 [b] (.ComputeHash (System.Security.Cryptography.SHA256/Create) b))
     (defn hmac-sha256 [k b] (.ComputeHash (System.Security.Cryptography.HMACSHA256. k) b))
     (defn hex [b] (.ToLowerInvariant (System.Convert/ToHexString b)))
     (defn byte-count [b] (.Length b))

     (defn- slice [b from n]
       (let [out (make-array System.Byte n)]
         (System.Array/Copy b (int from) out 0 (int n))
         out))

     (defn- p256-key
       "ECDsa for a 65-byte public point (and optionally a 32-byte private scalar)."
       [pub priv]
       ;; ECParameters and ECPoint are structs: fields are written on boxed copies by
       ;; reflection, which keeps the writes (verified on ClojureCLR 1.12.6)
       (let [p (System.Security.Cryptography.ECParameters.)
             pt (System.Security.Cryptography.ECPoint.)]
         (set! (.-X pt) (slice pub 1 32))
         (set! (.-Y pt) (slice pub 33 32))
         (set! (.-Curve p) System.Security.Cryptography.ECCurve+NamedCurves/nistP256)
         (set! (.-Q p) pt)
         (when priv (set! (.-D p) priv))
         (System.Security.Cryptography.ECDsa/Create p)))

     (defn es256-verify
       "True when `sig` (64-byte r || s) is a valid ES256 signature of `msg` by `pub`."
       [pub msg sig]
       (try
         (and (= 65 (.Length pub)) (= 4 (aget pub 0)) (= 64 (.Length sig))
              (.VerifyData (p256-key pub nil) msg sig System.Security.Cryptography.HashAlgorithmName/SHA256))
         (catch System.Exception _ false)))

     (defn es256-keypair
       "{:public 65 bytes :private 32 bytes} — for the issuing tool and tests."
       []
       (let [ec (System.Security.Cryptography.ECDsa/Create System.Security.Cryptography.ECCurve+NamedCurves/nistP256)
             ps (.ExportParameters ec true)
             q (.-Q ps)
             pub (make-array System.Byte 65)]
         (aset pub 0 (byte 4))
         (System.Array/Copy (.-X q) 0 pub 1 32)
         (System.Array/Copy (.-Y q) 0 pub 33 32)
         {:public pub :private (.-D ps)}))

     (defn es256-sign
       "64-byte r || s signature of `msg` with keypair {:public 65 bytes :private 32 bytes}."
       [{:keys [public private]} msg]
       (.SignData (p256-key public private) msg System.Security.Cryptography.HashAlgorithmName/SHA256))))
