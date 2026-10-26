;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.licensing-test
  "cloview.licensing on Clojure/JVM and ClojureCLR: digests against published vectors,
  ES256 keys signed on one runtime and verified on the other, token reasons, device
  codes, the trial seal, gate decisions, and gated events through a real store with an
  in-memory :storage/* (EDN round trip, like the desktop file store)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloview.core.store :as st]
            [cloview.core.tree :as t]
            [cloview.licensing :as licensing]
            [cloview.licensing.crypto :as crypto]
            [cloview.licensing.device :as device]
            [cloview.licensing.gate :as gate]
            [cloview.licensing.token :as token]
            [cloview.licensing.trial :as trial]
            [cloview.licensing.ui :as ui]))

;; A TEST key pair (never use it for a product).
(def test-public "BAUacvnZwe-g-3pushEOj8gBpYpxc6QAsblFn1xojTsI2PsWf711EpA8QtMN-NWQXbT5YtSXt2-k9RTDPNSOXsk")
(def test-private "ZSGC-pbFDkRQmtL26Wv7Hayc7M8UhmQTqDefkx0P0tU")
(def other-public "BJ1kver9Age_T6ef-repHUHiGmBfyNV8r-3HhWHyAG2NBKG789tk3quJqDdkYYA6qsFBgJCSxacW92CPTimia3w")
(def keypair {:public (crypto/b64url-decode test-public) :private (crypto/b64url-decode test-private)})
(def public-keys {"test-1" test-public})

;; Signed on Clojure/JVM: features [pro/remove-watermark], not bound, perpetual.
(def jvm-token
  (str "CLV1.eyJhbGciOiJFUzI1NiIsImZlYXR1cmVzIjpbInByby9yZW1vdmUtd2F0ZXJtYXJrIl0sImlkIjoiTC0wMDAxIiwia"
       "XNzdWVkIjoiMjAyNi0xMC0yNSIsImtpZCI6InRlc3QtMSIsImxpY2Vuc2VlIjoiQWRhIExvdmVsYWNlIiwicHJvZHVjdCI6In"
       "dtYXJrIiwidiI6MX0.2CFQNpoeV3KNGiS7Rtuo6t7yecu5KfZbyyOwtBk9NIM_gGEProHoxhp6LWs0ndVeOH4V8h10fqAY8Hrm"
       "x2dCqA"))

;; Signed on ClojureCLR 1.12.6: features [*], bound to test-machine-1, expires 2027-10-24.
(def clr-token
  (str "CLV1.eyJhbGciOiJFUzI1NiIsImRldmljZSI6IkQxLUE0NTctN0E5Qi04QjJELUEyRDQiLCJleHBpcmVzIjoiMjAyNy0xM"
       "C0yNCIsImZlYXR1cmVzIjpbIioiXSwiaWQiOiJMLTAwMDIiLCJpc3N1ZWQiOiIyMDI2LTEwLTI1Iiwia2lkIjoidGVzdC0xIi"
       "wibGljZW5zZWUiOiJHcmFjZSBIb3BwZXIiLCJwcm9kdWN0Ijoid21hcmsiLCJ2IjoxfQ.HYwg7bRnjkFR-DhcFjOnfvD1rOAzD"
       "iOIpnzX9zPQ39Mzg3vIfG-gVd1Gd8sIsql-0q6smqH8xXXxhhfBjeAkcQ"))

(def machine "test-machine-1")
(def machine-code "D1-A457-7A9B-8B2D-A2D4")

(defn- verify-opts [m]
  (merge {:product "wmark" :public-keys public-keys :device-code machine-code :today "2026-10-25"} m))

;; ---------------------------------------------------------------------------
;; crypto

(deftest digests
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (crypto/hex (crypto/sha256 (crypto/utf8 "abc")))))
  ;; RFC 4231, test case 2
  (is (= "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
         (crypto/hex (crypto/hmac-sha256 (crypto/utf8 "Jefe") (crypto/utf8 "what do ya want for nothing?"))))))

(deftest base64url
  (is (= "YW55IGNhcm5hbCBwbGVhcw" (crypto/b64url-encode (crypto/utf8 "any carnal pleas"))))
  (is (= "any carnal pleas" (crypto/utf8-str (crypto/b64url-decode "YW55IGNhcm5hbCBwbGVhcw"))))
  (is (= "any carnal pleas" (crypto/utf8-str (crypto/b64url-decode "YW55IGNhcm5hbCBwbGVhcw=="))))
  (is (= "-_8" (crypto/b64url-encode (crypto/b64url-decode "-_8"))) "URL alphabet")
  (is (= "éß€" (crypto/utf8-str (crypto/utf8 "éß€"))))
  (is (= 7 (crypto/byte-count (crypto/utf8 "éß€"))) "2 + 2 + 3 UTF-8 bytes")
  (is (nil? (crypto/b64url-decode "not base64!")))
  (is (nil? (crypto/b64url-decode nil))))

(deftest es256
  (let [msg (crypto/utf8 "cloview")
        sig (crypto/es256-sign keypair msg)]
    (is (= 64 (crypto/byte-count sig)))
    (is (true? (crypto/es256-verify (:public keypair) msg sig)))
    (is (false? (crypto/es256-verify (:public keypair) (crypto/utf8 "cloview!") sig)))
    (is (false? (crypto/es256-verify (crypto/b64url-decode other-public) msg sig)))
    (is (false? (crypto/es256-verify (:public keypair) msg (crypto/utf8 "short"))))
    (is (false? (crypto/es256-verify (crypto/utf8 "not a point") msg sig)))
    (is (false? (crypto/es256-verify (:public keypair) msg (crypto/b64url-decode (apply str (repeat 86 "A")))))
        "r = s = 0"))
  (testing "fresh key pairs"
    (let [kp (crypto/es256-keypair)
          msg (crypto/utf8 "x")]
      (is (= 65 (crypto/byte-count (:public kp))))
      (is (= 32 (crypto/byte-count (:private kp))))
      (is (true? (crypto/es256-verify (:public kp) msg (crypto/es256-sign kp msg))))))
  (testing "many signatures (r or s with leading zero bytes or the high bit set)"
    (is (every? (fn [i]
                  (let [m (crypto/utf8 (str "message " i))]
                    (crypto/es256-verify (:public keypair) m (crypto/es256-sign keypair m))))
                (range 40)))))

#?(:cljd nil
   :clj
   (deftest der-p1363-conversion
     ;; the JVM speaks DER; tokens carry IEEE P1363 r || s
     (let [p->d @#'crypto/p1363->der
           d->p @#'crypto/der->p1363
           sig (byte-array (map unchecked-byte (concat [0 0 0x80] (repeat 29 1) [0x7f] (repeat 31 0xff))))
           zeros (byte-array 64)]
       (is (= (seq sig) (seq (d->p (p->d sig)))))
       (is (= (seq zeros) (seq (d->p (p->d zeros))))))))

;; ---------------------------------------------------------------------------
;; tokens

(deftest token-verification
  (testing "a key signed on Clojure/JVM"
    (let [r (token/verify jvm-token (verify-opts {}))]
      (is (true? (:valid? r)))
      (is (= ["pro/remove-watermark"] (get-in r [:license :features])))
      (is (= "Ada Lovelace" (get-in r [:license :licensee])))))
  (testing "a key signed on ClojureCLR (device-bound, expiring)"
    (is (true? (:valid? (token/verify clr-token (verify-opts {})))))
    (is (= :wrong-device (:reason (token/verify clr-token (verify-opts {:device-code "D1-0000-0000-0000-0000"})))))
    (is (= :wrong-device (:reason (token/verify clr-token (verify-opts {:device-code nil})))))
    (is (true? (:valid? (token/verify clr-token (verify-opts {:today "2027-10-24"})))) "the expiry is the last valid day")
    (is (= :expired (:reason (token/verify clr-token (verify-opts {:today "2027-10-25"}))))))
  (testing "line wraps and spaces in a pasted key are ignored"
    (is (true? (:valid? (token/verify (str "  " (subs jvm-token 0 40) "\n" (subs jvm-token 40) " \r\n")
                                      (verify-opts {}))))))
  (testing "so are quotes and Unicode spaces, the same way on every host"
    (is (true? (:valid? (token/verify (str "\u00A0\"" (subs jvm-token 0 30) "\u2028" (subs jvm-token 30) "\"\u200B")
                                      (verify-opts {})))))
    (is (= jvm-token (token/normalize (str "<" jvm-token ">\u00A0")))))
  (testing "public keys may be given as bytes"
    (is (true? (:valid? (token/verify jvm-token (verify-opts {:public-keys {"test-1" (:public keypair)}})))))))

(deftest token-reasons
  (is (= :malformed (:reason (token/verify "hello" (verify-opts {})))))
  (is (= :malformed (:reason (token/verify nil (verify-opts {})))))
  (is (= :malformed (:reason (token/verify "CLV1.e30" (verify-opts {})))) "two parts")
  (is (= :malformed (:reason (token/verify (str jvm-token ".") (verify-opts {})))) "a trailing dot")
  (is (= :malformed (:reason (token/verify (str/replace jvm-token #"\.[^.]+$" ".") (verify-opts {}))))
      "an empty signature")
  (is (= :malformed (:reason (token/verify (str/replace jvm-token "CLV1." "CLV1..") (verify-opts {})))))
  (is (= :malformed (:reason (token/verify (str/replace jvm-token "CLV1." "CLV2.") (verify-opts {})))))
  (is (= :malformed (:reason (token/verify (str "CLV1." (crypto/b64url-encode (crypto/utf8 "[1,2]")) ".AAAA")
                                           (verify-opts {}))))
      "the payload must be a JSON object")
  (is (= :malformed (:reason (token/verify (str (subs jvm-token 0 (dec (count jvm-token))) "B") (verify-opts {}))))
      "a non-canonical last character (same bytes, other unused bits) is not another spelling of the key")
  (is (= :malformed (:reason (token/verify (str jvm-token (apply str (repeat token/max-length "A"))) (verify-opts {}))))
      "longer than any real key")
  (is (= :malformed (:reason (token/verify (str "CLV1." (crypto/b64url-encode
                                                         (crypto/utf8 (str (apply str (repeat 1000 "["))
                                                                           (apply str (repeat 1000 "]")))))
                                                ".AAAA")
                                           (verify-opts {}))))
      "a deeply nested payload is refused before it can exhaust the stack (fatal on .NET)")
  (is (= :unknown-key (:reason (token/verify jvm-token (verify-opts {:public-keys {"other" test-public}})))))
  (is (= :bad-signature (:reason (token/verify jvm-token (verify-opts {:public-keys {"test-1" other-public}})))))
  (let [[_ payload _] (str/split jvm-token #"\.")
        [_ _ sig] (str/split clr-token #"\.")]
    (is (= :bad-signature (:reason (token/verify (str "CLV1." payload "." sig) (verify-opts {}))))
        "a signature of another payload"))
  (is (= :wrong-product (:reason (token/verify jvm-token (verify-opts {:product "other"})))))
  (is (= :unsupported (:reason (token/verify (token/sign {:v 2 :kid "test-1" :product "wmark"} keypair) (verify-opts {})))))
  (is (= :unsupported (:reason (token/verify (token/sign {:alg "RS256" :kid "test-1" :product "wmark"} keypair)
                                             (verify-opts {}))))))

(deftest token-signing
  (let [tok (token/sign {:kid "test-1" :id "L-9" :product "wmark" :features ["a" "b"] :issued "2026-10-25"} keypair)
        r (token/verify tok (verify-opts {}))]
    (is (str/starts-with? tok "CLV1."))
    (is (true? (:valid? r)))
    (is (= {:v 1 :alg "ES256" :kid "test-1" :id "L-9" :product "wmark" :features ["a" "b"] :issued "2026-10-25"}
           (:license r))))
  (is (token/unlocks? {:features ["*"]} "anything"))
  (is (token/unlocks? {:features ["pro/a"]} "pro/a"))
  (is (not (token/unlocks? {:features ["pro/a"]} "pro/b")))
  (is (not (token/unlocks? {} "pro/a"))))

;; ---------------------------------------------------------------------------
;; device, trial, gate, dates

(deftest device-codes
  (is (= machine-code (device/device-code "wmark" machine)))
  (is (re-matches #"D1-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}" (device/device-code "other" machine)))
  (is (not= machine-code (device/device-code "other" machine)) "codes are per product")
  (is (not= machine-code (device/device-code "wmark" "test-machine-2")))
  (is (nil? (device/device-code "wmark" nil)))
  (let [id (device/machine-id)]
    (is (or (nil? id) (and (string? id) (not (str/blank? id)))))))

(deftest trial-seal
  (let [s (-> trial/empty-state (trial/record-use "pro/a") (trial/record-use "pro/a") (trial/record-use "pro/b"))
        sealed (trial/seal s "secret" machine)]
    (is (= 2 (trial/uses s "pro/a")))
    (is (= 0 (trial/uses s "pro/c")))
    (is (= {:state s :ok? true} (trial/check sealed "secret" machine)))
    (is (= {:state s :ok? true} (trial/check (edn/read-string (pr-str sealed)) "secret" machine)) "EDN round trip")
    (is (false? (:ok? (trial/check (assoc-in sealed [:uses "pro/a"] 0) "secret" machine))) "edited counts")
    (is (false? (:ok? (trial/check sealed "secret" "another-machine"))) "copied to another machine")
    (is (false? (:ok? (trial/check sealed "other-secret" machine))))
    (is (false? (:ok? (trial/check (dissoc sealed :mac) "secret" machine))))
    (is (false? (:ok? (trial/check "garbage" "secret" machine))))
    (is (false? (:ok? (trial/check nil "secret" machine))) "nothing is not a sealed state (first runs: see below)")
    (is (false? (:ok? (trial/check (trial/seal trial/void-state "secret" machine) "secret" machine))) "void stays void")
    (testing "only the exact shape is accepted: edits that keep the JSON (and the seal) the same"
      (let [void (trial/seal trial/void-state "secret" machine)]
        (is (false? (:ok? (trial/check (-> sealed (update :uses dissoc "pro/a") (assoc-in [:uses :pro/a] 2)) "secret" machine)))
            "a feature id turned into a keyword (would read as 0 uses)")
        (is (false? (:ok? (trial/check (-> sealed (update :uses dissoc "pro/a") (assoc-in [:uses 'pro/a] 2)) "secret" machine)))
            "or a symbol")
        (is (false? (:ok? (trial/check (-> void (dissoc :void) (assoc "void" true)) "secret" machine)))
            "the void flag turned into a string key (would read as a fresh trial)")
        (is (true? (trial/well-formed? sealed)))
        (doseq [[label bad] [["an extra key" (assoc sealed :extra 1)]
                             ["a negative count" (assoc-in sealed [:uses "pro/a"] -1)]
                             ["a non-integer count" (assoc-in sealed [:uses "pro/a"] 2.5)]
                             ["a string version" (assoc sealed :v "1")]
                             ["void false" (assoc sealed :void false)]
                             ["uses not a map" (assoc sealed :uses [])]]]
          (is (not (trial/well-formed? bad)) label)
          (is (false? (:ok? (trial/check bad "secret" machine))) label))))))

(deftest gate-decisions
  (let [trial5 {:mode :trial-count :max-uses 5}
        hard {:mode :hard}
        base {:loaded? true :trial trial/empty-state :trial-ok? true}
        used (fn [n] (assoc base :trial (assoc-in trial/empty-state [:uses "pro/x"] n)))
        licensed (assoc base :license {:features ["pro/x"]})]
    (is (= "pro/x" (gate/feature-id :pro/x)))
    (is (= "plain" (gate/feature-id :plain)))
    (is (= "pro/x" (gate/feature-id "pro/x")))
    (is (= {:allow? true :consume? false} (gate/decide licensed :pro/x hard)))
    (is (= {:allow? true :consume? false} (gate/decide licensed :pro/x trial5)))
    (is (= {:allow? false :reason :locked} (gate/decide base :pro/x hard)))
    (is (= {:allow? true :consume? true :remaining 4} (gate/decide base :pro/x trial5)))
    (is (= {:allow? true :consume? true :remaining 0} (gate/decide (used 4) :pro/x trial5)))
    (is (= {:allow? false :reason :trial-exhausted} (gate/decide (used 5) :pro/x trial5)))
    (is (= {:allow? false :reason :tampered} (gate/decide (assoc base :trial-ok? false) :pro/x trial5)))
    (is (= {:allow? false :reason :storage-failed} (gate/decide (assoc base :save-failed? true) :pro/x trial5)))
    (is (= {:allow? true :consume? false} (gate/decide (assoc licensed :save-failed? true) :pro/x trial5))
        "a licence does not depend on saving trial counts")
    (is (= 5 (gate/available base :pro/x trial5)))
    (is (= 0 (gate/available (assoc base :trial-ok? false) :pro/x trial5)))
    (is (= 0 (gate/available (assoc base :save-failed? true) :pro/x trial5)))
    (is (nil? (gate/available (dissoc base :loaded?) :pro/x trial5)) "unknown while loading")
    (is (nil? (gate/available base :pro/x hard)))
    (is (= {:allow? false :reason :loading} (gate/decide {} :pro/x trial5)))
    (is (= {:allow? false :reason :loading} (gate/decide nil :pro/x hard)))
    (is (= {:allow? false :reason :unknown-gate} (gate/decide base :pro/x {:mode :weekly})))
    (is (= 5 (gate/remaining base :pro/x trial5)))
    (is (= 0 (gate/remaining (used 7) :pro/x trial5)))
    (is (nil? (gate/remaining base :pro/x hard)))
    (is (true? (gate/licensed? licensed "pro/x")))
    (is (false? (gate/licensed? base :pro/x)))))

(deftest dates
  (is (= "1970-01-01" (licensing/date-of-ms 0)))
  (is (= "2000-02-29" (licensing/date-of-ms 951782400000)))
  (is (= "2026-10-25" (licensing/date-of-ms 1792886400000)))
  (is (= "2026-10-25" (licensing/date-of-ms 1792972799000)))
  (is (re-matches #"\d{4}-\d{2}-\d{2}" (licensing/today)))
  (is (every? (fn [d]
                (= #?(:clj (str (java.time.LocalDate/ofEpochDay d))
                      :cljr (.ToString (.AddDays (System.DateTime. 1970 1 1 0 0 0 System.DateTimeKind/Utc) (double d))
                                       "yyyy-MM-dd" System.Globalization.CultureInfo/InvariantCulture))
                   (licensing/date-of-ms (* d 86400000))))
              (range 0 200000 37))
      "agrees with the host calendar up to the year 2517"))

;; ---------------------------------------------------------------------------
;; gated events through a store

(def ^:private disk (atom {}))

;; storage behaviour of the current boot: answer reads later (async storage, as on
;; Flutter), and fail every write (read-only storage)
(def ^:private storage-opts (atom {}))
(def ^:private waiting-reads (atom []))

(defn- fake-storage!
  "In-memory :storage/* that round-trips values through EDN like the desktop store."
  []
  (st/reg-fx :storage/put (fn [v {:keys [dispatch!]}]
                            (doseq [p (if (map? v) [v] v)]
                              (if (:fail-writes? @storage-opts)
                                (when-some [ev (:on-failure p)] (dispatch! (conj ev {:error "read-only"})))
                                (swap! disk assoc (:key p) (edn/read-string (pr-str (:value p))))))))
  (st/reg-fx :storage/get (fn [{:keys [key on-loaded]} {:keys [dispatch!]}]
                            (let [answer (fn [] (dispatch! (conj on-loaded (get @disk key))))]
                              (if (:defer-reads? @storage-opts) (swap! waiting-reads conj answer) (answer))))))

(defn- answer-reads!
  "Deliver the reads held by a :defer-reads? boot."
  []
  (let [fs @waiting-reads]
    (reset! waiting-reads [])
    (doseq [f fs] (f))))

(def ^:private app-handlers
  {:events-fx
   {:pro/remove-watermark
    (licensing/wrap-gated-event :pro/remove-watermark {:mode :trial-count :max-uses 2}
                                (fn [{:keys [db]} [_ path]] {:db (update db :removed (fnil conj []) path)}))
    :pro/export
    (licensing/wrap-gated-event :pro/export {:mode :hard}
                                (fn [{:keys [db]} _] {:db (update db :exports (fnil inc 0))}))}})

(defn- boot!
  "A fresh app start on `mid` against the shared fake disk; returns the store. Options:
  :defer-reads? :fail-writes? (see fake-storage!), :public-keys."
  ([] (boot! machine))
  ([mid] (boot! mid {}))
  ([mid opts]
   (reset! storage-opts opts)
   (reset! waiting-reads [])
   (licensing/install! {:product "wmark" :public-keys (or (:public-keys opts) public-keys)
                        :trial-secret "wmark-test" :machine-id mid})
   (fake-storage!)
   (st/register-handlers! app-handlers)
   (st/create-store {:db {}})))

(def ^:private trial2 {:mode :trial-count :max-uses 2})

(defn- badge-text [db] (get-in (t/normalize (ui/trial-badge db :pro/remove-watermark trial2)) [:props :text]))

(defn- lic [store] (:cloview/licensing (st/db store)))

(defn- stored [k] (get-in @disk [licensing/storage-key k]))

(deftest gated-events-through-a-store
  (reset! disk {})
  (testing "events before the stored data is read are held, then run in order"
    (let [s (boot! machine {:defer-reads? true})]
      (st/dispatch! s [:licensing/init])
      (st/dispatch! s [:pro/remove-watermark "a.png"])
      (st/dispatch! s [:pro/export])
      (is (= :loading (get-in (lic s) [:prompt :reason])))
      (is (= {:licensed? false :remaining nil :reason :loading}
             (licensing/status (st/db s) :pro/remove-watermark trial2)))
      (is (nil? (ui/trial-badge (st/db s) :pro/remove-watermark trial2)) "no badge while loading")
      (st/dispatch! s [:licensing/dismiss])
      (is (nil? (:removed (st/db s))))
      (answer-reads!)
      (is (= ["a.png"] (:removed (st/db s))) "closing the dialog did not drop the held events")
      (is (= :locked (get-in (lic s) [:prompt :reason])) "the second held event ran after the first")
      (is (= [] (:pending (lic s))))
      (is (= 1 (trial/uses (:trial (lic s)) "pro/remove-watermark")))))
  (testing "trial uses are counted, persisted and enforced"
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (is (= machine-code (:device-code (lic s))))
      (is (true? (:trial-ok? (lic s))))
      (is (= {:licensed? false :remaining 1 :reason nil} (licensing/status (st/db s) :pro/remove-watermark trial2)))
      (is (= "1 free use left" (badge-text (st/db s))))
      (st/dispatch! s [:pro/remove-watermark "b.png"])
      (is (= ["b.png"] (:removed (st/db s))))
      (is (= 2 (get-in (stored :trial) [:uses "pro/remove-watermark"])))
      (is (string? (:mac (stored :trial))))
      (st/dispatch! s [:pro/remove-watermark "c.png"])
      (is (= ["b.png"] (:removed (st/db s))) "the third use is refused")
      (is (= {:feature "pro/remove-watermark" :reason :trial-exhausted}
             (select-keys (:prompt (lic s)) [:feature :reason])))
      (testing "the dialog"
        (let [tree (t/normalize (licensing/modal (st/db s) {:product-name "Watermark Pro" :on-buy [:app/buy]}))
              text (fn [id] (get-in (t/find-by-test-id tree id) [:props :text]))]
          (is (empty? (t/problems tree)))
          (is (= :ui/modal (:tag tree)))
          (is (true? (get-in tree [:props :open?])))
          (is (= "Unlock Watermark Pro" (get-in tree [:props :title])))
          (is (= "You have used all free tries of this feature." (text "license-reason")))
          (is (= (str "Device code: " machine-code) (text "license-device")))
          (is (= [:app/buy] (get-in (t/find-by-test-id tree "license-buy") [:props :on-press])))
          (is (true? (get-in (t/find-by-test-id tree "license-activate") [:props :disabled?])))
          (is (= tree (t/normalize [:cloview/license-modal {:state (lic s) :product-name "Watermark Pro"
                                                            :on-buy [:app/buy]}]))
              "the registered composite renders the same dialog")))
      (testing "a wrong key is explained; a right one unlocks and resumes the refused action"
        (st/dispatch! s [:licensing/key-input "CLV1.nonsense"])
        (st/dispatch! s [:licensing/activate])
        (is (= "That is not a licence key. Paste the whole key, starting with CLV1." (:error (lic s))))
        (is (nil? (:license (lic s))))
        (st/dispatch! s [:licensing/key-input jvm-token])
        (is (nil? (:error (lic s))))
        (st/dispatch! s [:licensing/activate])
        (is (= "L-0001" (get-in (lic s) [:license :id])))
        (is (nil? (:prompt (lic s))))
        (is (= ["b.png" "c.png"] (:removed (st/db s))))
        (is (= jvm-token (stored :token))))
      (testing "licensed runs do not consume trial uses"
        (st/dispatch! s [:pro/remove-watermark "d.png"])
        (is (= ["b.png" "c.png" "d.png"] (:removed (st/db s))))
        (is (= 2 (trial/uses (:trial (lic s)) "pro/remove-watermark"))))
      (testing "a licence that does not include a feature keeps its gate closed"
        (st/dispatch! s [:pro/export])
        (is (= :locked (get-in (lic s) [:prompt :reason])))
        (st/dispatch! s [:licensing/key-input jvm-token])
        (st/dispatch! s [:licensing/activate])
        (is (= "Your licence is active, but it does not include this feature." (:error (lic s))))
        (is (some? (:prompt (lic s))))
        (is (nil? (:exports (st/db s))))
        (st/dispatch! s [:licensing/dismiss])
        (is (nil? (:prompt (lic s))))
        (is (false? (get-in (t/normalize (licensing/modal (st/db s) {})) [:props :open?]))))))
  (testing "the licence survives a restart"
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (is (= "L-0001" (get-in (lic s) [:license :id])))
      (st/dispatch! s [:pro/remove-watermark "e.png"])
      (is (= ["e.png"] (:removed (st/db s))))))
  (testing "deactivation"
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (st/dispatch! s [:licensing/deactivate])
      (is (nil? (stored :token)))
      (st/dispatch! s [:pro/remove-watermark "f.png"])
      (is (= :trial-exhausted (get-in (lic s) [:prompt :reason]))))))

(deftest tampered-trial-fails-closed
  (reset! disk {})
  (let [s (boot!)]
    (st/dispatch! s [:licensing/init])
    (st/dispatch! s [:pro/remove-watermark "a.png"])
    (st/dispatch! s [:pro/remove-watermark "b.png"]))
  ;; "reset" the trial by editing the stored counts
  (swap! disk assoc-in [licensing/storage-key :trial :uses "pro/remove-watermark"] 0)
  (let [s (boot!)]
    (st/dispatch! s [:licensing/init])
    (is (false? (:trial-ok? (lic s))))
    (st/dispatch! s [:pro/remove-watermark "c.png"])
    (is (nil? (:removed (st/db s))))
    (is (= :tampered (get-in (lic s) [:prompt :reason])))
    (testing "activating and deactivating a licence does not launder the edited counts"
      (st/dispatch! s [:licensing/key-input jvm-token])
      (st/dispatch! s [:licensing/activate])
      (is (= ["c.png"] (:removed (st/db s))))
      (is (true? (:void (stored :trial))))
      (st/dispatch! s [:licensing/deactivate])))
  (let [s (boot!)]
    (st/dispatch! s [:licensing/init])
    (is (false? (:trial-ok? (lic s))))
    (st/dispatch! s [:pro/remove-watermark "d.png"])
    (is (= :tampered (get-in (lic s) [:prompt :reason])))))

(deftest device-binding-and-another-machine
  (reset! disk {})
  (let [bound (token/sign {:kid "test-1" :id "L-0003" :product "wmark" :features ["*"] :device machine-code
                           :issued "2026-10-25"} keypair)]
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (st/dispatch! s [:pro/remove-watermark "a.png"]))
    (testing "the trial state copied to another machine fails its check"
      (let [s (boot! "test-machine-2")]
        (st/dispatch! s [:licensing/init])
        (is (false? (:trial-ok? (lic s))))
        (testing "and a device-bound key only activates on its machine"
          (st/dispatch! s [:licensing/prompt])
          (is (= :manual (get-in (lic s) [:prompt :reason])))
          (st/dispatch! s [:licensing/key-input bound])
          (st/dispatch! s [:licensing/activate])
          (is (= "This licence key is bound to another device. Send the seller the device code shown below."
                 (:error (lic s)))))))
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (st/dispatch! s [:licensing/prompt])
      (st/dispatch! s [:licensing/key-input bound])
      (st/dispatch! s [:licensing/activate])
      (is (= "L-0003" (get-in (lic s) [:license :id])))
      (is (nil? (:prompt (lic s))))
      (st/dispatch! s [:pro/export])
      (is (= 1 (:exports (st/db s))) "\"*\" unlocks every feature"))))

(deftest stored-key-that-stopped-verifying
  (let [fresh-trial (trial/seal trial/empty-state "wmark-test" machine)
        expired (token/sign {:kid "test-1" :id "L-0004" :product "wmark" :features ["*"]
                             :issued "2019-01-01" :expires "2020-01-01"} keypair)
        rotated (token/sign {:kid "test-2" :id "L-0005" :product "wmark" :features ["*"]
                             :issued "2026-10-25"} keypair)]
    (testing "an expired key"
      (reset! disk {licensing/storage-key {:token expired :trial fresh-trial}})
      (let [s (boot!)]
        (st/dispatch! s [:licensing/init])
        (is (nil? (:license (lic s))))
        (is (= :expired (:stored-key-problem (lic s))))
        (st/dispatch! s [:pro/remove-watermark "a.png"])
        (is (= ["a.png"] (:removed (st/db s))) "the trial applies")
        (is (= 1 (get-in (stored :trial) [:uses "pro/remove-watermark"])))
        (is (= expired (stored :token)) "a save writes the key back as it was")))
    (testing "a key whose kid this version does not know comes back with an update that does"
      (reset! disk {licensing/storage-key {:token rotated :trial fresh-trial}})
      (let [s (boot!)]
        (st/dispatch! s [:licensing/init])
        (is (= :unknown-key (:stored-key-problem (lic s))))
        (st/dispatch! s [:pro/remove-watermark "a.png"])
        (is (= rotated (stored :token))))
      (let [s (boot! machine {:public-keys (assoc public-keys "test-2" test-public)})]
        (st/dispatch! s [:licensing/init])
        (is (= "L-0005" (get-in (lic s) [:license :id])))
        (is (nil? (:stored-key-problem (lic s))))))
    (testing "activating another key replaces it; deactivating removes it"
      (reset! disk {licensing/storage-key {:token expired :trial fresh-trial}})
      (let [s (boot!)]
        (st/dispatch! s [:licensing/init])
        (st/dispatch! s [:licensing/key-input (str " " jvm-token "\n")])
        (st/dispatch! s [:licensing/activate])
        (is (= jvm-token (stored :token)) "saved without the pasted whitespace")
        (is (nil? (:stored-key-problem (lic s))))
        (st/dispatch! s [:licensing/deactivate])
        (is (nil? (stored :token)))))))

(deftest gated-handler-effects-are-merged
  (reset! disk {})
  (let [s (boot!)]
    (st/dispatch! s [:licensing/init])
    (st/reg-event-fx :app/save-doc
                     (licensing/wrap-gated-event :pro/save {:mode :trial-count :max-uses 3}
                                                 (fn [_ _] {:storage/put {:key :doc :value 1} :dispatch [:app/saved]})))
    (let [fx (st/handle (st/db s) [:app/save-doc])]
      (is (= [:doc licensing/storage-key] (mapv :key (:storage/put fx))) "the app's own write runs first")
      (is (= [:app/saved] (:dispatch fx)))
      (is (= 1 (get-in fx [:db :cloview/licensing :trial :uses "pro/save"]))))))

(deftest trial-badges
  (let [db {:cloview/licensing {:loaded? true :trial (assoc-in trial/empty-state [:uses "pro/x"] 2) :trial-ok? true}}
        badge (fn [db gate] (get-in (t/normalize (ui/trial-badge db :pro/x gate)) [:props :text]))]
    (is (= "3 free uses left" (badge db {:mode :trial-count :max-uses 5})))
    (is (= "1 free use left" (badge db {:mode :trial-count :max-uses 3})))
    (is (= "No free uses left" (badge db {:mode :trial-count :max-uses 2})))
    (is (nil? (ui/trial-badge db :pro/x {:mode :hard})))
    (is (nil? (ui/trial-badge (assoc-in db [:cloview/licensing :license] {:features ["*"]}) :pro/x
                              {:mode :trial-count :max-uses 5})))))

(deftest a-handler-cannot-drop-the-licensing-state
  (reset! disk {})
  (let [s (boot!)
        all (token/sign {:kid "test-1" :id "L-0006" :product "wmark" :features ["*"] :issued "2026-10-25"} keypair)]
    (st/dispatch! s [:licensing/init])
    (st/reg-event-fx :app/new-doc (licensing/wrap-gated-event :pro/new {:mode :trial-count :max-uses 3}
                                                              (fn [_ _] {:db {:doc :blank}})))
    (st/dispatch! s [:app/new-doc])
    (is (= :blank (:doc (st/db s))))
    (is (true? (:loaded? (lic s))) "a handler that returns a fresh db keeps the licensing slice")
    (is (= 1 (trial/uses (:trial (lic s)) "pro/new")))
    (is (= 1 (get-in (stored :trial) [:uses "pro/new"])))
    (is (nil? (:void (stored :trial))) "and the save is not mistaken for a failed check")
    (st/dispatch! s [:licensing/key-input all])
    (st/dispatch! s [:licensing/activate])
    (st/dispatch! s [:app/new-doc])
    (is (= "L-0006" (get-in (lic s) [:license :id])) "licensed runs keep it too")
    (is (= all (stored :token)))))

(deftest stored-data-of-the-wrong-shape-fails-closed
  (doseq [[label value] [["a map without :trial" {:token nil}]
                         ["a map with :trial nil" {:trial nil}]
                         ["a string" "garbage"]
                         ["a vector" [1 2]]]]
    (reset! disk {licensing/storage-key value})
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (is (false? (:trial-ok? (lic s))) label)
      (st/dispatch! s [:pro/remove-watermark "x.png"])
      (is (= :tampered (get-in (lic s) [:prompt :reason])) label)
      (is (= "Free uses unavailable" (badge-text (st/db s))) label)
      (is (= {:licensed? false :remaining 0 :reason :tampered}
             (licensing/status (st/db s) :pro/remove-watermark trial2)) label)))
  (testing "a keyword feature id written into the store"
    (reset! disk {})
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (st/dispatch! s [:pro/remove-watermark "a.png"])
      (st/dispatch! s [:pro/remove-watermark "b.png"]))
    (swap! disk update-in [licensing/storage-key :trial :uses]
           (fn [uses] (-> uses (dissoc "pro/remove-watermark") (assoc :pro/remove-watermark 2))))
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (is (false? (:trial-ok? (lic s))) "same JSON, same seal, but not the shape the module writes")))
  (testing "nothing stored is a first run"
    (reset! disk {})
    (let [s (boot!)]
      (st/dispatch! s [:licensing/init])
      (is (true? (:trial-ok? (lic s)))))))

(deftest activation-while-the-stored-state-is-read
  (reset! disk {})
  (let [s (boot!)]
    (st/dispatch! s [:licensing/init])
    (st/dispatch! s [:pro/remove-watermark "a.png"]))
  (let [s (boot! machine {:defer-reads? true})]
    (st/dispatch! s [:licensing/init])
    (st/dispatch! s [:licensing/prompt])
    (st/dispatch! s [:licensing/key-input jvm-token])
    (st/dispatch! s [:licensing/activate])
    (is (nil? (:license (lic s))) "held until the stored state is read")
    (is (nil? (stored :token)) "and nothing is written before that")
    (answer-reads!)
    (is (= "L-0001" (get-in (lic s) [:license :id])))
    (is (= jvm-token (stored :token)))
    (is (= 1 (get-in (stored :trial) [:uses "pro/remove-watermark"])) "the stored counts survive")
    (is (nil? (:void (stored :trial))))))

(deftest a-failed-save-stops-trial-uses
  (reset! disk {})
  (let [s (boot! machine {:fail-writes? true})]
    (st/dispatch! s [:licensing/init])
    (st/dispatch! s [:pro/remove-watermark "a.png"])
    (is (= ["a.png"] (:removed (st/db s))) "the use whose count could not be saved has run")
    (is (true? (:save-failed? (lic s))))
    (st/dispatch! s [:pro/remove-watermark "b.png"])
    (is (= ["a.png"] (:removed (st/db s))) "no more uses: they could never be counted")
    (is (= :storage-failed (get-in (lic s) [:prompt :reason])))
    (is (= "Free uses unavailable" (badge-text (st/db s))))
    (is (= {:licensed? false :remaining 0 :reason :storage-failed}
           (licensing/status (st/db s) :pro/remove-watermark trial2)))
    (testing "a licence still unlocks the feature in this session"
      (st/dispatch! s [:licensing/key-input jvm-token])
      (st/dispatch! s [:licensing/activate])
      (is (= ["a.png" "b.png"] (:removed (st/db s)))))))

(deftest gated-handlers-need-the-licensing-state
  (boot!)
  (let [h (licensing/wrap-gated-event :pro/x {:mode :hard} (fn [_ _] {}))]
    (is (thrown-with-msg? #?(:cljd cljd.core/ExceptionInfo :default clojure.lang.ExceptionInfo)
                          #"licensing/init" (h {:db {}} [:pro/x]))
        "no [:licensing/init] yet"))
  (testing "a path interceptor hides the root db from the gate"
    (st/reg-event-fx :doc/gated [(st/path :doc)]
                     (licensing/wrap-gated-event :pro/x {:mode :hard} (fn [_ _] {:db {:edited true}})))
    (is (thrown-with-msg? #?(:cljd cljd.core/ExceptionInfo :default clojure.lang.ExceptionInfo)
                          #"path interceptor"
                          (st/handle {:doc {} :cloview/licensing {:loaded? true :license {:features ["*"]}}}
                                     [:doc/gated])))))
