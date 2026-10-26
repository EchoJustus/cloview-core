;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.style
  "The V1 cross-host style subset and its normalization.

  Views write a `:style` map with the keys in `supported-keys`; adapters call
  `normalize` once per node and translate the result to JavaFX CSS, Flutter
  TextStyle/BoxDecoration, or WPF properties.

  Units: every length is a logical pixel (JavaFX px, Flutter logical px, WPF DIP =
  1/96 in). All three hosts scale these for HiDPI themselves.

  Normalized values:
    colors        {:r :g :b :a} 0-255 (see cloview.core.color)
    lengths       doubles
    :padding/:margin  {:top :right :bottom :left} doubles
    :font-weight  integer 100..900 (CSS / OpenType scale)
    :text-align   one of :start :center :end
    :italic?      boolean

  Deliberately excluded in V1 (see `excluded-keys`): shadows, gradients, per-side
  borders, per-corner radii, pseudo-states (hover/pressed/focus), transitions and
  animations, letter-spacing, line-height, transforms. Reach them through :ui/native or
  the per-host `:host-style` escape hatch, which is passed through untouched."
  (:require [cloview.core.color :as color]))

(def supported-keys
  #{:color :background
    :font-size :font-weight :font-family :italic? :text-align
    :padding :margin
    :border :border-width :border-color :radius
    :opacity
    :width :height :min-width :max-width :min-height :max-height
    :host-style})

(def excluded-keys
  #{:shadow :box-shadow :text-shadow :gradient :background-image
    :border-top :border-right :border-bottom :border-left
    :radius-top-left :radius-top-right :radius-bottom-left :radius-bottom-right
    :hover :pressed :focus :disabled-style :transition :animation
    :letter-spacing :line-height :transform :filter :z-index :cursor})

(def font-weights
  {:thin 100 :extra-light 200 :light 300 :normal 400 :regular 400 :medium 500
   :semibold 600 :semi-bold 600 :bold 700 :extra-bold 800 :black 900})

(defn- num! [k v]
  (if (number? v)
    (double v)
    (throw (ex-info (str "Style " k " must be a number (logical px)") {:key k :value v}))))

(defn insets
  "Normalize padding/margin shorthands to {:top :right :bottom :left} doubles.
  Accepts: n | [vertical horizontal] | [top horizontal bottom] | [top right bottom left]
  | {:top :right :bottom :left :horizontal :vertical :all} (specific sides win)."
  [x]
  (cond
    (nil? x) nil
    (number? x) (let [v (double x)] {:top v :right v :bottom v :left v})
    (vector? x) (let [[a b c d] (map double x)]
                  (case (count x)
                    1 {:top a :right a :bottom a :left a}
                    2 {:top a :right b :bottom a :left b}
                    3 {:top a :right b :bottom c :left b}
                    4 {:top a :right b :bottom c :left d}
                    (throw (ex-info "Insets vector needs 1-4 numbers" {:value x}))))
    (map? x) (let [all (double (:all x 0))
                   h (double (:horizontal x all))
                   v (double (:vertical x all))]
               {:top (double (:top x v))
                :right (double (:right x h))
                :bottom (double (:bottom x v))
                :left (double (:left x h))})
    :else (throw (ex-info "Invalid insets" {:value x}))))

(defn font-weight
  "Normalize a font weight keyword/number to 100..900 (rounded to the nearest 100)."
  [w]
  (cond
    (nil? w) nil
    (keyword? w) (or (get font-weights w)
                     (throw (ex-info "Unknown font weight" {:value w})))
    (number? w) (let [r (* 100 (long (+ (/ (double w) 100.0) 0.5)))]
                  (cond (< r 100) 100 (> r 900) 900 :else r))
    :else (throw (ex-info "Invalid font weight" {:value w}))))

(defn- text-align [a]
  (when (some? a)
    (case a
      (:start :left) :start
      :center :center
      (:end :right) :end
      (throw (ex-info "Invalid :text-align (use :start :center :end)" {:value a})))))

(defn unknown-keys
  "Style keys outside the supported subset (excluded ones included)."
  [style]
  (into #{} (remove supported-keys) (keys style)))

(defn normalize
  "Validate and normalize a canonical :style map. Unknown keys are dropped (use
  `unknown-keys` to report them); `:host-style` is passed through untouched."
  [style]
  (if (empty? style)
    {}
    (let [border (:border style)
          [bw bc] (cond
                    (number? border) [border nil]
                    (map? border) [(:width border) (:color border)]
                    :else [nil nil])
          bw (or (:border-width style) bw)
          bc (or (:border-color style) bc)]
      (cond-> {}
        (contains? style :color) (assoc :color (color/parse! (:color style)))
        (contains? style :background) (assoc :background (color/parse! (:background style)))
        (contains? style :font-size) (assoc :font-size (num! :font-size (:font-size style)))
        (contains? style :font-weight) (assoc :font-weight (font-weight (:font-weight style)))
        (contains? style :font-family) (assoc :font-family (str (:font-family style)))
        (contains? style :italic?) (assoc :italic? (boolean (:italic? style)))
        (contains? style :text-align) (assoc :text-align (text-align (:text-align style)))
        (contains? style :padding) (assoc :padding (insets (:padding style)))
        (contains? style :margin) (assoc :margin (insets (:margin style)))
        (some? bw) (assoc :border-width (num! :border-width bw))
        (some? bc) (assoc :border-color (color/parse! bc))
        (and (some? bw) (nil? bc)) (assoc :border-color (color/parse! "#000000"))
        (contains? style :radius) (assoc :radius (num! :radius (:radius style)))
        (contains? style :opacity) (assoc :opacity (let [o (num! :opacity (:opacity style))]
                                                     (cond (< o 0.0) 0.0 (> o 1.0) 1.0 :else o)))
        (contains? style :width) (assoc :width (num! :width (:width style)))
        (contains? style :height) (assoc :height (num! :height (:height style)))
        (contains? style :min-width) (assoc :min-width (num! :min-width (:min-width style)))
        (contains? style :max-width) (assoc :max-width (num! :max-width (:max-width style)))
        (contains? style :min-height) (assoc :min-height (num! :min-height (:min-height style)))
        (contains? style :max-height) (assoc :max-height (num! :max-height (:max-height style)))
        (contains? style :host-style) (assoc :host-style (:host-style style))))))

(defn text-keys
  "Subset of a normalized style that concerns text rendering."
  [nstyle]
  (select-keys nstyle [:color :font-size :font-weight :font-family :italic? :text-align]))

(defn box-keys
  "Subset of a normalized style that concerns the box/decoration around a node."
  [nstyle]
  (select-keys nstyle [:background :padding :margin :border-width :border-color :radius
                       :opacity :width :height :min-width :max-width :min-height
                       :max-height]))
