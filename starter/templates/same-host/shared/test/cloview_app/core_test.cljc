(ns cloview-app.core-test
  "The app's logic and view, without a GUI: runs on the JVM (bb test), on the CLR
  (bb test:clr) and on Dart (bb test:flutter) unchanged."
  (:require [clojure.test :refer [deftest is]]
            [cloview.core.store :as st]
            [cloview.core.tree :as t]
            [cloview-app.core :as app]))

(defn- memory-storage!
  "In-memory :storage/* effects for tests; returns the atom that plays the disk."
  []
  (let [disk (atom {})]
    (st/reg-fx :storage/put (fn [{:keys [key value]} _] (swap! disk assoc key value)))
    (st/reg-fx :storage/get (fn [{:keys [key on-loaded]} {:keys [dispatch!]}]
                              (dispatch! (conj on-loaded (get @disk key)))))
    disk))

(deftest adding-and-removing-notes
  (app/register!)
  (let [disk (memory-storage!)
        s (st/create-store {:db app/initial-db})]
    (st/dispatch! s [:notes/load])
    (is (true? (:loaded? (st/db s))))
    (st/dispatch! s [:notes/draft "  buy milk "])
    (st/dispatch! s [:notes/add])
    (is (= [{:id 1 :text "buy milk"}] (:notes (st/db s))))
    (is (= "" (:draft (st/db s))))
    (is (= [{:id 1 :text "buy milk"}] (get @disk app/storage-key)) "saved")
    (st/dispatch! s [:notes/add])
    (is (= 1 (count (:notes (st/db s)))) "a blank draft adds nothing")
    (st/dispatch! s [:notes/draft "call Ada"])
    (st/dispatch! s [:notes/add "call Ada"])
    (st/dispatch! s [:notes/remove 1])
    (is (= [{:id 2 :text "call Ada"}] (:notes (st/db s))))
    (let [s2 (st/create-store {:db app/initial-db})]
      (st/dispatch! s2 [:notes/load])
      (is (= [{:id 2 :text "call Ada"}] (:notes (st/db s2))) "loaded again after a restart"))))

(deftest the-view
  (app/register!)
  (let [tree (t/normalize (app/root (assoc app/initial-db :loaded? true :notes [{:id 1 :text "a"}])))]
    (is (empty? (t/problems tree)))
    (is (= "1 note(s)" (get-in (t/find-by-test-id tree "count") [:props :text])))
    (is (= [:notes/remove 1] (get-in (t/find-by-test-id tree "remove-1") [:props :on-press])))
    (is (true? (get-in (t/find-by-test-id tree "add") [:props :disabled?])) "nothing to add yet"))
  (is (= "Loading..." (get-in (t/find-by-test-id (t/normalize (app/root app/initial-db)) "empty") [:props :text]))))
