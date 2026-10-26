;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.fx.timer
  "Timers and the clock coeffect, for every host.

  Effects
    :dispatch-later  {:ms 250 :dispatch [:ev]}   (or a vector of such maps)
    :timer/start     {:id :workout/clock :ms 1000 :dispatch [:workout/tick]}
                     repeating; the tick event is dispatched every :ms until stopped.
                     Starting an id that is already running restarts it.
    :timer/stop      :workout/clock               (id, or a vector of ids)
  Coeffect
    :now             epoch milliseconds — (st/inject-cofx :now) keeps handlers pure
                     (e.g. workout completion timestamps).

  Callbacks fire on a background thread (JVM/CLR) or the event loop (Dart) and go
  through the store's dispatch!, which marshals to the UI thread."
  (:require [cloview.core.store :as st]
            #?(:cljd ["dart:async" :as async])))

(defn now-ms
  "Current time in epoch milliseconds."
  []
  #?(:cljd (.-millisecondsSinceEpoch (DateTime/now))
     :clj (System/currentTimeMillis)
     :cljr (.ToUnixTimeMilliseconds System.DateTimeOffset/UtcNow)))

;; ---------------------------------------------------------------------------
;; Host scheduling primitives: (schedule! ms f) and (schedule-repeating! ms f) both
;; return a cancel fn. Public for other cloview modules (cloview.bridge request
;; timeouts); apps use the effects below.

#?(:cljd nil
   :clj
   (def ^:private ^java.util.concurrent.ScheduledExecutorService scheduler
     (delay
       (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
        (reify java.util.concurrent.ThreadFactory
          (newThread [_ r]
            (doto (Thread. ^Runnable r "cloview-timer")
              (.setDaemon true))))))))

#?(:cljr
   (def ^:private live-timers
     "Strong references so System.Threading.Timer instances are not collected."
     (atom #{})))

(defn schedule!
  "Run (f) once after `ms` milliseconds on a background thread (JVM/CLR) or the event
  loop (Dart). Returns a cancel fn."
  [ms f]
  #?(:cljd (let [t (async/Timer (Duration .milliseconds (int ms)) (fn [] (f) nil))]
             (fn [] (.cancel t)))
     :clj (let [fut (.schedule ^java.util.concurrent.ScheduledExecutorService @scheduler
                               ^Runnable (fn [] (f))
                               (long ms)
                               java.util.concurrent.TimeUnit/MILLISECONDS)]
            (fn [] (.cancel fut false)))
     :cljr (let [holder (atom nil)
                 cb (gen-delegate System.Threading.TimerCallback [_state]
                                  (swap! live-timers disj @holder)
                                  (f))
                 timer (System.Threading.Timer. cb nil (long ms) (long -1))]
             (reset! holder timer)
             (swap! live-timers conj timer)
             (fn [] (swap! live-timers disj timer) (.Dispose timer)))))

(defn schedule-repeating!
  "Run (f) every `ms` milliseconds until the returned cancel fn is called."
  [ms f]
  #?(:cljd (let [t (async/Timer.periodic (Duration .milliseconds (int ms)) (fn [_] (f) nil))]
             (fn [] (.cancel t)))
     :clj (let [fut (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService @scheduler
                                          ^Runnable (fn [] (f))
                                          (long ms) (long ms)
                                          java.util.concurrent.TimeUnit/MILLISECONDS)]
            (fn [] (.cancel fut false)))
     :cljr (let [cb (gen-delegate System.Threading.TimerCallback [_state] (f))
                 timer (System.Threading.Timer. cb nil (long ms) (long ms))]
             (swap! live-timers conj timer)
             (fn [] (swap! live-timers disj timer) (.Dispose timer)))))

;; ---------------------------------------------------------------------------
;; Effects

(defonce ^:private running (atom {}))

(defn running-ids
  "Ids of repeating timers currently running."
  []
  (set (keys @running)))

(defn- stop-timer! [id]
  (when-some [cancel (get @running id)]
    (cancel)
    (swap! running dissoc id))
  nil)

(defn- start-timer! [{:keys [id ms dispatch]} {:keys [dispatch!]}]
  (when (or (nil? id) (not (number? ms)) (not (pos? ms)) (not (vector? dispatch)))
    (throw (ex-info ":timer/start needs {:id kw :ms pos-number :dispatch [event]}"
                    {:value {:id id :ms ms :dispatch dispatch}})))
  (stop-timer! id)
  (swap! running assoc id (schedule-repeating! ms (fn [] (dispatch! dispatch))))
  nil)

(defn- dispatch-later! [value {:keys [dispatch!]}]
  (doseq [{:keys [ms dispatch]} (if (map? value) [value] value)
          :when (vector? dispatch)]
    (schedule! (max 0 (or ms 0)) (fn [] (dispatch! dispatch)))))

(defn install!
  "Register :dispatch-later, :timer/start, :timer/stop and the :now coeffect.
  Idempotent; call once at app start (the host app modules do it for you)."
  []
  (st/reg-fx :dispatch-later dispatch-later!)
  (st/reg-fx :timer/start start-timer!)
  (st/reg-fx :timer/stop (fn [ids _] (doseq [id (if (sequential? ids) ids [ids])] (stop-timer! id))))
  (st/reg-cofx :now (fn [cofx] (assoc cofx :now (now-ms))))
  :installed)

(defn stop-all!
  "Cancel every repeating timer (app shutdown, tests)."
  []
  (doseq [id (keys @running)] (stop-timer! id)))
