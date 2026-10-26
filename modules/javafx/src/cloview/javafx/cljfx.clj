;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.javafx.cljfx
  "Canonical tree -> cljfx description: a pure data -> data translation.

  cljfx does the diffing and the JavaFX mutations. This namespace never loads cljfx or
  JavaFX: custom cljfx types are referenced by keyword (:cloview/text-field,
  :cloview/split-pane, :cloview/tab-pane, :cloview/native, :cloview/let-refs) and
  resolved by `cloview.javafx.lifecycles/type->lifecycle`. That keeps every mapping
  snapshot-testable without a display.

  Events become cljfx map events {::event handler ::kind payload-kind ...};
  `cloview.javafx.app/map-event-handler` turns the JavaFX event into the canonical payload.

  Extension: `->desc` is a multimethod on the canonical tag; host-specific or user
  components add a method (see docs/STAGE_2_1_DESIGN.md §6)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloview.core.color :as color]
            [cloview.core.style :as style]
            [cloview.core.tree :as t]))

(def max-size Double/MAX_VALUE)

;; ---------------------------------------------------------------------------
;; Events

(defn- events
  "Event props with nil handlers removed: cljfx cannot coerce nil into an EventHandler /
  ChangeListener, so absent handlers must be absent keys."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn ev
  "cljfx map-event for `handler` (data vector or fn) with payload kind `kind`."
  ([handler kind] (ev handler kind nil))
  ([handler kind extra]
   (when (some? handler)
     (merge {::event handler ::kind kind} extra))))

;; ---------------------------------------------------------------------------
;; Style -> JavaFX CSS

(defn- css-decl [k v] (str k ": " v ";"))

(defn- px [d] (str (if (== d (Math/rint d)) (long d) d) "px"))

(defn- insets-css [{:keys [top right bottom left]}]
  (str/join " " (map px [top right bottom left])))

(defn font-weight-css [w]
  (case (long w) 400 "normal" 700 "bold" (str (long w))))

(defn text-css
  "JavaFX CSS for the text part of a normalized style (Labeled / Text controls)."
  [nstyle]
  (str/join " "
            (cond-> []
              (:color nstyle) (conj (css-decl "-fx-text-fill" (color/css-rgba (:color nstyle))))
              (:font-size nstyle) (conj (css-decl "-fx-font-size" (px (:font-size nstyle))))
              (:font-weight nstyle) (conj (css-decl "-fx-font-weight" (font-weight-css (:font-weight nstyle))))
              (:font-family nstyle) (conj (css-decl "-fx-font-family" (str "\"" (:font-family nstyle) "\"")))
              (:italic? nstyle) (conj (css-decl "-fx-font-style" "italic")))))

(defn box-css
  "JavaFX CSS for the box part (background, border, radius, opacity)."
  [nstyle]
  (let [radius (:radius nstyle)]
    (str/join " "
              (cond-> []
                (:background nstyle) (conj (css-decl "-fx-background-color" (color/css-rgba (:background nstyle))))
                (and radius (:background nstyle)) (conj (css-decl "-fx-background-radius" (px radius)))
                (:border-width nstyle) (conj (css-decl "-fx-border-width" (px (:border-width nstyle)))
                                             (css-decl "-fx-border-color" (color/css-rgba (:border-color nstyle))))
                (and radius (:border-width nstyle)) (conj (css-decl "-fx-border-radius" (px radius)))
                (:opacity nstyle) (conj (css-decl "-fx-opacity" (str (:opacity nstyle))))))))

(defn- join-css [& parts]
  (let [s (str/join " " (remove str/blank? parts))]
    (when-not (str/blank? s) s)))

(defn- host-css [nstyle]
  (let [h (:host-style nstyle)] (when (string? h) h)))

(defn- size-props [nstyle]
  (cond-> {}
    (:width nstyle) (assoc :pref-width (:width nstyle) :min-width (:width nstyle) :max-width (:width nstyle))
    (:height nstyle) (assoc :pref-height (:height nstyle) :min-height (:height nstyle) :max-height (:height nstyle))
    (:min-width nstyle) (assoc :min-width (:min-width nstyle))
    (:max-width nstyle) (assoc :max-width (:max-width nstyle))
    (:min-height nstyle) (assoc :min-height (:min-height nstyle))
    (:max-height nstyle) (assoc :max-height (:max-height nstyle))))

(defn- insets-map [{:keys [top right bottom left]}]
  {:top top :right right :bottom bottom :left left})

(defn- common
  "Props shared by every node: id, disable, style (css), sizes."
  [props nstyle css]
  (cond-> (size-props nstyle)
    (:test-id props) (assoc :id (str (:test-id props)))
    (:disabled? props) (assoc :disable true)
    css (assoc :style css)))

;; ---------------------------------------------------------------------------
;; Alignment helpers

(defn- h-part [a] (case a (:start nil :stretch) "left" :center "center" :end "right" "left"))
(defn- v-part [j] (case j (:start nil :space-between) "top" :center "center" :end "bottom" "top"))

(defn- pos-kw
  "JavaFX Pos keyword from vertical/horizontal parts."
  [v h]
  (if (and (= v "center") (= h "center"))
    :center
    (keyword (str v "-" h))))

(def ^:private align9
  {:top-left :top-left :top :top-center :top-right :top-right
   :left :center-left :center :center :right :center-right
   :bottom-left :bottom-left :bottom :bottom-center :bottom-right :bottom-right})

;; ---------------------------------------------------------------------------
;; Translation

(defmulti ->desc
  "Canonical node -> cljfx description."
  :tag)

(defmethod ->desc :default [node]
  (throw (ex-info (str "No cljfx adapter for " (:tag node)
                       " — add (defmethod cloview.javafx.cljfx/->desc " (:tag node) " ...)")
                  {:tag (:tag node)})))

(defn translate
  "Hiccup/node -> cljfx description (nil for nil)."
  [view]
  (some-> (t/normalize view) ->desc))

(defn- child->desc
  "Description of a node placed in a :children list. The canonical :key becomes cljfx's
  :fx/key, so keyed reordering moves JavaFX nodes instead of re-creating them — the same
  identity rule as the WPF reconciler and Flutter's ValueKey. (:fx/key is only valid
  inside collections, so single-child props such as :content use plain ->desc.)"
  [node]
  (cond-> (->desc node)
    (some? (:key node)) (assoc :fx/key (:key node))))

(defn- stretchable? [desc]
  ;; controls whose max size defaults to their pref size and must be told to stretch
  (contains? #{:button :label :check-box :combo-box :toggle-button} (:fx/type desc)))

(defn- flex-children
  "Children of a v-box/h-box with grow, margin and cross-axis stretch applied."
  [parent-tag props children]
  (let [vertical? (= parent-tag :ui/column)
        stretch? (= :stretch (:align props (t/default-align parent-tag)))
        grow-key (if vertical? :v-box/vgrow :h-box/hgrow)
        margin-key (if vertical? :v-box/margin :h-box/margin)]
    (mapv (fn [child]
            (let [d (child->desc child)
                  g (t/effective-grow parent-tag child)
                  margin (:margin (style/normalize (get-in child [:props :style])))]
              (cond-> d
                (pos? g) (assoc grow-key :always)
                margin (assoc margin-key (insets-map margin))
                (and stretch? vertical? (stretchable? d) (not (:max-width d))) (assoc :max-width max-size)
                (and stretch? (not vertical?) (stretchable? d) (not (:max-height d))) (assoc :max-height max-size)
                (and (pos? g) vertical? (stretchable? d)) (assoc :max-height max-size)
                (and (pos? g) (not vertical?) (stretchable? d)) (assoc :max-width max-size))))
          children)))

(defn- flex [fx-type {:keys [tag props children]}]
  (let [ns (style/normalize (:style props))
        vertical? (= tag :ui/column)
        align (:align props (t/default-align tag))
        justify (:justify props :start)
        pos (if vertical?
              (pos-kw (v-part justify) (h-part align))
              (pos-kw (case align (:start :stretch) "top" :center "center" :end "bottom" "top")
                      (case justify :center "center" :end "right" "left")))]
    (cond-> (merge (common props ns (join-css (box-css ns) (host-css ns)))
                   {:fx/type fx-type
                    :spacing (double (:gap props 0))
                    :alignment pos
                    :children (flex-children tag props children)})
      vertical? (assoc :fill-width (= align :stretch))
      (not vertical?) (assoc :fill-height (= align :stretch))
      (:padding ns) (assoc :padding (insets-map (:padding ns))))))

(defmethod ->desc :ui/column [node] (flex :v-box node))
(defmethod ->desc :ui/row [node] (flex :h-box node))

(defn- track->constraints [kind track]
  (let [[size-key grow-key] (if (= kind :column) [:pref-width :hgrow] [:pref-height :vgrow])
        fx-type (if (= kind :column) :column-constraints :row-constraints)]
    (cond
      (number? track) {:fx/type fx-type size-key (double track) (if (= kind :column) :min-width :min-height) (double track)}
      (and (map? track) (:fr track)) {:fx/type fx-type grow-key :always}
      :else {:fx/type fx-type})))

(defmethod ->desc :ui/grid [{:keys [props children]}]
  (let [ns (style/normalize (:style props))
        gap (double (:gap props 0))]
    (cond-> (merge (common props ns (join-css (box-css ns) (host-css ns)))
                   {:fx/type :grid-pane
                    :hgap gap
                    :vgap gap
                    :children (mapv (fn [c]
                                      (let [p (:props c)
                                            d (child->desc c)]
                                        (cond-> d
                                          ;; grid children fill their cell's width, as on WPF and
                                          ;; Flutter (canonical layout rule, docs §4)
                                          (and (stretchable? d) (not (:max-width d))) (assoc :max-width max-size)
                                          true (assoc :grid-pane/row (int (:grid/row p 0))
                                                      :grid-pane/column (int (:grid/col p 0)))
                                          (:grid/row-span p) (assoc :grid-pane/row-span (int (:grid/row-span p)))
                                          (:grid/col-span p) (assoc :grid-pane/column-span (int (:grid/col-span p))))))
                                    children)})
      (seq (:columns props)) (assoc :column-constraints (mapv #(track->constraints :column %) (:columns props)))
      (seq (:rows props)) (assoc :row-constraints (mapv #(track->constraints :row %) (:rows props)))
      (:padding ns) (assoc :padding (insets-map (:padding ns))))))

(defmethod ->desc :ui/wrap [{:keys [props children]}]
  ;; FlowPane: children flow left to right and wrap at the available width
  (let [ns (style/normalize (:style props))
        gap (double (:gap props 0))]
    (cond-> (merge (common props ns (join-css (box-css ns) (host-css ns)))
                   {:fx/type :flow-pane
                    :hgap gap
                    :vgap (double (:run-gap props gap))
                    :children (mapv child->desc children)})
      (:padding ns) (assoc :padding (insets-map (:padding ns))))))

(defmethod ->desc :ui/scroll [{:keys [props children]}]
  (let [ns (style/normalize (:style props))
        dir (:direction props :vertical)]
    (merge (common props ns (join-css (box-css ns) (host-css ns)))
           {:fx/type :scroll-pane
            :fit-to-width (= dir :vertical)
            :fit-to-height (= dir :horizontal)
            :hbar-policy (if (= dir :vertical) :never :as-needed)
            :vbar-policy (if (= dir :horizontal) :never :as-needed)
            :content (->desc (first children))})))

(defmethod ->desc :ui/box [{:keys [props children]}]
  (let [ns (style/normalize (:style props))]
    (cond-> (merge (common props ns (join-css (box-css ns) (host-css ns)))
                   {:fx/type :stack-pane
                    :alignment :top-left
                    :children (mapv child->desc children)})
      (:padding ns) (assoc :padding (insets-map (:padding ns))))))

(defmethod ->desc :ui/align [{:keys [props children]}]
  (let [ns (style/normalize (:style props))]
    (merge (common props ns (join-css (box-css ns) (host-css ns)))
           {:fx/type :stack-pane
            :alignment (get align9 (:align props :center) :center)
            :children (mapv child->desc children)})))

(defmethod ->desc :ui/spacer [{:keys [props]}]
  (merge (common props {} nil)
         {:fx/type :region :min-width 0.0 :min-height 0.0}))

(defn- text-alignment [nstyle]
  (case (:text-align nstyle) :center :center :end :center-right :center-left))

(defmethod ->desc :ui/text [{:keys [props]}]
  (let [ns (style/normalize (:style props))]
    (cond-> (merge (common props ns (join-css (text-css ns) (box-css ns) (host-css ns)))
                   {:fx/type :label
                    :text (str (:text props ""))
                    :alignment (text-alignment ns)})
      (:wrap? props) (assoc :wrap-text true)
      (:padding ns) (assoc :padding (insets-map (:padding ns))))))

(defmethod ->desc :ui/button [{:keys [props]}]
  (let [ns (style/normalize (:style props))]
    (merge (common props ns (join-css (text-css ns) (box-css ns) (host-css ns)))
           {:fx/type :button
            :text (str (:label props ""))}
           (events {:on-action (ev (:on-press props) :none)}))))

(defmethod ->desc :ui/text-input [{:keys [props]}]
  (let [ns (style/normalize (:style props))]
    (cond-> (merge (common props ns (join-css (text-css ns) (box-css ns) (host-css ns)))
                   {:fx/type (if (:password? props) :password-field :cloview/text-field)
                    :prompt-text (str (:placeholder props ""))}
                   ;; :cloview/on-text-edited ignores programmatic writes (no echo);
                   ;; password fields use the stock listener.
                   (events {(if (:password? props) :on-text-changed :cloview/on-text-edited)
                            (ev (:on-change props) :string)
                            :on-action (ev (:on-submit props) :submit-text)}))
      (contains? props :value) (assoc :text (str (:value props ""))))))

(defn- toggle [{:keys [props]}]
  (let [ns (style/normalize (:style props))]
    (merge (common props ns (join-css (text-css ns) (host-css ns)))
           {:fx/type :check-box
            :text (str (:label props ""))
            :selected (boolean (:checked props))}
           (events {:on-selected-changed (ev (:on-toggle props) :boolean)}))))

(defmethod ->desc :ui/checkbox [node] (toggle node))

(defmethod ->desc :ui/switch [node]
  ;; JavaFX has no core switch control: a check-box with a style class (documented).
  (assoc (toggle node) :style-class ["check-box" "cloview-switch"]))

(defmethod ->desc :ui/toggle [node]
  ;; filter chip: a ToggleButton (pressed = checked; JavaFX exposes the state to
  ;; accessibility as a toggle button, not by colour alone)
  (assoc (toggle node) :fx/type :toggle-button))

(defn option-label [opt] (str (:label opt (:value opt))))

(defmethod ->desc :ui/select [{:keys [props]}]
  (let [ns (style/normalize (:style props))
        options (:options props [])
        labels (mapv option-label options)
        label->value (zipmap labels (map :value options))
        selected (some #(when (= (:value %) (:value props)) (option-label %)) options)]
    (cond-> (merge (common props ns (join-css (text-css ns) (host-css ns)))
                   {:fx/type :combo-box
                    :items labels
                    :value selected}
                   (events {:on-value-changed (ev (:on-select props) :select {::options label->value})}))
      (:placeholder props) (assoc :prompt-text (str (:placeholder props))))))

(defn image-url
  "URL string for a canonical image :src ({:url} {:file} {:asset}), or nil."
  [src]
  (cond
    (nil? src) nil
    (string? src) src
    (:url src) (str (:url src))
    (:file src) (str (.toURI (io/file (:file src))))
    (:asset src) (some-> (io/resource (str (:asset src))) str)
    :else nil))

(defmethod ->desc :ui/image [{:keys [props]}]
  (let [ns (style/normalize (:style props))
        url (image-url (:src props))
        fit (:fit props :contain)]
    (cond-> (merge (dissoc (common props ns (host-css ns)) :pref-width :pref-height :min-width :min-height
                           :max-width :max-height)
                   {:fx/type :image-view
                    :preserve-ratio (not= fit :fill)
                    :smooth true})
      url (assoc :image {:url url :background-loading true})
      (:width ns) (assoc :fit-width (:width ns))
      (:height ns) (assoc :fit-height (:height ns)))))

(defmethod ->desc :ui/split-view [{:keys [props children]}]
  (let [ns (style/normalize (:style props))]
    (merge (common props ns (host-css ns))
           {:fx/type :cloview/split-pane
            :orientation (if (= :vertical (:orientation props)) :vertical :horizontal)
            :divider-positions [(double (:ratio props 0.5))]
            :items (mapv ->desc children)}
           (events {:cloview/on-divider-moved (ev (:on-resize props) :ratio)}))))

(defmethod ->desc :ui/tabs [{:keys [props children]}]
  (let [ns (style/normalize (:style props))
        ids (mapv #(get-in % [:props :id]) children)
        selected (when (contains? props :selected)
                   (let [i (.indexOf ^java.util.List ids (:selected props))] (when (>= i 0) i)))]
    (cond-> (merge (common props ns (host-css ns))
                   {:fx/type :cloview/tab-pane
                    :tabs (mapv (fn [tab]
                                  (let [tp (:props tab)]
                                    (cond-> {:fx/type :tab
                                             :text (str (:label tp (:id tp)))
                                             :closable false
                                             :content (->desc (first (:children tab)))}
                                      (:test-id tp) (assoc :id (str (:test-id tp))))))
                                children)}
                   (events {:cloview/on-selected-index-changed
                            (ev (:on-select props) :tab-index {::options ids})}))
      selected (assoc :cloview/selected-index selected))))

(defmethod ->desc :ui/tab [node]
  (throw (ex-info ":ui/tab is only valid as a direct child of :ui/tabs" {:node node})))

(defmethod ->desc :ui/modal [{:keys [props children]}]
  (let [ns (style/normalize (:style props))
        content (first children)]
    {:fx/type :cloview/let-refs
     :refs {::modal (merge
                     {:fx/type :stage
                      :showing (boolean (:open? props))
                      :title (str (:title props ""))
                      :modality :application-modal
                      ;; without :on-dismiss the close button simply closes the stage
                      :on-close-request (ev (or (:on-dismiss props) (fn [] nil)) :none {::consume? (some? (:on-dismiss props))})
                      :scene {:fx/type :scene
                             :root (merge {:fx/type :stack-pane
                                           :padding (insets-map (or (:padding ns) (style/insets 16)))
                                           :children (if content [(->desc content)] [])}
                                          (when (:test-id props) {:id (str (:test-id props))}))}})}
     :desc {:fx/type :region :managed false :visible false}}))

(defmethod ->desc :ui/native [{:keys [props]}]
  (let [{:keys [create update dispose]} (:host props)]
    (when-not (fn? create)
      (throw (ex-info ":ui/native on the JVM needs :host {:create (fn [] javafx-node) ...}"
                      {:props (dissoc props :host)})))
    (cond-> {:fx/type :cloview/native
             :props (:props props)
             :create create
             :update update
             :dispose dispose}
      (:test-id props) (assoc :cloview/test-id (str (:test-id props))))))
