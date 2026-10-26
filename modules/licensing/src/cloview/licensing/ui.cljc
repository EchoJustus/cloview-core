;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.ui
  "The unlock dialog and the trial badge, built from canonical tags only (every host
  renders them; no host code). Use them as functions of the db:

    (ui/license-modal db {:product-name \"Watermark Pro\" :on-buy [:app/open-shop]})
    (ui/trial-badge db :pro/remove-watermark {:mode :trial-count :max-uses 5})

  or, in data-only views, as the composite [:cloview/license-modal {:state
  (:cloview/licensing db) ...}] registered by cloview.licensing/install!."
  (:require [cloview.licensing.gate :as gate]))

(def ^:private prompt-text
  {:loading "Reading the licence data..."
   :locked "This feature needs a licence."
   :trial-exhausted "You have used all free tries of this feature."
   :tampered "The trial data on this device is not valid any more. Activate a licence to continue."
   :storage-failed "Free uses are paused: the app could not save how many were used. Activate a licence to continue."
   :manual "Enter your licence key to unlock all features."
   :unknown-gate "This feature is not available."})

(defn license-modal-node
  "The dialog for props {:state <:cloview/licensing slice> :product-name s :on-buy ev
  :buy-label s :title s}. Closed (an empty modal) while no gate is asking."
  [{:keys [state product-name on-buy buy-label title]}]
  (let [{:keys [prompt key-input error device-code]} state]
    [:ui/modal {:key :cloview/license-modal
                :open? (some? prompt)
                :title (or title (str "Unlock " (or product-name "the full version")))
                :on-dismiss [:licensing/dismiss]}
     [:ui/column {:gap 10 :style {:padding 4 :width 440}}
      [:ui/text {:test-id "license-reason" :wrap? true
                 :text (get prompt-text (:reason prompt) (get prompt-text :manual))}]
      [:ui/text-input {:test-id "license-key" :value (or key-input "") :placeholder "Paste your licence key (CLV1...)"
                       :on-change [:licensing/key-input]}]
      (when error
        [:ui/text {:test-id "license-error" :wrap? true :text error :style {:color "#B3261E"}}])
      (when device-code
        [:ui/text {:test-id "license-device" :style {:font-size 11 :color "#5F6368"}
                   :text (str "Device code: " device-code)}])
      [:ui/row {:gap 8 :align :center}
       [:ui/button {:test-id "license-activate" :label "Unlock" :on-press [:licensing/activate]
                    :disabled? (empty? key-input)}]
       (when on-buy
         [:ui/button {:test-id "license-buy" :label (or buy-label "Buy a licence") :on-press on-buy}])
       [:ui/spacer]
       [:ui/button {:test-id "license-dismiss" :label "Not now" :on-press [:licensing/dismiss]}]]]]))

(defn license-modal
  "The unlock dialog for `db` (see license-modal-node for the options)."
  [db opts]
  (license-modal-node (assoc opts :state (:cloview/licensing db))))

(defn trial-badge
  "A small text such as \"3 free uses left\" for a trial gate; \"Free uses unavailable\"
  when the trial cannot be used (failed check, unsaved counts); nil when licensed, not a
  trial gate, or while the stored state is being read."
  [db feature gate-spec]
  (let [lic (:cloview/licensing db)]
    (when-not (gate/licensed? lic feature)
      (when-some [n (gate/available lic feature gate-spec)]
        [:ui/text {:key [:cloview/trial-badge (gate/feature-id feature)]
                   :style {:font-size 11 :color "#5F6368"}
                   :text (cond
                           (not (gate/trial-usable? lic)) "Free uses unavailable"
                           (= 0 n) "No free uses left"
                           (= 1 n) "1 free use left"
                           :else (str n " free uses left"))}]))))
