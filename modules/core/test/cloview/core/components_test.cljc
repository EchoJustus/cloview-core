;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.components-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloview.core.components :as c]
            [cloview.core.reconcile :as r]
            [cloview.core.store :as st]
            [cloview.core.tree :as t]))

(def muscle-options
  [{:id "pectoralis-major" :label "Pectoralis major"}
   {:id "trapezius" :label "Trapezius"}
   {:id "biceps" :label "Biceps"}])

(deftest chip-group-structure
  (let [n (t/normalize [c/chip-group {:options muscle-options
                                      :selected #{"trapezius"}
                                      :on-toggle [:filter/toggle :muscles]
                                      :on-clear [:filter/clear :muscles]
                                      :test-id "muscles"}])
        chips (filter #(= :ui/toggle (:tag %)) (:children n))
        clear (t/find-by-test-id n "muscles/clear")]
    (is (= :ui/wrap (:tag n)))
    (is (= "muscles" (get-in n [:props :test-id])))
    (is (= ["pectoralis-major" "trapezius" "biceps"] (mapv :key chips)) "chips keyed by option id, order kept")
    (is (= [false true false] (mapv #(get-in % [:props :checked]) chips)))
    (is (= [:filter/toggle :muscles "biceps"] (get-in (last chips) [:props :on-toggle])))
    (is (= "Biceps" (get-in (last chips) [:props :label])))
    (is (= [:filter/clear :muscles] (get-in clear [:props :on-press])))
    (is (not (get-in clear [:props :disabled?])))
    (is (= [] (t/problems n)))))

(deftest chip-group-edge-cases
  (testing "nothing selected: clear is disabled; no :on-clear: no button"
    (let [n (t/normalize [c/chip-group {:options muscle-options :selected [] :on-clear [:clear]}])]
      (is (true? (get-in (last (:children n)) [:props :disabled?])))))
  (is (not-any? #(= :ui/button (:tag %))
                (:children (t/normalize [c/chip-group {:options muscle-options}]))))
  (testing "empty options show the empty text and keep the :ui/wrap root"
    (let [n (t/normalize [c/chip-group {:options [] :empty-text "No equipment yet"}])]
      (is (= :ui/wrap (:tag n)))
      (is (= "No equipment yet" (get-in (first (:children n)) [:props :text])))))
  (testing "a disabled option, and labels default to ids"
    (let [n (t/normalize [c/chip-group {:options [{:id "low"} {:id "high" :disabled? true}]}])]
      (is (= ["low" "high"] (mapv #(get-in % [:props :label]) (:children n))))
      (is (= [nil true] (mapv #(get-in % [:props :disabled?]) (:children n)))))))

(st/reg-event-db :ct/toggle (fn [db [_ dim id on?]] (update db dim (if on? conj disj) id)))

(deftest chip-toggle-round-trip-through-the-reconciler
  ;; the path the WPF host takes: user toggles -> event -> db -> nothing written back;
  ;; an external db change -> exactly one :checked patch, its echo swallowed
  (let [events (atom [])
        store (st/create-store {:db {:muscles #{}} :on-event (fn [e _] (swap! events conj e))})
        view (fn [db] [c/chip-group {:options muscle-options :selected (:muscles db)
                                     :on-toggle [:ct/toggle :muscles] :test-id "m"}])
        mock (r/mock-host {:echo-controlled? true})
        rctx (r/create-reconciler (:ops mock) {:dispatch! #(st/dispatch! store %)})]
    (r/render! rctx (view (st/db store)))
    (let [chip (r/handle-by-test-id rctx "m/trapezius")]
      (r/clear-log! mock)
      (r/mock-fire! mock chip :on-toggle true)
      (is (= #{"trapezius"} (:muscles (st/db store))))
      (r/render! rctx (view (st/db store)))
      (is (= [] (r/op-log mock)) "the host already shows the toggled state: nothing is written back")
      (st/dispatch! store [:ct/toggle :muscles "trapezius" false])
      (r/render! rctx (view (st/db store)))
      (is (= [[:patch chip {:checked false}]] (r/op-log mock)) "an external change is one patch")
      (is (= [[:ct/toggle :muscles "trapezius" true] [:ct/toggle :muscles "trapezius" false]] @events)
          "the mock's synchronous echo of the programmatic write was not dispatched"))))

(def calendar-fixture
  ;; 2026-10: Thursday the 1st, so the grid starts on Monday 2026-09-28
  {:month "2026-10"
   :weekdays ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"]
   :cells (vec (concat
                [{:date "2026-09-28" :day 28 :in-month? false :duration-min 0.0 :sets 0
                  :planned-items 0 :completed-items 0 :plan-complete? false}
                 {:date nil :day nil :in-month? false :duration-min 0.0 :sets 0
                  :planned-items 0 :completed-items 0 :plan-complete? false}
                 {:date "2026-09-30" :day 30 :in-month? false :duration-min 0.0 :sets 0
                  :planned-items 0 :completed-items 0 :plan-complete? false}
                 {:date "2026-10-01" :day 1 :in-month? true :duration-min 44.6 :sets 2
                  :planned-items 2 :completed-items 1 :plan-complete? false}]
                (for [d (range 2 40)]
                  {:date (str "2026-10-" (if (< d 10) (str "0" d) d)) :day d :in-month? true
                   :duration-min 0.0 :sets 0 :planned-items 0 :completed-items 0 :plan-complete? false})))})

(deftest month-grid-structure
  (let [n (t/normalize [c/month-grid {:calendar calendar-fixture
                                      :selected "2026-10-01"
                                      :today "2026-10-02"
                                      :kcal {"2026-10-01" 312.4}
                                      :on-select [:calendar/select-date]
                                      :on-prev [:calendar/prev] :on-next [:calendar/next]
                                      :on-today [:calendar/today]
                                      :test-id "cal"}])
        grid (second (:children n))
        cells (filter #(= :ui/button (:tag %)) (:children grid))
        oct1 (t/find-by-test-id n "cal/2026-10-01")]
    (is (= :ui/column (:tag n)))
    (is (= 49 (count (:children grid))) "7 weekday names + 42 cells")
    (is (= 42 (count cells)))
    (is (= [0 0] [(get-in (first cells) [:props :grid/col]) (dec (get-in (first cells) [:props :grid/row]))]))
    (is (= [6 6] [(get-in (last cells) [:props :grid/col]) (get-in (last cells) [:props :grid/row])]))
    (testing "every value is written out; the selection is bracketed (not colour only)"
      (is (= "[1]\n2 sets · 44.6 min\n312 kcal\n1/2 done" (get-in oct1 [:props :label])))
      (is (str/includes? (get-in (t/find-by-test-id n "cal/2026-10-02") [:props :label]) "(today)")))
    (is (= [:calendar/select-date "2026-10-01"] (get-in oct1 [:props :on-press])))
    (testing "adjacent-month cells select their exact date; blank cells emit nothing"
      (is (= [:calendar/select-date "2026-09-28"] (get-in (first cells) [:props :on-press])))
      (is (nil? (get-in (second cells) [:props :on-press])))
      (is (true? (get-in (second cells) [:props :disabled?]))))
    (is (= [] (t/problems n)))))

(deftest month-grid-unknown-energy-and-bounds
  (let [cal (assoc calendar-fixture :month "1900-01")
        n (t/normalize [c/month-grid {:calendar cal :on-prev [:p] :on-next [:n] :test-id "cal"}])]
    (is (true? (get-in (t/find-by-test-id n "cal/prev") [:props :disabled?])) "1900-01 is the lower bound")
    (is (nil? (get-in (t/find-by-test-id n "cal/prev") [:props :on-press])))
    (is (= [:n] (get-in (t/find-by-test-id n "cal/next") [:props :on-press])))
    (is (str/includes? (get-in (t/find-by-test-id n "cal/2026-10-01") [:props :label]) "? kcal")
        "activity without a known kcal value is shown as unknown, never 0"))
  (let [n (t/normalize [c/month-grid {:calendar (assoc calendar-fixture :month "2100-12")
                                      :on-prev [:p] :on-next [:n] :test-id "cal"}])]
    (is (true? (get-in (t/find-by-test-id n "cal/next") [:props :disabled?])) "2100-12 is the upper bound")))

(deftest keyword-registration
  (c/register!)
  (is (= :ui/wrap (:tag (t/normalize [:cloview/chip-group {:options muscle-options}]))))
  (is (= :ui/column (:tag (t/normalize [:cloview/month-grid {:calendar calendar-fixture}])))))
