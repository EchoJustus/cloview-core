;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.verify.test-runner
  "Portable clojure.test runner (Clojure/JVM and ClojureCLR, no dependencies).

  JVM:  clj -M:test                      (portable + JVM-only suites, see deps.edn :test alias)
        clj -M:test core                 (one suite)
        clj -M:test --out verify/out/x.json cloview.core.tree-test
  CLR:  CloViewHost.exe --run-tests      (calls `run-suites!` with `portable-suites`)

  Returns/exits with 0 when every test passed, 1 otherwise."
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [cloview.verify.report :as report]))

(def suites
  {:core '[cloview.core.tree-test
           cloview.core.style-test
           cloview.core.store-test
           cloview.core.reconcile-test
           cloview.core.order-test
           cloview.core.memo-test
           cloview.core.text-test
           cloview.core.components-test]
   :fx '[cloview.fx.fx-test
         cloview.fx.codec-test]
   :bridge '[cloview.bridge.bridge-test
             cloview.bridge.process-test
             cloview.bridge.rplot-test]
   :licensing '[cloview.licensing.licensing-test]
   ;; JVM only: the seller's key tool (modules/licensing/tool) and the project initializer
   :licensing-tool '[cloview.licensing.issue-test]
   :starter '[cloview.starter-test]
   :javafx '[cloview.javafx.cljfx-test]})

(def portable-suites
  "Suites that run on every runtime (JVM and ClojureCLR): the CLR host runs these."
  ["core" "fx" "bridge" "licensing"])

(def jvm-only-suites
  "Suites the JVM runs in addition to the portable ones."
  ["licensing-tool" "starter" "javafx"])

(defn- run-ns [ns-sym]
  (try
    (require ns-sym)
    (let [summary (test/run-tests ns-sym)]
      {:ns (str ns-sym)
       :test (:test summary 0)
       :pass (:pass summary 0)
       :fail (:fail summary 0)
       :error (:error summary 0)})
    (catch #?(:clj Throwable :cljr System.Exception) ex
      (println "ERROR loading" ns-sym ":" (str ex))
      {:ns (str ns-sym) :test 0 :pass 0 :fail 0 :error 1 :load_error (str ex)})))

(defn run-namespaces!
  "Run `ns-syms`; print a summary; write JSON to `out` when given. Returns exit code."
  ([ns-syms] (run-namespaces! ns-syms nil))
  ([ns-syms out]
   (let [started (report/now-ms)
         results (mapv run-ns ns-syms)
         totals (reduce (fn [acc r] (merge-with + acc (select-keys r [:test :pass :fail :error])))
                        {:test 0 :pass 0 :fail 0 :error 0}
                        results)
         ok (and (zero? (:fail totals)) (zero? (:error totals)) (pos? (:test totals)))]
     (println)
     (println "==== cloview test summary ====")
     (doseq [{:keys [ns test pass fail error]} results]
       (println (str "  " (if (and (zero? fail) (zero? error)) "PASS" "FAIL") "  " ns
                     "  tests=" test " assertions=" pass " fail=" fail " error=" error)))
     (println (str "  TOTAL tests=" (:test totals) " assertions=" (:pass totals)
                   " fail=" (:fail totals) " error=" (:error totals)
                   "  => " (if ok "OK" "FAILED")))
     (when out
       (report/write-json! out {:suite "unit-tests"
                                :host (report/host-info)
                                :ok ok
                                :totals totals
                                :namespaces results
                                :duration_ms (- (report/now-ms) started)}))
     (if ok 0 1))))

(defn- parse-args [args]
  (loop [args (seq args) out nil nses []]
    (if-some [[a & more] args]
      (cond
        (= a "--out") (recur (next more) (first more) nses)
        (contains? suites (keyword a)) (recur more out (into nses (get suites (keyword a))))
        :else (recur more out (conj nses (symbol a))))
      {:out out :nses nses})))

(defn run-suites!
  "Entry point for hosts: `suite-names` are strings/keywords (\"core\" \"fx\" \"javafx\") or
  namespace names. Returns the exit code."
  [suite-names out]
  (let [{:keys [nses]} (parse-args (map name suite-names))]
    (run-namespaces! nses out)))

(defn run-tests!
  "Host entry point with the same shape as greeter.wpf.main/run-tests! (used by
  `CloViewHost --ns cloview.verify.test-runner --run-tests` when WPF is unavailable)."
  [out]
  (run-suites! portable-suites out))

(defn -main [& args]
  (let [{:keys [out nses]} (parse-args args)
        nses (if (seq nses) nses (into [] (mapcat #(get suites (keyword %))) (into portable-suites jvm-only-suites)))
        code (run-namespaces! nses out)]
    (shutdown-agents)
    #?(:clj (System/exit code)
       :cljr (System.Environment/Exit code))))
