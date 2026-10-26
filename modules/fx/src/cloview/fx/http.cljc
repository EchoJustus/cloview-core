;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.fx.http
  "The :http effect on every host.

    {:http {:method     :get                 ; :get :post :put :patch :delete
            :url        \"http://192.168.1.20:47812/cloview-sync\"
            :headers    {\"Accept\" \"application/edn\"}
            :body       \"...\"               ; string or nil
            :timeout-ms 15000
            :on-success [:sync/response]     ; dispatched with {:status :headers :body}
            :on-failure [:sync/failed]}}     ; dispatched with {:status :body} or {:error}

  2xx responses go to :on-success, everything else (non-2xx, timeouts, I/O errors) to
  :on-failure. Bodies are text; EDN/JSON parsing belongs to the handler (pure code).
  Response :headers are filled on the JVM only in V1 ({} on CLR and Dart).
  Hosts: java.net.http (JVM 11+), System.Net.Http.HttpClient (CLR), package:http (Dart)."
  (:require [clojure.string :as str]
            [cloview.core.store :as st]
            [cloview.core.text :as text]
            #?(:cljd ["package:http/http.dart" :as http])))

(def default-timeout-ms 15000)

(defn normalize-request
  "Validate and fill defaults of an :http effect value."
  [{:keys [method url headers body timeout-ms on-success on-failure] :as req}]
  (when-not (and (string? url) (or (str/starts-with? url "http://") (str/starts-with? url "https://")))
    (throw (ex-info ":http needs an absolute http(s) :url" {:request req})))
  (when-not (or (nil? on-success) (vector? on-success))
    (throw (ex-info ":http :on-success must be an event vector" {:request req})))
  (when-not (or (nil? on-failure) (vector? on-failure))
    (throw (ex-info ":http :on-failure must be an event vector" {:request req})))
  (let [method (or method :get)]
    (when-not (contains? #{:get :post :put :patch :delete} method)
      (throw (ex-info "Unsupported :http :method" {:request req})))
    {:method method
     :method-name (text/ascii-upper (name method))
     :url url
     :headers (or headers {})
     :body body
     :timeout-ms (or timeout-ms default-timeout-ms)
     :on-success on-success
     :on-failure on-failure}))

(defn success? [status] (and (number? status) (<= 200 status 299)))

(defn- deliver! [dispatch! {:keys [on-success on-failure]} result]
  (if (and (:status result) (success? (:status result)) (nil? (:error result)))
    (when on-success (dispatch! (conj on-success result)))
    (when on-failure (dispatch! (conj on-failure result)))))

#?(:cljd nil
   :clj
   (def ^:private client
     (delay (-> (java.net.http.HttpClient/newBuilder)
                (.connectTimeout (java.time.Duration/ofMillis 10000))
                (.build)))))

#?(:cljr
   (def ^:private client (delay (System.Net.Http.HttpClient.))))

(defn- send! [{:keys [method-name url headers body timeout-ms] :as req} dispatch!]
  #?(:cljd
     (let [uri (Uri/parse url)
           hs (into {} (map (fn [[k v]] [(str k) (str v)])) headers)
           fut (case method-name
                 "GET" (http/get uri .headers hs)
                 "DELETE" (http/delete uri .headers hs)
                 "POST" (http/post uri .headers hs .body body)
                 "PUT" (http/put uri .headers hs .body body)
                 "PATCH" (http/patch uri .headers hs .body body))]
       (-> fut
           (.timeout (Duration .milliseconds (int timeout-ms)))
           (.then (fn [^http/Response r]
                    (deliver! dispatch! req {:status (.-statusCode r)
                                             :headers {}
                                             :body (.-body r)})
                    nil))
           (.catchError (fn [e]
                          (deliver! dispatch! req {:error (str e)})
                          nil))))
     :clj
     (let [publisher (if (some? body)
                       (java.net.http.HttpRequest$BodyPublishers/ofString (str body))
                       (java.net.http.HttpRequest$BodyPublishers/noBody))
           builder (-> (java.net.http.HttpRequest/newBuilder (java.net.URI. url))
                       (.timeout (java.time.Duration/ofMillis (long timeout-ms)))
                       (.method ^String method-name publisher))
           _ (doseq [[k v] headers] (.header builder (str k) (str v)))
           request (.build builder)]
       (-> (.sendAsync ^java.net.http.HttpClient @client request
                       (java.net.http.HttpResponse$BodyHandlers/ofString))
           (.whenComplete
            (reify java.util.function.BiConsumer
              (accept [_ resp ex]
                (deliver! dispatch! req
                          (if ex
                            {:error (str ex)}
                            (let [^java.net.http.HttpResponse resp resp]
                              {:status (.statusCode resp)
                               :headers (into {} (map (fn [[k vs]] [k (str/join "," vs)]))
                                              (.map (.headers resp)))
                               :body (.body resp)}))))))))
     :cljr
     (future
       (let [result
             (try
               (let [msg (System.Net.Http.HttpRequestMessage.
                          (System.Net.Http.HttpMethod. ^String method-name) ^String url)
                     content (when (some? body) (System.Net.Http.StringContent. (str body)))
                     _ (when content (set! (.Content msg) content))
                     _ (doseq [[k v] headers]
                         (if (and content (= "content-type" (text/ascii-lower (str k))))
                           (do (.Remove (.Headers content) "Content-Type")
                               (.TryAddWithoutValidation (.Headers content) "Content-Type" (str v)))
                           (.TryAddWithoutValidation (.Headers msg) (str k) (str v))))
                     cts (System.Threading.CancellationTokenSource. (int timeout-ms))
                     resp (.Result (.SendAsync ^System.Net.Http.HttpClient @client msg (.Token cts)))
                     text (.Result (.ReadAsStringAsync (.Content resp)))]
                 {:status (System.Convert/ToInt32 (.StatusCode resp))
                  :headers {}
                  :body text})
               (catch System.Exception ex
                 {:error (str (or (.InnerException ex) ex))}))]
         (deliver! dispatch! req result)))))

(defn- run-http! [value {:keys [dispatch!]}]
  (let [reqs (if (map? value) [value] value)]
    (doseq [r reqs]
      (send! (normalize-request r) dispatch!))))

(defn install!
  "Register the :http effect. Idempotent."
  []
  (st/reg-fx :http run-http!)
  :installed)
