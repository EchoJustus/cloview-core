;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.javafx.lifecycles
  "cljfx lifecycles for the :cloview/* types emitted by cloview.javafx.cljfx.

    :cloview/text-field  TextField whose :text writes preserve the caret (clamped) and never
                         echo through :cloview/on-text-edited
    :cloview/split-pane  SplitPane + :cloview/on-divider-moved (first divider position)
    :cloview/tab-pane    TabPane + :cloview/selected-index / :cloview/on-selected-index-changed
    :cloview/native      escape hatch: user-created JavaFX node with :create/:update/:dispose
    :cloview/let-refs    alias of cljfx's ext-let-refs (used for off-tree modal stages)

  Requiring this namespace loads cljfx.api, i.e. starts the JavaFX toolkit (unless the
  cljfx.skip-javafx-initialization system property is true)."
  (:require [cljfx.api :as fx]
            [cljfx.component :as component]
            [cljfx.composite :as composite]
            [cljfx.fx.split-pane :as fx.split-pane]
            [cljfx.fx.tab-pane :as fx.tab-pane]
            [cljfx.fx.text-field :as fx.text-field]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [cloview.core.reconcile :as reconcile])
  (:import [javafx.beans.value ChangeListener]
           [javafx.scene Node]
           [javafx.scene.control SplitPane SplitPane$Divider TabPane TextField]))

(set! *warn-on-reflection* true)

(defn- set-text-preserving-caret!
  "Controlled :text write: skipped when the field already shows `text` (user typing),
  otherwise written with the caret clamped to the new length. While writing, the
  ::writing flag makes the guarded listeners below swallow the programmatic change, so
  an external value never echoes back as an :on-change event (same contract as the
  WPF reconciler's re-entrancy guard)."
  [^TextField tf text]
  (let [text (str text)]
    (when-not (= text (.getText tf))
      (let [caret (.getCaretPosition tf)
            props (.getProperties tf)]
        (.put props ::writing true)
        (try
          (.setText tf text)
          (.positionCaret tf (int (reconcile/preserve-caret caret text)))
          (finally (.remove props ::writing)))))))

(def ^:private guarded-text-listener
  "Mutator for :cloview/on-text-edited: a text ChangeListener that ignores changes made
  by `set-text-preserving-caret!`."
  (mutator/adder-remover
   (fn [^TextField tf ^ChangeListener l]
     (let [props (.getProperties tf)
           wrapped (reify ChangeListener
                     (changed [_ obs old new]
                       (when-not (.get props ::writing)
                         (.changed l obs old new))))]
       (.put props [::listener l] wrapped)
       (.addListener (.textProperty tf) wrapped)))
   (fn [^TextField tf ^ChangeListener l]
     (when-some [wrapped (.remove (.getProperties tf) [::listener l])]
       (.removeListener (.textProperty tf) ^ChangeListener wrapped)))))

(def text-field
  (lifecycle/annotate
   (composite/describe TextField
     :ctor []
     :props (merge fx.text-field/props
                   {:text (prop/make (mutator/setter set-text-preserving-caret!) lifecycle/scalar
                                     :default "")
                    :cloview/on-text-edited (prop/make guarded-text-listener
                                                       lifecycle/change-listener)}))
   :cloview/text-field))

(defn- first-divider-position [^SplitPane sp]
  (let [^SplitPane$Divider d (first (.getDividers sp))]
    (.positionProperty d)))

(def split-pane
  (lifecycle/annotate
   (composite/describe SplitPane
     :ctor []
     :prop-order {:items 0 :divider-positions 1 :cloview/on-divider-moved 2}
     :props (merge fx.split-pane/props
                   {:cloview/on-divider-moved
                    (prop/make (mutator/property-change-listener first-divider-position)
                               lifecycle/change-listener)}))
   :cloview/split-pane))

(defn- select-index! [^TabPane tp i]
  (when (some? i)
    (let [sm (.getSelectionModel tp)]
      (when-not (= (int i) (.getSelectedIndex sm))
        (.select sm (int i))))))

(def tab-pane
  (lifecycle/annotate
   (composite/describe TabPane
     :ctor []
     :prop-order {:tabs 0 :cloview/selected-index 1 :cloview/on-selected-index-changed 2}
     :props (merge fx.tab-pane/props
                   {:cloview/selected-index
                    (prop/make (mutator/setter select-index!) lifecycle/scalar)
                    :cloview/on-selected-index-changed
                    (prop/make (mutator/property-change-listener
                                #(.selectedIndexProperty (.getSelectionModel ^TabPane %)))
                               lifecycle/change-listener)}))
   :cloview/tab-pane))

(def native
  "Escape hatch. Desc keys: :create (fn [] node), :update (fn [node props]) called after
  create and whenever :props changes (fresh closures alone never trigger it),
  :dispose (fn [node]) on removal, :props (data)."
  (with-meta
    [::native]
    {`lifecycle/create
     (fn [_ {:keys [create update dispose props] :as desc} _opts]
       (let [node (create)]
         (when-let [id (:cloview/test-id desc)]
           (when (instance? Node node) (.setId ^Node node ^String id)))
         (when update (update node props))
         (with-meta {:node node :props props :update update :dispose dispose}
           {`component/instance :node})))
     `lifecycle/advance
     (fn [_ component {:keys [update dispose props]} _opts]
       (when (and update (not= props (:props component)))
         (update (:node component) props))
       (assoc component :props props :update update :dispose dispose))
     `lifecycle/delete
     (fn [_ component _opts]
       (when-let [dispose (:dispose component)]
         (dispose (:node component))))}))

(def cloview-types
  {:cloview/text-field text-field
   :cloview/split-pane split-pane
   :cloview/tab-pane tab-pane
   :cloview/native native
   :cloview/let-refs fx/ext-let-refs})

(defn type->lifecycle
  "Value for the renderer's :fx.opt/type->lifecycle option."
  [fx-type]
  (or (get cloview-types fx-type)
      (fx/keyword->lifecycle fx-type)
      (fx/fn->lifecycle fx-type)))
