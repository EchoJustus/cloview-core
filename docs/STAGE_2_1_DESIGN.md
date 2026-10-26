# cloview — Stage 2-1 design record

This document closes the three open technical questions left by the Stage 1 study and
records the decisions taken while building the Greeter spike:

1. how the C# WPF host finds its Clojure sources, and how it picks a .NET target
   framework (§1);
2. a formal, portable `HostOps` protocol, so the WPF reconciler is tested headless on the
   JVM and on the CLR (§2);
3. the Flutter Material shim, and a controlled text input that keeps the caret safe (§3).

Everything described here is implemented in this repository. The parts that need
Windows, WPF or Flutter are checked by `verify/run-windows-checks.ps1` (see
`VERIFY_ON_WINDOWS.md`). §8 lists what was verified where.

> **Stage 2-2 updates.** This record is kept as written in Stage 2-1, except for the
> lines marked *(Stage 2-2)*. What changed since, and where it is documented:
>
> - **ClojureCLR.** 1.12.6 `compare-and-set!` compares boxed values by reference, so the
>   store and the WPF coalescer now use a `swap-vals!` test-and-set.
>   See [`WINDOWS_VERIFICATION_REPORT.md`](WINDOWS_VERIFICATION_REPORT.md) §3.1.
> - **Flutter.** ≥ 3.35 is required (ClojureDart 0.9.20261002 uses
>   `AlignmentGeometry.topLeft`). The module `pubspec.yaml` bridges were removed; apps run
>   `flutter pub add http shared_preferences path_provider`. Report §3.3.
> - **ClojureDart host pass.** It reads `.cljc` with `:clj` active, so the `:cljd` branch
>   comes first, and is enforced by the static check. Report §3.3 (d).
> - **New canonical tags** `:ui/toggle` and `:ui/wrap`, and the portable composites
>   `chip-group` and `month-grid`. See [`ARCHITECTURE.md`](ARCHITECTURE.md) §4.
> - **Fitness benchmark.** Integrated under `benchmarks/fitness-baseline` and
>   `examples/fitness`, which moved to cloview-examples in the split (last item).
>   The native contracts are in
>   [`NATIVE_CONTRACTS.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/NATIVE_CONTRACTS.md),
>   and the capability mapping in
>   [`FITNESS_CAPABILITY_MAP.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/FITNESS_CAPABILITY_MAP.md),
>   also in cloview-examples.
> - **Names (Stage 2-2b).** Targets are named after their toolkits:
>   - `modules/jvm` → `modules/javafx` and `modules/clr` → `modules/wpf`, and the same in
>     `examples/*/`;
>   - `greeter.jvm.*` → `greeter.javafx.*` and `greeter.clr.*` → `greeter.wpf.*`;
>   - the bb tasks `smoke:javafx`, `run:wpf`, `wpf:sync` and so on.
>
>   This record has been updated to the new names. The reasons are in
>   STAGE_2_2_DECISIONS.md item 44.
> - **Repository split (Stage 2-2b).** Since the split (STAGE_2_2_DECISIONS.md item 98),
>   the fitness app is
>   [`apps/fitness/`](https://github.com/echojustus/cloview-examples/tree/main/apps/fitness)
>   and the benchmark is
>   [`benchmarks/fitness-baseline/`](https://github.com/echojustus/cloview-examples/tree/main/benchmarks/fitness-baseline)
>   in [cloview-examples](https://github.com/echojustus/cloview-examples). Paths below are
>   those of the monorepo at the time.

Contents

- §0 Baseline and scope
- §1 C# host: source resolution and .NET target fallback — **closed**
- §2 HostOps protocol and headless reconciler verification — **closed**
- §3 Flutter: Material shim and controlled text input — **closed**
- §4 Canonical layout rules (decided during the spike)
- §5 Findings that changed the implementation
- §6 Contract readiness for the Stage 2-2 fitness benchmark
- §7 Known V1 limitations
- §8 Verification matrix
- §9 Inputs expected for Stage 2-2

---

## 0. Baseline and scope

| Piece | Pinned / required | Where |
|---|---|---|
| Clojure (JVM) | 1.12.6 | `deps.edn`, `modules/*/deps.edn` |
| JDK | 21+ (25 LTS recommended). *(Stage 2-2: 11+ works, as the Windows run used 11.0.32; 21+ recommended.)* | Windows machine |
| cljfx | `cljfx/cljfx {:mvn/version "1.10.363"}`; alias `:cljfx-git` (tag 1.10.363, sha `6bee5107…`) if Clojars is unreachable | `modules/javafx/deps.edn` |
| ClojureDart | git tag `0.9.20261002`, sha `247b3c2c…` | `examples/greeter/flutter/deps.edn` |
| Flutter | ≥ 3.27 (Row/Column `spacing`); 3.47.8+ is the tested baseline. *(Stage 2-2: **≥ 3.35 required** by ClojureDart 0.9.20261002; checked by the harness and `bb flutter:check`. The module pubspec was removed.)* | ~~`modules/flutter/pubspec.yaml`~~ `verify/bb/cloview/tasks.clj` (`min-flutter`) |
| ClojureCLR | NuGet `Clojure` 1.12.6, referenced by the C# host | `modules/wpf/host-template/CloViewHost.csproj` |
| .NET | 10 SDK preferred; 9 or 8 accepted (§1.4) | Windows machine |
| cljr | `Clojure.Cljr` 0.1.0-beta1 (optional tooling check only) | Windows machine |

Stage 2-1 does **not** build the fitness app. It does make sure the contracts the
benchmark needs already exist and are extensible (§6).

---

## 1. C# host: source resolution and .NET target fallback — closed

### 1.1 Decision

`CloViewHost.exe` (`modules/wpf/host-template/`) is a `[STAThread]` C# console exe. It
targets `netN.0-windows` with `UseWPF` and references the `Clojure` NuGet package.
**No Clojure code is compiled into it.** At start-up it:

1. resolves one staged source tree (§1.3);
2. calls `Environment.SetEnvironmentVariable("CLOJURE_LOAD_PATH", stagedPath)` before any
   Clojure call;
3. touches one type from each of WindowsBase, PresentationCore and PresentationFramework.
   ClojureCLR resolves type names only among assemblies already loaded in the process,
   so WPF must be loaded before the first WPF symbol is compiled;
4. calls `Clojure.var("clojure.core", "require")` on the entry namespace (`--ns`, default
   `greeter.wpf.main`);
5. runs one of three entry points, chosen by flag:

| Flag | Entry fn | Contract |
|---|---|---|
| *(none)* | `(start! application)` | then `app.Run()` |
| `--headless-smoke` | `(run-headless-smoke! out-path)` | returns the exit code; writes the JSON report |
| `--run-tests` | `(run-tests! out-path)` | returns the exit code; writes the JSON report |

`--repl <port>` also starts a `clojure.core.server` socket REPL. UI work from the REPL
must be wrapped in `cloview.wpf.dispatch/on-ui!`.

### 1.2 Staging: one merged tree

`bb wpf:sync` and step 04 of the harness copy every `.clj`/`.cljc`/`.cljr` file from
eight roots into `target/clr-src`:

```
modules/core/src  modules/core/test  modules/fx/src  modules/fx/test  modules/wpf/src
verify/src        examples/greeter/shared/src        examples/greeter/wpf/src
```

Why one merged tree rather than a multi-entry `CLOJURE_LOAD_PATH`:

- **Determinism.** There is exactly one place the host reads from, and `--src` can point
  at any snapshot of it.
- **Stand-alone runs.** When the tree exists at build time, the csproj copies it to
  `<output>/clr-src`. The built output folder then runs on another machine as it is.
- **Distribution path.** ClojureCLR's `RT.load` falls back to embedded resources (compiled
  `*.clj.dll` resources or `.cljr`/`.cljc`/`.clj` source resources). Embedding this exact
  tree is the planned single-file option for Stage 3+. Nothing in the host contract
  changes.
- **No dependency on cljr at run time.** cljr is optional (`cljr -Spath` is only a tooling
  check). See §5.6 for why that matters.

### 1.3 Resolution order (`Program.cs`)

The host takes the first candidate that contains the marker file `cloview/core/tree.cljc`:

1. `--src <dir>`
2. the environment variable `CLOVIEW_CLR_SRC`
3. `<exe dir>/clr-src`, copied at build time
4. `target/clr-src`, found by walking up from the exe directory
5. `target/clr-src`, found by walking up from the current directory

If none matches, the host exits with code 3 and prints a hint (`bb wpf:sync` or `--src`).

ClojureCLR facts this relies on. They come from reading the ClojureCLR source, and were
exercised with ClojureCLR built from source in the sandbox:

- `CLOJURE_LOAD_PATH` is re-read on every namespace lookup (`RT.GetFindFilePathsRaw`). So
  setting it in-process before the first `require` is enough; no runtime re-init is needed.
- The application base directory and the current directory are searched **before**
  `CLOJURE_LOAD_PATH`. Never put a `cloview/` source tree directly next to the exe; the
  build copies it into the `clr-src` sub-folder for that reason.
- Extension order beats directory order: a `.cljr` anywhere on the path wins over a
  `.cljc`, which wins over a `.clj`. That gives host-specific overrides for free.
- Only the `:cljr` reader feature is active when reading `.cljc` files.

### 1.4 Target framework fallback

There are two layers.

**(a) In the csproj, with no arguments.** The target is derived from the major version
of the SDK that runs the build:

```xml
<_CloViewSdkMajor>$(NETCoreSdkVersion.Substring(0, $(NETCoreSdkVersion.IndexOf('.'))))</_CloViewSdkMajor>
<CloViewTargetFramework Condition="'$(CloViewTargetFramework)' == '' and $(_CloViewSdkMajor) &gt;= 10">net10.0-windows</CloViewTargetFramework>
<CloViewTargetFramework Condition="'$(CloViewTargetFramework)' == '' and $(_CloViewSdkMajor) == 9">net9.0-windows</CloViewTargetFramework>
<CloViewTargetFramework Condition="'$(CloViewTargetFramework)' == ''">net8.0-windows</CloViewTargetFramework>
<RollForward>Major</RollForward>
```

**(b) In the harness and bb.** `Select-ClrTfm` in the ps1 and `pick-tfm` in
`verify/bb/cloview/tasks.clj` choose explicitly and pass
`-p:CloViewTargetFramework=…`. The rule is the highest N in {10, 9, 8} such that:

- the newest SDK can build it (SDK major ≥ N), **and**
- some installed `Microsoft.WindowsDesktop.App` runtime can run it (runtime major ≥ N;
  `RollForward=Major` allows a newer runtime).

To override: `-ClrTargetFramework net8.0-windows` for the ps1, or `CLOVIEW_TFM` for bb.

| Machine has | Chosen | Runs on |
|---|---|---|
| SDK 10 (+ Desktop 10, bundled on Windows) | net10.0-windows | runtime 10 |
| SDK 9 only | net9.0-windows | runtime 9 |
| SDK 8 only | net8.0-windows | runtime 8 |
| SDK 8 and 9, Desktop runtime 8 only | net8.0-windows | runtime 8 |
| SDK 11 preview only | net10.0-windows | runtime 11 (RollForward=Major) |
| no SDK, or no Desktop runtime ≥ 8 | none; step 04 logs how to install the .NET 10 SDK | — |

Checked in the sandbox:

- MSBuild evaluation of the csproj gives `net10.0-windows` for SDK 10 and 11-preview,
  `net9.0-windows` for SDK 9 and `net8.0-windows` for SDK 8.
- The `-p:` override wins.
- `pick-tfm` was run against simulated `dotnet --list-sdks/--list-runtimes` outputs for
  the rows above.

### 1.5 Why not run WPF from `Clojure.Main` or `cljr`

- The `Clojure.Main` dotnet tool targets plain `netX.0`. Its runtimeconfig has no
  `Microsoft.WindowsDesktop.App` framework reference, so loading WPF from it is fragile.
- WPF needs an STA thread that owns `Application.Run()`. That belongs in an exe we
  control.
- The exe is about 200 lines and has no Clojure in it. Upgrading ClojureCLR means changing
  `ClojureClrVersion`.

---

## 2. HostOps protocol and headless reconciler verification — closed

### 2.1 Who reconciles

| Host | Reconciler | cloview's job |
|---|---|---|
| JVM / cljfx | cljfx (diffs descriptions) | pure translation, canonical node → cljfx description (`cloview.javafx.cljfx`) |
| Flutter | Flutter's Element tree | canonical node → Widget, with a few stateful wrappers (`cloview.flutter.adapters`) |
| WPF | **`cloview.core.reconcile`** (portable `.cljc`) | `HostOps` implementation in `cloview.wpf.hostops` |

### 2.2 The protocol (`cloview.core.reconcile`)

```clojure
(defprotocol HostOps
  (create-element    [ops node emit])                ; -> opaque handle; attach native handlers ONCE
  (patch-props!      [ops handle tag changed prev])  ; changed: prop -> value, nil = reset to default
  (replace-children! [ops handle tag child-handles]) ; exact ordered children; MOVE, never recreate
  (attach-child-slot! [ops parent-handle parent-tag child-handle slot])
  (destroy-element!  [ops handle tag]))              ; children first, after detachment
```

- `emit` is a stable `(fn [event-key payload])`. The host calls it from its native
  handlers for the element's whole lifetime.
- `create-element` must not apply props. `patch-props!` follows immediately with all
  initial props.
- `slot` is `{:index :grow :layout :parent}`. `:layout` holds the child's layout keys
  (`:grow :grid/row :grid/col :grid/row-span :grid/col-span`). `:parent` holds the
  parent's layout props (`:gap :align :justify :columns :rows :orientation :direction`).
- For `:ui/native`, `changed` also carries the latest `:host` fn map under `:host`. The
  closures are recreated on every render, so they are never diffed.

### 2.3 Algorithm (one `render!` pass)

1. **Identity.** A child is reused when its sibling identity (`:key`, else its position)
   and its tag both match. Otherwise the old element is destroyed and a new one created.
   A root change of tag or key returns a new root handle.
2. **Props.** Only changed *diffable* props go to `patch-props!`. Event handlers,
   parent-slot layout keys and the `:ui/native` `:host` fns are excluded
   (`t/diffable-props`). A removed prop is sent as `nil`.
3. **Events.** Each element gets one `emit` trampoline at creation. New handler vectors
   are swapped into a side table, so changing a handler costs zero host operations.
4. **Controlled values.** The controlled prop of each tag is written only when it differs
   from what the host currently shows (`:host-value`, which user events update). The
   controlled props are: text-input `:value`, checkbox/switch `:checked`, select `:value`,
   tabs `:selected`, split-view `:ratio`.
5. **Re-entrancy.** The whole pass runs with `applying?` set. A host event raised
   synchronously by a programmatic write is swallowed (for example, WPF raises
   `TextChanged` after `TextBox.Text = …`). It only updates `:host-value`.
6. **Children.** `replace-children!` is called only when the ordered child list changed.
   `attach-child-slot!` is called only for children whose slot changed.

### 2.4 `mock-host`: the deterministic MockHostOps

`(r/mock-host)` returns `{:ops :log :elements}`. Elements are integers in creation order,
so op logs are deterministic and tests compare them literally:

```clojure
[[:create 1 :ui/column] [:create 2 :ui/text] [:patch 2 {:text "Hi"}]
 [:children 1 [2]] [:slot 1 2 0] ...]
```

Behaviour of the mock:

- Text inputs keep `:text` and `:caret`. External writes apply `r/preserve-caret`: the
  previous caret, clamped to the new length.
- By default it **echoes** controlled writes synchronously, like WPF does. That is how the
  tests prove the re-entrancy guard swallows them (`:echo-controlled? false` disables it).
- `:ui/native` elements call `:host :create/:update/:dispose`.
- `mock-type!` simulates a user edit (text and caret change, then `:on-change`).
  `mock-fire!` simulates any host event.

### 2.5 What the headless tests pin down (`modules/core/test/cloview/core/reconcile_test.cljc`)

| Test | Property |
|---|---|
| `mount-produces-deterministic-op-log` | exact create/patch/children/slot sequence |
| `identical-render-is-a-no-op` | a second render of the same view issues **zero** host ops |
| `controlled-text-guard-and-caret` | typing never writes back; external writes keep the caret; the echo is swallowed |
| `echo-without-guard-would-dispatch` | control experiment: the echo is real, so the guard is needed |
| `handler-changes-cost-no-host-ops` | new handler vectors go through trampolines |
| `removed-props-are-patched-to-nil` | prop removal resets to the host default |
| `keyed-reordering-moves-instead-of-recreating` | a keyed reorder is one `replace-children!` with the same handles |
| `tag-change-at-same-identity-replaces` / `root-replacement-returns-new-handle` | identity rules |
| `unmount-destroys-children-first` | destroy order |
| `parent-layout-changes-reslot-children` | a parent `:gap`/`:align` change re-slots children without patching them |
| `controlled-checkbox` | the guard applies to every controlled tag |
| `native-nodes-diff-on-props-only` | `:ui/native` `:update` runs only when `:props` changes; fresh `:host` closures do not count |
| `duplicate-keys-do-not-crash`, `counting-ops-wrapper`, `preserve-caret-rule` | robustness and helpers |

The same `.cljc` test file runs on Clojure/JVM and on ClojureCLR. In the sandbox it passes
on both (§8). On Windows, step 04 runs it again inside the real WPF host.

### 2.6 WPF HostOps invariants (`cloview.wpf.hostops`)

- **No detaching.** Children of flex and grid panels are positioned with
  `Grid.Row`/`Grid.Column`, never by their index in `Panel.Children`. So
  `replace-children!` only adds and removes elements. Elements that stay are never
  detached, and focus, caret and scroll position survive reorders.
- Flex containers are a `Grid` with interleaved pixel gap tracks. Growing children get
  `Star` tracks; the others get `Auto`. WPF has no `StackPanel.Spacing`, and dotnet/wpf
  issue #3591 is still open.
- Props are set through DependencyProperty reflection (`wpf/set-dp!`). `nil` calls
  `ClearValue`, which restores the host default.
- `:ui/modal` is an overlay (barrier plus card) in the window's overlay layer, above the
  content. `ShowDialog` is not used, so there is no nested message loop and the reconciler
  stays in control.
- Rendering is coalesced. A watch on the db schedules at most one
  `Dispatcher.BeginInvoke(Normal)` per dispatcher turn. The smoke drains the queue with
  `dispatch/pump!` (a `DispatcherFrame`). Apps never need `pump!`.
- Off-thread `dispatch!` (HTTP callbacks, timers) is marshalled by the store executor
  bound to the Dispatcher.

The CLR smoke (`greeter.wpf.main/run-headless-smoke!`) mounts the Greeter in an
off-screen window (placed at -32000,-32000, not activated, not in the taskbar) on the
`[STAThread]`. It then:

1. simulates typing `"A"`, then `"Ad"` with the caret at 2;
2. dispatches `[:app/set-name "Ada"]` and `[:app/greet]`;
3. asserts `TextBox.Text == "Ada"`, `CaretIndex == 2` (intact, not reset to 0) and that
   the greeting TextBlock shows `"Hello, Ada!"`;
4. asserts the event log has no echo, that five dispatches cost one render pass, and that
   the TextBox keeps its identity;
5. mounts and patches the kitchen sink (every V1 tag, keyed reorder, `:ui/native`).

It writes its JSON report to the `--out` path (the harness passes
`verify/out/05-clr-wpf-smoke.json`). Without `--out` it writes
`verify/out/clr-wpf-smoke.json`. It exits 0 when every critical Greeter check passed.

---

## 3. Flutter: Material shim and controlled text input — closed

### 3.1 Material shim

**Rule:** under `modules/`, only `cloview.flutter.material` may import Flutter UI
libraries (`package:flutter/*`, `package:material_ui/*`, `package:cupertino_ui/*`).
Examples and tests may import them directly. The rule is enforced by
`verify/static_check.clj`, which fails step 02 if a module namespace breaks it.

Why: Flutter 3.47 ships `material_ui`/`cupertino_ui` 1.0 as opt-in packages, and the
in-SDK Material library is slated for deprecation in November 2026.

**Decision for Stage 2-1:** keep `package:flutter/material.dart`. It works on every
Flutter version from 3.27, which is the floor set by Row/Column `spacing`, and on 3.47.
When the move becomes necessary, it touches two files:

1. the `:require` of `modules/flutter/src/cloview/flutter/material.cljd`;
2. `modules/flutter/pubspec.yaml`, which gains `material_ui`. *(Stage 2-2: the module
   pubspec was removed; the app adds `material_ui` with `flutter pub add`, like the
   other Dart packages.)*

Shim conventions:

- Plain Clojure values go in and Flutter objects come out. Numbers are converted to the
  `int`/`double` the signature demands, and absent nullable parameters receive `nil`.
- Generic types are explicit: `#/(m/DropdownButton int)`. Dart generics are reified, so
  the button and its items must agree.
- Editing primitives also live here: `text-editing-controller`,
  `external-editing-value`, `post-frame!`. So do the overlay portal pieces used by
  `:ui/modal`.

### 3.2 Controlled `:ui/text-input` (`cloview.flutter.text-input`)

**The problem.** A Flutter `TextField` keeps its editing state in a
`TextEditingController`: the text, the selection, and the IME composing range. A naive
"controlled" binding causes three failures:

- recreating the controller on rebuild, or assigning `.text` on every build, resets the
  caret;
- it breaks IME composition (CJK input, dead keys);
- writing during build triggers "setState() called during build".

**The design.**

```clojure
(f/widget
 :managed [ctrl (mat/text-editing-controller (or value "")) :refresh-on nil]
 :let [seen (mat/controller-text ctrl)
       _ (when (and (some? value) (not= value seen))
           (schedule-sync! ctrl seen value))]          ; post-frame, never during build
 (mat/text-field ctrl {:on-changed (fn [s] (t/emit! dispatch! on-change :string s) nil) ...}))
```

- **One controller per element.** It is `:managed` with `:refresh-on nil`. The creation
  expression closes over the initial `value`, so without `:refresh-on nil` cljd.flutter
  would dispose and recreate the controller on every value change. It is disposed with
  the element.
- **Typing.** The TextField mutates the controller, then `onChanged` calls `dispatch!`.
  Dispatch is synchronous on the UI isolate, so the db now holds the same text. The
  rebuild sees `value == controller.text` and writes nothing. Caret, selection and
  composing range are untouched.
- **External change** (`value != controller.text` at build time). The write is deferred
  with `addPostFrameCallback`, so no listener fires during build. It is skipped if the
  controller text changed since the build, because the user typed in between and their
  newer text has already been dispatched. The written `TextEditingValue`
  (`mat/external-editing-value`):
  - keeps the selection clamped to the new length (an invalid selection moves to the end);
  - keeps the composing range only if it is still valid and covers unchanged text.

| Case | Result |
|---|---|
| user types (any speed) | no write-back; caret and IME state untouched |
| IME composition in progress, unrelated external re-render | value equals the controller, so nothing is written and composition survives |
| external replace (`[:app/set-name "Ada Lovelace"]`) | post-frame write; caret kept (clamped) |
| external clear/reset | post-frame write of `""`; caret clamped to 0 |
| external write races with a keystroke in the same frame | write skipped; the keystroke already set the db |
| external write of the value already shown | nothing to do |

Verified on Windows by step 06 (`examples/greeter/flutter/test/greeter/flutter/greeter_test.cljd`):

- typing `"Ada"` updates the db;
- `[:app/set-name "Ada Lovelace"]` updates the controller post-frame **with the caret
  offset unchanged**;
- the controller instance is identical across rebuilds;
- tapping *Greet* shows `"Hello, Ada Lovelace!"`;
- the kitchen sink mounts and patches every V1 tag, and a keyed reorder moves the
  `"item a"` element instead of rebuilding it.

### 3.3 Other Flutter decisions

- **Static adapter registry.** Built-in adapters are a def'd map (`builtin-adapters`).
  `render-node` consults the user registry (`reg-adapter!`) first. Dart has no `eval`,
  and reflection is only available in dev mode.
- **One reactive root.** A single `:watch` on the db atom re-interprets the canonical tree
  into fresh widgets, and Flutter's Element tree reconciles them. `:key` becomes a
  `ValueKey`; `:test-id` becomes `ValueKey("test-id:<id>")` for `find.byKey`.
- **`:ui/native`.** It takes `:host {:build (fn [build-context props] widget)}`, memoized
  on `:props`. Equal props return the identical Widget instance, so Flutter skips that
  subtree. This is the path for heavy painters such as heatmaps and charts.
- **Registration from data.** ClojureDart emits definitions only, so handlers are
  registered from data (§5.1).

---

## 4. Canonical layout rules (decided during the spike)

Semantic equivalence needs one shared rule set. Flutter is the strictest host, because
an unbounded `TextField` or scroll view is a hard layout error. So the rules live in
`cloview.core.tree`, and all three adapters apply them identically.

`t/effective-grow parent-tag child` gives the main-axis grow factor:

1. an explicit `:grow` wins (`true` = 1, `false` = 0, or a number);
2. `:ui/spacer` grows by 1;
3. inside a `:ui/row`, inputs, selects and containers (`column grid scroll tabs
   split-view`) without a `:style :width` grow by 1 *(Stage 2-2: `wrap` too)*;
4. inside a `:ui/column`, `scroll` and `split-view` without a `:style :height` grow by 1
   *(Stage 2-2: `tabs` too: tab pages are usually scroll or split views and need a
   bounded height. A horizontal `scroll` does not: it takes its content's height, and in
   a column inside a vertical scroll a flexing child is itself a Flutter layout error)*;
5. *(Stage 2-2)* a `:ui/box` or `:ui/align` counts as its content: a padded box around a
   column grows in a row like the column, unless the box or its content sets the size.
   The fitness analytics panels exposed this: on Flutter, a stretching column inside an
   intrinsic box in a row needs an infinite width;
6. everything else keeps its intrinsic size.

`t/default-align tag` gives the cross-axis alignment when `:align` is absent. Columns
stretch their children. Rows centre them, because a stretching row inside a column would
need an unbounded height on Flutter.

`:gap` is realised as Flutter Row/Column `spacing` (3.27+), as the JavaFX `:spacing` of
`v-box`/`h-box`, and as pixel gap tracks in WPF grids.

The goal is equivalence, not pixel parity. Exact fonts, control chrome and theme metrics
stay native.

---

## 5. Findings that changed the implementation

### 5.1 ClojureDart drops top-level side effects

ClojureDart compiles definitions only. A bare `(reg-event-db …)` at top level is not
emitted: Dart has no top-level statements, and top-level fields are initialized lazily.

Consequences:

- `cloview.core.store/register-handlers!` registers a whole feature from a def'd map.
- Every app namespace exposes `register!` (see `greeter.core`), and every entry point
  calls it.
- Built-in Flutter adapters are a map, not `reg-adapter!` calls.

This pattern is portable and also the recommended style on the JVM and the CLR.

### 5.2 Store dispatch semantics

- Dispatch is synchronous and queued on the UI thread. Events raised while processing are
  appended to the same queue. A keystroke's db change is therefore applied before the host
  renders again, which is what keeps controlled inputs exact.
- Off the UI thread, `dispatch!` posts through the store executor: JavaFX
  `Platform/runLater`, the WPF `Dispatcher`, or synchronous in Dart.
- The queue pops with `swap-vals!`. A single drainer is elected by CAS, and a re-check
  after releasing the flag closes the lost-wakeup window. Both came out of a race found in
  testing.

### 5.3 cljfx specifics

- An event prop set to `nil` cannot be coerced to an `EventHandler`, so nil handlers are
  omitted from descriptions.
- `ImageView` has no `max-width` prop, so images are not in the stretchable set.
- A programmatic `setText` on a `TextField` echoes through its text property. The custom
  `:cloview/text-field` lifecycle writes with a guard flag and preserves the caret, the
  same guard as on WPF.
- Custom lifecycles (text field, split pane, tab pane, `:cloview/native`) are built with
  `composite/describe` and `:prop-order`.
- **Keyed identity.** A canonical `:key` must become `:fx/key` on descriptions inside
  `:children` collections (`child->desc`). Without it, cljfx matches children by
  position, and a keyed reorder relabels the existing JavaFX nodes instead of moving
  them. The new smoke check `kitchen-sink/keyed-reorder-reuses-node` caught this: a
  control run without `:fx/key` fails it (23/25), the fixed adapter passes (25/25).
  `:fx/key` is only valid inside collections, so single-child props such as a scroll
  pane's `:content` stay unkeyed.

### 5.4 The Windows Clojure CLI can be a PowerShell function

There are three kinds of Windows install:

- clojure.org now recommends WSL or [clj-msi](https://github.com/casselc/clj-msi). clj-msi
  installs `clojure.exe`, built on deps.clj.
- Many machines still have the older ClojureTools PowerShell module (`win-install.ps1`,
  marked as no longer maintained).
- That module defines `clj`/`clojure` as aliases of the function `Invoke-Clojure`.

Read from `ClojureTools.psm1`:

- **Alias arguments must be split.** It recognises an alias argument only in the form
  PowerShell produces for a typed `-M:test`, which is two arguments: `'-M:'` `'test'`. An
  unsplit `'-M:test'` falls through to `clojure.main` as a REPL argument.
- **It sets `$ErrorActionPreference = 'Stop'`.** Under Windows PowerShell 5.1, a `2>&1`
  around it would turn Java's first stderr line into a terminating error.

Consequences:

- The harness pre-splits alias arguments and runs a function or script `clj` in a child
  PowerShell (`-EncodedCommand`, `-OutputFormat Text`). The child starts with
  `Set-Location` to the step's directory, and its stdout and stderr are captured like a
  native command.
- If the module was installed only for PowerShell 7 (`Documents\PowerShell\Modules`, which
  Windows PowerShell 5.1 does not search), the harness finds `ClojureTools.psd1` there and
  imports it by path.
- A plain `clojure.exe`/`clj.exe` (clj-msi, deps.clj) is invoked directly.
- bb tasks use `babashka.tasks/clojure` (deps.clj inside bb), which is unaffected.

An independent review of the harness found no defect that aborts the run or loses the
summary or zip. It did find six smaller issues, all fixed:

- the PowerShell 7-only module location above;
- provider-qualified paths on UNC/WSL locations (now `ProviderPath`, plus a warning);
- step 06 ignoring the init and effects-compile exit codes;
- a stale `CloViewHost.exe` being tested when the build failed;
- a profile changing the child's directory;
- `00-summary.json` and `.gitkeep` bookkeeping.

### 5.5 Static checking for code that cannot be compiled here

`verify/static_check.clj` reads every source file per platform (`:clj :cljr :cljd`, with
the right reader features) and checks every call into a `cloview.*`/`greeter.*` namespace:
the var exists and the arity matches. It also checks two architecture rules:

- the Material shim (§3.1);
- core purity: `modules/core/src` may require only `clojure.*` and `cloview.core.*`.

It runs as part of step 02, and a negative test confirms that it fails on violations.

### 5.6 cljr: git deps with a relative `:deps/root`

Reading `clr.core.cli` at commit 7021077, the `:cljr` branch of
`ext/manifest-type :git` (in `clojure/tools/deps/extensions/git.cljc`) builds the root
with `(cio/file-info root)`. A relative `:deps/root` is therefore resolved against the
**process working directory**, not the git checkout; the JVM branch joins it with the
checkout directory. A source comment there flags the same question.

Consequences:

- Strategy 2, a monorepo whose modules are consumed as git deps with `:deps/root`, works
  for the JVM Clojure CLI. It is unverified, and probably broken, for cljr today.
- cloview's CLR path does not depend on cljr resolution at all (§1.2). CLR consumers use
  `:local/root`, for example a submodule or a vendored checkout, until this is fixed.
- Stage 2-2 follow-up: reproduce with cljr on Windows once the repository is public, and
  report upstream.

---

## 6. Contract readiness for the Stage 2-2 fitness benchmark

| Benchmark feature | Contracts that exist now | Stage 2-2 work |
|---|---|---|
| **Exercise library**: multi-dimensional filters | `reg-sub`/`sub`, memoized on db identity; `:ui/select` `:ui/checkbox` `:ui/text-input` as filters; keyed children (WPF keyed reconciler, Flutter `ValueKey`, cljfx `:fx/key`), identity-checked on all three hosts (both smokes and the Flutter widget test) | a virtualized `:ui/list` tag (ListView.builder / ListView / VirtualizingStackPanel), driven by the 500-row benchmark |
| **GIF/MP4 import** | `:file/pick` (JavaFX FileChooser, WPF OpenFileDialog), `:file/copy`, `:file/app-dir`, `:file/list {:extensions ["gif" "mp4"]}`; `:ui/image {:src {:file p}}` | Flutter `:file/pick` via `file_picker`; MP4 playback through `:ui/native` (JavaFX MediaView, WPF MediaElement, Flutter `video_player`); animated GIF on WPF via `:ui/native` (WPF `Image` does not animate GIFs) |
| **Plan builder with completion timestamps** | `:now` coeffect (`(st/inject-cofx :now)`, epoch ms); `:timer/start`/`:timer/stop` for workout clocks; `:dispatch-later`; `:storage/put`/`:storage/get` (EDN) | domain model and views |
| **Heart-rate CSV/JSON import** | `:file/pick {:extensions ["csv" "json"]}` → `:file/read` (text) → pure `.cljc` parsing in handlers | a portable JSON reader (`cloview.fx.codec`: the JVM reader, `System.Text.Json` on the CLR, `dart:convert` on Dart); CSV stays pure `.cljc` |
| **2D/3D anatomy heatmap, radar charts, calorie trends** | `:ui/native` on every host, diffed on `:props` only: JVM `:host {:create :update :dispose}` → JavaFX `Node` (Canvas; SubScene for 3D); WPF the same contract → `UIElement` (DrawingVisual/Canvas; Viewport3D); Flutter `:host {:build}` memoized on props (CustomPaint) | chart and heatmap widgets per host behind one `.cljc` component API; pick the Flutter 3D approach |
| **Training calendar** | `:ui/grid` with track specs and spans; composite components (`reg-component!`); `:ui/select` for month/week | calendar component in `.cljc` |
| **Android ↔ Windows LAN sync** (`modules/fx`) | `cloview.fx.lan-sync`: protocol v1 (UDP discovery, HTTP POST `/cloview-sync`, EDN envelope `{:cloview.sync/v 1 :type :device :sent-at :body}`); pairing codes; last-writer-wins merge on `[:updated-at :device]` with tombstones; all pure and unit-tested; effect value shapes final (`:lan/advertise :lan/discover :lan/serve :lan/reply :lan/request :lan/stop`) | network runners per host: JVM `DatagramSocket` + `com.sun.net.httpserver`; CLR `UdpClient` + `HttpListener`; Dart `RawDatagramSocket` + `HttpServer` |

Extension points the benchmark will use, with no core changes needed:

- **New tags.** Register adapters on each host (`reg-adapter!` on Flutter, the `->desc`
  multimethod on the JVM, a `HostOps` `case` branch on WPF). `t/v1-tags` is data, so
  coverage tests extend naturally.
- **New effects.** `reg-fx` in a host module. Value shapes are documented in each
  `cloview.fx.*` docstring.
- **Composite components.** `reg-component!` expands in `normalize`, so hosts never see
  them.
- **Escape hatch.** `:ui/native` for anything host-specific (charts, video, 3D).

---

## 7. Known V1 limitations

- `:ui/switch` falls back to a checkbox on JavaFX and WPF, which have no native switch.
- `:ui/text-input :placeholder` shows on JavaFX (`prompt-text`) and Flutter (`hintText`),
  but not on WPF, whose `TextBox` has no placeholder property. An adorner-based hint is
  planned for Stage 2-2.
- `:ui/grid` spans (`:grid/row-span`, `:grid/col-span`) work on JavaFX and WPF. Flutter's
  `Table` has no spans, so on Flutter a spanning cell renders in its first row and
  column only.
- No list virtualization yet (Stage 2-2, see §6).
- `:http` response headers are filled on the JVM only. They are `{}` on the CLR and Dart.
- `:file/pick` on Flutter reports `:unsupported` until the `file_picker` plugin is added.
- The `:lan/*` runners report `{:error :not-implemented}`. The protocol, codecs and merge
  are done.
- WPF `Image` does not animate GIFs (use `:ui/native`).
- Styling is a deliberate subset: padding, background, border, radius, colour, font size,
  weight, family and italic, opacity, and width/height constraints.
- Flutter re-interprets the whole canonical tree on each db change. That is fine for the
  spike. Large screens use `:ui/native` islands with their own `:watch`, or memoized
  subtrees.

---

## 8. Verification matrix

**Sandbox (Linux):**

- OpenJDK 21.0.12 with Clojure 1.11.1 from the Ubuntu archive.
- ClojureCLR master (1.13.0-SNAPSHOT, commit f3f98a7), built from source on .NET 8.
- cljfx 1.10.363 from git source, on JavaFX 11 under Xvfb.
- No Flutter, Dart, Windows or PowerShell.

| Check | Sandbox result | Windows step → file |
|---|---|---|
| JVM unit tests (core + fx + cljfx descriptions) | **65 tests, 265 assertions, 0 failures** | 02 → `02-jvm-unit-tests.log` / `.json` (Clojure 1.12.6) |
| Static check (vars, arities, Material shim, pure core) | clj 25 / cljr 24 / cljd 29 files, **OK** | 02 (same log) |
| cljfx off-screen smoke (Greeter + kitchen sink) | **25/25 checks**, 19/19 tags mounted and patched, keyed reorder moves nodes | 03 → `03-jvm-cljfx-smoke.json` (cljfx 1.10.363 from Clojars) |
| ClojureCLR unit tests (core + fx, MockHostOps reconciler) | **55 tests, 206 assertions, 0 failures** | 04 → `04-clr-unit-tests.json` (inside the WPF host, NuGet Clojure 1.12.6) |
| Real `Program.cs` entry path (WPF types stubbed; staged-tree resolution by walking up) | built and ran `--run-tests`: 55/206 OK | 04 → `04-clr-build-and-run.log` (real `dotnet build`, UseWPF) |
| csproj TFM selection (MSBuild evaluation, SDK 8/9/10/11-preview) | as specified in §1.4 | 04 log shows the chosen TFM |
| bb `pick-tfm` against simulated SDK/runtime sets | all scenarios as specified | — |
| WPF off-screen smoke | not possible (no WPF) | 05 → `05-clr-wpf-smoke.json` |
| ClojureDart compile + Flutter widget tests | not possible (no Flutter) | 06 → `06-flutter-compile-and-test.log` |
| `run-windows-checks.ps1` | parses cleanly (tree-sitter PowerShell grammar); ASCII only; independently reviewed against PowerShell semantics (§5.4); never executed | the run itself |
| All `deps.edn` / `deps-clr.edn` / `bb.edn` | valid EDN; every `:local/root` exists | — |

---

## 9. Inputs expected for Stage 2-2

From the Windows run, `verify/cloview-windows-verification-results.zip` contains:

```
00-summary.json                  step statuses, exit codes, durations, present/missing files, warnings
01-env-check.json                OS, PowerShell, java/clj/bb/git/dotnet/cljr/flutter/dart, SDK + Desktop runtimes, chosen TFM
02-jvm-unit-tests.log            JVM tests + static check output
02-jvm-unit-tests.json           per-namespace test totals
03-jvm-cljfx-smoke.json          JavaFX smoke checks + per-tag coverage
03-jvm-cljfx-smoke.log           smoke console output (incl. a :cljfx-git retry if needed)
04-clr-build-and-run.log         cljr -Spath, staging, dotnet build, --run-tests, --headless-smoke
04-clr-unit-tests.json           core + fx tests on ClojureCLR inside CloViewHost
05-clr-wpf-smoke.json            WPF smoke checks (TextBox "Ada", caret intact, "Hello, Ada!") + coverage
06-flutter-compile-and-test.log  cljd init/compile + flutter widget tests
```

Together with `fitness-benchmark-pack.zip`, these drive:

- fixes for whatever failed on real hosts;
- the Stage 2-2 items in §6 and §7;
- the `cloview-init-github.zip` repository.
