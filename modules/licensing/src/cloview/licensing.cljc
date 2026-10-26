;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing
  "Opt-in feature unlock and trial licensing for indie apps (docs/LICENSING.md): offline
  ES256 licence keys, optional device binding, metered trials, and a gate around event
  handlers. No server is involved: the seller signs keys with a private key that never
  ships, the app verifies them with the public key.

    (licensing/install! {:product \"wmark\"
                         :public-keys {\"2026-1\" \"BJ3...\"}     ; base64url, 65-byte point
                         :trial-secret \"wmark-trial-1\"})
    ;; every entry point, after registering the app's handlers and :storage/* effects:
    (st/dispatch! store [:licensing/init])

    ;; an event-fx handler that needs a licence (5 free uses first):
    (def handlers
      {:events-fx {:pro/remove-watermark
                   (licensing/wrap-gated-event :pro/remove-watermark {:mode :trial-count :max-uses 5}
                     (fn [cofx [_ path]] {:backend/call {:method \"wm/remove\" :params {:path path}}}))}})

    ;; somewhere in the root view (the dialog stays closed until a gate asks):
    (licensing/modal db {:product-name \"Watermark Pro\" :on-buy [:app/open-shop]})

  State lives in the db under :cloview/licensing:
    {:loaded? true :device-code \"D1-...\"
     :token \"CLV1...\"            ; the key kept on this machine, even when it does not verify
     :license {..}                 ; its verified payload, or nil
     :stored-key-problem :expired  ; why :token does not verify, or nil
     :trial {:v 1 :uses {..}} :trial-ok? true :save-failed? false
     :pending [..]                 ; events held until the stored state is read
     :prompt {:feature f :reason r :gate g :event e} :key-input \"\" :error nil}
  and is persisted with cloview.fx.storage (key :cloview.licensing/state). The slice
  belongs to this module: a handler that replaces the whole db must keep it.

  Store builds: the Apple App Store, the Mac App Store and Google Play require their own
  in-app purchase for unlocking features (licence keys are not allowed there); use this
  module for direct distribution (website, Developer ID on macOS) and the Microsoft
  Store (non-game PC apps may use a secure third-party purchase API; keys sold through
  your own shop fit that, on our reading). See docs/LICENSING.md §8.

  Runs on Clojure/JVM and ClojureCLR. ClojureDart: install! throws {:reason
  :unsupported} until the Dart crypto lands (Stage 3, cloview.licensing.crypto)."
  (:require [cloview.core.store :as st]
            [cloview.core.tree :as t]
            [cloview.fx.timer :as timer]
            [cloview.licensing.device :as device]
            [cloview.licensing.gate :as gate]
            [cloview.licensing.token :as token]
            [cloview.licensing.trial :as trial]
            [cloview.licensing.ui :as ui]))

(def storage-key :cloview.licensing/state)

(defonce ^:private cfg (atom nil))

(defn config
  "The installed configuration (with the derived :device-code), or nil."
  []
  @cfg)

;; ---------------------------------------------------------------------------
;; dates

(defn- days->date
  "\"YYYY-MM-DD\" of a day number since 1970-01-01 (proleptic Gregorian)."
  [z]
  ;; Howard Hinnant's civil_from_days
  (let [z (+ z 719468)
        era (quot (if (>= z 0) z (- z 146096)) 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y (+ yoe (* era 400))
        doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (if (< mp 10) (+ mp 3) (- mp 9))
        y (if (<= m 2) (inc y) y)
        pad (fn [n w] (let [s (str n)] (str (apply str (repeat (- w (count s)) "0")) s)))]
    (str (pad y 4) "-" (pad m 2) "-" (pad d 2))))

(defn date-of-ms
  "UTC \"YYYY-MM-DD\" of epoch milliseconds."
  [ms]
  (days->date (quot ms 86400000)))

(defn today
  "Today's UTC date as \"YYYY-MM-DD\"."
  []
  (date-of-ms (timer/now-ms)))

;; ---------------------------------------------------------------------------
;; handlers

(defn verify
  "cloview.licensing.token/verify of `tok` against the installed configuration."
  [tok]
  (let [{:keys [product public-keys device-code]} @cfg]
    (token/verify tok {:product product :public-keys public-keys :device-code device-code :today (today)})))

(defn- persisted [lic]
  {;; the key as kept on this machine: one that stopped verifying is saved back as it
   ;; was, so only activate/deactivate ever change it
   :token (:token lic)
   ;; once a check failed, only the void state is ever written (see trial ns)
   :trial (trial/seal (if (true? (:trial-ok? lic)) (or (:trial lic) trial/empty-state) trial/void-state)
                      (:trial-secret @cfg) (:machine-id @cfg))})

(defn- save-fx
  "The :storage/put of the slice; nothing before the stored state has been read (a save
  then would overwrite data this session has not seen)."
  [lic]
  (when (:loaded? lic)
    {:storage/put {:key storage-key :value (persisted lic)
                   :on-failure [:licensing/save-failed]}}))

(defn- stored-trial
  "{:state :ok?} of what storage returned: nothing stored is a first run; anything else
  must be a map holding a well-formed, correctly sealed :trial."
  [stored]
  (cond
    (nil? stored) {:state trial/empty-state :ok? true}
    (and (map? stored) (contains? stored :trial)) (trial/check (:trial stored) (:trial-secret @cfg) (:machine-id @cfg))
    :else {:state trial/empty-state :ok? false}))

(def ^:private max-pending
  "Events held while the stored state is read; later ones are dropped."
  16)

(defn- hold
  "`lic` with `event` queued until the stored state has been read."
  [lic event]
  (update lic :pending (fn [q] (let [q (vec q)] (if (< (count q) max-pending) (conj q event) q)))))

(defn- replay
  "Effects that run the events held while the stored state was read, in order, and close
  the :loading dialog."
  [effects]
  (let [lic (get-in effects [:db :cloview/licensing])
        q (:pending lic)]
    (cond-> (assoc-in effects [:db :cloview/licensing :pending] [])
      (= :loading (get-in lic [:prompt :reason])) (assoc-in [:db :cloview/licensing :prompt] nil)
      (seq q) (assoc :dispatch-n (vec q)))))

(def ^:private missing-state
  (str "cloview.licensing: no :cloview/licensing state in the db. Dispatch [:licensing/init] "
       "when the app starts; register gated handlers without a path interceptor (the gate "
       "reads the root db); and keep :cloview/licensing when a handler replaces the whole db."))

(def ^:private reason-text
  {:malformed "That is not a licence key. Paste the whole key, starting with CLV1."
   :unsupported "This licence key is for a newer version of the app."
   :unknown-key "This licence key was not issued for this version of the app."
   :bad-signature "This licence key is not valid."
   :wrong-product "This licence key is for a different product."
   :wrong-device "This licence key is bound to another device. Send the seller the device code shown below."
   :expired "This licence key has expired."
   :feature-missing "Your licence is active, but it does not include this feature."})

(def handlers
  {:events-fx
   {:licensing/init
    (fn [{:keys [db]} _]
      {:db (update db :cloview/licensing merge {:device-code (:device-code @cfg) :key-input ""})
       :storage/get {:key storage-key :on-loaded [:licensing/loaded] :on-failure [:licensing/load-failed]}})

    :licensing/loaded
    (fn [{:keys [db]} [_ stored]]
      (let [{:keys [state ok?]} (stored-trial stored)
            tok (when (map? stored) (:token stored))
            v (when (some? tok) (verify tok))]
        (replay
         {:db (update db :cloview/licensing merge
                      {:trial state :trial-ok? ok? :loaded? true
                       ;; a stored key that stopped verifying (expired, other machine,
                       ;; key list of another app version) is kept and reported
                       :token tok
                       :license (when (:valid? v) (:license v))
                       :stored-key-problem (when (and v (not (:valid? v))) (:reason v))})})))

    :licensing/load-failed
    (fn [{:keys [db]} [_ failure]]
      ;; unreadable storage: no licence, and the trial fails closed
      (replay
       {:db (update db :cloview/licensing merge
                    {:loaded? true :trial trial/empty-state :trial-ok? false
                     :error (str "Licence data could not be read: " (:error failure))})}))

    :licensing/save-failed
    (fn [{:keys [db]} [_ failure]]
      ;; trial uses stop for this session (gate reason :storage-failed)
      {:db (update db :cloview/licensing assoc :save-failed? true
                   :error (str "Licence data could not be saved: " (:error failure)))})

    :licensing/key-input
    (fn [{:keys [db]} [_ s]]
      {:db (update db :cloview/licensing assoc :key-input s :error nil)})

    :licensing/activate
    (fn [{:keys [db]} event]
      (let [lic (:cloview/licensing db)]
        (if-not (:loaded? lic)
          ;; after the read: the stored state would otherwise overwrite the new key
          {:db (assoc db :cloview/licensing (hold lic event))}
          (let [tok (token/normalize (:key-input lic))
                v (verify tok)
                {:keys [feature] refused :event} (:prompt lic)]
            (if (:valid? v)
              (let [covers? (or (nil? feature) (token/unlocks? (:license v) feature))
                    lic' (merge lic {:license (:license v) :token tok :key-input "" :stored-key-problem nil
                                     :prompt (when-not covers? (:prompt lic))
                                     :error (when-not covers? (:feature-missing reason-text))})]
                (cond-> (merge {:db (assoc db :cloview/licensing lic')} (save-fx lic'))
                  ;; resume the action the gate stopped
                  (and covers? refused) (assoc :dispatch refused)))
              {:db (assoc-in db [:cloview/licensing :error] (get reason-text (:reason v) (:bad-signature reason-text)))})))))

    :licensing/dismiss
    (fn [{:keys [db]} _]
      {:db (update db :cloview/licensing assoc :prompt nil :error nil)})

    :licensing/prompt
    ;; open the dialog from a menu ("Enter licence key..."), optionally for a feature
    (fn [{:keys [db]} [_ feature]]
      {:db (assoc-in db [:cloview/licensing :prompt]
                     {:feature (when (some? feature) (gate/feature-id feature)) :reason :manual})})

    :licensing/deactivate
    (fn [{:keys [db]} event]
      (let [lic (:cloview/licensing db)]
        (if-not (:loaded? lic)
          {:db (assoc db :cloview/licensing (hold lic event))}
          (let [lic' (merge lic {:license nil :token nil :stored-key-problem nil})]
            (merge {:db (assoc db :cloview/licensing lic')} (save-fx lic'))))))}})

(defn wrap-gated-event
  "Wrap an event-fx handler (fn [cofx event] effects) so that it runs only when `gate`
  allows `feature` (see cloview.licensing.gate):
    - licensed: the handler runs unchanged;
    - trial use left: the handler runs, the use is counted and saved;
    - stored state not read yet: the event is held and runs as soon as it is read;
    - otherwise: the handler does not run; the licence modal opens with the reason and
      remembers the event, which is dispatched again right after a successful unlock.
  The licensing slice is taken from the db the handler received, so a handler that
  returns a fresh db cannot drop or rewrite it. Throws when the db has no licensing
  state (no [:licensing/init], or a path interceptor focused the db)."
  [feature gate handler]
  (let [fid (gate/feature-id feature)]
    (fn [{:keys [db] :as cofx} event]
      (let [lic (:cloview/licensing db)]
        (when-not (map? lic)
          (throw (ex-info missing-state {:reason :no-licensing-state :event event})))
        (let [{:keys [allow? consume? reason]} (gate/decide lic feature gate)]
          (cond
            allow?
            (let [fx (or (handler cofx event) {})
                  lic' (if consume? (update lic :trial #(trial/record-use (or % trial/empty-state) fid)) lic)
                  fx (if (or consume? (contains? fx :db))
                       (assoc fx :db (assoc (if (contains? fx :db) (:db fx) db) :cloview/licensing lic'))
                       fx)]
              (if consume?
                ;; the handler's own :storage/put (if any) runs first
                (merge-with (fn [a b] (if (sequential? a) (conj (vec a) b) [a b])) fx (save-fx lic'))
                fx))

            (= :loading reason)
            {:db (assoc db :cloview/licensing
                        (assoc (hold lic event) :prompt {:feature fid :reason :loading :gate gate}))}

            :else
            {:db (assoc-in db [:cloview/licensing :prompt]
                           {:feature fid :reason reason :gate gate :event event})}))))))

(defn modal
  "The unlock dialog for `db` — cloview.licensing.ui/license-modal."
  [db opts]
  (ui/license-modal db opts))

(defn status
  "{:licensed? bool :remaining n :reason r} of `feature` under `gate` for a db, for badges
  and menus. :remaining counts the trial uses that can run now (cloview.licensing.gate/
  available): nil for other gates and while the stored state is read, 0 when the trial
  is not usable. :reason is why the gate would refuse now (nil when it would run)."
  [db feature gate]
  (let [lic (:cloview/licensing db)]
    {:licensed? (gate/licensed? lic feature)
     :remaining (gate/available lic feature gate)
     :reason (:reason (gate/decide lic feature gate))}))

(defn install!
  "Configure the module and register its events and the :cloview/license-modal
  composite. Options:
    :product       product id the keys must name
    :public-keys   {kid public-key} (base64url strings of the 65-byte point)
    :trial-secret  any app-specific string (keys the trial seal)
    :machine-id    override (tests); default: cloview.licensing.device/machine-id
  Reads the machine id once (host I/O) and keeps only the derived device code in the db."
  [{:keys [product public-keys trial-secret] :as opts}]
  #?(:cljd (throw (ex-info "cloview.licensing is not available on ClojureDart yet (Stage 3)"
                           {:reason :unsupported}))
     :default
     (let [mid (if (contains? opts :machine-id) (:machine-id opts) (device/machine-id))]
       (reset! cfg {:product product
                    :public-keys public-keys
                    :trial-secret (or trial-secret (str "cloview-trial:" product))
                    :machine-id mid
                    :device-code (device/device-code product mid)})
       (st/register-handlers! handlers)
       (t/reg-component! :cloview/license-modal (fn [props _children] (ui/license-modal-node props)))
       :installed)))
