;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.licensing.issue-test
  "The seller's key tool (JVM only): keygen -> issue -> the app verifies."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloview.licensing.issue :as issue]
            [cloview.licensing.token :as token]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory "cloview-issue-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest keygen-issue-verify
  (let [dir (temp-dir)
        {:keys [kid file public]} (issue/keygen! {:kid "2026-1" :out dir})]
    (try
      (is (= "2026-1" kid))
      (is (.isFile (io/file file)))
      (is (str/includes? (slurp file) "SECRET"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exists" (issue/keygen! {:kid "2026-1" :out dir}))
          "never replaces a key")
      (is (thrown? clojure.lang.ExceptionInfo (issue/keygen! {:kid "bad kid!" :out dir})))
      (let [tok (issue/issue {:key file :product "wmark" :features "pro/remove-watermark, pro/batch"
                              :licensee "Ada Lovelace" :id "L-7" :issued "2026-10-25" :expires "2027-10-24"})
            app {:product "wmark" :public-keys {"2026-1" public} :today "2026-11-01"}]
        (is (str/starts-with? tok "CLV1."))
        (is (= {:valid? true
                :license {:v 1 :alg "ES256" :kid "2026-1" :id "L-7" :product "wmark"
                          :features ["pro/remove-watermark" "pro/batch"] :licensee "Ada Lovelace"
                          :issued "2026-10-25" :expires "2027-10-24"}}
               (token/verify tok app)))
        (is (= :expired (:reason (token/verify tok (assoc app :today "2027-10-25")))))
        (is (= "L-7" (get-in (issue/inspect tok {}) [:payload :id])))
        (is (true? (get-in (issue/inspect tok {:public public :today "2026-11-01"}) [:verification :valid?]))))
      (testing "bound keys"
        (let [tok (issue/issue {:key file :product "wmark" :features "*" :device "D1-A457-7A9B-8B2D-A2D4"})]
          (is (true? (:valid? (token/verify tok {:product "wmark" :public-keys {"2026-1" public}
                                                 :device-code "D1-A457-7A9B-8B2D-A2D4"}))))
          (is (= :wrong-device (:reason (token/verify tok {:product "wmark" :public-keys {"2026-1" public}
                                                           :device-code "D1-0000-0000-0000-0000"}))))))
      (testing "bad input is refused"
        (doseq [bad [{:product "wmark" :features "a"}                                   ; no key
                     {:key file :features "a"}                                          ; no product
                     {:key file :product "wmark" :features " , "}                       ; no features
                     {:key file :product "wmark" :features "a" :device "d1-abcd"}       ; device format
                     {:key file :product "wmark" :features "a" :expires "24.10.2027"}   ; date format
                     {:key file :product "wmark" :features "a" :expires "2027-02-30"}   ; no such day
                     {:key file :product "wmark" :features "a" :expires "2027-19-99"}
                     {:key file :product "wmark" :features "a" :issued "2026-10-25" :expires "2026-01-01"}
                     {:key file :product "wmark " :features "a"}                        ; would never match
                     {:key file :product "wmark" :features "a" :id " "}]]
          (is (thrown? clojure.lang.ExceptionInfo (issue/issue bad)) (pr-str (dissoc bad :key)))))
      (testing "the command line refuses what it does not know"
        (let [run (fn [& args] (let [err (java.io.StringWriter.)
                                     out (java.io.StringWriter.)
                                     code (binding [*err* err *out* out] (issue/run args))]
                                 [code (str err) (str out)]))
              base ["issue" "--key" file "--product" "wmark" "--features" "a"]]
          (let [[code err out] (apply run (concat base ["--expire" "2027-01-31"]))]
            (is (= 2 code) "a misspelt --expires must not issue a perpetual key")
            (is (str/includes? err "unknown option for issue: --expire"))
            (is (= "" out) "nothing issued"))
          (is (= 2 (first (apply run (concat base ["--device-code" "D1-A457-7A9B-8B2D-A2D4"]))))
              "the device-code command's name is not --device")
          (is (= 2 (first (apply run (concat base ["--features" "b"])))) "an option given twice")
          (is (= 2 (first (apply run (concat base ["stray"])))) "an extra argument")
          (is (= 2 (first (run "inspect" "CLV1.x.y" "--today" "2027-02-30"))))
          (let [[code _ out] (apply run base)]
            (is (= 0 code))
            (is (str/starts-with? out "CLV1.")))))
      (testing "the key file is private from the moment it exists (POSIX)"
        (when (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix")
          (let [sub (io/file dir "fresh" "keys")
                {f :file} (issue/keygen! {:kid "2026-2" :out (str sub)})
                perms (fn [p] (java.nio.file.attribute.PosixFilePermissions/toString
                               (java.nio.file.Files/getPosixFilePermissions
                                (.toPath (io/file p)) (make-array java.nio.file.LinkOption 0))))]
            (is (= "rw-------" (perms f)))
            (is (= "rwx------" (perms sub)) "and so is a folder it creates"))))
      (finally
        (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f))))))
