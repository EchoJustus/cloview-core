# Cross-Platform Clojure UI Framework (cljfx / ClojureDart / ClojureCLR): Architecture & Feasibility Study (October 2026)

> **Archived verbatim.** This is the deep-research report that preceded the repository,
> completed on 2026-10-23 and copied here unchanged. Two things were added: the citation
> markers (`[^n]`), restored at the passages the report cited, and its 55 sources at the
> end.
>
> The code samples use the study's placeholder names (`ui.core.*`, `unifui/`,
> `modules/jvm`, `modules/clr`); the repository uses `cloview.core.*`, `modules/javafx`
> and `modules/wpf` (STAGE_2_2_DECISIONS.md item 44). How the repository followed or
> departed from the study: [Since the study](#since-the-study), after the original, and
> [`02_REJECTED_AND_DEFERRED_APPROACHES.md`](02_REJECTED_AND_DEFERRED_APPROACHES.md).

---

The project is feasible. Build it as a pure-data `.cljc` core (Hiccup-style view trees, data-vector events, a small re-frame-like store) plus three thin host adapters:

- **JVM:** a data→data translation into cljfx descriptions.
- **Flutter:** a runtime interpreter, implemented as a static registry of hand-written ClojureDart adapter functions.
- **.NET:** WPF on .NET 10 inside a small C# host, with a purpose-built reconciler of roughly 1.5–2.5k LOC.

Don't build on `.edn` view templates, compile-time macro DSLs, Avalonia for V1, or compiled native artifacts.

## TL;DR

- **Write views as data, not as macros or EDN.** Views are plain `.cljc` functions `state → canonical tree`, and events are data vectors such as `[:app/increment 1]`. A runtime interpreter works on ClojureDart because Flutter widgets are immutable runtime objects and Flutter's Element tree already reconciles them. It does need a static registry of adapter fns, because Dart has no runtime eval and allows reflection only in dev mode.
- **CLR target: WPF on `net10.0-windows`.** Host it in a tiny C# exe that references the `Clojure` NuGet package and drives a home-grown keyed reconciler with stable event trampolines. The stock `Clojure.Main` tool targets plain `netX.0`, so loading WPF from it is fragile. Avalonia 12 brings a heavy dependency closure and has no ClojureCLR prior art, so defer it.
- **Distribution: a multi-module monorepo with a core plus per-host adapter modules (Strategy 2).** Ship git deps with `:deps/root`, plus Clojars source jars with an embedded `pubspec.yaml` for the JVM and ClojureDart. Skip compiled native artifacts: they lose macros, protocols and REPL-ability.

---

## Version Baseline (verified, October 2026)

| Component | Version / status | Notes |
|---|---|---|
| Clojure (JVM) | 1.12.6 (Sep 2, 2026); 1.13.0 alphas | Recommended Java 25 LTS, minimum Java 8[^1] |
| cljfx | CHANGELOG 1.10.8 (2026-05-08); GitHub releases 1.10.362 (Sep 15, 2026, JavaFX 27 fix) and 1.10.363 (Oct 8, 2026)[^2][^3][^4] | MIT. Uses a `1.10.<build>` scheme, so pin exact coordinates |
| OpenJFX | 27 (Sep 15, 2026); 28 in preview[^5] | |
| ClojureDart | 0.9.20261002 (Oct 2, 2026)[^6] | Date-stamped releases |
| Flutter / Dart | 3.47 (Aug 12, 2026)[^7] with Dart 3.13; pin 3.47.8 or later (flutter.dev: "Flutter 3.47.8 now blocks compromised eMudhra CA keys", advisory GHSA-frr6-h895-5pxp) | `material_ui`/`cupertino_ui` 1.0 are opt-in; in-SDK Material slated for deprecation in November[^7][^8] |
| ClojureCLR | `Clojure`/`Clojure.Main` 1.12.6 (Sep 9, 2026); 1.13.0-alpha8 (Oct 2, 2026)[^9] | Library targets net6.0–net11.0 and net462/net481. The tool targets plain `netX.0` only[^9][^10] |
| cljr | `Clojure.Cljr` 0.1.0-beta1 | "Anything related to Maven or JVM is not supported"[^11] |
| ClojureCLR.Next | F# rewrite, in progress[^12] | Not for V1 |
| .NET | .NET 10 LTS; .NET 11 in development | ClojureCLR 1.12.3-alpha6+ has experimental .NET 11 runtime-async support[^13] |
| Avalonia | 12.1.4 (Oct 9, 2026)[^14][^15] | v12 requires .NET 8+ and recommends .NET 10[^16] |
| clr.core.async | 1.9.865 | |
| re-dash | 1.2.1 (Aug 2025) | re-frame for ClojureDart; prior art only[^17] |

---

## 1. Bridging the Host Rendering Paradigms

### 1.1 The unifying insight

All three hosts consume the same per-frame artifact: an immutable canonical tree, re-derived from state. They differ only in who diffs it.

| Host | Who reconciles? | Adapter job |
|---|---|---|
| JVM / cljfx | cljfx (diffs description maps → JavaFX mutations) | Pure fn: canonical node → cljfx description |
| Flutter / ClojureDart | Flutter's Element tree (matches on runtimeType + key)[^18] | Fn: canonical node → `Widget`, plus a few stateful wrappers |
| CLR / WPF | **Nobody yet** | Reconciler: diff trees → mutate `UIElement`s |

Only the CLR needs a new engine, and that asymmetry should drive the budget.

### 1.2 JVM: cljfx

**Verified model:**

- Components are maps with `:fx/type`. Lifecycles implement `create`/`advance`/`delete`, and props are applied by mutators that receive old and new values.[^19][^20]
- `fx/create-renderer` takes `:middleware` (e.g. `(fx/wrap-map-desc assoc :fx/type root)`) and `:opts`.[^20]
- With `:fx.opt/map-event-handler`, a map-valued event prop calls one handler, which receives the JavaFX event under `:fx/event`.[^19][^20]
- `fx/create-context` with `fx/sub-val`/`fx/sub-ctx` gives memoized subscriptions.[^21][^22]
- `fx/ext-instance-factory` and `fx/ext-on-instance-lifecycle` are the escape hatches for pre-built nodes.[^19]
- The 1.10.8 changelog says JavaFX mutations "are still the main bottleneck".[^2]

**Adapter: pure data→data.**

```clojure
(ns ui.jvm.cljfx
  (:require [cljfx.api :as fx] [ui.core.tree :as t] [ui.core.style :as s]))

(defn ev [event-vec] (when event-vec {::event event-vec}))   ; vector → cljfx map-event
(defmulti ->desc t/tag)

(defmethod ->desc :ui/column [{:keys [props children]}]
  {:fx/type :v-box :spacing (:gap props 0) :padding (s/->insets (:padding props))
   :style (s/->fx-css props)
   :children (mapv #(cond-> (->desc %) (:grow (t/props %)) (assoc :v-box/vgrow :always)) children)})
(defmethod ->desc :ui/text-input [{:keys [props]}]
  {:fx/type :text-field :text (:value props "") :prompt-text (:placeholder props "")
   :on-text-changed (ev (:on-change props))})
(defmethod ->desc :ui/button [{:keys [props]}]
  {:fx/type :button :text (:label props) :on-action (ev (:on-press props))})

(defn map-event-handler [dispatch!]
  (fn [m] (dispatch! (conj (::event m) (t/normalize-payload (:fx/event m))))))
```

**Overhead and limits.** The extra pass is an O(n) map allocation, small next to JavaFX mutation cost. Two things to watch:

- Dispatch text changes synchronously on the FX thread, or controlled inputs will lose their caret position.
- All mutations must run on the JavaFX Application Thread. `fx/mount-renderer` schedules renders there; effect callbacks must use `Platform/runLater` (or `fx/on-fx-thread`).

**Port cljfx's engine to `.cljc` for the CLR?** The MIT licence allows it, but it isn't worth it. The engine is bound to JavaFX coercion tables, and the author's issue #33 lists design regrets. One of them, the trampoline insight, cljfx has since shipped: its 1.9.0 changelog (2024-06-11) says "even when cljfx description's event handler changes, the JavaFX EventHandler/ChangeListener object stays the same… we only update the reference to the current handler in the state." **Port the concepts (create/advance/delete, prop mutators, ext-instance) into a smaller `.cljc` engine.**

### 1.3 Dart: ClojureDart and `cljd.flutter`

**Verified (ClojureDart Book and source):**

- `f/widget` is "a threading macro tailored for building Flutter UIs" with directives. Namespaced keywords are open for third-party directives.[^18]
- `:watch` "works with anything that implements the `cljd.flutter/Subscribable` protocol": nil, atoms, Streams, Futures, `ValueListenable` and `Listenable`.[^18]
  - It is destructuring-aware: `:watch [{:keys [the-key]} busy-atom]` rebuilds only when `:the-key` changes.[^18]
  - Options: `:as`, `:default`, `:>`, `:dispose`, `:value>`, `:dispose-value`, `:refresh-on`.[^18]
- `:managed` keeps a resource alive across rebuilds, disposes it by default, and re-evaluates when dependent locals change.[^18]
  - The Book warns that updating global state on every keystroke can trigger a rebuild "which in turn disposes and recreates the controller — and you lose caret position and IME state."[^18]
- `:key` must be unique among siblings.[^18]
- Generated code: the deprecated `cljd.flutter.alpha` source emits a stateful widget whose state calls `(add-watch ~watch ~state-sym (fn [k# r# o# n#] (.setState ~state-sym (fn []))))`, with `remove-watch` on dispose.[^23] So **`f/widget` with stateful directives expands to an anonymous StatefulWidget per call site; without them it is just nested constructor calls.** That is verified for the alpha namespace only. I did not re-read the current namespace line by line.
- Dart "offers only limited reflection (and only in dev mode), doesn't support dynamic code loading", and `instance?` "must be fed a class name directly." Constructors can be torn off (`DateTime.new`). `:no-dynamic true` turns dynamic warnings into errors.[^18]

**The crucial question: can we interpret runtime maps instead of using macros?**

**Yes.** A `Widget` is an ordinary immutable object constructed at runtime, and `f/widget` is sugar over that. An interpreter called inside one root `build` produces a fresh widget tree each frame. Flutter's Element tree diffs it by runtimeType + key and keeps the `State` objects, exactly as it would for hand-written Dart.[^18]

Constraints:

1. **No eval, no mirrors in AOT.** Every widget class must appear literally in compiled code, which keeps tree-shaking safe.
2. **Named args from a map** are possible via `Function.apply(f, pos, Map<Symbol,dynamic>)`. Symbols exist so that `Function.apply` "work[s] better with minification".[^24][^25] But this route is untyped, bypasses magicast, and triggers dynamic warnings. How runtime-built Symbols behave under web minification is unverified. Avoid it.
3. **So you need a static registry of hand-written adapter fns.** That is the right design anyway, since each adapter also normalizes style and event payloads.

```clojure
(ns ui.flutter.adapters
  (:require ["package:flutter/material.dart" :as m]   ; → package:material_ui after migration
            ["package:flutter/widgets.dart" :as w]
            [cljd.flutter :as f] [ui.core.tree :as t] [ui.flutter.style :as s]))
(declare render text-input-widget)
(defonce registry
  (atom
   {:ui/column (fn [{:keys [props children]} d]
                 (m/Column .spacing (double (:gap props 0))
                   .children (mapv #(cond->> (render % d) (:grow (t/props %)) (m/Expanded .child)) children)))
    :ui/text   (fn [{:keys [props]} _] (m/Text (str (:text props)) .style (s/text-style props)))
    :ui/button (fn [{:keys [props]} d]
                 (m/ElevatedButton .onPressed (when-let [e (:on-press props)] (fn [] (d e) nil))
                   .child (m/Text (str (:label props)))))
    :ui/text-input (fn [{:keys [props key]} d] (text-input-widget key props d))}))
(defn reg-adapter! [tag f] (swap! registry assoc tag f))
(defn render [node d]
  (let [f (or (@registry (t/tag node)) (throw (ex-info "No adapter" {:tag (t/tag node)})))
        wdg (f node d)]
    (if-let [k (t/key node)] (m/KeyedSubtree .key (w/ValueKey k) .child wdg) wdg)))
```

**Controlled text input:** keep the controller in `:managed` with `:refresh-on nil`, and write it only when the external value differs:

```clojure
(defn text-input-widget [k {:keys [value placeholder on-change]} dispatch!]
  (f/widget
    :key k
    :managed [ctrl (m/TextEditingController .text (or value "")) :refresh-on nil]
    :let [_ (when (and value (not= value (.-text ctrl)))
              (.-value! ctrl (m/TextEditingValue .text value
                               .selection (m/TextSelection.collapsed .offset (count value)))))]
    (m/TextField .controller ctrl .decoration (m/InputDecoration .hintText placeholder)
      .onChanged (fn [^String s] (when on-change (dispatch! (conj on-change s))) nil))))
```

> Mutating the controller during build may trip a "setState during build" assertion. If it does, defer the write with `addPostFrameCallback`. Verify on Flutter 3.47.

**Other things that need real stateful widgets:**

- APIs that depend on BuildContext (`Theme.of`, `Navigator`, `showDialog`). Use a `Builder` or `f/widget`'s context access.
- `:ui/modal`, a StatefulWidget that calls `showDialog` post-frame on the rising edge of `:open?` and pops on the falling edge.
- Animations and vsync, which are out of V1 scope.

**Performance.** One root `:watch` rebuilds the whole widget tree on every change. That is fine for forms, but not for large lists. Mitigations:

1. Memoize subtrees by `identical?` input and return the *same* Widget instance, which Flutter short-circuits.
2. Add `:ui/island` nodes, which render as an `f/widget` with their own `:watch` on a subscription.
3. Render `:ui/list` lazily with `ListView.builder`.

Use Option C macros only for profiled hot paths. Even then, the macros should emit the same adapter calls.

### 1.4 CLR: a lightweight reconciler for WPF

The reconciler keeps an instance tree that mirrors the canonical tree: `{:tag :key :props :control :children :handlers (atom {...}) :applying? (volatile! false)}`.

1. **Identity.** A node is reused when tag and `:key` are equal; unkeyed nodes fall back to position. Otherwise destroy and create.
2. **Prop diffing.** For each changed key, call a per-tag mutator: `{:label (fn [^Button b v] (set! (.Content b) v))}`.
3. **Keyed children.** Build a `key → old-inst` map. Remove stale children. Then for each index, `Remove`/`Insert` on `Panel.Children` if the control isn't the desired one. The worst case is O(n²), which is fine at UI scale.
4. **Stable trampolines.** Attach one delegate per control and event at creation. It reads the current event vector from `:handlers`, and updates just `swap!` that atom, so there is no `add_`/`remove_` churn:

```clojure
(defn- attach-click! [^System.Windows.Controls.Button b handlers dispatch!]
  (.add_Click b (gen-delegate System.Windows.RoutedEventHandler [_ _]
                  (when-let [e (:on-press @handlers)] (dispatch! e)))))
```

5. **Attached props.** Use parent-relative setters such as `(Grid/SetRow el (int r))` and `(DockPanel/SetDock el Dock/Left)`.
6. **Dispatcher and coalescing.** On each state change, run `(when (compare-and-set! dirty? false true) (.BeginInvoke dispatcher (sys-action [] (reset! dirty? false) (render!)) DispatcherPriority/Render))`. Many `swap!`s then produce one reconcile. Overload resolution for `BeginInvoke` may need hints, or use `InvokeAsync` instead; verify at the REPL.
7. **Focus and caret.** Matching controls are reused, so focus survives. Write `TextBox.Text` only when it differs, and restore `CaretIndex` to `min(old, len)`.
8. **Re-entrancy guard.** Programmatic `.Text` writes raise `TextChanged`, so set `applying?` around writes and have the trampoline return early while it is set.

```clojure
(defn- set-text! [^System.Windows.Controls.TextBox tb inst v]
  (when (not= (.Text tb) v)
    (let [caret (.CaretIndex tb)]
      (vreset! (:applying? inst) true)
      (try (set! (.Text tb) v) (set! (.CaretIndex tb) (min caret (count v)))
           (finally (vreset! (:applying? inst) false))))))
```

**Size:**

| Part | LOC |
|---|---|
| `.cljc` reconcile core over a `HostOps` protocol | 400–600 |
| WPF tag table (~20 tags) | 700–1,200 |
| Dispatcher, bootstrap, dialogs | 200–400 |
| **Total** | **≈1.5–2.5k** |

The core also runs headless on the JVM against a fake `HostOps`, which makes it testable.

**.NET prior art:** Avalonia.FuncUI, Fabulous, MauiReactor, Comet and Elmish.WPF. They are F#/C# libraries built on generics, computation expressions or source generators, and most target MAUI or Avalonia. Treat them as **design references only**; FuncUI's attribute diffing and Fabulous's keyed child diff are the most relevant. I did not re-verify their 2026 status.

---

## 2. `.cljc` vs `.edn` View Representation

### 2.1 Constraints

- **Reader conditionals.** `:clj` and `:cljr` are documented platform tags,[^26][^27] and ClojureDart uses `:cljd`. ClojureDart moved to tools.reader so it can ignore `:clj` when reading `.cljc`.[^28]
- **ClojureDart macros run on the JVM.** Namespaces use `(:host-ns (:require ...))` and `^:macro-support` helpers, both visible in the `cljd.flutter.alpha` source.[^23] A `:cljd/clj-host` feature keyword is **unverified**.
- **Shared macros.** These must emit only calls to runtime fns and never host interop, and must not inspect `&env`. The recommended design needs **zero macros** in the core.
- **Runtime EDN.** `clojure.edn` is available on the JVM and ClojureCLR. ClojureDart has a reader (tools.reader-compatible fixes were reported in 2026),[^29] but I could not confirm a public `cljd.edn` namespace. Flutter assets load via `rootBundle.loadString`.
- **Nullability.** In ClojureDart, `^String` means non-nullable.[^18] Avoid type hints in shared `.cljc`.

### 2.2 Trade-off matrix

| Dimension | A: `.edn` templates | **B: `.cljc` data + data events** | C: macro DSL |
|---|---|---|---|
| Expressiveness | Low (needs `:if`/`:for`/`:sub` language) | **Full Clojure** | Full, macro-time |
| REPL/hot reload | Asset reload | **Re-eval fn / Dart hot reload** | Re-expansion |
| Runtime cost | Interpreter + templates | One interpreter pass | Lowest on Dart |
| Compile-time safety | None | Spec/malli + tests | Best |
| Views as values | Yes | **Yes** | No |
| JVM testability | Good | **Excellent (snapshots)** | Poor |
| Editor tooling | Weak | **Full clj-kondo/LSP** | Needs hooks |
| Escape-hatch friction | High (no fns) | **Low** | Medium |
| ClojureDart fit | Needs runtime EDN (unverified) | **Static registry works** | Native |
| Learning curve | New language | **Familiar** | 3 expansion semantics |

**Verdict: B.**

- **Option A reinvents a language.** EDN can't hold functions, so loops, conditionals and subscriptions become a bespoke interpreter.
- **Option C** makes views opaque and triples the macro semantics.

Prior-art lessons:

- re-frame: data events, interceptors, effects as data.
- Replicant + Nexus: pure hiccup plus action data.
- cljfx: map events.[^20]
- membrane: a cross-backend Clojure UI that draws on Skia/Java2D/WebGL/terminal with cljfx integration;[^30][^31][^32] I found no Flutter or CLR backend.

membrane validates the "data model + backends" approach, but it draws its own widgets, which is the opposite of native mapping.[^33] EDN is still fine for *static data*: themes, strings and schemas.

### 2.3 The same sample under A, B and C

**A (EDN):**
```clojure
[:ui/column {:gap 8 :padding 16}
 [:ui/text {:text "Greeter" :style {:font-size 20 :font-weight :bold :color "#3366CC"}}]
 [:ui/text-input {:value {:sub [:name]} :on-change [:app/set-name]}]
 [:ui/button {:label "Greet" :on-press [:app/greet]}]
 [:ui/when {:sub [:greeting]} [:ui/text {:text {:sub [:greeting]}}]]]
```

**B (recommended):**
```clojure
(defn greeter [{:keys [name greeting]}]
  [:ui/column {:gap 8 :padding 16}
   [:ui/text {:text "Greeter" :style {:font-size 20 :font-weight :bold :color "#3366CC"}}]
   [:ui/text-input {:key :name :value name :placeholder "Your name" :on-change [:app/set-name]}]
   [:ui/button {:label "Greet" :on-press [:app/greet]}]
   (when greeting [:ui/text {:text greeting}])])
```

**C (macro):**
```clojure
(defview greeter [{:keys [name greeting]}]
  (column {:gap 8 :padding 16}
    (text "Greeter" {:font-size 20 :font-weight :bold :color "#3366CC"})
    (text-input {:value name :on-change [:app/set-name]})
    (button "Greet" [:app/greet])
    (when greeting (text greeting))))
```

### 2.4 The recommended approach end to end

```clojure
(ns app.greeter (:require [ui.core.store :as st]))
(st/reg-event :app/set-name (fn [db [_ s]] {:db (assoc db :name s)}))
(st/reg-event :app/greet (fn [db _] {:db (assoc db :greeting (str "Hello, " (:name db) "!"))}))
(defn root [db] (greeter db))
```

**JVM:**
```clojure
;; produced description
{:fx/type :v-box :spacing 8 :padding 16
 :children [{:fx/type :label :text "Greeter" :style "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #3366CC;"}
            {:fx/type :text-field :text "" :prompt-text "Your name" :on-text-changed {::event [:app/set-name]}}
            {:fx/type :button :text "Greet" :on-action {::event [:app/greet]}}]}
(fx/mount-renderer st/app-db
  (fx/create-renderer
    :middleware (fx/wrap-map-desc (fn [db] {:fx/type :stage :showing true
                                           :scene {:fx/type :scene :root (->desc (t/normalize (root db)))}}))
    :opts {:fx.opt/map-event-handler (map-event-handler st/dispatch!)}))
```

**Dart:**
```clojure
(defn main []
  (f/run (m/MaterialApp) .home (m/Scaffold) .body
    (f/widget :watch [db st/app-db]
      (adapters/render (t/normalize (app.greeter/root db)) st/dispatch!))))
;; ≈ (m/Column .spacing 8.0 .children [(m/Text "Greeter" .style (m/TextStyle .fontSize 20.0 ...
;;     .color (m/Color 0xFF3366CC))) <keyed text-input> (m/ElevatedButton ...)])
```

**CLR (WPF reconciler op log):**
```text
mount:  StackPanel(Vertical, Margin=16) + TextBlock("Greeter", 20, Bold, #FF3366CC)
        + TextBox(trampoline → [:app/set-name s], Margin 0,8,0,0) + Button("Greet", → [:app/greet])
type A: dispatch [:app/set-name "A"] → coalesced render → .Text already "A" → no write, caret intact
click:  [:app/greet] → Insert TextBlock("Hello, A!") at index 3
```

---

## 3. ClojureCLR GUI Target Selection

### 3.1 Facts

- **Versions.** `Clojure`/`Clojure.Main` 1.12.6 (Sep 9, 2026) and 1.13.0-alpha8 (Oct 2, 2026). The library targets net6.0–net11.0 and net462/net481.[^9] The tool targets plain `netX.0`; `net8.0-windows` appears only as "computed".[^10]
- **Running.**
  - `dotnet tool install -g Clojure.Main`.[^34]
  - `cljr` reads **`deps-clr.edn` first**, which is the documented workaround for multi-dialect repos.[^11]
  - It supports REPL, `-X` and `-M`; `-T` is "Not yet", and there is no Maven support.[^11]
- **Embedding.** The wiki says to "include the ClojureCLR library package via NuGet… `Install-Package Clojure`" and to "use the methods in the `clojure.clr.api.Clojure` class" rather than `RT`. Example: `IFn load = clojure.clr.api.Clojure.var("clojure.core","load");`.[^35]
- **WPF from `Clojure.Main` (inferred, not verified).** On .NET 5+, WPF and WinForms ship in `Microsoft.WindowsDesktop.App`, which the app's runtimeconfig must reference. IronRuby hit exactly this: "ir.runtimeconfig.json bundled only Microsoft.NETCore.App."[^36] Old ClojureCLR WPF gists (`Assembly/LoadWithPartialName "PresentationFramework"`, a manual STA thread, `gen-delegate Action`) date from the .NET Framework/GAC era.[^37][^38]
- **Robust pattern: a tiny C# host.**

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><OutputType>WinExe</OutputType>
    <TargetFramework>net10.0-windows</TargetFramework><UseWPF>true</UseWPF></PropertyGroup>
  <ItemGroup><PackageReference Include="Clojure" Version="1.12.6" />
    <None Include="..\src\**\*.clj*" CopyToOutputDirectory="PreserveNewest" LinkBase="clj" /></ItemGroup>
</Project>
```
```csharp
public static class Program {
  [System.STAThread] public static void Main(string[] args) {
    var app = new System.Windows.Application();
    clojure.clr.api.Clojure.var("clojure.core","require").invoke(clojure.clr.api.Clojure.read("app.main.clr"));
    clojure.clr.api.Clojure.var("app.main.clr","start!").invoke(app);
    app.Run();
  }
}
```
> How the host locates `.clj` files (via the load path or the `CLOJURE_LOAD_PATH` environment variable) needs testing. `CLOJURE_LOAD_PATH` appears only in community tutorials, not the official wiki.[^39]

- **.NET Framework 4.8 fallback.** WPF is in the GAC there, but cljr needs .NET 6+.[^11] Use this only as a fallback.

### 3.2 Comparison

| Criterion | **WPF** | WinForms | Avalonia 12 |
|---|---|---|---|
| Loading | WindowsDesktop runtime via C# host | Same | Large NuGet closure (Avalonia, Desktop, Skia, SkiaSharp natives, HarfBuzzSharp, Fluent theme); native assets need MSBuild |
| Threading | STA + `Dispatcher` | STA + `Application.Run`/`BeginInvoke` | `Dispatcher.UIThread` |
| Construction | Code objects (easy) or `XamlReader.Parse` | Code, easy | Needs an `Application` subclass and a theme before controls render |
| Taxonomy fit | StackPanel/Grid/GridSplitter/TabControl, `CornerRadius` | **No corner radius**, weaker DPI | Excellent |
| Churn | Stable | Stable | Mixing 12.0.x/12.1.x assemblies has caused `TypeLoadException` (NXUI)[^40] |
| ClojureCLR prior art | Old gists/CloDE[^37][^41] | 2011–12 blog/gists[^42][^43] | None found |

**Verdict: WPF for V1**, code-constructed, inside a `net10.0-windows` C# host. Avalonia is a V2 option behind the same `HostOps` protocol.

**Interop:**
```clojure
(def btn (doto (System.Windows.Controls.Button.) (.set_Content "Greet")))
(.add_Click btn (gen-delegate System.Windows.RoutedEventHandler [s e] (println "clicked")))
(System.Windows.Controls.Grid/SetRow btn 1)
(set! (.Margin btn) (System.Windows.Thickness. 0 8 0 0))
(set! (.Background btn) (System.Windows.Media.SolidColorBrush. (System.Windows.Media.Color/FromArgb 255 0x33 0x66 0xCC)))
```

---

## 4. Canonical Widget, Layout & Styling Taxonomy (V1)

### 4.1 Host facts

- **Gap.** JavaFX `spacing` works. Flutter `Row`/`Column` gained `.spacing` in **Flutter 3.27**.[^44][^45] The Flutter Blog's "What's new in Flutter 3.27" (Dec 11, 2024) describes it as a "double parameter [that] will apply a fixed amount of pixels to offset children of Rows and Columns from each other." WPF `StackPanel` still has no gap property:
  - dotnet/wpf issue #3591 ("Add Spacing property to StackPanel (similar to Uwp)") is still an open request.
  - The UWP property dates from Windows 10 Fall Creators Update (10.0.16299.0).
  - The claim that .NET 8 added it comes from an unofficial demo page that contradicts itself.

  **Emulate it with child margins.**[^46]
- **Grow.** A WPF `StackPanel` gives children infinite length along its axis, so it can't grow them.[^47] When any child has `:grow`, map `:ui/column` to a `Grid` with star rows. JavaFX uses `vgrow`/`hgrow`, Flutter uses `Expanded(flex)`.
- **Switch.** JavaFX has no core switch (ControlsFX has `ToggleSwitch`), and WPF needs a templated `ToggleButton`. V1 falls back to a checkbox on both.
- **Material imports.** Isolate them in one namespace so the Flutter 3.47 `material_ui` migration touches only that file.[^48]

### 4.2 Mapping table

| Tag | Props | cljfx | ClojureDart | WPF |
|---|---|---|---|---|
| `:ui/column`/`:ui/row` | `:gap :align :padding`; child `:grow` | `:v-box`/`:h-box` `:spacing`, `vgrow` | `Column`/`Row` `.spacing`, `Expanded` | `StackPanel` + margins; `Grid` star if grow |
| `:ui/grid` | `:columns :rows`; `:grid/row :grid/col :grid/span` | `:grid-pane` | `Table` or custom layout | `Grid` + definitions, `SetRow/SetColumn` |
| `:ui/scroll` | `:direction` | `:scroll-pane` | `SingleChildScrollView` | `ScrollViewer` |
| `:ui/box` | `:padding :style` | `:stack-pane` + style | `Container`/`DecoratedBox` | `Border` |
| `:ui/align` | `:align` | `:stack-pane :alignment` | `Align` | H/V alignment |
| `:ui/text` | `:text :style` | `:label` | `Text` | `TextBlock` |
| `:ui/button` | `:label :on-press` | `:button` | `ElevatedButton` | `Button` |
| `:ui/text-input` | `:value :on-change(str)` | `:text-field` | `TextField` + managed controller | `TextBox` |
| `:ui/checkbox`/`:ui/switch` | `:checked :on-toggle(bool)` | `:check-box` | `Checkbox`/`Switch` | `CheckBox` |
| `:ui/select` | `:options :value :on-select(value)` | `:combo-box` | `DropdownMenu` | `ComboBox` |
| `:ui/image` | `:src {:asset/:file/:url}` | `:image-view` | `Image.asset/.file/.network` | `Image` + `BitmapImage` |
| `:ui/split-view` | `:orientation :ratio :on-resize` | `:split-pane` (custom lifecycle) | custom Row + drag divider (~80 LOC) | `Grid` + `GridSplitter` |
| `:ui/tabs` | `:tabs :selected :on-select(id)` | `:tab-pane` | `TabBar`/`TabBarView` | `TabControl` |
| `:ui/modal` | `:open? :on-dismiss` | `:stage` with modality | `showDialog` on edges | overlay layer (avoid `ShowDialog` nested loop) |
| `:ui/native` | per-host hooks | ext-instance-factory | Widget or `(fn [ctx])` | wrapped `UIElement` |

**Declarative modal.** `:open?` lives in db, and each adapter turns its edges into the host idiom. On WPF, use an overlay inside the window: it keeps one tree and avoids `ShowDialog`'s blocking nested loop.

### 4.3 Styling subset

| Key | JavaFX CSS | Flutter | WPF |
|---|---|---|---|
| `:color` | `-fx-text-fill` | `TextStyle.color` | `Foreground` |
| `:background` | `-fx-background-color` | `BoxDecoration.color` | `Background` |
| `:font-size` | `-fx-font-size: Npx` | `fontSize` | `FontSize` |
| `:font-weight` | `-fx-font-weight` | `FontWeight.wN00` | `FontWeight.FromOpenTypeWeight` |
| `:font-family` | `-fx-font-family` | `fontFamily` | `FontFamily` |
| `:padding`/`:margin` | `-fx-padding` / pane margin | `EdgeInsets`/`Padding` | `Padding`/`Margin` |
| `:border` (uniform) | `-fx-border-width/color` | `Border.all` | `BorderThickness/BorderBrush` |
| `:radius` (uniform) | `-fx-background-radius` + `-fx-border-radius` | `BorderRadius.circular` | `CornerRadius` |
| `:opacity` | `-fx-opacity` | `Opacity` | `Opacity` |
| sizes | pref/min/max | `SizedBox`/`ConstrainedBox` | `Width/MinWidth/MaxWidth` |

**Units.** All three hosts use logical pixels in practice (JavaFX scales px on HiDPI, WPF DIPs are 1/96 inch). One canonical unit equals one logical pixel.

**Colors.** The canonical form is `"#RRGGBBAA"`. Alpha ordering differs per host:

- WPF uses `#AARRGGBB`.
- Flutter uses `Color(0xAARRGGBB)`.
- JavaFX CSS accepts `#RRGGBBAA` (verify against the JavaFX CSS Reference).

Centralize the conversions in `ui.core.color`.

**Excluded from V1:** shadows, gradients, per-side borders and per-corner radii, pseudo-states, animations, letter-spacing/line-height, and theming beyond light/dark. Reach these via `:ui/native` or a pass-through `:host-style` map.

### 4.4 Event payloads

`:on-press` appends nothing; `:on-change` appends the string; `:on-toggle` appends a bool; `:on-select` appends the option `:value`; `:on-resize` appends a ratio. Inputs are controlled by default, and `:default-value` gives uncontrolled mode. Children in dynamic lists require `:key`.

---

## 5. Host-Specific Customization & Extension Mechanism

**Rule: the canonical tree never contains host objects, except inside `:ui/native`.** Extension goes through a per-host registry keyed by tag: `defmulti` on the JVM/CLR, and a `reg-adapter!` map on Dart.

**Shared usage:**
```clojure
[:ui/split-view {:orientation :horizontal :ratio (:split db 0.3) :on-resize [:app/set-split]}
 [:ui/column {} [:ui/text {:text "Sidebar"}]]
 [:ui/box {:padding 8} [:ui/text {:text "Content"}]]]
```

**Library implementation:**
```clojure
(ns ui.adapters.split-view
  (:require #?(:clj [ui.jvm.cljfx :as jfx]) #?(:cljd [ui.flutter.adapters :as fl])
            #?(:cljr [ui.clr.wpf :as wpf])))
#?(:clj (defmethod jfx/->desc :ui/split-view [{:keys [props children]}]
          {:fx/type jfx/split-pane-lifecycle :orientation (:orientation props)
           :divider-positions [(:ratio props)] :on-divider-moved (jfx/ev (:on-resize props))
           :items (mapv jfx/->desc children)}))
#?(:cljd (fl/reg-adapter! :ui/split-view
           (fn [{:keys [props children]} d]
             (split-view-widget props (mapv #(fl/render % d) children) d))))  ; Row + drag divider
#?(:cljr (defmethod wpf/create :ui/split-view [{:keys [props]}]
           (let [g (System.Windows.Controls.Grid.)
                 sp (doto (System.Windows.Controls.GridSplitter.) (.set_Width 5.0))]
             (wpf/set-columns! g [[(:ratio props) :star] [5 :px] [(- 1 (:ratio props)) :star]])
             (System.Windows.Controls.Grid/SetColumn sp 1)
             (.Add (.Children g) sp)
             (wpf/on-drag-completed! sp #(wpf/emit-ratio! g props))
             g)))
```
On WPF, `wpf/child-slot` places children in columns 0 and 2 (the parent-relative hook from §1.4).

**User components.** Composite components are pure composition, inlined by `normalize`, and need no host code:
```clojure
(ui.core/defcomponent :app/rating
  (fn [{:keys [value on-change]}]
    (into [:ui/row {:gap 2}]
          (for [i (range 1 6)]
            [:ui/button {:key i :label (if (<= i value) "★" "☆") :on-press (conj on-change i)}]))))
```

**Raw native nodes:**
```clojure
[:ui/native
 {:key :chart :props {:data (:series db)}
  :host #?(:clj  {:create #(make-fx-chart) :update (fn [c p] (update-series! c (:data p)))}
           :cljd {:build (fn [ctx p] (chart-widget (:data p)))}
           :cljr {:create #(make-wpf-chart) :update (fn [c p] (update-wpf-chart! c (:data p)))
                  :dispose (fn [c] (.Dispose c))})}]
```

How each host handles the native node:

- **cljfx:** `fx/ext-instance-factory` wrapped in `ext-on-instance-lifecycle`, or a tiny custom lifecycle that calls `:update` in `advance`.[^19]
- **Flutter:** `:build` runs every render, and Flutter reconciles the result.
- **WPF:** `:create` runs once per key, `:update` runs when `:props` changes, and `:dispose` runs on removal.

**Diffing rule.** Native nodes are compared on tag, key and `:props` only. The `:host` closures are excluded, or the node would be recreated on every render. **A `:ui/native` without a `:key` inside a dynamic list loses its state.**

---

## 6. Cross-Platform Business Logic, State & Effects

### 6.1 State

- **One `app-db` atom.** Atoms are thread-safe CAS on the JVM and CLR. On ClojureDart they are `Subscribable`, so `:watch` works directly.[^18]
- **Threading.** `swap!` can happen on any thread; the renderer marshals. cljfx handles render scheduling, while effects use `Platform/runLater`. WPF uses the coalesced `BeginInvoke`. Dart runs in a single isolate.
- **Don't depend on re-frame or re-dash.** re-frame is tied to CLJS/React, and re-dash is ClojureDart-only and has no CLR port.[^49][^50] A `.cljc` core of **~300–500 LOC** is enough.

### 6.2 Core

```clojure
(ns ui.core.store)
(defonce app-db (atom {}))
(defonce ^:private hs (atom {:event {} :fx {} :sub {}}))
(defn reg-event [id f & {:keys [interceptors]}] (swap! hs assoc-in [:event id] {:f f :ic (vec interceptors)}))
(defn reg-fx [id f] (swap! hs assoc-in [:fx id] f))
(defn reg-sub [id f] (swap! hs assoc-in [:sub id] f))

(defn handle "Pure: db, event -> effects map" [db [id :as event]]
  (let [{:keys [f ic]} (or (get-in @hs [:event id]) (throw (ex-info "No handler" {:event event})))
        run (fn [ctx k ics] (reduce #(if-let [g (k %2)] (g %1) %1) ctx ics))
        ctx (run {:coeffects {:db db :event event}} :before ic)
        ctx (assoc ctx :effects (f (get-in ctx [:coeffects :db]) event))]
    (:effects (run ctx :after (rseq ic)))))

(defonce ^:private queue (atom []))
(defonce ^:private running? (volatile! false))
(defn dispatch! [event]
  (swap! queue conj event)
  (when-not @running?
    (vreset! running? true)
    (try (loop []
           (when-let [e (first @queue)]
             (swap! queue subvec 1)
             (let [fx (handle @app-db e)]
               (when (contains? fx :db) (reset! app-db (:db fx)))
               (doseq [[k v] (dissoc fx :db)] ((get-in @hs [:fx k]) v dispatch!)))
             (recur)))
         (finally (vreset! running? false)))))

(let [cache (atom {})]
  (defn query [db id & args]
    (let [k (into [id] args) [db' v] (@cache k)]
      (if (identical? db db') v
          (let [v (apply (get-in @hs [:sub id]) db args)] (swap! cache assoc k [db v]) v)))))
```
> The queue keeps synchronous semantics, which preserves the caret. On the JVM and CLR it assumes `dispatch!` runs on the UI thread, so effect runners must marshal back first.

### 6.3 Effect runners

```clojure
(st/reg-event :user/load
  (fn [db [_ id]] {:db (assoc db :loading? true)
                   :http {:url (str "https://api.example.com/users/" id)
                          :on-success [:user/loaded] :on-failure [:user/failed]}}))

(st/reg-fx :http
  (fn [{:keys [url on-success on-failure]} dispatch!]
    #?(:clj (-> (.sendAsync (java.net.http.HttpClient/newHttpClient)
                  (.build (java.net.http.HttpRequest/newBuilder (java.net.URI. url)))
                  (java.net.http.HttpResponse$BodyHandlers/ofString))
                (.whenComplete (reify java.util.function.BiConsumer
                  (accept [_ r ex] (javafx.application.Platform/runLater
                    #(dispatch! (if ex (conj on-failure (str ex)) (conj on-success (.body r)))))))))
       :cljd (-> (http/get (Uri/parse url))
                 (.then (fn [r] (dispatch! (conj on-success (.-body r))) nil))
                 (.catchError (fn [e] (dispatch! (conj on-failure (str e))) nil)))
       :cljr (.ContinueWith (.GetStringAsync (System.Net.Http.HttpClient.) url)
               (sys-action [t] (ui.clr.wpf/on-ui #(dispatch! (if (.IsFaulted t)
                                                               (conj on-failure (str (.Exception t)))
                                                               (conj on-success (.Result t))))))))))
```
> In the `:cljr` branch, `ContinueWith` overload resolution with `sys-action` is unverified, and generic delegate typenames may be needed. The `:cljd` branch needs the `http` package in the module's `pubspec.yaml`.

| Concern | JVM | ClojureDart | ClojureCLR |
|---|---|---|---|
| Async | `CompletableFuture`, virtual threads | `Future`/`Stream`, `await` | `Task`; .NET 11 runtime async (experimental)[^13] |
| core.async | yes | unverified | `clr.core.async` 1.9.865[^51] |
| HTTP | `java.net.http` | `package:http` (`dart:io` not on web) | `HttpClient` |
| Files | `java.nio` | `dart:io` + `path_provider` (not web) | `System.IO` |
| KV | EDN file / SQLite | `shared_preferences` | EDN file / `Microsoft.Data.Sqlite` |
| Timers | `ScheduledExecutorService` | `Timer` | `DispatcherTimer` |

V1 effects: `:http`, `:dispatch-later`, `:dispatch-n`, `:storage/get|set`, and `:file/read|write` (not on web). Whether missionary and promesa work on ClojureDart/CLR is unverified, so keep the core free of them and of core.async.

### 6.4 JVM tests

```clojure
(deftest greet-flow
  (is (= "Hello, Ada!" (get-in (st/handle {:name "Ada"} [:app/greet]) [:db :greeting]))))
(deftest load-user-requests-http
  (is (= [:user/loaded] (get-in (st/handle {} [:user/load 7]) [:http :on-success]))))
(deftest view-snapshot
  (is (= [:ui/text :ui/text-input :ui/button]
         (map :tag (:children (t/normalize (g/root {:name ""})))))))
```
The same tests run under `cljr -X:test`. On ClojureDart they compile through the `:cljd` test alias, as in re-dash's `clj -M:cljd:test compile …`.[^50][^52]

---

## 7. Repository Structure, Build Tooling & Distribution

### 7.1 How each host consumes libraries

- **JVM:** git, local or Clojars dependencies, with OpenJFX coming transitively through cljfx.
- **ClojureDart:** git dependency on `tensegritics/clojuredart`, with `:cljd/opts {:kind :flutter :main …}` and output in `lib/cljd-out/`.[^18][^53]
  - Since 2023, "cljc or cljd libraries can use Dart dependencies without their own consumers having to redeclare them". The one requirement is to "copy `pubspec.yaml` in the jar."[^28]
  - Publishing compiled Dart to pub.dev loses macros, protocol openness and the REPL.
- **ClojureCLR:** `cljr` with git and local deps, but **no Maven**.[^11] Libraries also ship on NuGet with embedded sources (e.g. `clojure.core.async`).[^51]

### 7.2 Strategies

| | 1. Unified source | **2. Core + host modules** | 3. Native artifacts |
|---|---|---|---|
| Consumer | One dep, but mixed-host code | **Clean per-host deps** | Familiar |
| ClojureDart | Works | **Works (pubspec per module)** | Breaks macros/protocols |
| CLR | Works | **Works (`:deps/root`)** | .NET Core AOT is limited (loss of `AssemblyBuilder`), so sources ship anyway[^54] |
| Versioning | Trivial | **Aligned tags** | 3 pipelines |

**Verdict: Strategy 2.**

### 7.3 Layout

```text
unifui/
├── deps.edn  bb.edn
├── modules/
│   ├── core/     deps.edn deps-clr.edn  src/ui/core/{tree,store,style,color,reconcile}.cljc
│   ├── fx/       deps.edn deps-clr.edn pubspec.yaml  src/ui/fx/{http,storage,timer,file}.cljc
│   ├── jvm/      deps.edn  src/ui/jvm/{cljfx,lifecycles,app}.clj
│   ├── flutter/  deps.edn pubspec.yaml  src/ui/flutter/{adapters,style,text_input,modal,split_view}.cljd
│   └── clr/      deps-clr.edn  src/ui/clr/{wpf,hostops,dispatch,app}.cljr  host-template/
├── examples/greeter/{shared,jvm,flutter,clr}/
└── .github/workflows/ci.yml
```

### 7.4 deps.edn examples

```clojure
;; modules/jvm/deps.edn
{:paths ["src"] :deps {io.github.you/unifui-core {:local/root "../core"}
                       cljfx/cljfx {:mvn/version "1.10.363"}}}   ; verify coordinate

;; app-jvm/deps.edn
{:deps {org.clojure/clojure {:mvn/version "1.12.6"}
        io.github.you/unifui {:git/tag "v0.1.0" :git/sha "abc1234" :deps/root "modules/jvm"}}}

;; app-flutter/deps.edn
{:deps {tensegritics/clojuredart {:git/url "https://github.com/tensegritics/ClojureDart.git"
                                  :git/tag "0.9.20261002" :git/sha "<sha>"}
        io.github.you/unifui {:git/tag "v0.1.0" :git/sha "abc1234" :deps/root "modules/flutter"}}
 :aliases {:cljd {:main-opts ["-m" "cljd.build"]}}
 :cljd/opts {:kind :flutter :main app.main.flutter}}

;; app-clr/deps-clr.edn
{:deps {io.github.you/unifui {:git/url "https://github.com/you/unifui" :git/tag "v0.1.0"
                              :git/sha "abc1234" :deps/root "modules/clr"}}}
```

> Check whether `cljr` honours `:deps/root`; clr.core.cli says "most of the features" are supported.[^11] To feed sources into the C# host, have a bb task run `cljr -Spath` and copy the source roots into the output folder. This is the least polished part.

### 7.5 CI, tests, dev loop

- **CI:**
  - Ubuntu: JVM tests, headless cljfx (Monocle/xvfb), and Flutter `flutter test`.
  - macOS: Flutter desktop smoke test.
  - Windows: `dotnet build` of the host, `cljr -X:test` with headless reconciler tests against a fake `HostOps`, and an optional FlaUI smoke test.
- **Tests:**
  - Most tests are canonical-tree snapshots and handler tests on the JVM.
  - Per-adapter golden tests: cljfx description equality, Flutter `testWidgets`, and WPF op-log snapshots.
  - A few real-UI smoke tests.
- **Dev loop:**
  - JVM: REPL with automatic re-render.
  - Flutter: `clj -M:cljd flutter` with hot reload. A Flutter REPL was on the roadmap;[^28][^53] its current status is unverified.
  - CLR: start a socket REPL from the C# host via `clojure.core.server/start-server`.[^55]

### 7.6 Maintenance

V1 comes to an estimated **6–9k LOC**. Recurring cost comes mostly from host churn: Flutter's Material extraction, JavaFX bumps, and cljr still being beta. Flutter desktop and JavaFX overlap on Win/macOS/Linux, so keep the CLR adapter thin and prioritize JVM + Flutter parity.

---

## Final Architectural Recommendation Summary

| Decision | Verdict |
|---|---|
| **View format** | **Option B:** `.cljc` fns returning Hiccup canonical data; data-vector events with the normalized payload appended. EDN only for static data. Macros only as an opt-in Dart optimization later. |
| **CLR GUI (V1)** | **WPF on `net10.0-windows`**, code-constructed, launched from a `[STAThread]` C# host that references `Clojure` 1.12.6 and calls `clojure.clr.api.Clojure.var`. Avalonia in V2. |
| **Flutter rendering** | **Runtime interpreter + static adapter registry** under a root `f/widget :watch`; Flutter's Element tree reconciles. Stateful adapters for text input (managed controller, `:refresh-on nil`, keyed), modal, tabs and split view. `:ui/island` for performance. No `Function.apply`. |
| **CLR reconciler** | `.cljc` core over `HostOps` plus a WPF tag table: keyed diff, mutators, one stable trampoline per event, attached props, coalesced Dispatcher renders, caret preservation, TextChanged guard. ≈1.5–2.5k LOC. cljfx/FuncUI/Fabulous as references only. |
| **State/effects** | One atom; own re-frame-lite core (effects maps, interceptors, memoized subs, queued synchronous dispatch); host runners for HTTP, timers, storage and files. No re-frame/re-dash/core.async dependency. |
| **Taxonomy/styling** | Layouts: column, row, grid, scroll, box, align. Controls: text, button, text-input, checkbox, switch, select, image. Composites: split-view, tabs, declarative modal. Style: color, background, font size/weight/family, padding/margin, uniform border/radius, opacity, sizes. Logical px; CSS-order hex. Shadows, gradients, pseudo-states and animations excluded. |
| **Extension** | Per-host open registry by tag; `defcomponent` for host-free composites; `:ui/native` with `{:create :update :dispose}`/`{:build}`, diffed on tag+key+props. |
| **Distribution** | **Strategy 2 monorepo** `modules/{core,fx,jvm,flutter,clr}`, aligned tags, git deps with `:deps/root`, Clojars jars (with embedded `pubspec.yaml`), `deps-clr.edn`, a C# host template. No native artifacts. |

## Key Risks & Open Questions

1. Confirm `Clojure.Main`'s runtimeconfig, and settle how the C# host loads source (load path, `CLOJURE_LOAD_PATH`, or embedded resources).
2. Confirm that `cljr` supports `:deps/root`.
3. Read the current `cljd.flutter` expansion, and prototype the controlled text input on Flutter 3.47.
4. Choose between `package:flutter/material.dart` and `material_ui` before the November deprecation.[^7]
5. `StackPanel.Spacing` on WPF is settled: dotnet/wpf issue #3591 asking for it is still open, so keep margin emulation.
6. Verify runtime EDN reading on ClojureDart (only needed if themes are loaded at runtime).
7. Decide on split-view for Flutter: build it yourself rather than use `multi_split_view` unless that package is confirmed maintained.
8. Benchmark a 500-row list on Flutter early.
9. **Week-one spike:** build Greeter end to end on all three hosts before locking the API.

---

## Since the study

*Added on 2026-10-25, after Stages 2-1, 2-2 and 2-2b; not part of the original.*

| Study verdict | What the repository did | Where |
|---|---|---|
| View format: Option B | Followed. Views are `.cljc` functions returning canonical hiccup, events are data vectors, and the core has no macros. Stage 2-2 added `:ui/toggle`, `:ui/wrap` and the chip-group and month-grid composites. | `cloview.core.tree` (`v1-tags`), STAGE_2_1_DESIGN.md |
| CLR GUI: WPF on `net10.0-windows` in a C# host | Followed, with a .NET 9/8 fallback. Real WPF passes the Greeter (29/29) and fitness (19/19) smokes on Windows. | `modules/wpf/host-template`, WINDOWS_VERIFICATION_REPORT.md |
| Flutter: runtime interpreter + static adapter registry | Followed, including the managed controlled text input and a split view built in cloview. `:ui/island` is not built yet. | `cloview.flutter.adapters`, `cloview.flutter.split-view` |
| CLR reconciler over `HostOps` | Followed: a portable keyed reconciler behind a five-operation `HostOps` protocol, tested headless against `MockHostOps` on the JVM and the CLR. WPF flex containers are a `Grid` with gap tracks, so no element is ever detached on reorder. | `cloview.core.reconcile`, STAGE_2_1_DESIGN.md §2.6 |
| State and effects | Followed: queued synchronous dispatch, interceptors and memoized subscriptions in `cloview.core.store`; host runners in `modules/fx` (HTTP, timers, durable storage with backup, files, CSV/JSON codecs). No re-frame, re-dash or core.async dependency. | `modules/core`, `modules/fx` |
| Taxonomy and styling | Followed, with the additions above. | `cloview.core.style`, `cloview.core.color` |
| Extension | Followed: registered composites and `:ui/native` with per-host maps. The fitness app's heatmap, radar and calorie charts use it. | [`NATIVE_CONTRACTS.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/NATIVE_CONTRACTS.md) in cloview-examples |
| Distribution: Strategy 2 monorepo | Followed, with three changes: the host modules are named after their toolkits (`javafx`, `wpf`; item 44); the module jars carry no `pubspec.yaml`, and apps run `flutter pub add` instead, because Windows broke the quoted JSON of the pubspec bridge (item 2); and until releases exist, `bb init` writes `:local/root` dependencies (item 51, open question 11). The opt-in modules `bridge` and `licensing` were added in Stage 2-2b. | README, STAGE_2_2_DECISIONS.md |

| Study risk or open question | Outcome |
|---|---|
| 1. How the C# host loads sources | Resolved: the host sets `CLOJURE_LOAD_PATH` to one staged folder (`target/clr-src`), which a bb task or the harness fills (75 files in the last Windows run). |
| 2. `cljr` and `:deps/root` | Not confirmed and believed broken for relative roots, so `cljr` stays optional (STAGE_2_1_DESIGN.md §5.6). |
| 3. `cljd.flutter` expansion; controlled text input on 3.47 | The controlled input keeps its controller across rebuilds; the Greeter and kitchen-sink widget tests pass on Flutter 3.47.8 (Windows). |
| 4. `material.dart` or `material_ui` | `package:flutter/material.dart` for now, imported by one namespace only (`cloview.flutter.material`), so switching touches one file. |
| 5. `StackPanel.Spacing` | Moot: WPF flex containers are a `Grid` with pixel gap tracks. |
| 6. Runtime EDN on ClojureDart | Not needed: themes and strings live in code. |
| 7. Split view on Flutter | Built in cloview, without a package. |
| 8. A 500-row list on Flutter | Not benchmarked yet; a virtualized `:ui/list` is planned. |
| 9. The week-one Greeter spike | Done as Stage 2-1, on all three hosts. |

One baseline fact surfaced later: the pinned ClojureDart 0.9.20261002 needs Flutter 3.35 or
newer, which the first Windows run found (WINDOWS_VERIFICATION_REPORT.md §3.3).

## Sources

The 55 sources the study cited, numbered in order of first citation. On GitHub they appear
as footnotes at the very end of the page.

[^1]: [Clojure - Clojure Downloads](https://clojure.org/releases/downloads)
[^2]: [cljfx/CHANGELOG.md at master · cljfx/cljfx · GitHub](https://github.com/cljfx/cljfx/blob/master/CHANGELOG.md)
[^3]: [Release 1.10.363 · cljfx/cljfx · GitHub](https://github.com/cljfx/cljfx/releases/tag/1.10.363)
[^4]: [Release 1.10.362 · cljfx/cljfx · GitHub](https://github.com/cljfx/cljfx/releases/tag/1.10.362)
[^5]: [JavaFX](https://en.wikipedia.org/wiki/JavaFX)
[^6]: [Release 0.9.20261002 · Tensegritics/ClojureDart · GitHub](https://github.com/Tensegritics/ClojureDart/releases/tag/0.9.20261002)
[^7]: [Flutter 3.47 unbundles Material and starts dropping Intel Macs](https://induwara.lk/blog/2026-08-13-what-s-new-in-flutter-3-47)
[^8]: [What’s new in Flutter 3.47](https://flutter.dev/blog/whats-new-in-flutter-3-47)
[^9]: [Clojure 1.12.6](https://www.nuget.org/packages/Clojure/)
[^10]: [Clojure.Main 1.12.6](https://www.nuget.org/packages/Clojure.Main)
[^11]: [GitHub - clojure/clr.core.cli](https://github.com/clojure/clr.core.cli)
[^12]: [clojureclr · GitHub Topics · GitHub](https://github.com/topics/clojureclr)
[^13]: [Runtime async](https://dmiller.github.io/clojure-clr-next/general/2026/04/16/runtime-async.html)
[^14]: [Releases · AvaloniaUI/Avalonia · GitHub](https://github.com/AvaloniaUI/Avalonia/releases)
[^15]: [Avalonia (software framework)](https://en.wikipedia.org/wiki/Avalonia_(software_framework))
[^16]: [Breaking changes in Avalonia 12](https://docs.avaloniaui.net/docs/avalonia12-breaking-changes)
[^17]: [(net.clojars.htihospitality/re-dash "1.2.1") - Clojars](https://clojars.org/net.clojars.htihospitality/re-dash)
[^18]: [ClojureDart/doc/BOOK.md at main · Tensegritics/ClojureDart](https://github.com/Tensegritics/ClojureDart/blob/main/doc/BOOK.md)
[^19]: [cljfx/README.md at master · cljfx/cljfx · GitHub](https://github.com/cljfx/cljfx/blob/master/README.md)
[^20]: [github.com](https://github.com/cljfx/cljfx)
[^21]: [Changelog — cljfx 1.7.16](https://cljdoc.org/d/cljfx/cljfx/1.7.16/doc/changelog)
[^22]: [cljfx/README.md at e944d0cafa771786ad6774b360e5f41af91dab07 · cljfx/cljfx · GitHub](https://github.com/cljfx/cljfx/blob/e944d0cafa771786ad6774b360e5f41af91dab07/README.md)
[^23]: [ClojureDart/clj/src/cljd/flutter/alpha.cljd at main · Tensegritics/ClojureDart · GitHub](https://github.com/Tensegritics/ClojureDart/blob/main/clj/src/cljd/flutter/alpha.cljd)
[^24]: [BREAKING CHANGE: Function.apply uses Symbol for named arguments](https://groups.google.com/a/dartlang.org/g/misc/c/e3cs8doTaEs)
[^25]: [apply method - Function class - dart:core library - Dart API](https://api.dart.dev/dart-core/Function/apply.html)
[^26]: [Clojure - Reader Conditionals Guide](https://clojure.org/guides/reader_conditionals)
[^27]: [Clojure - Reader Conditionals Guide](https://japan-clojurians.github.io/clojure-site-ja/guides/reader_conditionals)
[^28]: [Clojurists Together](https://www.clojuriststogether.org/news/september-october-2023-long-term-project-updates/)
[^29]: [Clojurists Together](https://www.clojuriststogether.org/news/annually-funded-developers-update-july-august-2026/)
[^30]: [GitHub - phronmophobic/membrane: A Simple UI Library That Runs Anywhere · GitHub](https://github.com/phronmophobic/membrane)
[^31]: [Readme — com.phronemophobic/membrane 0.9.31.4-beta](https://cljdoc.org/d/com.phronemophobic/membrane/0.9.31.4-beta/doc/readme)
[^32]: [Membrane Topics](https://phronmophobic.github.io/membrane/membrane-topics.html)
[^33]: [Readme — com.phronemophobic/membrane 0.9.21-beta](https://cljdoc.org/d/com.phronemophobic/membrane/0.9.21-beta)
[^34]: [Getting started · clojure/clojure-clr Wiki · GitHub](https://github.com/clojure/clojure-clr/wiki/Getting-started)
[^35]: [Using ClojureCLR in a C%23 project](https://github.com/clojure/clojure-clr/wiki/Using-ClojureCLR-in-a-C%23-project)
[^36]: [Windows Forms and WPF on .NET 8: ship the Windows Desktop runtime by Largo · Pull Request #3 · Largo/ironruby · GitHub](https://github.com/Largo/ironruby/pull/3)
[^37]: [Run a WPF Window loaded from a xaml file in ClojureCLR and modify it at the REPL · GitHub](https://gist.github.com/schuster-rainer/3083679)
[^38]: [Clojure.Compile a ClojureCLR script with gen-class and a WPF UI into an executable console app · GitHub](https://gist.github.com/schuster-rainer/3101452)
[^39]: [Clojure CLR from scratch — Part 1 - The Tools](https://medium.com/@mickduprez/clojure-clr-from-scratch-part-1-the-tools-6037311b815e)
[^40]: [chore: upgrade to Avalonia 12 and .NET 10 by AngelMunoz · Pull Request #1 · AngelMunoz/MVUish.Avalonia · GitHub](https://github.com/AngelMunoz/MVUish.Avalonia/pull/1)
[^41]: [GitHub - schuster-rainer/CloDE: ClojureCLR Development Environment · GitHub](https://github.com/schuster-rainer/CloDE)
[^42]: [intro.clj - a quick example of clojure-clr · GitHub](https://gist.github.com/1309958)
[^43]: [My Clojure Adventure: Intro to Clojure-clr: Creating a UI with Windows.Forms](http://rob-rowe.blogspot.com/2012/01/intro-to-clojure-clr-creating-ui-with.html)
[^44]: [Flutter 3.27 : Simplify Your UI with Spacing in Rows and Columns](https://ijogendrajat.medium.com/flutter-3-27-simplify-your-ui-with-spacing-in-rows-and-columns-d02238bf1b0a)
[^45]: [New Spacing Argument in Row/Column (Flutter 3.27)](https://codewithandrea.com/tips/spacing-row-column/)
[^46]: [Easily Maintain Consistent Spacing Between WPF Controls](https://sam.elborai.me/articles/wpf-controls-consistent-spacing/)
[^47]: [StackPanel](https://s-iguchi09.github.io/apps/wpf-standard-control-demo/stackpanel.html)
[^48]: [Migrate to standalone material_ui and cupertino_ui packages](https://docs.flutter.dev/release/breaking-changes/material-ui-and-cupertino-ui)
[^49]: [Streamlining state management in ClojureDart w…](https://clojure.org/events/2024/streamlining-state-management-in--1311727846)
[^50]: [A ClojureDart framework, inspired by re-frame, for building user interfaces with Flutter](https://github.com/htihospitality/re-dash)
[^51]: [GitHub - clojure/clr.core.async: Port of Clojure core.async to the CLR · GitHub](https://github.com/clojure/clr.core.async)
[^52]: [Readme — net.clojars.htihospitality/re-dash 1.2.1](https://cljdoc.org/d/net.clojars.htihospitality/re-dash/1.2.1/doc/readme)
[^53]: [GitHub - Tensegritics/ClojureDart: Clojure dialect for Flutter and Dart · GitHub](https://github.com/tensegritics/ClojureDart)
[^54]: [ClojureCLR](https://dmiller.github.io/clojure-clr-next/general/2023/01/06/clojureclr-reconsidered.html)
[^55]: [Clojureclr Repl in c# example projects](https://groups.google.com/g/clojure-clr/c/pOEaOASq0Tk)
