;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns greeter.core
  "The Greeter spike — one .cljc file shared verbatim by JavaFX, Flutter and WPF.

  State, events and the view are pure; each host entry point calls `register!` once
  and mounts `root` for `store` with its adapter.

  Handlers live in a def'd map registered by `register!` (not as top-level reg-* calls):
  ClojureDart emits definitions only, so top-level side effects would never run on Dart."
  (:require [cloview.core.store :as st]))

(def initial-db {:name "" :greeting nil})

(defonce store (st/create-store {:db initial-db}))

(def handlers
  {:events-db
   {:app/set-name (fn [db [_ s]] (assoc db :name s))
    :app/greet (fn [db _] (assoc db :greeting (str "Hello, " (:name db) "!")))
    :app/reset (fn [_ _] initial-db)}
   :subs
   {:greeting (fn [db] (:greeting db))}})

(defn register!
  "Register the Greeter's handlers (idempotent; every entry point calls it)."
  []
  (st/register-handlers! handlers))

(defn root
  "db -> hiccup."
  [db]
  [:ui/column {:gap 8 :style {:padding 16} :test-id "root"}
   [:ui/text {:test-id "title"
              :text "Greeter"
              :style {:font-size 20 :font-weight :bold :color "#3366CC"}}]
   [:ui/text-input {:test-id "name"
                    :value (:name db)
                    :placeholder "Your name"
                    :on-change [:app/set-name]}]
   [:ui/button {:test-id "greet" :label "Greet" :on-press [:app/greet]}]
   (when-some [greeting (st/sub db :greeting)]
     [:ui/text {:key :greeting :test-id "greeting" :text greeting}])])
