# cloview-core

**Write the app once, in Clojure, as data. Run it as a real native desktop app.**

cloview is one declarative UI and application layer written in `.cljc`, rendered
natively on three hosts. This repository, **cloview-core**, is the framework: the host
adapters, the bridge to backends in other languages, licensing, the project initializer
and a minimal smoke example. The example apps and the showcase gallery are in the
companion repository [cloview-examples] (MIT-licensed).

| Host | Language runtime | Native UI | Module |
|---|---|---|---|
| JVM | Clojure 1.12 | JavaFX, through [cljfx](https://github.com/cljfx/cljfx) | `modules/javafx` |
| Dart | [ClojureDart](https://github.com/Tensegritics/ClojureDart) | Flutter (Windows, macOS, Linux, Android, iOS) | `modules/flutter` |
| CLR | ClojureCLR | WPF (Windows) | `modules/wpf` |

> **Status: Stage 2-2b, before the first public release.**
>
> - **All three hosts pass every check on Windows** (the run of 2026-10-25 on the Stage 2-2b
>   code, before the repository split):
>   - JavaFX and WPF: unit tests, GUI smokes and the 37 business oracles of the fitness
>     benchmark, plus the bridge, licensing and initializer suites;
>   - Flutter (3.47.8): both example apps compile, the Greeter widget tests pass, and the
>     fitness app passes its 47 Dart tests and the same 37 oracles.
> - **New in 2-2b:**
>   - a project initializer (`bb init`);
>   - decoupled backends in any language (`modules/bridge`);
>   - R plots;
>   - offline licensing (`modules/licensing`);
>   - the split into this repository and [cloview-examples], where the fitness app and
>     its benchmark now live.
> - APIs may still change. See the [roadmap](docs/ROADMAP_AND_EXTENSIONS.md).

## The problem

Small teams and solo developers who need a **desktop tool** hit two walls.

**Native toolkits are each a deep, separate investment.**

- **Flutter** means learning Dart and the widget and state-management ecosystem around
  it, a language used almost nowhere else.
- **JavaFX** means FXML, its own CSS dialect and the scene graph.
- **WPF** means XAML and dependency properties, and it runs on Windows only. Microsoft
  now recommends WinUI 3 for new general-purpose Windows apps, while keeping WPF
  supported and improving
  ([Windows developer FAQ](https://learn.microsoft.com/en-us/windows/apps/get-started/windows-developer-faq)).

What you learn for one toolkit transfers poorly to the next. Picking one early is a bet
you pay for whenever the platform needs change.

**The usual escape, JavaScript/TypeScript plus Electron, moves the cost elsewhere:**

- **Size.** An Electron app bundles Chromium and Node.js. In a public CI benchmark of an
  empty app on Windows x64 (release builds), the Electron build is about 385 MB against
  about 25 MB for Flutter. Its processes use about 279 MB of RAM against about 103 MB;
  measured as the drop in free system memory, the figures are about 100 MB and 83 MB
  ([benchmark](https://github.com/Elanis/web-to-desktop-framework-comparison), read
  2026-10-25). The Electron 45.0.0 runtime alone is 115–151 MB zipped per platform
  ([release assets](https://github.com/electron/electron/releases/tag/v45.0.0)).
- **A second stack.** For a developer without a web-frontend background, the stack is a
  career of its own: a framework, a bundler, a state library, IPC between the main and
  renderer processes, and a team that must know all of it.

## Who cloview is for

1. **One-person companies and indie developers** without a web-frontend background who
   need a polished desktop app without hiring a frontend team.
2. **Technical professionals and learners** who build tools for their own work:
   architects, consultants, product managers, QA and DevOps engineers, students, and
   career switchers.
3. **Domain-focused utilities.** For example:
   - a fitness tracker (the flagship app of [cloview-examples]);
   - a watermark remover or a folder encryptor;
   - a local AI detector or a quantitative calculator.

   The domain logic matters more than the UI, but the UI must still feel native.

## Philosophy

- **One declarative frontend, native execution.** Views are plain `.cljc` functions that
  return data. Each host turns that data into its own native controls, so there is no
  web view and no bundled browser.
- **An escape hatch, not a cage.** `:ui/native` embeds a real host widget (a JavaFX
  `Canvas`, a WPF `Canvas` of shapes, a Flutter `CustomPaint`) where the shared
  vocabulary stops. The fitness app's anatomy heatmap, radar and calorie charts use it.
- **Pragmatic semantic equivalence.** The same structure, state, events and behaviour on
  every host, with native fonts, controls and metrics. One set of layout rules keeps the
  hosts in agreement; pixel parity is not a goal.
- **AI-native ergonomics.** Everything an assistant (or a new teammate) must understand
  is data:
  - the tag vocabulary is a map (`cloview.core.tree/v1-tags`);
  - views return inspectable trees;
  - events are vectors;
  - effects are maps.

  A static check verifies every call and arity on every platform, so generated code is
  checked before it reaches a GUI.
- **Your logic, where it fits.** Keep it in `.cljc` in the same process (the default), or
  run an existing Java, C#, Dart or R engine as a decoupled backend
  ([Mode B](docs/POLYGLOT_BACKEND_ARCHITECTURE.md)).

## The manual in five minutes

### Views

A view is a function from the app state (the *db*) to canonical hiccup:

```clojure
(defn root [db]
  [:ui/column {:gap 8 :style {:padding 16}}
   [:ui/text {:text "Greeter" :style {:font-size 20 :font-weight :bold}}]
   [:ui/text-input {:value (:name db) :placeholder "Your name" :on-change [:app/set-name]}]
   [:ui/button {:label "Greet" :on-press [:app/greet] :disabled? (empty? (:name db))}]
   (when-some [g (:greeting db)]
     [:ui/text {:key :greeting :test-id "greeting" :text g}])])
```

| Kind | Tags |
|---|---|
| Layout | `:ui/column` `:ui/row` `:ui/grid` `:ui/wrap` `:ui/scroll` `:ui/box` `:ui/align` `:ui/spacer` |
| Controls | `:ui/text` `:ui/button` `:ui/text-input` `:ui/checkbox` `:ui/switch` `:ui/toggle` `:ui/select` `:ui/image` |
| Composites | `:ui/split-view` `:ui/tabs` / `:ui/tab` `:ui/modal`, plus `chip-group` and `month-grid` (`cloview.core.components`) |
| Escape hatch | `:ui/native`: a host widget with `:create`/`:update`/`:dispose` (`:build` on Flutter), updated only when its `:props` change |

What you need to know about the vocabulary:

- **Keys.** `:key` gives a child a stable identity, so reordering moves controls instead
  of recreating them.
- **Test ids.** `:test-id` lets tests and GUI smokes find nodes on every host.
- **Layout.** Inputs grow in rows and scroll views grow in columns, by one rule set for
  all hosts (`cloview.core.tree/effective-grow`).

### State

All state is one immutable map in a store. Derived values are subscriptions, memoized on
the db:

```clojure
(def initial-db {:name "" :greeting nil})
(defonce store (st/create-store {:db initial-db}))
```

### Events

Events are data vectors. Handlers are pure functions, declared in a map and registered
by one function that every entry point calls:

```clojure
(def handlers
  {:events-db {:app/set-name (fn [db [_ s]] (assoc db :name s))
               :app/greet    (fn [db _] (assoc db :greeting (str "Hello, " (:name db) "!")))}
   :subs      {:greeting (fn [db] (:greeting db))}})

(defn register! [] (st/register-handlers! handlers))
```

Register from a map rather than with top-level `reg-*` calls. ClojureDart compiles
definitions only, so a top-level side effect never runs on Flutter.

### Effects

A handler that needs the outside world returns effects; the host runs them and reports
back with events:

```clojure
:notes/add (fn [{:keys [db]} _]
             (let [db' (update db :notes conj (:draft db))]
               {:db db'
                :storage/put {:key :notes :value (:notes db') :on-failure [:notes/save-failed]}}))
```

| Effect | What it does |
|---|---|
| `:storage/*` | durable key-value storage, with a backup and corrupt-store protection on desktop |
| `:file/*` | pick, read, copy and list files |
| `:http` | HTTP requests |
| `:dispatch-later` `:timer/*` | time |
| `:backend/call` `:backend/stream` | a decoupled backend process ([Mode B](docs/POLYGLOT_BACKEND_ARCHITECTURE.md)) |
| `:r/plot` | a ggplot2/base-R figure rendered to an image ([R plots](docs/R_VISUALIZATION_INTEGRATION.md)) |
| `:lan/*` | LAN sync protocol (the network runners are Stage 3) |

Licensing is a wrapper around handlers: `(licensing/wrap-gated-event :pro/export {:mode
:trial-count :max-uses 5} handler)` gives five free uses, then asks for an offline
licence key ([licensing](docs/LICENSING.md)).

### Entry points

Each target folder holds a short entry point (under 70 lines in the examples and the
`bb init` templates) that registers the handlers, installs the host's effects and
mounts the view. The WPF files also hold the smoke and test entry points that
`CloViewHost` calls:

```clojure
(app/start! {:store store :view root :title "Greeter"})
```

> **CRITICAL: develop on ONE target.**
>
> Choose one primary GUI target when you create the project, and use only that target
> day to day. Do **not** configure, build or test all three targets during daily
> development:
>
> - every extra target multiplies toolchains, build times and failure sources;
> - the shared `.cljc` is already tested headless on the JVM by `bb test`.
>
> Add the other targets only for a release (`bb target:add wpf`), run their checks
> (`bb test:clr`, `bb test:flutter`), and go back to the primary.

## Start a project

From a cloview-core checkout, with [babashka](https://babashka.org):

```bash
bb init ../notes-app                                   # JavaFX, logic in .cljc (the defaults)
bb init ../notes-app --target wpf                      # or flutter
bb init ../engine-app --logic backend --backend java   # Mode B: the GUI plus a Java backend (or csharp, dart)
bb init ../pro-app --with licensing                    # adds modules/licensing
```

Without babashka, `clojure -M:init ../notes-app …` creates the project, and
`clojure -M:test` runs its shared tests; the other project tasks need babashka. With
babashka:

```bash
cd ../notes-app
bb test            # the shared tests on the JVM: fast, no GUI
bb dev             # run on the primary target
bb target:add wpf  # later, for a release: write wpf/ and enable it
```

What you get:

| Path | Content |
|---|---|
| `cloview.edn` | the manifest: app name, `:targets {:primary … :enabled […]}`, logic mode, modules, where the cloview-core checkout is |
| `shared/` | almost the whole app (state, events, effects, views) and its tests |
| `javafx/` `wpf/` `flutter/` | one folder per enabled target, only the primary at first; `wpf/host` is the app's own C# host |
| `backend/` | Mode B only: `backend-manifest.json` and a working backend in the chosen language |
| `bb.edn`, `bb/` | the project's tasks (`bb tasks` lists them; the code is yours to edit) |

`--title` may not contain `"`, `\` or line breaks, and names that clash with existing
namespaces (`java`, `clojure`, `cloview`, …) are refused. `bb target:add` changes only
`:targets :enabled` in `cloview.edn`, keeps your comments and keys, and writes nothing
when a file it would create already exists.

The Greeter's target folders (`examples/greeter/{javafx,wpf,flutter}`) and those of the
[fitness app][fitness] in cloview-examples show the same idea: thin entry points plus
optional `:ui/native` renderers. In the fitness app, about 90% of the code is in
`shared/` (2,622 lines, against 220–280 per target; smokes and tests excluded). An app
needs only the targets it ships.

## 🏆 Featured Success Stories & Showcase

Running apps explain cloview better than any page of documentation. The companion
repository **[cloview-examples]** holds the runnable reference apps and the community
gallery. **Its code is MIT-licensed: copying from it into your own project,
commercial or not, is welcome.**

| App | What it shows | Hosts | Where |
|---|---|---|---|
| **Fitness** (flagship) | a workout tracker with five tabs (catalog, plan builder, execution log, anatomy and analytics, calendar) from one shared `.cljc` app: keyed lists, multi-select filters, heart-rate import, and `:ui/native` charts and an anatomy heatmap. It is held to a frozen acceptance benchmark of 37 business oracles, all passed on all three hosts in the Windows run of 2026-10-25. | JavaFX, WPF, Flutter | [`apps/fitness`][fitness] (MIT) |
| **Greeter** | the smallest complete app, plus a kitchen sink of every tag; the smoke test of this repository's CI and Windows harness | JavaFX, WPF, Flutter | [`examples/greeter`](examples/greeter) (this repository: EPL-2.0 OR GPL-2.0-or-later) |

Built something with cloview? List it in the community showcase: see
[`showcase/`](https://github.com/echojustus/cloview-examples/tree/main/showcase) in cloview-examples.

## Documentation map

| Document | What it covers |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | how the pieces fit, repository map, vocabulary, portability rules, verification, how to extend |
| [`docs/POLYGLOT_BACKEND_ARCHITECTURE.md`](docs/POLYGLOT_BACKEND_ARCHITECTURE.md) | Mode A vs Mode B; the `cloview-bridge/1` protocol, effects, the manifest and the launcher lifecycle |
| [`docs/LICENSING.md`](docs/LICENSING.md) | feature gates, metered trials, offline ES256 licence keys, device binding, store rules |
| [`docs/R_VISUALIZATION_INTEGRATION.md`](docs/R_VISUALIZATION_INTEGRATION.md) | R plots in views: options, the `:r/plot` contract, shipping R, licensing, measurements |
| [`docs/ROADMAP_AND_EXTENSIONS.md`](docs/ROADMAP_AND_EXTENSIONS.md) | V1 status, the Stage 3 work list, store packaging (future) |
| [`docs/STAGE_2_2_DECISIONS.md`](docs/STAGE_2_2_DECISIONS.md) | decisions taken without asking in Stages 2-2 and 2-2b, with alternatives |
| [`docs/DEVELOPMENT_WORKFLOW.md`](docs/DEVELOPMENT_WORKFLOW.md) | who does what from Stage 3: CI, local operator agents, Claude Code, the owner; job tickets and results |
| [`docs/research/`](docs/research/) | the feasibility study archive, and rejected and deferred approaches |
| [`docs/STAGE_2_1_DESIGN.md`](docs/STAGE_2_1_DESIGN.md) | design record: C# host and .NET fallback, `HostOps` and the headless reconciler, Flutter shim and controlled text input |
| [`docs/WINDOWS_VERIFICATION_REPORT.md`](docs/WINDOWS_VERIFICATION_REPORT.md) | the Windows runs: results, root causes, fixes |
| [`VERIFY_ON_WINDOWS.md`](VERIFY_ON_WINDOWS.md) | running the Windows verification harness |
| [cloview-examples] | the fitness app (layout, provenance, logical events), its frozen acceptance benchmark, the `:ui/native` contracts (`docs/NATIVE_CONTRACTS.md`) and the capability map (`docs/FITNESS_CAPABILITY_MAP.md`) |

## Repository layout

```
cloview-core/
├── deps.edn  bb.edn          JVM aliases and babashka tasks (bb tasks lists them)
├── modules/
│   ├── core/       .cljc     tree, store, reconcile (HostOps + keyed reconciler + MockHostOps),
│   │                         style, color, components, order, memo, text
│   ├── fx/         .cljc     effect runners for JVM / CLR / Dart, CSV/JSON codec
│   ├── javafx/     .clj      cljfx translation, lifecycles, JavaFX app shell
│   ├── flutter/    .cljd     adapters, Material shim, controlled text input, app shell, effects
│   ├── wpf/        .cljr     WPF HostOps, Dispatcher integration, GIF animation, app shell
│   │   └── host-template/    CloViewHost.csproj + Program.cs ([STAThread] C# host)
│   ├── bridge/     .cljc     Mode B: protocol, manifest, client, launcher, loopback, R plots;
│   │   └── backends/         reference backends: Java, C#, Dart, R
│   └── licensing/  .cljc     gates, trials, ES256 licence keys, device codes, unlock dialog
│       └── tool/             the seller's key tool (keygen / issue / inspect), JVM only
├── starter/                  bb init: the generator and its templates
├── examples/
│   └── greeter/              the spike: shared view + kitchen sink (every tag) on three hosts
├── verify/
│   ├── run-windows-checks.ps1     Windows harness: steps 01–06 and a results zip
│   ├── static_check.clj           var/arity checker + architecture/portability rules
│   │                              (cloview-examples runs it on its apps: --core/--app)
│   ├── src/cloview/verify/        portable test runner and JSON reports (JVM and CLR)
│   ├── cljd/                      ClojureDart diagnostics and the pinned public-var list
│   └── bb/cloview/tasks.clj       babashka task implementations
├── ops/                           job tickets for the local operator, result recorder, CI summary,
│                                  the Claude Code session's bootstrap prompt
├── .github/workflows/             CI: Linux checks on pushes to main and on pull requests;
│                                  the Windows harness on demand
├── docs/                          architecture, design records, contracts, reports, research
├── CLAUDE.md  AGENTS.md           instructions for Claude Code (developer) and operator agents
├── LICENSE                        EPL-2.0, with GPL-2.0-or-later as a Secondary License
└── VERIFY_ON_WINDOWS.md
```

The fitness app (`apps/fitness/`), its acceptance benchmark (`benchmarks/fitness-baseline/`)
and their docs are in cloview-examples, which expects this repository next to it
(`../cloview-core`).

## Working on cloview itself

```bash
clj -M:test                    # JVM unit tests: every portable suite, plus the key tool, the initializer and cljfx
clj -M:static-check            # reads every .clj/.cljc/.cljd/.cljr: calls, arities, portability and ClojureDart coverage
clj -M:smoke-javafx            # off-screen JavaFX smoke -> verify/out/03-jvm-cljfx-smoke.json
clj -M:run-javafx              # the Greeter on JavaFX
clj -M:license keygen --kid 2026-1   # licence signing keys (docs/LICENSING.md §9)
```

With babashka, `bb tasks` lists everything, for example `bb run:wpf` (WPF) and
`bb run:flutter` (Flutter).

On Windows, the full verification of all hosts is one command:

```powershell
powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1
```

The fitness app's tasks (`bb fitness:test`, `bb run:fitness:wpf`, …) and its own Windows
harness run in cloview-examples.

## Using the modules without `bb init`

Until releases are published, depend on a local checkout. A git dependency on this
repository with `:deps/root` also works for the JVM. For cljr it is unverified and
probably broken, because cljr resolves a relative `:deps/root` against the working
directory (STAGE_2_1_DESIGN.md §5.6).

- **JVM:** `{:deps {cloview/javafx {:local/root "../cloview-core/modules/javafx"}}}`
  brings core, fx and cljfx. Add `cloview/bridge` or `cloview/licensing` the same way.
  As a git dependency, the lib name gives the URL for one module, and several modules
  need a name each:

  ```clojure
  {:deps {io.github.echojustus/cloview-core {:git/sha "<commit>" :deps/root "modules/javafx"}}}

  {:deps {cloview/javafx {:git/url "https://github.com/echojustus/cloview-core.git"
                          :git/sha "<commit>" :deps/root "modules/javafx"}
          cloview/bridge {:git/url "https://github.com/echojustus/cloview-core.git"
                          :git/sha "<commit>" :deps/root "modules/bridge"}}}
  ```
- **ClojureDart:** add `cloview/flutter {:local/root "…/modules/flutter"}` next to
  `tensegritics/clojuredart`. After `clj -M:cljd init`, add the Dart packages of the
  effect runners: `flutter pub add http shared_preferences path_provider`. The modules
  declare no pubspec bridge, because it breaks through `flutter.bat` on Windows.
- **WPF:**
  1. Copy `modules/wpf/host-template/` (`bb init --target wpf` does this for you).
  2. Stage your sources the way `bb wpf:sync` does.
  3. Run `CloViewHost.exe --ns your.app.main`.

  Your namespace provides `start!`, plus `run-headless-smoke!` and `run-tests!` for the
  verification modes.

## Requirements

| Host | Requirement |
|---|---|
| JVM | JDK 11 or newer and the Clojure CLI. **JDK 21+ recommended.** cloview pins no JavaFX version: it uses the JavaFX that cljfx 1.10.363 brings for the running JDK (`modules/javafx/deps.edn`). On JDK 11 the newest JavaFX LTS line is 17, listed as LTS "until Oct 2026" ([Gluon](https://gluonhq.com/products/javafx/), read 2026-10-25) |
| Flutter | **Flutter ≥ 3.35** (3.47.x stable recommended) and ClojureDart `0.9.20261002`. The pinned ClojureDart uses Flutter APIs added in 3.35; `bb flutter:check` and the harness verify the version. |
| WPF | Windows 10/11 with the .NET 10 SDK (9 or 8 accepted) and the NuGet `Clojure` 1.12.6 package, restored by the build |
| ops tools, CI summary | Python 3 (optional) |
| Babashka | needed for a generated project's tasks (`bb dev`, `bb target:add`, `bb test:clr`, …). Without it, `clojure -M:init` creates a project, `clojure -M:test` runs its shared tests, `clojure -M:run` in `javafx/` starts a JavaFX app, and the Windows harness needs only PowerShell |

## Working in this repository (people and Claude Code)

Claude Code reads [`CLAUDE.md`](CLAUDE.md); operator agents that run job tickets (Codex
and others) read [`AGENTS.md`](AGENTS.md). How the work is divided:
[`docs/DEVELOPMENT_WORKFLOW.md`](docs/DEVELOPMENT_WORKFLOW.md). How a Claude Code session's
work becomes commits (the session never commits or pushes):
[`docs/DELIVERY_DESIGN.md`](docs/DELIVERY_DESIGN.md) and
[`docs/COLLABORATION.md`](docs/COLLABORATION.md).

- **Shared code is `.cljc`.** Follow the portability rules in
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §6:
  - `:cljd` first in reader conditionals;
  - only ClojureDart-defined symbols in Dart-compiled files (`with-redefs`, `format`,
    `slurp` and friends need a reader conditional);
  - ordinal string order;
  - no `str` of doubles in UI or JSON;
  - ASCII case changes for identifiers;
  - no top-level side effects;
  - no type hints on struct locals in `.cljr`.

  `clj -M:static-check` enforces the mechanical ones.
- **Every change should keep these green:**
  - `clj -M:test`;
  - `clj -M:static-check`;
  - the JavaFX smoke.

  On Windows, also the harness steps 04–06. A change to a module's API should also keep
  cloview-examples green (`clj -M:fitness` and `clj -M:static-check` there): it is the
  largest app built on these modules.
- **Host-only code** belongs in the host modules or in an example's
  `javafx/` / `wpf/` / `flutter/` folders. Never hide host copies of the app in reader
  conditionals.
- **The licences are deliberate.** This repository is EPL-2.0 OR GPL-2.0-or-later;
  cloview-examples is MIT. Do not "harmonise" them (see "License" below).

## License

© 2026 EchoJustus (Jiapeng Ji)

The whole of cloview-core is licensed under the [Eclipse Public License 2.0](LICENSE),
with the **GNU General Public License, version 2 or (at your option) any later version,
as a Secondary License**. In SPDX terms: `EPL-2.0 OR GPL-2.0-or-later`. The filled-in
Exhibit A at the end of [`LICENSE`](LICENSE) designates the secondary licence; its notice
reads:

> "This Source Code may also be made available under the following Secondary Licenses
> when the conditions for such availability set forth in the Eclipse Public License,
> v. 2.0 are satisfied: GNU General Public License as published by the Free Software
> Foundation, either version 2 of the License, or (at your option) any later version."

The framework's Clojure, PowerShell and Python sources also carry the line
`SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later`. The files that `bb init` copies
into new projects (the starter templates, the WPF host template and the reference
backends) are under the same licence without a per-file line. The arrangement follows
the Clojure ecosystem's pattern: Leiningen writes an EPL-2.0 Exhibit A notice naming
GPL-2.0-or-later into every new project. Leiningen's notice also adds the GNU Classpath
Exception; this one does not (see the GPL route below).

Two routes, in short (a summary, not legal advice):

- **Commercial and closed-source applications: EPL-2.0, the default.** The EPL is a weak
  copyleft at the file level: your own files can stay closed and use any licence. When
  you distribute your app, cloview-core's files, with any changes you made to them, must
  stay available as source under the EPL, and your app must say where to get it (EPL-2.0
  §3.1). Each contributor also grants an express patent licence. One caveat: a file that
  `bb init` generated from a template is a modified copy of cloview-core's file, so it
  falls under the same rule (open question 15 in the decision record).
- **GPL projects: the Secondary License.** The EPL alone is not compatible with the GPL.
  Because of the Exhibit A notice, cloview-core may be distributed under the GPL,
  version 2 or later, when it is combined with GPL-licensed code (EPL-2.0 §3.2 and
  Exhibit A). The Clojure runtimes themselves (Clojure, ClojureCLR, ClojureDart) are
  under EPL-1.0, which the FSF considers incompatible with the GPL, and this notice adds
  no permission to link with them. That kind of permission is what Leiningen's Classpath
  Exception adds (open question 16 in the decision record).

The example apps and the showcase are in [cloview-examples] under the MIT licence, which
covers that repository's code only, so their code can be copied freely.

[cloview-examples]: https://github.com/echojustus/cloview-examples
[fitness]: https://github.com/echojustus/cloview-examples/tree/main/apps/fitness
