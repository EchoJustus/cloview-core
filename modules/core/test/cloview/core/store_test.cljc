;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.store-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloview.core.store :as st]))

;; Handlers are registered at load time (like an app would) under a test-only namespace.

(st/reg-event-db :t/set-name (fn [db [_ s]] (assoc db :name s)))
(st/reg-event-db :t/greet (fn [db _] (assoc db :greeting (str "Hello, " (:name db) "!"))))

(st/reg-event-fx :t/chain
                 (fn [{:keys [db]} [_ n]]
                   {:db (update db :log (fnil conj []) [:chain n])
                    :dispatch [:t/note n]}))
(st/reg-event-db :t/note (fn [db [_ n]] (update db :log (fnil conj []) [:note n])))

(st/reg-event-fx :t/fx-order
                 (fn [_ _]
                   {:fx [[:t/record 1] nil [:t/record 2] [:dispatch [:t/note :from-fx]]]}))

(def recorded (atom []))
(st/reg-fx :t/record (fn [v _ctx] (swap! recorded conj v)))

(st/reg-cofx :t/now (fn [cofx] (assoc cofx :now 1700000000000)))
(st/reg-cofx :t/arg (fn [cofx arg] (assoc cofx :arg arg)))

(st/reg-event-fx :t/stamp
                 [(st/inject-cofx :t/now) (st/inject-cofx :t/arg :x)]
                 (fn [{:keys [db now arg]} _]
                   {:db (assoc db :stamped now :arg arg)}))

(st/reg-event-db :t/inc-counter
                 [(st/path :counters :a)]
                 (fn [n _] (inc (or n 0))))

(st/reg-event-fx :t/async-load
                 (fn [{:keys [db]} [_ id]]
                   {:db (assoc db :loading? true)
                    :t/fake-http {:id id :on-success [:t/loaded] :on-failure [:t/failed]}}))
(st/reg-event-db :t/loaded (fn [db [_ v]] (assoc db :loading? false :value v)))

(st/reg-fx :t/fake-http
           (fn [{:keys [id on-success]} {:keys [dispatch!]}]
             ;; a host runner would call dispatch! from a callback thread
             (dispatch! (conj on-success (str "user-" id)))))

(st/reg-event-db :t/boom (fn [_ _] (throw (ex-info "boom" {}))))

(deftest handle-is-pure
  (is (= {:db {:name "Ada"}} (st/handle {} [:t/set-name "Ada"])))
  (is (= "Hello, Ada!" (get-in (st/handle {:name "Ada"} [:t/greet]) [:db :greeting])))
  (testing "fx handlers return effects without running them"
    (is (= {:db {:loading? true}
            :t/fake-http {:id 7 :on-success [:t/loaded] :on-failure [:t/failed]}}
           (st/handle {} [:t/async-load 7])))))

(deftest dispatch-runs-to-completion-in-order
  (let [s (st/create-store {:db {}})]
    (st/dispatch! s [:t/chain 1])
    (is (= [[:chain 1] [:note 1]] (:log (st/db s))))))

(deftest fx-vector-is-ordered-and-skips-nil
  (reset! recorded [])
  (let [s (st/create-store)]
    (st/dispatch! s [:t/fx-order])
    (is (= [1 2] @recorded))
    (is (= [[:note :from-fx]] (:log (st/db s))))))

(deftest coeffects-and-interceptors
  (let [s (st/create-store)]
    (st/dispatch! s [:t/stamp])
    (is (= 1700000000000 (:stamped (st/db s))))
    (is (= :x (:arg (st/db s)))))
  (testing "path focuses db and writes back"
    (let [s (st/create-store {:db {:counters {:a 1 :b 5}}})]
      (st/dispatch! s [:t/inc-counter])
      (st/dispatch! s [:t/inc-counter])
      (is (= {:counters {:a 3 :b 5}} (st/db s))))))

(deftest effect-runners-dispatch-back
  (let [s (st/create-store)]
    (st/dispatch! s [:t/async-load 42])
    (is (= {:loading? false :value "user-42"} (st/db s)))))

(deftest executor-marshals-off-thread-dispatches
  (let [posted (atom [])
        on-ui? (atom false)
        s (st/create-store {:executor {:ui-thread? (fn [] @on-ui?)
                                       :post! (fn [f] (swap! posted conj f))}})]
    (st/dispatch! s [:t/set-name "Bob"])
    (is (= {} (st/db s)) "nothing processed off the UI thread")
    (is (= 1 (count @posted)))
    (reset! on-ui? true)
    ((first @posted))
    (is (= {:name "Bob"} (st/db s)))))

(deftest identical-db-does-not-notify-watchers
  (st/reg-event-db :t/noop (fn [db _] db))
  (let [s (st/create-store {:db {:a 1}})
        notified (atom 0)]
    (add-watch (st/app-db s) ::w (fn [_ _ _ _] (swap! notified inc)))
    (st/dispatch! s [:t/noop])
    (is (= 0 @notified))
    (st/dispatch! s [:t/set-name "x"])
    (is (= 1 @notified))))

(deftest errors
  (testing "default on-error rethrows, and the queue stays usable"
    (let [s (st/create-store)]
      (is (thrown? #?(:cljd dynamic :clj Exception :cljr System.Exception)
                   (st/dispatch! s [:t/boom])))
      (st/dispatch! s [:t/set-name "after"])
      (is (= "after" (:name (st/db s))))))
  (testing "custom on-error receives the event and processing continues"
    (let [errors (atom [])
          s (st/create-store {:on-error (fn [_ex info] (swap! errors conj info))})]
      (st/dispatch! s [:t/boom])
      (st/dispatch! s [:t/unknown-event])
      (is (= [{:event [:t/boom]} {:event [:t/unknown-event]}] @errors))))
  (testing "events must be vectors"
    (is (thrown? #?(:cljd dynamic :clj Exception :cljr System.Exception)
                 (st/dispatch! (st/create-store) :not-a-vector)))))

(deftest trace-hook
  (let [seen (atom [])
        s (st/create-store {:on-event (fn [e fx] (swap! seen conj [e (contains? fx :db)]))})]
    (st/dispatch! s [:t/set-name "Ada"])
    (st/dispatch! s [:t/greet])
    (is (= [[[:t/set-name "Ada"] true] [[:t/greet] true]] @seen))))

(deftest subscriptions-memoize-on-db-identity
  (let [calls (atom 0)]
    (st/reg-sub :t/upper (fn [db] (swap! calls inc) (some-> (:name db) str/upper-case)))
    (st/reg-sub :t/nth-char (fn [db i] (nth (:name db) i)))
    (st/clear-sub-cache!)
    (let [db1 {:name "ada"}]
      (is (= "ADA" (st/sub db1 :t/upper)))
      (is (= "ADA" (st/sub db1 :t/upper)))
      (is (= 1 @calls) "same db => cached")
      (is (= "BOB" (st/sub {:name "bob"} :t/upper)))
      (is (= 2 @calls))
      (is (= (nth "ada" 1) (st/sub db1 :t/nth-char 1))))))

(def feature-handlers
  {:events-db {:t2/set (fn [db [_ v]] (assoc db :v v))
               :t2/scoped {:interceptors [(st/path :nested)] :handler (fn [m [_ v]] (assoc m :v v))}}
   :events-fx {:t2/later {:interceptors [(st/inject-cofx :t/now)]
                          :handler (fn [{:keys [db now]} _] {:db (assoc db :at now)})}}
   :subs {:t2/v (fn [db] (:v db))}})

(deftest data-driven-registration
  (is (= #{:t2/set :t2/scoped :t2/later} (st/register-handlers! feature-handlers)))
  (let [s (st/create-store)]
    (st/dispatch! s [:t2/set 1])
    (st/dispatch! s [:t2/scoped 2])
    (st/dispatch! s [:t2/later])
    (is (= {:v 1 :nested {:v 2} :at 1700000000000} (st/db s)))
    (is (= 1 (st/sub (st/db s) :t2/v)))))

(deftest registry-introspection
  (is (st/registered? :event :t/set-name))
  (is (contains? (st/handler-ids :fx) :t/record))
  (is (not (st/registered? :event :t/never))))
