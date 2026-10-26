;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.trial
  "Metered trials (docs/LICENSING.md §6): how many times each gated feature was used,
  sealed with an HMAC so that editing the stored counts is detected.

    state   {:v 1 :uses {\"pro/remove-watermark\" 3}}         (+ :void true, see below)
    sealed  state + :mac = hex HMAC-SHA256(key, canonical JSON of state)
    key     SHA-256(trial-secret | machine-id)

  Only that exact shape is accepted: keyword top-level keys from a fixed set, string
  feature ids, non-negative integer counts. The JSON writer encodes :a and \"a\" alike,
  so without the shape check an edited key type (\"void\" for :void, :pro/x for
  \"pro/x\") would keep a valid seal while reading differently.

  The secret ships inside the app, so this is tamper-evident, not tamper-proof: it
  stops casual edits and copying the file to another machine. Deleting the stored
  state resets the counts, like reinstalling would; the module keeps the state in the
  app's storage (cloview.fx.storage). A seal that does not verify fails closed: the
  trial counts as used up until a licence is activated, and the next save writes the
  sealed `void-state`, so a later save can never turn edited counts into valid ones."
  (:require [cloview.fx.codec :as codec]
            [cloview.licensing.crypto :as crypto]))

(def empty-state {:v 1 :uses {}})

(def void-state
  "Written instead of the counts once a check failed: a valid seal that still fails."
  {:v 1 :uses {} :void true})

(defn- mac-of [state secret machine-id]
  (let [k (crypto/sha256 (crypto/utf8 (str secret "|" (or machine-id ""))))]
    (crypto/hex (crypto/hmac-sha256 k (crypto/utf8 (codec/write-json (dissoc state :mac)))))))

(defn seal [state secret machine-id]
  (assoc (dissoc state :mac) :mac (mac-of state secret machine-id)))

(defn- count? [n] (and (integer? n) (not (neg? n))))

(defn well-formed?
  "True for exactly the shape `seal` writes: {:v 1 :uses {string count} :mac string},
  optionally with :void true."
  [stored]
  (and (map? stored)
       (every? #{:v :uses :void :mac} (keys stored))
       (= 1 (:v stored))
       (map? (:uses stored))
       (every? (fn [[k n]] (and (string? k) (count? n))) (:uses stored))
       (or (not (contains? stored :void)) (true? (:void stored)))
       (string? (:mac stored))))

(defn check
  "{:state s :ok? bool} for a stored, sealed state. ok? is true only for a well-formed
  state whose seal verifies and that is not void; otherwise the state is the stored
  counts when their shape is valid (for display) or the empty state."
  [stored secret machine-id]
  (if (well-formed? stored)
    (let [state (dissoc stored :mac)]
      {:state state
       :ok? (and (= (:mac stored) (mac-of state secret machine-id))
                 (not (:void state)))})
    {:state empty-state :ok? false}))

(defn uses [state feature] (get-in state [:uses feature] 0))

(defn record-use [state feature] (update-in state [:uses feature] (fnil inc 0)))
