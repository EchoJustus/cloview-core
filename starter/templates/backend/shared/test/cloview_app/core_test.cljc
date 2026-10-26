(ns cloview-app.core-test
  "The app against an in-process stand-in of the backend (same wire protocol, no
  process): runs on the JVM (bb test) and on the CLR (bb test:clr)."
  (:require [clojure.test :refer [deftest is]]
            [cloview.bridge :as bridge]
            [cloview.bridge.loopback :as loopback]
            [cloview.core.store :as st]
            [cloview.core.tree :as t]
            [cloview-app.core :as app]))

(def ^:private manifest
  {:manifest_version 1 :id "engine" :protocol "cloview-bridge/1"
   :command {:default ["unused"]} :start "on-launch"})

(deftest asks-the-backend
  (app/register!)
  (let [s (st/create-store {:db app/initial-db})]
    (bridge/install! {:manifests [manifest]
                      :transport-fn (loopback/transport-fn app/backend-contract {:name "stand-in"})
                      :dispatch! (fn [ev] (st/dispatch! s ev))
                      :on-status [:app/backend-status]})
    (is (= :ready (:backend (st/db s))))
    (st/dispatch! s [:text/input "hello"])
    (st/dispatch! s [:text/upper])
    (is (= "HELLO" (:result (st/db s))))
    (is (false? (:busy? (st/db s))))
    (bridge/stop! :engine)))

(deftest the-view
  (let [tree (t/normalize (app/root (assoc app/initial-db :input "x" :result "X" :backend :ready)))]
    (is (empty? (t/problems tree)))
    (is (= "X" (get-in (t/find-by-test-id tree "result") [:props :text])))
    (is (= "backend: ready" (get-in (t/find-by-test-id tree "backend") [:props :text])))
    (is (false? (get-in (t/find-by-test-id tree "upper") [:props :disabled?])))))
