# cloview architecture

cloview lets one Clojure application, meaning its state, events, effects, business
logic and views, live in shared `.cljc` files and run natively on three hosts:

| Host | Language | Native UI | Module |
|---|---|---|---|
| JVM | Clojure | JavaFX through cljfx | `modules/javafx` |
| Dart | ClojureDart | Flutter (Windows, Android, …) | `modules/flutter` |
| CLR | ClojureCLR | WPF (Windows) | `modules/wpf` |

This document explains how the pieces fit, the rules that keep shared code portable, and
how the work is verified. The decision records are
[`STAGE_2_1_DESIGN.md`](STAGE_2_1_DESIGN.md) and
[`STAGE_2_2_DECISIONS.md`](STAGE_2_2_DECISIONS.md). The optional modules (backends,
licensing, R plots) and the project initializer are §10–§11. The fitness app, which
several sections use as an example, and its acceptance benchmark live in the companion
repository [cloview-examples](https://github.com/echojustus/cloview-examples) (§7).

## 1. The shape of an app

```
      shared .cljc (one copy)                        per host (thin)
┌─────────────────────────────────────┐   ┌─────────────────────────────────────────────┐
│ db (plain map)                      │   │ entry point: register handlers, natives,    │
│ events  [:catalog/filter-toggle …]  │   │ effect runners; start the app shell         │
│ handlers (pure)  db/cofx → db + fx  │   └─────────────────────────────────────────────┘
│ views   (fn [db] hiccup)            │
└──────────────┬──────────────────────┘
               │ canonical tree (normalized data)
┌──────────────▼────────────────────────────────────────────────────────────────────────┐
│ cloview.core: tree (vocabulary, normalize, composites) · store · reconcile · style    │
└──────┬──────────────────────────────┬─────────────────────────────────┬───────────────┘
       │ data → cljfx descriptions    │ runtime interpreter (adapters)  │ keyed reconciler
┌──────▼──────────┐          ┌────────▼─────────────┐          ┌─────────▼──────────────┐
│ modules/javafx  │          │ modules/flutter      │          │ modules/wpf            │
│ cljfx → JavaFX  │          │ widgets → Flutter    │          │ HostOps → WPF elements │
└─────────────────┘          └──────────────────────┘          └────────────────────────┘
```

1. **The store** (`cloview.core.store`) is a small re-frame-style store.
   - Events are vectors. `dispatch!` queues them, and one thread at a time drains the queue
     to completion, in order.
   - Handlers are pure. `reg-event-db` returns a new db; `reg-event-fx` returns
     `{:db … :<effect> …}`. They take time and environment from coeffects (`:now`, or an
     app's own, such as the fitness app's `:fitness/today`), so every host computes the
     same state.
   - Effects run through registered runners. They report back by dispatching events, never
     by returning values.
   - `sub` memoizes derived values on db identity.
2. **Views** are plain functions `db → hiccup`. Hiccup uses only the canonical
   vocabulary (§4), composites built from it, and `:ui/native` for drawing surfaces.
   `cloview.core.tree/normalize` expands composites and checks every node against the
   vocabulary (`problems`).
3. **Each host renders the canonical tree with native widgets.**
   - **JVM:** a pure translation to cljfx descriptions; cljfx does the diffing.
   - **Flutter:** a registry of adapter functions under one root widget, rebuilt from the
     current tree.
   - **WPF:** the portable keyed reconciler `cloview.core.reconcile` diffs trees and calls
     a `HostOps` protocol (`create-element`, `patch-props!`, `replace-children!`, …),
     implemented for WPF in `cloview.wpf.hostops`. The same reconciler runs headless
     against `mock-host` in the unit tests.
4. **User input becomes an event vector.** A prop such as
   `:on-toggle [:catalog/filter-toggle :muscles "biceps"]` gets the payload appended
   (`true`). Controlled widgets (text input, checkbox, toggle, select, tabs, split view)
   show the db value and report edits. Adapters never write a value back that the host
   already shows, and programmatic writes never echo as events. The caret and IME
   composing state survive external updates.

The result is that one declaration gives the same controls, events and state transitions
on every host. Only fonts, chrome and native behaviour differ.

## 2. Repository map

```
cloview-core/
├── deps.edn  bb.edn            JVM aliases (:test :static-check :smoke-javafx :run-javafx :init :license) and bb tasks
├── modules/
│   ├── core/      .cljc        tree, store, reconcile, style, color, components, order, memo, text
│   ├── fx/        .cljc        effect runners: http, storage, timer, file, lan_sync, codec
│   ├── javafx/    .clj         cljfx translation (cljfx.clj), lifecycles, app shell
│   ├── flutter/   .cljd        adapters, Material shim, text input, split view, modal, app, effects
│   ├── wpf/       .cljr        hostops (WPF HostOps), interop helpers, dispatch, gif, app
│   │   └── host-template/      CloViewHost.csproj + Program.cs: the [STAThread] C# host
│   ├── bridge/    .cljc        opt-in Mode B: protocol, manifest, client, process, loopback, rplot (§10)
│   │   └── backends/           reference backends: java/ csharp/ dart/ r/
│   └── licensing/ .cljc        opt-in: crypto, token, device, trial, gate, ui (§10); tool/ = key tool (JVM)
├── starter/                    bb init: src/cloview/starter.clj + templates/ (§11)
├── examples/
│   └── greeter/                the spike: shared/ + javafx/ flutter/ wpf/ entry points and smokes
├── verify/
│   ├── run-windows-checks.ps1  the Windows harness (steps 01–06 + results zip)
│   ├── static_check.clj        reads every .clj/.cljc/.cljd/.cljr: calls, arities, architecture rules, ClojureDart coverage
│   │                           (--core/--app: another repository's app tree checked against this core, §8)
│   ├── src/cloview/verify/     portable test runner and JSON report helpers (JVM + CLR)
│   ├── cljd/                   ClojureDart diagnostics (:cljd-diag) and the pinned release's public vars
│   └── bb/cloview/tasks.clj    babashka task implementations
├── ops/                        job tickets, the result recorder, the CI summary (DEVELOPMENT_WORKFLOW.md)
├── .github/workflows/          ci.yml (Linux checks), windows-harness.yml (on demand)
└── docs/                       this file, the design records, contracts and reports
```

The fitness app (`apps/fitness/`) and its frozen acceptance benchmark
(`benchmarks/fitness-baseline/`) are in
[cloview-examples](https://github.com/echojustus/cloview-examples), which expects this
repository next to it (`../cloview-core`): its `:local/root` paths point into that
checkout (§7).

The key namespaces of `modules/core`:

| Namespace | Responsibility |
|---|---|
| `cloview.core.tree` | the canonical vocabulary (`v1-tags`), `normalize`, `problems`, `find-by-test-id`, the composite registry (`reg-component!`), layout rules (width-hungry tags) |
| `cloview.core.store` | the store, handler registration (`register-handlers!` takes a data map), interceptors, coeffects, subs, the effect executor (marshals off-thread dispatches) |
| `cloview.core.reconcile` | the `HostOps` protocol, the keyed reconciler, `mock-host` (a deterministic recording host for tests) |
| `cloview.core.style` / `color` | the portable style map, and colour parsing (CSS order `#RRGGBB[AA]`) |
| `cloview.core.components` | portable composites: `chip-group` (multi-select chips) and `month-grid` (Monday-first calendar) |
| `cloview.core.order` | ordinal string comparison and stable sorts, identical on every host |
| `cloview.core.memo` | `memo-last`, a one-entry cache keyed on arguments, plus a call-counting helper |
| `cloview.core.text` | ASCII-only case changes for identifiers (`ascii-lower`, `ascii-upper`) |

## 3. Hosts in more detail

**JVM / JavaFX** (`cloview.javafx.cljfx`, `app`, `lifecycles`):

- `->desc` is a multimethod from canonical node to cljfx description: pure data, testable
  without a display.
- cljfx suppresses event handlers while rendering, so programmatic changes never echo.
- `:ui/native` maps to a lifecycle that calls the host map's `:create` / `:update` /
  `:dispose`. `:update` runs only when `:props` change.
- `app/start!` mounts the renderer and installs the effect runners.

**Flutter** (`cloview.flutter.*`):

- `adapters` maps each tag to a function `node → widget`.
- `material` is the only module namespace allowed to import Flutter UI libraries (a
  static-check rule).
- `text_input` keeps a managed `TextEditingController` whose external sync preserves the
  caret and IME composing range.
- The `:ui/native` adapter memoizes its widget on `:props`.
- Two ClojureDart facts shape the design:
  - top-level side effects are not run, so every registration is an explicit
    `register!` call;
  - the host pass reads `.cljc` with `:clj` active (§6).

**WPF** (`cloview.wpf.*`, `host-template/`):

- `CloViewHost.exe` is a tiny `[STAThread]` C# program referencing the NuGet `Clojure`
  1.12.6. It sets `CLOJURE_LOAD_PATH` to the staged sources (`target/clr-src`, built by
  `bb wpf:sync` or the harness), requires `--ns`, and calls one of its entry functions:
  - `start!` for the app;
  - `run-headless-smoke!` for an off-screen WPF check;
  - `run-tests!` for the unit tests on the CLR.
- The WPF backend:
  - `hostops` implements `HostOps` with WPF elements and an element state atom kept in
    `Tag`;
  - `dispatch` integrates the WPF `Dispatcher` (marshalling and a render coalescer);
  - `gif` animates local GIFs, since WPF's `Image` does not;
  - `app` provides the window, mount and file dialogs.
- Re-entrancy guards keep programmatic patches from echoing.

## 4. Vocabulary

The vocabulary is data in `cloview.core.tree/v1-tags`. Each entry records the tag's child
arity, its event payloads and its controlled-value contract.

| Kind | Tags |
|---|---|
| Layout | `:ui/column` `:ui/row` `:ui/grid` `:ui/wrap` `:ui/scroll` `:ui/box` `:ui/align` `:ui/spacer` |
| Controls | `:ui/text` `:ui/button` `:ui/text-input` `:ui/checkbox` `:ui/switch` `:ui/toggle` `:ui/select` `:ui/image` |
| Composites | `:ui/split-view` `:ui/tabs` / `:ui/tab` `:ui/modal` |
| Escape hatch | `:ui/native`: a host widget with `:create` / `:update` / `:dispose` (or `:build` on Flutter), diffed on `:props` only |

Stage 2-2 added two tags for multi-select filtering:

- **`:ui/toggle`**, a toggle button or filter chip:
  - JavaFX `ToggleButton`, WPF `ToggleButton`, Flutter `FilterChip`;
  - controlled `:checked`; `:on-toggle` appends the new boolean.
- **`:ui/wrap`**, a flow layout with `:gap` and `:run-gap`:
  - JavaFX `FlowPane`, WPF `WrapPanel` (spacing emulated with margins), Flutter `Wrap`.

**Layout rules.** These live in `cloview.core.tree` (`effective-grow`, `default-align`)
and every host applies them identically. They exist because Flutter is the strictest
host: an unbounded text field, scroll view or stretching column is a hard error there.

- Inside a row, inputs, selects and containers grow.
- Inside a column, vertical scroll views, split views and tabs grow. A horizontal scroll
  view keeps its content's height.
- A box or align counts as its content.
- An explicit `:grow`, or a `:style` size, wins.

See [`STAGE_2_1_DESIGN.md`](STAGE_2_1_DESIGN.md) §4.

**Portable composites** live in `cloview.core.components`. They are used as function
heads, or as `:cloview/chip-group` and `:cloview/month-grid` after `(components/register!)`.

- `chip-group`:
  - one event per toggle, `(conj on-toggle id checked?)`, rather than a recomputed set,
    which would go stale when two clicks land before a render;
  - an optional clear button;
  - an empty-state text.
- `month-grid`:
  - the 42-cell Monday-first calendar;
  - cells are multi-line buttons whose text carries every value, so nothing is conveyed by
    colour alone;
  - the selected day is bracketed;
  - navigation is disabled outside 1900-01..2100-12.

**Native views.** The fitness app's drawing surfaces (body map, radar, calorie chart,
media player) follow the contract in cloview-examples'
[`NATIVE_CONTRACTS.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/NATIVE_CONTRACTS.md):

- props are EDN computed in shared code;
- events travel as data in `:props :events`;
- a host without a renderer shows an explicit "not available" placeholder.

**Effects** (`modules/fx`):

| Effect | Notes |
|---|---|
| `:http` | |
| `:dispatch-later`, `:timer/*` | `cloview.fx.timer`; also provides the `:now` coeffect |
| `:storage/put` / `get` / `remove` | `on-success` / `on-failure`; on desktop, atomic replace with backup and corrupt-store protection |
| `:file/read` `write` `copy` `list` `app-dir` | |
| `:file/pick` | registered by each UI host |
| `:lan/*` | protocol, codec, pairing code and merge implemented; network runners report `:not-implemented` |

`cloview.fx.codec` holds portable CSV (RFC 4180) and JSON readers, and a deterministic
JSON writer (ordinal key order, invariant numbers), in pure `.cljc`. The opt-in modules
add `:backend/*` and `:r/plot` (§10).

## 5. Data and event flow, one example

The user clicks the "Triceps brachii" chip in the catalog filters of the fitness app
(cloview-examples,
[`apps/fitness/`](https://github.com/echojustus/cloview-examples/tree/main/apps/fitness))
on WPF:

1. `ToggleButton.Checked` fires. The hostops trampoline looks up the node's current
   `:on-toggle`, `[:catalog/filter-toggle :muscles "triceps-brachii"]`, appends `true`
   and calls `dispatch!`.
2. The store queues the event and drains it. The pure handler returns a db whose
   `[:catalog :filters :muscles]` contains the id.
3. The store's watch schedules a render through the coalescer: at most one pending render
   per `Dispatcher` turn.
4. `views/root` runs, and `derive/visible-exercises` recomputes only because its inputs
   changed. The reconciler diffs the result: the count text is patched and the chip's
   `:checked` already equals the displayed state, so nothing is written back.
5. `fitness.derive/calls` shows which derivations ran, which is the ARCH-004 evidence.

The same click on JavaFX goes through cljfx's event map, and on Flutter through
`FilterChip.onSelected`. Steps 2–5 are identical code.

## 6. Portability rules

These rules come from real failures. Rules 1, 2, 13, 14 and 15 are enforced by
`verify/static_check.clj`; the others are code-review rules.

1. **Reader conditionals list `:cljd` first, and top-level host-only forms carry
   `:cljd nil`.** ClojureDart's host pass reads `.cljc` with
   `#{:cljd :cljd/clj-host :clj}`, so a `:clj` branch placed first wins during the Dart
   build.
2. **No `compare-and-set!` with a literal expected value.** ClojureCLR 1.12.6 compares
   boxed values by reference. Use a `swap-vals!` test-and-set (see `store/try-acquire!`).
3. **Strings sort ordinally.** Use `cloview.core.order` (`compare-str`, `sort-strings`,
   `sort-by-str`): ClojureCLR compares strings by culture.
4. **Sorts that must be stable use `order/stable-sort-by`.** .NET switches to an unstable
   introsort above 16 items, and Dart's `List.sort` is unstable above 32.
5. **Never use `str`, `format` or `.ToString` of a double for UI or JSON text.** On
   ClojureCLR 1.12.6, `str` drops a zero fraction (`(str 20.0)` is `"20"`), and .NET
   formatting follows the current culture (`70,5` in de-DE).
   - UI text: integer arithmetic (the private helpers of `cloview.core.components`, and
     `fitness.derive/fmt-quantity` in cloview-examples).
   - JSON reports: the private `json-number` in `cloview.verify.report`
     (culture-invariant, always with a fraction).
   - `pr-str`, and so EDN storage, is correct on every host.
6. **Identifiers use `cloview.core.text/ascii-lower` and `ascii-upper`**, not
   `clojure.string/lower-case` / `upper-case`. On ClojureCLR those follow the culture
   (Turkish `I` → `ı`). Keep `clojure.string` for human text.
7. **Regex groups:** an unmatched optional group is `""` on ClojureCLR but `nil` on the
   JVM and in Dart. Normalize with `not-empty`.
8. **Numbers:** `(= 2 2.0)` is false on the JVM and CLR but true in Dart. Keep quantities
   as doubles and counts as longs, in props and in data compared across hosts. `(long x)`
   truncates; round explicitly (`(long (+ x 0.5))` for non-negative values).
9. **Characters** are 1-character strings in ClojureDart. Avoid char arithmetic in shared
   code; regex and string functions behave the same everywhere.
10. **No top-level side effects** in shared code: ClojureDart does not run them. Register
    handlers, composites and renderers from an explicit `register!` called by each entry
    point.
11. **Colours** are CSS order `#RRGGBB` / `#RRGGBBAA`, with alpha last, on every host,
    even though WPF's native order is ARGB.
12. **Files:** read and write UTF-8 explicitly on the JVM (`slurp` / `spit` with
    `:encoding`). Use forward slashes in shared paths; every host accepts them.
13. **Only `cloview.flutter.material` imports Flutter UI libraries** inside `modules/`
    (static-check rule).
14. **ClojureCLR: no type hint on a `let` / `loop` local whose type is a .NET value
    type** (`Point`, `Rect`, `Size`, `Thickness`, …). ClojureCLR treats every struct as a
    primitive, so `(let [^Point p (.GetPosition e c)] …)` is a compile error ("Can't
    type hint a local with a primitive initializer") and the namespace fails to load.
    Leave the local unhinted. Hints on parameters and on uses (`(.X ^Point d)`) are fine.
    This is a static-check rule.
15. **Dart-compiled files use only what the pinned ClojureDart defines.** `with-redefs`,
    `format`, `slurp`, `spit`, `pmap`, `locking`, `agent`, refs and `bigdec` do not exist
    there; `clojure.X` namespaces map to `cljd.X` with their own public vars. The
    **ClojureDart coverage** rule resolves every symbol of the files ClojureDart compiles
    (portable module sources, shared app sources and tests, Flutter entry points, the
    initializer's templates). It checks each one against the public vars of the pinned
    release (`verify/cljd/cljd-publics.edn`, generated by `verify/cljd/extract_publics.clj`
    from its sources), the special forms, locals and Dart interop. Regenerate the list
    when the pinned ClojureDart changes. A `with-redefs` in a shared test, which broke the
    fitness Dart tests on Windows, is what this rule now catches.

## 7. The fitness benchmark (in cloview-examples)

The fitness app
([`apps/fitness/`](https://github.com/echojustus/cloview-examples/tree/main/apps/fitness))
and its acceptance benchmark
([`benchmarks/fitness-baseline/`](https://github.com/echojustus/cloview-examples/tree/main/benchmarks/fitness-baseline))
are in [cloview-examples](https://github.com/echojustus/cloview-examples), which uses
this repository's modules from a sibling checkout (`../cloview-core`). The paths below
are relative to cloview-examples, and the commands run there.

- **`benchmarks/fitness-baseline/`** is the acceptance package, frozen: every file is
  in its SHA-256 manifest, checked by its own `tools/check_package.py`, and the one edit
  since delivery (its licence paragraph) is recorded in its `benchmarks/README.md`. It contains the docs, the 37
  frozen business oracles, fixtures, media, the reference core and the reference UI
  (Flutter).
- **`apps/fitness/shared/`** contains:
  - `src/clogem/fitness/{domain,catalog,analytics}.cljc`: the reference core, copied
    verbatim (provenance hashes are in `apps/fitness/README.md`);
  - `test/clogem/fitness/*_test.cljc`: the reference tests;
  - `src/fitness/*`: the app layer (state, events, derivations, views, HR import, dates,
    2D anatomy geometry, native registry, oracle runner, verification entry point).
- **`apps/fitness/{javafx,wpf,flutter}/`** hold the entry points, the native renderers
  and the smokes.
- **Acceptance flow (DOM-001).** `fitness.verify` runs the reference unit tests plus the
  app tests, executes every oracle with the shared core on the current host, and writes
  the `FITNESS_BUSINESS_RESULTS_V1` envelope. The baseline's
  `tools/check_fixtures.py --observed <envelope>` then compares it.
  - `bb fitness:test` does this on the JVM;
  - `bb fitness:test:clr` on ClojureCLR;
  - `bb fitness:flutter` on Dart;
  - cloview-examples' own Windows harness (`verify/run-windows-checks.ps1`) does all
    three on Windows in steps 02–04, and runs the baseline's Python checkers in step 05.

This repository's CI and Windows harness do not run these checks. cloview-examples' CI
does, with cloview-core checked out next to it (DEVELOPMENT_WORKFLOW.md §2). The
capability-by-capability mapping is in
[`FITNESS_CAPABILITY_MAP.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/FITNESS_CAPABILITY_MAP.md).

## 8. Verification

| Check | Command | Where it runs |
|---|---|---|
| JVM unit tests (core, fx, bridge incl. real Java and R child processes, licensing, the key tool, the initializer, cljfx descriptions) | `bb test` / `clj -M:test` | anywhere (R tests skip without `Rscript`) |
| Static check (all four dialects + rules + ClojureDart coverage) | `bb static-check` | anywhere |
| JavaFX smoke (off-screen: Greeter + kitchen sink) | `bb smoke:javafx` | desktop or Xvfb |
| CLR unit tests, WPF smoke | `bb test:clr`, `bb smoke:wpf` | Windows (.NET SDK) |
| ClojureDart compile + widget tests | `bb test:flutter` | Flutter ≥ 3.35 |
| Everything on Windows, packaged (steps 01–06) | `verify\run-windows-checks.ps1` | Windows |

The fitness app's checks run in cloview-examples (§7): `bb fitness:test`,
`bb fitness:test:clr`, `bb fitness:flutter`, the fitness smokes and its own Windows
harness. There, `clj -M:static-check` runs this repository's checker on the app tree,
`static_check.clj --core ../cloview-core --app apps`: the core's files then feed the
index only, and problems are reported for the app's files. Without arguments, the
checker checks this repository.

The headless reconciler tests (`reconcile_test` and `components_test` here,
`fitness.app-test` in cloview-examples) are the backbone: they pin down keyed
reordering, controlled-value guards, echo suppression and native props diffing on the
JVM and the CLR without a display.

## 9. Extending cloview

**A new canonical tag:**

1. Add the entry to `v1-tags` (children, events, controlled contract), plus a layout rule
   if it should grow inside rows.
2. Add a `->desc` method in `cloview.javafx.cljfx`.
3. Add an adapter in `cloview.flutter.adapters`, with any Material pieces in `material`.
4. Add `create`, `patch` and `destroy` branches in `cloview.wpf.hostops`.
5. Add tree tests, a cljfx description test, the tag in the greeter kitchen sink
   (exercised by all three smokes), and a row in §4.

**A new effect:** a namespace in `modules/fx` with `install!` registering runners per host
branch. Runners report through `:on-success` / `:on-failure` event vectors.

**A new native view:**

1. Shared code builds EDN props, including `:events` and `:a11y-label`.
2. Each host registers a host map.
3. The view falls back to an explicit placeholder.
4. Document its props and events, as cloview-examples'
   [`NATIVE_CONTRACTS.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/NATIVE_CONTRACTS.md)
   does for the fitness views.

**A composite:** a function from props to canonical hiccup in `cloview.core.components`
(generic) or in the app (specific). It needs no host code.

## 10. Optional modules

Apps add these with a `:local/root` (or `bb init --logic backend` / `--with licensing`);
an app that does not use them never loads them.

- **`modules/bridge`: decoupled backends (Mode B).** A backend in any language runs as a
  child process and speaks JSON-RPC 2.0 over stdio (`cloview-bridge/1`).
  - `cloview.bridge/install!` takes parsed manifests (`cloview.bridge.manifest/parse` of
    a `backend-manifest.json`) and registers `:backend/call`, `:backend/stream`,
    `:backend/cancel`, `:backend/start` and `:backend/stop`.
  - The launcher handles start-up, timeouts, cancellation, crash restarts with backoff,
    shutdown and kill-tree.
  - `cloview.bridge.loopback` runs a Clojure function as the backend over the same wire
    format, for tests and prototypes.
  - Reference backends: Java, C#, Dart and R.
  - Details: [`POLYGLOT_BACKEND_ARCHITECTURE.md`](POLYGLOT_BACKEND_ARCHITECTURE.md).
- **`cloview.bridge.rplot`: R plots.** The `:r/plot` effect and `plot-view` render
  ggplot2 or base-R figures in a long-lived `Rscript` worker (`backends/r/backend.R`) and
  show them with `:ui/image`. Requests per plot are coalesced (latest wins).
  [`R_VISUALIZATION_INTEGRATION.md`](R_VISUALIZATION_INTEGRATION.md).
- **`modules/licensing`: feature gates and trials.**
  - `wrap-gated-event` wraps an event handler with a hard gate or a metered trial.
  - Offline ES256 licence keys (`CLV1.…`) unlock features, optionally bound to a device
    code.
  - Trial counts are HMAC-sealed and fail closed.
  - The unlock dialog is built from canonical tags.
  - The seller's key tool lives in `tool/`.
  - [`LICENSING.md`](LICENSING.md).

All three run on Clojure/JVM and ClojureCLR. On ClojureDart, the bridge's process
transport and the licensing crypto are Stage 3; their contracts are fixed and refuse
clearly (`:unsupported`).

## 11. The project initializer

`bb init <dir>` (or `clojure -M:init <dir>`) runs `cloview.starter`, a zero-dependency
generator that works under babashka and on the JVM. It writes:

- a project with **one** primary target (`--target javafx|wpf|flutter`) and a logic mode
  (`--logic same-host|backend`, `--backend java|csharp|dart`);
- a `cloview.edn` manifest;
- the project's own `bb` tasks.

`bb target:add <target>` adds release targets later. It edits only the `:targets
:enabled` vector of `cloview.edn` in place (comments and hand-added keys stay), checks
every file before the first write, and prints the exact change instead when it cannot
edit the vector safely.

Mode B apps find their `backend/` folder through `-Dcloview.app.dir` on JavaFX (the
generated `:run` alias passes `..`, because `bb dev` runs it from `javafx/`) and
`CLOVIEW_APP_DIR` on WPF, falling back to the working directory, its parent and, on
WPF, the executable's folder and the folders above it. A packaged app sets the property
or variable to its install folder.

The templates are real sources in `starter/templates/<mode>/…`. Their namespace prefix
`cloview-app`, file prefix `cloview_app` and title `Cloview App` are replaced, so
`static_check.clj` and the ClojureDart coverage rule check them like any other code.

Tests:

- **`cloview.starter-test`** (JVM) generates every target and mode combination, checks
  that every generated file parses with no placeholder left and every `:local/root`
  exists, adds targets, and runs a generated project's own tests in a child JVM.
- **A sandbox end-to-end script**, used while building Stage 2-2b and not shipped in the
  repository, went further:
  - loads the generated JavaFX entry points against cljfx;
  - runs the generated WPF entry points (`run-tests!`, `run-headless-smoke!`) on
    ClojureCLR with the WPF stand-ins (also sandbox-only, STAGE_2_2_DECISIONS.md
    item 39);
  - drives a generated Mode B app against its real Java backend;
  - builds the generated C# backend;
  - runs the generated `bb` tasks.
