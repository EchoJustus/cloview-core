;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
(ns cloview.core.components
  "Portable composite components, built only from canonical tags.

  Composites expand in `cloview.core.tree/normalize`, so hosts never see them: every
  host renders the result with its own native controls, and the same declaration means
  the same controls and events everywhere. Use them as function heads,

      [components/chip-group {:options [...] :selected #{...} :on-toggle [:filter/toggle :muscle]}]

  or call (register!) once and use the keyword tags :cloview/chip-group and
  :cloview/month-grid in data-only views. (Registration is an explicit call because
  ClojureDart does not run top-level side effects.)

  Accessibility rule followed by every component here: state is never conveyed by
  colour alone — selected chips are checked toggles, the selected calendar day is
  bracketed, plan progress is written out."
  (:require [cloview.core.tree :as t]))

;; ---------------------------------------------------------------------------
;; Multiple choice: toggle chips

(defn chip-group
  "Multiple choice as toggle chips (:ui/toggle) in a wrapping row (:ui/wrap): a stable
  set of chosen ids, independent labels and an optional explicit clear action.

  props
    :options     [{:id id :label \"text\" :disabled? bool}]  — order is kept; ids are
                 stable identities (each chip is keyed by its id)
    :selected    collection of selected ids
    :on-toggle   event vector; a chip dispatches (conj on-toggle id checked?)
                 — one event per user action, carrying the option and its new state
                 (never a recomputed whole set, which would go stale if two clicks
                 land before the next render)
    :on-clear    event vector for a trailing clear button (omitted when absent;
                 disabled while nothing is selected)
    :clear-label default \"Clear\"
    :empty-text  text shown when there are no options (e.g. \"No equipment yet\")
    :gap         spacing between chips (default 6); :run-gap between rows
    :disabled?   disables every chip and the clear button
    :test-id     prefix: <id>/<option-id>, <id>/clear; the group itself gets <id>"
  [{:keys [options selected on-toggle on-clear clear-label empty-text gap run-gap
           disabled? test-id style]
    :or {gap 6}}]
  (let [sel (set selected)]
    [:ui/wrap (cond-> {:gap gap}
                run-gap (assoc :run-gap run-gap)
                style (assoc :style style)
                test-id (assoc :test-id test-id))
     (when (and (empty? options) empty-text)
       [:ui/text {:key ::empty :text (str empty-text)}])
     (for [{:keys [id label] :as option} options]
       [:ui/toggle (cond-> {:key id
                            :label (str (if (some? label) label id))
                            :checked (contains? sel id)}
                     (or disabled? (:disabled? option)) (assoc :disabled? true)
                     on-toggle (assoc :on-toggle (conj on-toggle id))
                     test-id (assoc :test-id (str test-id "/" id)))])
     (when on-clear
       [:ui/button (cond-> {:key ::clear
                            :label (or clear-label "Clear")
                            :on-press on-clear}
                     (or disabled? (empty? sel)) (assoc :disabled? true)
                     test-id (assoc :test-id (str test-id "/clear")))])]))

;; ---------------------------------------------------------------------------
;; Training calendar: Monday-first 6 x 7 month grid

(def default-labels
  {:prev "<" :next ">" :today "Today" :set "set" :sets "sets" :min "min" :kcal "kcal"
   :done "done" :today-mark "(today)"})

(defn- round-int
  "Non-negative number rounded half up, as an integer (same on every host)."
  [x]
  (long (+ (double x) 0.5)))

(defn- quantity-text
  "Non-negative number as text: integers as such, otherwise one decimal (2.5 -> \"2.5\").
  Integer arithmetic only: on ClojureCLR 1.12.6 `str` of a double drops a zero fraction
  and .NET number formatting follows the current culture."
  [x]
  (let [tenths (round-int (* 10.0 (double x)))]
    (str (quot tenths 10) (when (pos? (rem tenths 10)) (str "." (rem tenths 10))))))

(defn- month-index
  "\"YYYY-MM\" -> months since year 0 (for range checks), nil when malformed."
  [s]
  (when (and (string? s) (= 7 (count s)) (= "-" (subs s 4 5)))
    (let [digits {"0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7 "8" 8 "9" 9}
          num (fn [part] (reduce (fn [n ch] (when (and n (contains? digits ch)) (+ (* 10 n) (get digits ch))))
                                 0 (map #(subs part % (inc %)) (range (count part)))))
          y (num (subs s 0 4))
          m (num (subs s 5 7))]
      (when (and y m (<= 1 m 12)) (+ (* 12 y) (dec m))))))

(defn default-cell-label
  "Text of one dated calendar cell: every value is written out (no colour-only cues)."
  [{:keys [day duration-min sets planned-items completed-items]}
   kcal selected? today? labels]
  (let [minutes (or duration-min 0)
        active? (or (pos? (or sets 0)) (pos? minutes))
        lines [(str (if selected? (str "[" day "]") day)
                    (when today? (str " " (:today-mark labels))))
               (when active?
                 (str sets " " (if (= 1 sets) (:set labels) (:sets labels))
                      " · " (quantity-text minutes) " " (:min labels)))
               (when active?
                 (if (number? kcal)
                   (str (round-int kcal) " " (:kcal labels))
                   (str "? " (:kcal labels))))
               (when (pos? (or planned-items 0))
                 (str completed-items "/" planned-items " " (:done labels)))]]
    (apply str (interpose "\n" (remove nil? lines)))))

(defn month-grid
  "Monday-first month calendar: a header (previous / title / next / today) above a
  7-column :ui/grid holding the weekday names and the 42 date cells — the shape the
  fitness app's reference core returns (clogem.fitness.analytics/calendar, in
  cloview-examples).

  props
    :calendar   {:month \"YYYY-MM\" :weekdays [7 names]
                 :cells [42 x {:date \"YYYY-MM-DD\" | nil, :day n, :in-month? bool,
                               :duration-min x, :sets n, :planned-items n,
                               :completed-items n, :plan-complete? bool}]}
    :selected   \"YYYY-MM-DD\" or nil            :today \"YYYY-MM-DD\" or nil
    :kcal       {date kcal-or-nil}; a day with activity and no number shows \"? kcal\"
                (unknown energy is never shown as 0)
    :on-select  event vector; a dated cell dispatches (conj on-select date), including
                adjacent-month cells; blank (nil-date) cells are disabled and emit nothing
    :on-prev :on-next :on-today   navigation event vectors
    :min-month \"1900-01\"  :max-month \"2100-12\"  — navigation beyond is disabled
    :title      header text (default: the month)
    :labels     overrides for default-labels (localization)
    :cell-label (fn [cell kcal selected? today? labels] text) replaces default-cell-label
    :gap 4      :cell-height 76 (px; every cell has the same size)
    :test-id    prefix: <id>/prev <id>/next <id>/today <id>/<date>"
  [{:keys [calendar selected today kcal on-select on-prev on-next on-today
           min-month max-month title labels cell-label gap test-id style cell-height]
    :or {min-month "1900-01" max-month "2100-12" gap 4 cell-height 76}}]
  (let [labels (merge default-labels labels)
        cell-label (or cell-label default-cell-label)
        month (:month calendar)
        mi (month-index month)
        prev-off? (or (nil? on-prev) (nil? mi) (<= mi (or (month-index min-month) mi)))
        next-off? (or (nil? on-next) (nil? mi) (>= mi (or (month-index max-month) mi)))
        tid (fn [suffix] (when test-id (str test-id "/" suffix)))]
    [:ui/column (cond-> {:gap gap}
                  style (assoc :style style)
                  test-id (assoc :test-id test-id))
     [:ui/row {:key ::header :gap gap :align :center}
      [:ui/button (cond-> {:label (:prev labels) :disabled? prev-off?}
                    (not prev-off?) (assoc :on-press on-prev)
                    test-id (assoc :test-id (tid "prev")))]
      [:ui/text (cond-> {:text (str (or title month)) :style {:font-size 16 :font-weight :semibold}}
                  test-id (assoc :test-id (tid "title")))]
      [:ui/spacer]
      [:ui/button (cond-> {:label (:today labels) :disabled? (nil? on-today)}
                    on-today (assoc :on-press on-today)
                    test-id (assoc :test-id (tid "today")))]
      [:ui/button (cond-> {:label (:next labels) :disabled? next-off?}
                    (not next-off?) (assoc :on-press on-next)
                    test-id (assoc :test-id (tid "next")))]]
     (into [:ui/grid {:key ::cells :gap gap :columns (vec (repeat 7 {:fr 1}))}]
           (concat
            (map-indexed (fn [i wd]
                           [:ui/text {:key (str "wd" i) :grid/row 0 :grid/col i :text (str wd)
                                      :style {:text-align :center :font-weight :semibold}}])
                         (:weekdays calendar))
            (map-indexed
             (fn [i {:keys [date in-month?] :as cell}]
               (let [selected? (and (some? date) (= date selected))
                     today? (and (some? date) (= date today))
                     props {:key (str "cell" i)
                            :grid/row (inc (quot i 7))
                            :grid/col (rem i 7)
                            :label (if date
                                     (cell-label cell (get kcal date) selected? today? labels)
                                     "")
                            ;; equal cells: a fixed height (4 text lines), full column width
                            :style (cond-> {:height cell-height}
                                     (not in-month?) (assoc :opacity 0.55)
                                     selected? (assoc :border {:width 2 :color "#3366cc"}))}]
                 [:ui/button (cond-> props
                               (and date on-select) (assoc :on-press (conj on-select date))
                               (nil? date) (assoc :disabled? true)
                               (and date test-id) (assoc :test-id (tid date)))]))
             (:cells calendar))))]))

;; ---------------------------------------------------------------------------
;; Keyword registration (optional)

(defn register!
  "Register :cloview/chip-group and :cloview/month-grid as composite tags."
  []
  (t/reg-component! :cloview/chip-group (fn [props _children] (chip-group props)))
  (t/reg-component! :cloview/month-grid (fn [props _children] (month-grid props)))
  :registered)
