;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns greeter.javafx.smoke
  "Off-screen JavaFX smoke test (verify step 03).

  Mounts the Greeter in a hidden stage through the real cljfx renderer, simulates typing,
  dispatches [:app/set-name \"Ada\"] and [:app/greet], checks the live JavaFX nodes, then
  mounts the kitchen sink (every V1 tag), applies prop patches and reports per-tag
  results. Writes JSON (default verify/out/03-jvm-cljfx-smoke.json); exit code 0 when
  every critical Greeter check passed.

    clj -M:smoke-javafx [out.json]"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [cloview.core.store :as st]
            [cloview.javafx.app :as app]
            [cloview.verify.report :as report]
            [greeter.core :as g]
            [greeter.kitchen-sink :as ks])
  (:import [javafx.application Platform]
           [javafx.scene Node Parent Scene]
           [javafx.scene.control CheckBox ComboBox Label ScrollPane SplitPane Tab TabPane TextField
            ToggleButton]
           [javafx.scene.paint Color]
           [javafx.scene.shape Rectangle]
           [javafx.stage Stage Window]))

(set! *warn-on-reflection* true)

(def ^:private timeout-ms 20000)

(defn- on-fx
  "Run (f) on the JavaFX Application Thread and wait for its value (rethrows)."
  [f]
  (if (Platform/isFxApplicationThread)
    (f)
    (let [p (promise)]
      (Platform/runLater (fn [] (deliver p (try [nil (f)] (catch Throwable e [e nil])))))
      (let [r (deref p timeout-ms ::timeout)]
        (when (= r ::timeout) (throw (ex-info "FX thread timeout" {})))
        (let [[e v] r] (if e (throw e) v))))))

(defn- settle!
  "Let queued FX-thread work (posted dispatches, cljfx renders) run: renders are posted
  with runLater, so a few FIFO round-trips drain them."
  []
  (dotimes [_ 5] (on-fx (fn [] nil))))

(defn- lookup ^Node [^Stage stage id]
  (some-> stage .getScene .getRoot (.lookup (str "#" id))))

(defn- mount!
  "Mount `view` for `store` in a hidden stage; returns the Stage."
  [store view title errors]
  (let [stage-p (promise)
        {:keys [mounted]} (app/start! {:store store
                                       :view view
                                       :title title
                                       :showing false
                                       :install-effects? false
                                       :error-handler (fn [^Throwable e]
                                                        (swap! errors conj (str e))
                                                        (.printStackTrace e))
                                       :wrap-desc (fn [desc]
                                                    {:fx/type fx/ext-on-instance-lifecycle
                                                     :on-created #(deliver stage-p %)
                                                     :desc desc})})]
    (deref mounted timeout-ms nil)
    (settle!)
    (let [stage (deref stage-p timeout-ms nil)]
      (when-not stage (throw (ex-info (str "stage for " title " was not created") {})))
      stage)))

(defn- greeter-checks! [rep]
  (let [events (atom [])
        errors (atom [])
        store (st/create-store {:db g/initial-db
                                :on-event (fn [e _] (swap! events conj e))})
        ^Stage stage (mount! store g/root "greeter smoke" errors)
        ^TextField tf (on-fx #(lookup stage "name"))
        ^Label title (on-fx #(lookup stage "title"))]
    (report/check! rep "greeter/textfield-found" true (instance? TextField tf))
    (report/check! rep "greeter/title-text" "Greeter" (on-fx #(.getText title)))
    (on-fx #(.applyCss (.getRoot (.getScene stage))))
    (report/check! rep "greeter/title-font-size" 20.0 (on-fx #(.getSize (.getFont title))))
    (report/check! rep "greeter/title-bold" true
                   (on-fx #(boolean (re-find #"(?i)bold" (.getStyle (.getFont title))))))
    (report/check! rep "greeter/title-color" "0x3366ccff"
                   (on-fx #(str (.getTextFill title))) {:critical? false})
    ;; simulated typing: the user edits the field, the store follows
    (on-fx #(do (.setText tf "A") (.positionCaret tf 1)))
    (settle!)
    (on-fx #(do (.setText tf "Ad") (.positionCaret tf 2)))
    (settle!)
    (report/check! rep "typing/db-follows-user" "Ad" (:name (st/db store)))
    (report/check! rep "typing/text-not-rewritten" "Ad" (on-fx #(.getText tf)))
    (report/check! rep "typing/caret-kept" 2 (on-fx #(.getCaretPosition tf)))
    ;; programmatic events, as required by the spike
    (st/dispatch! store [:app/set-name "Ada"])
    (settle!)
    (report/check! rep "external/textfield-text" "Ada" (on-fx #(.getText tf)))
    (report/check! rep "external/caret-intact" 2 (on-fx #(.getCaretPosition tf))
                   {:note "caret preserved (clamped), not reset by the external write"})
    (st/dispatch! store [:app/greet])
    (settle!)
    (report/check! rep "greet/greeting-text" "Hello, Ada!"
                   (on-fx #(some-> ^Label (lookup stage "greeting") .getText)))
    (report/check! rep "events/no-echo"
                   [[:app/set-name "A"] [:app/set-name "Ad"] [:app/set-name "Ada"] [:app/greet]]
                   @events
                   {:note "programmatic :value writes must not re-dispatch :on-change"})
    (report/check! rep "greeter/render-errors" [] @errors)
    (on-fx #(.close stage))))

(def ^:private native-host
  {:create (fn [] (Rectangle. 20.0 20.0))
   :update (fn [^Rectangle r props] (.setWidth r (double (+ 20 (:n props 0)))))})

(defn- tag-check [stage tag id]
  (try
    (let [node (on-fx #(lookup stage id))
          cls (some-> node class .getSimpleName)]
      (case tag
        :ui/tab (let [^TabPane tp (on-fx #(lookup stage "ks-tabs"))]
                  {:tag tag :ok (= 2 (on-fx #(.size (.getTabs tp)))) :host "Tab"})
        :ui/modal {:tag tag :ok true :host "Stage (off-tree, see modal checks)"}
        {:tag tag :ok (some? node) :host cls}))
    (catch Throwable e {:tag tag :ok false :error (str e)})))

(defn- item-label
  "The Label showing `text` among the keyed items of the kitchen sink's scroll box."
  ^Label [^Stage stage text]
  (let [^Parent box (.getContent ^ScrollPane (lookup stage "ks-scroll"))]
    (some (fn [n] (when (and (instance? Label n) (= text (.getText ^Label n))) n))
          (.getChildrenUnmodifiable box))))

(defn- showing-titles []
  (on-fx #(into [] (keep (fn [^Window w] (when (and (instance? Stage w) (.isShowing w))
                                           (.getTitle ^Stage w))))
                (Window/getWindows))))

(defn- kitchen-sink! [rep]
  (let [errors (atom [])
        events (atom [])
        store (st/create-store {:db ks/initial-db :on-event (fn [e _] (swap! events conj e))})
        ^Stage stage (mount! store #(ks/view % native-host) "kitchen sink" errors)
        coverage (mapv (fn [[tag id]] (tag-check stage tag id)) (sort-by (comp str key) ks/tag->test-id))
        item-a (on-fx #(item-label stage "item a"))]
    (swap! rep assoc :tag_coverage coverage)
    (report/check! rep "kitchen-sink/all-tags-mounted" [] (mapv :tag (remove :ok coverage)) {:critical? false})
    (doseq [e ks/patch-events]
      (st/dispatch! store e)
      (settle!)
      (when (= e [:ks/open-modal])
        (report/check! rep "kitchen-sink/modal-shown" true
                       (boolean (some #{"Kitchen sink dialog"} (showing-titles))) {:critical? false})))
    (report/check! rep "kitchen-sink/modal-hidden-after-close" false
                   (boolean (some #{"Kitchen sink dialog"} (showing-titles))) {:critical? false})
    (report/check! rep "kitchen-sink/input-patched" "typed"
                   (on-fx #(.getText ^TextField (lookup stage "ks-input"))) {:critical? false})
    (report/check! rep "kitchen-sink/checkbox-patched" true
                   (on-fx #(.isSelected ^CheckBox (lookup stage "ks-checkbox"))) {:critical? false})
    (report/check! rep "kitchen-sink/select-patched" "Alpha"
                   (on-fx #(.getValue ^ComboBox (lookup stage "ks-select"))) {:critical? false})
    (report/check! rep "kitchen-sink/tab-patched" 1
                   (on-fx #(.getSelectedIndex (.getSelectionModel ^TabPane (lookup stage "ks-tabs"))))
                   {:critical? false})
    (report/check! rep "kitchen-sink/split-ratio-patched" 0.6
                   (on-fx #(let [^SplitPane sp (lookup stage "ks-split")]
                             (/ (Math/round (* 10.0 (first (.getDividerPositions sp)))) 10.0)))
                   {:critical? false})
    (report/check! rep "kitchen-sink/native-props-updated" 21.0
                   (on-fx #(.getWidth ^Rectangle (lookup stage "ks-native"))) {:critical? false})
    (report/check! rep "kitchen-sink/keyed-reorder-reuses-node" true
                   (on-fx #(and (some? item-a) (identical? item-a (item-label stage "item a"))))
                   {:critical? false :note "[:ks/rotate] moved the keyed Label (:fx/key) instead of relabelling"})
    (report/check! rep "kitchen-sink/keyed-reorder-moved-node" 2
                   (on-fx #(let [^Parent box (.getContent ^ScrollPane (lookup stage "ks-scroll"))]
                             (.indexOf (.getChildrenUnmodifiable box) item-a)))
                   {:critical? false})
    (report/check! rep "kitchen-sink/toggle-patched" [false true]
                   (on-fx #(vector (.isSelected ^ToggleButton (lookup stage "ks-toggle"))
                                   (.isSelected ^ToggleButton (lookup stage "ks-toggle-y"))))
                   {:critical? false})
    (report/check! rep "kitchen-sink/no-echo" ks/patch-events @events
                   {:critical? false
                    :note "programmatic writes of checked/value/ratio/selected never re-dispatch (cljfx suppresses listeners while rendering)"})
    (report/check! rep "kitchen-sink/render-errors" [] @errors {:critical? false})
    (on-fx #(.close stage))))

(defn run-smoke! [out]
  (g/register!)
  (ks/register!)
  (let [started (report/now-ms)
        rep (report/new-report "jvm-cljfx-smoke" "jvm/javafx")]
    (swap! rep assoc :env (report/host-info))
    (try
      (greeter-checks! rep)
      (catch Throwable e (report/error! rep "greeter" e) (.printStackTrace e)))
    (try
      (kitchen-sink! rep)
      (catch Throwable e
        (swap! rep update :checks conj {:id "kitchen-sink/run" :ok false :critical false :actual (str e)})
        (.printStackTrace e)))
    (let [final (report/finish rep started (report/now-ms))]
      (report/write-json! out final)
      (println (str "jvm cljfx smoke: " (if (:ok final) "OK" "FAILED")
                    " (" (:checks_passed final) "/" (:checks_total final) " checks) -> " out))
      (if (:ok final) 0 1))))

(defn -main [& [out]]
  (let [code (run-smoke! (or out "verify/out/03-jvm-cljfx-smoke.json"))]
    (shutdown-agents)
    (javafx.application.Platform/exit)
    (System/exit code)))
