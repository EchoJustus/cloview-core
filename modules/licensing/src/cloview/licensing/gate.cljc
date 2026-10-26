;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.gate
  "The gate decision (docs/LICENSING.md §2): may this feature run now? Pure.

  Gate specs:
    {:mode :hard}                        runs only with a licence that unlocks it
    {:mode :trial-count :max-uses 5}     runs `max-uses` times without a licence; each
                                         unlicensed run consumes one use
  Licensing state (the :cloview/licensing slice of the db):
    {:loaded? bool :license <verified payload or nil> :trial <trial state>
     :trial-ok? bool :save-failed? bool ...}"
  (:require [cloview.fx.codec :as codec]
            [cloview.licensing.token :as token]
            [cloview.licensing.trial :as trial]))

(defn feature-id
  "The licence feature string of a feature keyword or string: :pro/remove-watermark ->
  \"pro/remove-watermark\"."
  [feature]
  (codec/json-key feature))

(defn licensed?
  "True when the state's licence unlocks `feature`."
  [state feature]
  (boolean (some-> (:license state) (token/unlocks? (feature-id feature)))))

(defn remaining
  "Uses left in a trial gate by the stored counts (nil for other gates)."
  [state feature {:keys [mode max-uses]}]
  (when (= :trial-count mode)
    (max 0 (- (or max-uses 0) (trial/uses (:trial state) (feature-id feature))))))

(defn trial-usable?
  "True when trial uses may run: the stored trial passed its check and no save of the
  counts has failed in this session."
  [state]
  (and (true? (:trial-ok? state)) (not (:save-failed? state))))

(defn available
  "Trial uses that can actually run now: nil for other gates and while the stored state
  is being read, 0 when the trial is not usable (see trial-usable?)."
  [state feature gate]
  (when (and (= :trial-count (:mode gate)) (:loaded? state))
    (if (trial-usable? state) (remaining state feature gate) 0)))

(defn decide
  "{:allow? true :consume? bool :remaining n} or {:allow? false :reason r}, r one of
  :loading (the stored licence and trial have not been read yet), :locked (hard gate,
  no licence), :tampered (the stored trial state failed its check), :storage-failed
  (a trial count could not be saved, so trial uses stop: failing open would make the
  trial endless on read-only storage), :trial-exhausted or :unknown-gate."
  [state feature {:keys [mode] :as gate}]
  (cond
    (licensed? state feature) {:allow? true :consume? false}
    (not (:loaded? state)) {:allow? false :reason :loading}
    (= :hard mode) {:allow? false :reason :locked}
    (= :trial-count mode) (let [left (remaining state feature gate)]
                            (cond
                              (not (true? (:trial-ok? state))) {:allow? false :reason :tampered}
                              (:save-failed? state) {:allow? false :reason :storage-failed}
                              (pos? left) {:allow? true :consume? true :remaining (dec left)}
                              :else {:allow? false :reason :trial-exhausted}))
    :else {:allow? false :reason :unknown-gate}))
