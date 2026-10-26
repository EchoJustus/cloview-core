;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.fx.lan-sync
  "LAN pairing & sync between devices (benchmark: Android phone <-> Windows desktop).

  Status (Stage 2-2): the wire protocol, codecs, pairing code and the merge function are
  implemented and unit-tested (pure .cljc, identical on every host). The network effect
  runners are registered with their final value shapes but report
  {:error :not-implemented} through :on-failure. They are Stage 3 work, per host
  (JVM: DatagramSocket + com.sun.net.httpserver; CLR: UdpClient + HttpListener;
  Dart: RawDatagramSocket + HttpServer from dart:io), together with what the fitness
  benchmark's optional LAN profile (INT-001..003) requires beyond this skeleton:
  invitation-based pairing with authenticated encryption, per-entity versions with
  explicit conflict resolution, bounded and hash-checked media transfer, a stale-snapshot
  and active-session guard before committing, and cancellation on page disposal.
  See docs/FITNESS_CAPABILITY_MAP.md in cloview-examples (LAN sync alignment).

  Protocol (version 1)
    discovery  UDP broadcast of an encoded :hello envelope to `discovery-port`;
               listeners answer with their own :hello (unicast)
    transport  HTTP POST http://<host>:<port>/cloview-sync, body = encoded envelope,
               Content-Type application/edn; the response body is an envelope too
    envelope   {:cloview.sync/v 1 :type t :device {:id :name :platform} :sent-at ms :body b}
    types      :hello     body {:port n :pairing? bool}
               :pair      body {:code \"123456\"}             -> :paired / :rejected
               :pull      body {:since ms :collections #{...}} -> :push
               :push      body {:records {collection [record ...]}}  -> :ack
    records    maps with :id (string/uuid), :updated-at (epoch ms), :device (origin id),
               optional :deleted? true (tombstone). Merge is last-writer-wins on
               [:updated-at :device] per :id — deterministic on every device.

  Effects (value shapes are final; runners land in Stage 3)
    :lan/advertise {:device {...} :port 47812}                 ; start answering discovery
    :lan/discover  {:timeout-ms 3000 :on-found [:ev] :on-done [:ev] :on-failure [:ev]}
    :lan/serve     {:port 47812 :on-request [:ev] :on-failure [:ev]}
                   ; each request dispatches (conj on-request {:envelope e :reply-id id});
                   ; the handler answers with the :lan/reply effect
    :lan/reply     {:reply-id id :envelope e}
    :lan/request   {:peer {:host h :port p} :envelope e :on-response [:ev] :on-failure [:ev]}
    :lan/stop      {}"
  (:require [clojure.edn :as edn]
            [cloview.core.order :as order]
            [cloview.core.store :as st]))

(def protocol-version 1)
(def discovery-port 47811)
(def sync-port 47812)
(def sync-path "/cloview-sync")

(def envelope-types #{:hello :pair :paired :rejected :pull :push :ack :error})

(defn envelope
  "Build a protocol envelope."
  [type device sent-at body]
  (when-not (contains? envelope-types type)
    (throw (ex-info "Unknown envelope type" {:type type})))
  {:cloview.sync/v protocol-version
   :type type
   :device (select-keys device [:id :name :platform])
   :sent-at sent-at
   :body body})

(defn encode
  "Envelope -> wire string (EDN)."
  [env]
  (pr-str env))

(defn decode
  "Wire string -> envelope. Throws ex-info on malformed or incompatible input."
  [s]
  (let [env (try
              (edn/read-string s)
              (catch #?(:cljd dynamic :clj Throwable :cljr System.Exception) ex
                (throw (ex-info "Malformed sync envelope" {:cause (str ex)}))))]
    (when-not (map? env)
      (throw (ex-info "Sync envelope must be a map" {:value env})))
    (when-not (= protocol-version (:cloview.sync/v env))
      (throw (ex-info "Unsupported sync protocol version" {:version (:cloview.sync/v env)})))
    (when-not (contains? envelope-types (:type env))
      (throw (ex-info "Unknown envelope type" {:type (:type env)})))
    env))

(defn pairing-code
  "Six-digit pairing code derived from a random integer `n` supplied by the caller
  (keeps this namespace pure). Shown on one device, typed on the other."
  [n]
  (let [s (str (mod (if (neg? n) (- n) n) 1000000))]
    (str (apply str (repeat (- 6 (count s)) "0")) s)))

(defn- newer? [a b]
  ;; deterministic total order: :updated-at first, then the origin device id compared
  ;; ORDINALLY — every peer must pick the same winner, and ClojureCLR 1.12's default
  ;; string compare is culture-sensitive (see cloview.core.order)
  (let [ta (or (:updated-at a) 0)
        tb (or (:updated-at b) 0)]
    (if (= ta tb)
      (pos? (order/compare-str (str (:device a)) (str (:device b))))
      (> ta tb))))

(defn merge-records
  "Last-writer-wins merge of two record vectors (same collection) by :id. Tombstones
  (:deleted? true) are kept so deletions propagate. Result is sorted by :id (ordinal)."
  [local remote]
  (->> (concat local remote)
       (reduce (fn [m r]
                 (let [id (:id r)
                       cur (get m id)]
                   (if (or (nil? cur) (newer? r cur)) (assoc m id r) m)))
               {})
       vals
       (order/sort-by-str (comp str :id))))

(defn merge-collections
  "Merge {collection [records]} maps collection by collection."
  [local remote]
  (reduce (fn [acc k] (assoc acc k (merge-records (get local k) (get remote k))))
          {}
          (distinct (concat (keys local) (keys remote)))))

(defn changes-since
  "Records modified after `since` (epoch ms), per collection."
  [collections since]
  (reduce-kv (fn [acc k rs]
               (let [changed (filterv #(> (or (:updated-at %) 0) since) rs)]
                 (if (seq changed) (assoc acc k changed) acc)))
             {}
             collections))

(defn live-records
  "Records without tombstones."
  [records]
  (filterv (complement :deleted?) records))

;; ---------------------------------------------------------------------------
;; Effect registration (contract only until the Stage 3 runners)

(def effect-ids [:lan/advertise :lan/discover :lan/serve :lan/reply :lan/request :lan/stop])

(defn- not-implemented [effect-id]
  (fn [value {:keys [dispatch!]}]
    (when-some [ev (:on-failure value)]
      (dispatch! (conj ev {:error :not-implemented :effect effect-id})))))

(defn install!
  "Register the :lan/* effects. Until Stage 3 every runner reports :not-implemented
  through :on-failure so apps can already be written against the final contract."
  []
  (doseq [id effect-ids] (st/reg-fx id (not-implemented id)))
  :installed)
