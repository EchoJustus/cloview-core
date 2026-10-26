(ns cloview-app.core
  "Cloview App (Mode B: decoupled backend): state, events and the view are shared .cljc,
  while the work runs in a backend process (backend/, any language) that cloview starts,
  talks to over JSON-RPC on stdio, restarts if it crashes and stops on exit
  (docs/POLYGLOT_BACKEND_ARCHITECTURE.md in the cloview-core repository).

  Handlers ask the backend with the :backend/call effect; the answer comes back as an
  ordinary event. The entry points (javafx/, wpf/) install the bridge with the manifest
  backend/backend-manifest.json. Tests use an in-process stand-in with the same contract
  (`backend-contract`), which is also a quick way to prototype a method before writing
  it in the backend's language."
  (:require [clojure.string :as str]
            [cloview.core.store :as st]))

(def initial-db {:input "" :result nil :error nil :busy? false :backend :idle})

(defonce store (st/create-store {:db initial-db}))

(def handlers
  {:events-db
   {:text/input (fn [db [_ s]] (assoc db :input s))
    :text/done (fn [db [_ result]] (assoc db :result result :busy? false :error nil))
    :text/failed (fn [db [_ failure]] (assoc db :busy? false :error (:error failure)))
    ;; status changes of the backend process (:starting :ready :crashed ...)
    :app/backend-status (fn [db [_ _id status _info]] (assoc db :backend status))}
   :events-fx
   {:text/upper (fn [{:keys [db]} _]
                  {:db (assoc db :busy? true :error nil)
                   :backend/call {:method "text/upper"
                                  :params {:text (:input db)}
                                  :on-success [:text/done]
                                  :on-failure [:text/failed]}})}})

(defn register!
  "Register the handlers (idempotent; every entry point calls it)."
  []
  (st/register-handlers! handlers))

(defn backend-contract
  "The backend's methods as a Clojure function, for cloview.bridge.loopback (tests and
  prototyping): the same answers as backend/<language>/."
  [{:keys [method params]} {:keys [respond! fail!]}]
  (case method
    "text/upper" (respond! (str/upper-case (str (:text params))))
    (fail! -32601 (str "unknown method " method))))

(defn root
  "db -> hiccup."
  [db]
  [:ui/column {:gap 10 :style {:padding 16} :test-id "root"}
   [:ui/text {:test-id "title" :text "Cloview App" :style {:font-size 20 :font-weight :bold}}]
   [:ui/row {:gap 8}
    [:ui/text-input {:test-id "input" :value (:input db) :placeholder "Text for the backend"
                     :on-change [:text/input] :on-submit [:text/upper]}]
    [:ui/button {:test-id "upper" :label "Upper-case" :on-press [:text/upper]
                 :disabled? (or (:busy? db) (str/blank? (:input db)))}]]
   (cond
     (:error db) [:ui/text {:key :error :test-id "error" :wrap? true :style {:color "#B3261E"} :text (:error db)}]
     (:result db) [:ui/text {:key :result :test-id "result" :text (:result db)}]
     :else nil)
   [:ui/text {:test-id "backend" :style {:font-size 11 :color "#5F6368"}
              :text (str "backend: " (name (:backend db)))}]])
