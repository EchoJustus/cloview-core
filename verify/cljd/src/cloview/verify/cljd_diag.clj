;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.verify.cljd-diag
  "Drop-in replacement for `clj -M:cljd <command>` that also prints the FULL cause chain
  of a ClojureDart compilation error.

  Why: cljd.build/print-exception prints only the immediate cause of the OUTERMOST emit
  error. When a namespace fails inside a nested require, the output names the outer
  namespaces and hides the real error (the Stage 2-1 Windows run showed only
  \"compiling namespace cloview.flutter.app\"). This wrapper keeps ClojureDart's own
  output and appends every exception of the chain with its message and ex-data
  (emit stacks abbreviated).

    clj -M:cljd-diag compile            ; examples' deps.edn define the :cljd-diag alias
    clj -M:cljd-diag test

  JVM-only build tooling; it is not part of any app."
  (:require [cljd.build :as build]))

(defn- abbreviated [v]
  (binding [*print-length* 12 *print-level* 5]
    (let [s (pr-str v)]
      (if (> (count s) 1500) (str (subs s 0 1500) " ...") s))))

(defn print-chain
  "Print every exception in `e`'s cause chain, outermost first."
  [^Throwable e]
  (println)
  (println "=== cloview cljd-diag: full cause chain (outermost first) ===")
  (doseq [[i ^Throwable x] (map-indexed vector (take-while some? (iterate #(.getCause ^Throwable %) e)))]
    (println (str "[" i "] " (.getName (class x)) ": " (.getMessage x)))
    (when-some [data (ex-data x)]
      (doseq [[k v] (dissoc data :cljd.compiler/emit-stack)]
        (println (str "      " k " " (abbreviated v))))
      (when-some [stack (seq (:cljd.compiler/emit-stack data))]
        (println (str "      emit-stack (innermost form first): " (abbreviated (first stack))))))
    (when-not (ex-data x)
      (doseq [frame (take 6 (.getStackTrace x))]
        (println (str "      at " frame)))))
  (println "=== end of cause chain ==="))

(defn -main [& args]
  (alter-var-root #'build/print-exception
                  (fn [original]
                    (fn [e]
                      (original e)
                      (print-chain e))))
  (apply build/-main args))
