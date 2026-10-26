;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.memo
  "Memoization for view derivations.

  `cloview.core.store/reg-sub` memoizes a subscription on db identity: ANY db change
  (a selection, a hover, a tab switch) recomputes it. Expensive derivations — analytics
  over every record, a filtered 500-row catalog — should instead depend on the db
  slices they read. `memo-last` caches the last call per argument list: arguments are
  compared with `=`, which returns at once for identical values, so passing the slices
  (`(:records doc)`, `(:exercises doc)`, an options map) makes unrelated db changes free.

    (def analytics* (memo/memo-last (fn [records plans exercises opts] ...)))
    (analytics* (:records doc) (:plans doc) (:exercises doc) opts)

  `counting` wraps a fn with a call counter, so tests and benchmarks can prove that a
  derivation was not recomputed (benchmark ARCH-004 evidence).")

(defn memo-last
  "Single-entry cache of `f`: returns the previous result when the arguments are `=` to
  the previous call's (identical arguments short-circuit the comparison)."
  [f]
  (let [cache (atom nil)]
    (fn [& args]
      (let [c @cache]
        (if (and (some? c) (= (:args c) args))
          (:value c)
          (let [v (apply f args)]
            (reset! cache {:args args :value v})
            v))))))

(defn counting
  "`f` wrapped so every call increments `counter` (an atom holding a number)."
  [counter f]
  (fn [& args]
    (swap! counter inc)
    (apply f args)))
