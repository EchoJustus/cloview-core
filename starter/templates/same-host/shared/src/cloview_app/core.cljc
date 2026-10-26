(ns cloview-app.core
  "Cloview App: state, events, effects and the view, shared verbatim by every target.

  Keep the app here. The target folders (javafx/, wpf/, flutter/) are thin entry points:
  they call `register!`, install the host's effects and mount `root`.

    state    one immutable map, the db, inside `store`
    events   data vectors such as [:notes/add], handled by pure functions (`handlers`)
    effects  maps that handlers return ({:storage/put ...}); the host runs them
    view     `root`: a pure function db -> canonical hiccup (:ui/column, :ui/text, ...)

  Handlers live in a def'd map registered by `register!`, not in top-level reg-* calls:
  ClojureDart compiles definitions only, so a top-level side effect would never run on
  Flutter."
  (:require [clojure.string :as str]
            [cloview.core.store :as st]))

(def initial-db {:draft "" :notes [] :loaded? false})

(defonce store (st/create-store {:db initial-db}))

(def storage-key :cloview-app/notes)

(defn add-note
  "db with the trimmed draft appended as a note (unchanged when the draft is blank)."
  [db]
  (let [text (str/trim (:draft db))]
    (if (str/blank? text)
      db
      (-> db
          (update :notes conj {:id (inc (reduce max 0 (map :id (:notes db)))) :text text})
          (assoc :draft "")))))

(defn- save [db] {:key storage-key :value (:notes db)})

(def handlers
  {:events-db
   {:notes/draft (fn [db [_ s]] (assoc db :draft s))
    :notes/loaded (fn [db [_ notes]] (assoc db :notes (vec notes) :loaded? true))
    :notes/load-failed (fn [db _] (assoc db :loaded? true))}
   :events-fx
   {:notes/load (fn [_ _]
                  {:storage/get {:key storage-key :on-loaded [:notes/loaded] :on-failure [:notes/load-failed]}})
    :notes/add (fn [{:keys [db]} _]
                 (let [db' (add-note db)]
                   (if (identical? db db') {} {:db db' :storage/put (save db')})))
    :notes/remove (fn [{:keys [db]} [_ id]]
                    (let [db' (update db :notes (fn [notes] (vec (remove #(= id (:id %)) notes))))]
                      {:db db' :storage/put (save db')}))}
   :subs
   {:note-count (fn [db] (count (:notes db)))}})

(defn register!
  "Register the handlers (idempotent; every entry point calls it)."
  []
  (st/register-handlers! handlers))

(defn- note-row [{:keys [id text]}]
  [:ui/row {:key id :gap 8}
   [:ui/text {:text text :grow 1 :wrap? true}]
   [:ui/button {:test-id (str "remove-" id) :label "Remove" :on-press [:notes/remove id]}]])

(defn root
  "db -> hiccup."
  [db]
  [:ui/column {:gap 10 :style {:padding 16} :test-id "root"}
   [:ui/text {:test-id "title" :text "Cloview App" :style {:font-size 20 :font-weight :bold}}]
   [:ui/row {:gap 8}
    [:ui/text-input {:test-id "draft" :value (:draft db) :placeholder "Write a note"
                     :on-change [:notes/draft] :on-submit [:notes/add]}]
    [:ui/button {:test-id "add" :label "Add" :on-press [:notes/add] :disabled? (str/blank? (:draft db))}]]
   (if (empty? (:notes db))
     [:ui/text {:key :empty :test-id "empty" :style {:color "#5F6368"}
                :text (if (:loaded? db) "No notes yet." "Loading...")}]
     (into [:ui/column {:key :notes :gap 4 :test-id "notes"}] (map note-row) (:notes db)))
   [:ui/text {:test-id "count" :style {:font-size 11 :color "#5F6368"}
              :text (str (st/sub db :note-count) " note(s)")}]])
