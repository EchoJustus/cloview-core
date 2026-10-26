;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.reconcile-test
  "Headless verification of the keyed reconciler against the deterministic MockHostOps.
  Runs identically on Clojure/JVM and ClojureCLR."
  (:require [clojure.test :refer [deftest is testing]]
            [cloview.core.reconcile :as r]
            [cloview.core.store :as st]))

(st/reg-event-db :rt/set-name (fn [db [_ s]] (assoc db :name s)))
(st/reg-event-db :rt/greet (fn [db _] (assoc db :greeting (str "Hello, " (:name db) "!"))))
(st/reg-event-db :rt/toggle (fn [db [_ v]] (assoc db :checked v)))
(st/reg-event-db :rt/other (fn [db _] (assoc db :other true)))

(defn greeter [{:keys [name greeting on-press]}]
  [:ui/column {:gap 8 :test-id "root"}
   [:ui/text {:test-id "title" :text "Greeter"}]
   [:ui/text-input {:test-id "name" :value (or name "") :on-change [:rt/set-name]}]
   [:ui/button {:test-id "greet" :label "Greet" :on-press (or on-press [:rt/greet])}]
   (when greeting [:ui/text {:key :greeting :test-id "greeting" :text greeting}])])

(defn- setup
  ([] (setup {}))
  ([mock-opts]
   (let [events (atom [])
         store (st/create-store {:db {:name ""}
                                 :on-event (fn [e _] (swap! events conj e))})
         mock (r/mock-host mock-opts)
         rctx (r/create-reconciler (:ops mock) {:dispatch! #(st/dispatch! store %)})]
     {:store store :mock mock :rctx rctx :events events})))

(defn- render! [{:keys [rctx store]} view-fn]
  (r/render! rctx (view-fn (st/db store))))

(deftest mount-produces-deterministic-op-log
  (let [{:keys [mock] :as env} (setup)]
    (render! env greeter)
    (is (= [[:create 1 :ui/column]
            [:patch 1 {:gap 8 :test-id "root"}]
            [:create 2 :ui/text]
            [:patch 2 {:test-id "title" :text "Greeter"}]
            [:create 3 :ui/text-input]
            [:patch 3 {:test-id "name" :value ""}]
            [:create 4 :ui/button]
            [:patch 4 {:test-id "greet" :label "Greet"}]
            [:children 1 [2 3 4]]
            [:slot 1 2 0] [:slot 1 3 1] [:slot 1 4 2]]
           (r/op-log mock)))
    (is (= 1 (r/root-handle (:rctx env))))
    (is (= 3 (r/handle-by-test-id (:rctx env) "name")))))

(deftest identical-render-is-a-no-op
  (let [{:keys [mock] :as env} (setup)]
    (render! env greeter)
    (r/clear-log! mock)
    (render! env greeter)
    (is (= [] (r/op-log mock)))
    (is (= 2 (r/passes (:rctx env))))))

(deftest controlled-text-guard-and-caret
  (let [{:keys [mock store events] :as env} (setup)]
    (render! env greeter)
    (r/clear-log! mock)
    (testing "user typing: host already shows the value, so it is never written back"
      (r/mock-type! mock 3 "A" 1)
      (render! env greeter)
      (r/mock-type! mock 3 "Ad" 2)
      (render! env greeter)
      (is (= "Ad" (:name (st/db store))))
      (is (= [] (r/op-log mock)) "no :value patch while the user types")
      (is (= {:text "Ad" :caret 2} (select-keys (r/element mock 3) [:text :caret]))))
    (testing "external value change is written once, caret preserved, echo swallowed"
      (st/dispatch! store [:rt/set-name "Ada"])
      (render! env greeter)
      (is (= [[:patch 3 {:value "Ada"}]] (r/op-log mock)))
      (is (= {:text "Ada" :caret 2} (select-keys (r/element mock 3) [:text :caret])))
      (is (= [[:rt/set-name "A"] [:rt/set-name "Ad"] [:rt/set-name "Ada"]] @events)
          "the mock's synchronous TextChanged echo did not re-dispatch"))
    (testing "greet inserts the keyed greeting text"
      (r/clear-log! mock)
      (st/dispatch! store [:rt/greet])
      (render! env greeter)
      (is (= [[:create 5 :ui/text]
              [:patch 5 {:test-id "greeting" :text "Hello, Ada!"}]
              [:children 1 [2 3 4 5]]
              [:slot 1 5 3]]
             (r/op-log mock))))))

(deftest echo-without-guard-would-dispatch
  ;; Sanity check of the mock itself: outside a render pass the echo is a user event.
  (let [{:keys [mock events] :as env} (setup)]
    (render! env greeter)
    (r/mock-fire! mock 3 :on-change "typed")
    (is (= [[:rt/set-name "typed"]] @events))))

(deftest handler-changes-cost-no-host-ops
  (let [{:keys [mock store] :as env} (setup)]
    (render! env greeter)
    (r/clear-log! mock)
    (render! env #(greeter (assoc % :on-press [:rt/other])))
    (is (= [] (r/op-log mock)))
    (r/mock-fire! mock 4 :on-press nil)
    (is (true? (:other (st/db store))) "the stable trampoline reads the new handler")))

(deftest removed-props-are-patched-to-nil
  (let [{:keys [mock] :as env} (setup)]
    (r/render! (:rctx env) [:ui/text {:text "x" :style {:color "#f00"}}])
    (r/clear-log! mock)
    (r/render! (:rctx env) [:ui/text {:text "x"}])
    (is (= [[:patch 1 {:style nil}]] (r/op-log mock)))))

(defn- item-list [ids]
  (into [:ui/column {:gap 4}]
        (for [id ids] [:ui/button {:key id :label (name id)}])))

(deftest keyed-reordering-moves-instead-of-recreating
  (let [{:keys [mock rctx]} (setup)]
    (r/render! rctx (item-list [:a :b :c]))
    (is (= [:children 1 [2 3 4]] (some #(when (= :children (first %)) %) (r/op-log mock))))
    (r/clear-log! mock)
    (r/render! rctx (item-list [:c :a :b]))
    (is (= [[:children 1 [4 2 3]]
            [:slot 1 4 0] [:slot 1 2 1] [:slot 1 3 2]]
           (r/op-log mock))
        "same elements, new order: one replace-children!, slots for moved children only")
    (r/clear-log! mock)
    (r/render! rctx (item-list [:c :b :d]))
    (is (= [[:create 5 :ui/button]
            [:patch 5 {:label "d"}]
            [:children 1 [4 3 5]]
            [:destroy 2 :ui/button]
            [:slot 1 3 1]
            [:slot 1 5 2]]
           (r/op-log mock))
        "insert + remove: create new, detach, then destroy stale")))

(deftest tag-change-at-same-identity-replaces
  (let [{:keys [mock rctx]} (setup)]
    (r/render! rctx [:ui/column {} [:ui/text {:key :x :text "a"}]])
    (r/clear-log! mock)
    (r/render! rctx [:ui/column {} [:ui/button {:key :x :label "a"}]])
    (is (= [[:create 3 :ui/button]
            [:patch 3 {:label "a"}]
            [:children 1 [3]]
            [:destroy 2 :ui/text]
            [:slot 1 3 0]]
           (r/op-log mock)))))

(deftest root-replacement-returns-new-handle
  (let [{:keys [mock rctx]} (setup)]
    (is (= 1 (r/render! rctx [:ui/column {}])))
    (is (= 2 (r/render! rctx [:ui/row {}])))
    (is (= [[:create 1 :ui/column] [:create 2 :ui/row] [:destroy 1 :ui/column]]
           (r/op-log mock)))))

(deftest unmount-destroys-children-first
  (let [{:keys [mock rctx] :as env} (setup)]
    (render! env greeter)
    (r/clear-log! mock)
    (r/unmount! rctx)
    (is (= [[:destroy 2 :ui/text] [:destroy 3 :ui/text-input] [:destroy 4 :ui/button]
            [:destroy 1 :ui/column]]
           (r/op-log mock)))
    (is (nil? (r/root-handle rctx)))))

(deftest parent-layout-changes-reslot-children
  (let [{:keys [mock rctx]} (setup)]
    (r/render! rctx [:ui/row {:gap 4} [:ui/text {:text "a"}] [:ui/text-input {:value ""}]])
    (is (= 1 (get-in (r/element mock 3) [:slot :grow])) "implicit grow for inputs in rows")
    (r/clear-log! mock)
    (r/render! rctx [:ui/row {:gap 12} [:ui/text {:text "a"}] [:ui/text-input {:value ""}]])
    (is (= [[:patch 1 {:gap 12}] [:slot 1 2 0] [:slot 1 3 1]] (r/op-log mock)))))

(deftest controlled-checkbox
  (let [{:keys [mock rctx store]} (setup)
        view (fn [db] [:ui/checkbox {:checked (boolean (:checked db)) :label "ok"
                                     :on-toggle [:rt/toggle]}])]
    (r/render! rctx (view (st/db store)))
    (r/clear-log! mock)
    (r/mock-fire! mock 1 :on-toggle true)
    (is (true? (:checked (st/db store))))
    (r/render! rctx (view (st/db store)))
    (is (= [] (r/op-log mock)) "host already shows checked")
    (st/dispatch! store [:rt/toggle false])
    (r/render! rctx (view (st/db store)))
    (is (= [[:patch 1 {:checked false}]] (r/op-log mock)))))

(deftest native-nodes-diff-on-props-only
  (let [{:keys [mock rctx]} (setup)
        native-log (atom [])
        chart (fn [data]
                ;; fresh closures on every render, like a real view function
                [:ui/native {:key :chart
                             :test-id "chart"
                             :props {:data data}
                             :host {:create (fn [] (swap! native-log conj [:create]) :chart-object)
                                    :update (fn [obj p] (swap! native-log conj [:update obj p]))
                                    :dispose (fn [obj] (swap! native-log conj [:dispose obj]))}}])]
    (r/render! rctx (chart [1 2 3]))
    (is (= [[:create] [:update :chart-object {:data [1 2 3]}]] @native-log))
    (r/clear-log! mock)
    (reset! native-log [])
    (r/render! rctx (chart [1 2 3]))
    (is (= [] (r/op-log mock)) "new :host closures alone never patch")
    (is (= [] @native-log))
    (r/render! rctx (chart [4 5]))
    (is (= [[:patch 1 {:props {:data [4 5]}}] [:native-update 1 {:data [4 5]}]] (r/op-log mock)))
    (is (= [[:update :chart-object {:data [4 5]}]] @native-log))
    (r/unmount! rctx)
    (is (= [:dispose :chart-object] (last @native-log)))))

(deftest duplicate-keys-do-not-crash
  (let [{:keys [mock rctx]} (setup)]
    (r/render! rctx [:ui/column {} [:ui/text {:key :a :text "1"}] [:ui/text {:key :a :text "2"}]])
    (r/render! rctx [:ui/column {} [:ui/text {:key :a :text "1"}] [:ui/text {:key :a :text "3"}]])
    (is (= 2 (count (:children (r/element mock 1)))))))

(deftest counting-ops-wrapper
  (let [counter (atom {})
        mock (r/mock-host)
        rctx (r/create-reconciler (r/counting-ops (:ops mock) counter) {})]
    (r/render! rctx (item-list [:a :b]))
    (is (= {:create 3 :patch 3 :children 1 :slot 2} @counter))))

(deftest preserve-caret-rule
  (is (= 2 (r/preserve-caret 2 "Ada")))
  (is (= 3 (r/preserve-caret 9 "Ada")))
  (is (= 0 (r/preserve-caret nil "")))
  (is (= 0 (r/preserve-caret 4 nil))))
