;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.bridge.loopback
  "In-process transport: a Clojure function plays the backend, speaking the same wire
  protocol (every message is encoded to a line and decoded again, so the protocol code
  is exercised exactly as with a child process).

  Used by the tests on every host, and usable by apps that want to start with an
  in-process implementation of their backend contract and move it to another language
  later without touching the views or events.

    (loopback/transport-fn
      (fn [{:keys [method params]} {:keys [respond! fail! notify!]}]
        (case method
          \"math/add\" (respond! (+ (:a params) (:b params)))
          (fail! -32601 (str \"unknown method \" method)))))

  The handler receives every request and notification except the protocol's own
  $/initialize, $/shutdown and $/ping, which are answered here (override them by
  handling them yourself). For a request it must call exactly one of respond! / fail!,
  now or later (e.g. from a timer); notify! sends notifications ($/progress, $/stream)."
  (:require [cloview.bridge.protocol :as p]))

(defn transport-fn
  "A transport constructor (the shape cloview.bridge's :transport-fn option takes):
  (fn [launch-spec callbacks] transport). `opts`:
    :name :version :methods   reported by the built-in $/initialize answer
    :async!   (fn [f]) runs replies later (default: synchronously)
    :log      atom; every line sent and received is conj'ed as [:in|:out line]"
  ([handler] (transport-fn handler {}))
  ([handler {:keys [name version methods async! log] :or {name "loopback" version "0" methods []}}]
   (fn [_spec {:keys [on-line on-exit]}]
     (let [open? (atom true)
           async! (or async! (fn [f] (f)))
           emit! (fn [msg]
                   (let [line (p/encode msg)]
                     (async! (fn []
                               (when @open?
                                 (when log (swap! log conj [:in line]))
                                 (on-line line))))))
           close! (fn []
                    ;; test-and-set with swap-vals! (portable; see cloview.core.store/try-acquire!)
                    (let [[was-open? _] (swap-vals! open? (constantly false))]
                      (when was-open?
                        (async! (fn [] (on-exit {:exit 0}))))))]
       {:send! (fn [line]
                 (when log (swap! log conj [:out line]))
                 (when @open?
                   (let [{:keys [kind message]} (p/decode line)
                         {:keys [id method params]} message
                         replies {:respond! (fn [result] (emit! (p/result-response id result)))
                                  :fail! (fn ([code msg] (emit! (p/error-response id code msg)))
                                           ([code msg data] (emit! (p/error-response id code msg data))))
                                  :notify! (fn [m ps] (emit! (p/notification m ps)))}]
                     (case kind
                       :request
                       (cond
                         (= method p/initialize-method)
                         ((:respond! replies) {:protocol p/protocol-version :name name :version version
                                               :methods methods})
                         (= method p/ping-method) ((:respond! replies) "pong")
                         (= method p/shutdown-method) (do ((:respond! replies) nil) (close!))
                         :else (handler message replies))
                       :notification (handler message replies)
                       nil))))
        :close! close!
        :kill! close!
        :pid 0}))))
