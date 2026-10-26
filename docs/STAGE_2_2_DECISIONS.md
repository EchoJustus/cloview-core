# Stages 2-2 and 2-2b: decisions taken without asking

Stage 2-2 and Stage 2-2b ran unattended. Wherever a question would normally have gone to
the project owner, the recommended option was taken. This list collects those questions,
the choice, the main alternatives and how to reverse the choice. Each item points to the
place where it is implemented or documented. Items 1–40 are Stage 2-2. Stage 2-2b starts
at item 41 ([below](#stage-2-2b)).

Items before 98 use the paths of the monorepo. Since the split (item 98), the fitness app
is `apps/fitness/` and the benchmark `benchmarks/fitness-baseline/` in
[cloview-examples](https://github.com/echojustus/cloview-examples).

## Windows findings and the harness

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 1 | How to fix the ClojureCLR 1.12.6 `compare-and-set!` failure (boxed booleans compared by reference)? | Portable test-and-set with `swap-vals!` in the store and the WPF coalescer, plus a static-check rule against `compare-and-set!` with a literal | pin ClojureCLR master in CloViewHost (not on NuGet); per-host code | `store/try-acquire!`, `dispatch/coalescer` |
| 2 | ClojureDart's pubspec bridge loses its JSON quotes through `flutter.bat`. Keep the module `pubspec.yaml` files? | **Removed.** Apps run `flutter pub add http shared_preferences path_provider`, and the harness and bb do it for the examples. | keep the bridges and patch ClojureDart; ship a PowerShell shim for `flutter` | `verify/bb/cloview/tasks.clj` (`dart-packages`), README |
| 3 | Flutter 3.29.3 is too old for ClojureDart 0.9.20261002 (needs 3.35). Pin an older ClojureDart, or require a newer Flutter? | Require **Flutter ≥ 3.35** (3.47.x recommended). The harness checks and stops with instructions; `-FlutterUpgrade` is opt-in. | pin an older ClojureDart (loses fixes, untested); auto-upgrade Flutter (changes the user's SDK) | `min-flutter` in tasks.clj, harness step 06 |
| 4 | Hidden ClojureDart compile errors? | A `:cljd-diag` wrapper prints the full cause chain. The harness runs it after a failed compile. | upstream patch only | `verify/cljd/` |
| 5 | ClojureDart's host pass reads `.cljc` with `:clj` active. How to keep `:clj` branches out of the Dart build? | `:cljd` branch first everywhere, `:cljd nil` for top-level host-only forms, and a static-check rule | separate `.cljd` files per namespace | `verify/static_check.clj` |
| 6 | Console mojibake (GBK-decoded UTF-8) in the logs? | The harness sets UTF-8 for the console, Python and Java (`JAVA_TOOL_OPTIONS`), and restores everything at the end | leave as is; ask the user to run `chcp 65001` | `run-windows-checks.ps1` |
| 7 | JDK 21 was documented as required, but the run used JDK 11. | **JDK 11+ supported**, 21+ recommended | keep requiring 21 | README, VERIFY_ON_WINDOWS |
| 8 | Python is needed for the baseline's own checkers. Required? | **Optional.** Found via `py -3` / `python` / `python3`, each tried with `--version`; skipped with a note | make it required | harness step 07, `tasks.clj/python` |
| 9 | Line endings of the Windows-native files in the zip? | `.ps1`, `.cs` and `.csproj` are shipped with CRLF, as Git checks them out (`.gitattributes`); everything else LF | all LF (PowerShell accepts both) | packaging only |
| 9a | Windows Developer Mode is off: Flutter cannot create plugin symlinks, and `flutter pub add` exits 1 after adding the packages. | Detect it in step 01 and warn. If the packages are in `pubspec.yaml`, continue: compile and tests do not need the symlinks. Documented as a prerequisite for running the Windows app. | stop the Flutter steps; init without the `windows` platform for verification | `Initialize-FlutterProject`, `tasks.clj/flutter-prepare!` |
| 9b | How should the harness report step 07 when a part could not run (old Flutter, no CLR build, no Python)? | `partial`, listing `skipped_parts` | `ok` (hides gaps); `failed` (a gap is not a failure) | harness step 07 |
| 9c | The `try`/`finally` that restores the environment on Ctrl+C also meant one unexpected exception (a log file locked by an antivirus) would skip every later step, the summary and the zip. Found by review. | Each step is its own `try`/`catch` (the step is `failed` with the message, the run goes on); the file helpers retry once and never throw; the summary and zip are written from the `finally`, so a stopped run still produces them, marked `aborted` | drop the Ctrl+C restore; a `trap` (does not run on Ctrl+C) | `run-windows-checks.ps1` (`Register-StepException`, `Complete-Run`) |

## Portability rules adopted

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 10 | Cross-host string order (ClojureCLR compares by culture)? | `cloview.core.order`: ordinal comparison and a stable sort, used by LAN-sync merges, file lists, JSON keys and UI option lists | accept host-dependent order | `cloview.core.order` |
| 11 | The reference core has the same CLR hazards (culture sort of equipment tags, unstable `sort-by` on ties). Patch it? | **No.** It stays byte-identical, so the oracles test the reference itself. Hazards are documented, and the app re-sorts where it matters. | patch the copy (then it is no longer the reference) | `examples/fitness/README.md` |
| 12 | Number text on ClojureCLR (`(str 20.0)` is `"20"`; .NET formatting follows the culture)? | JSON writers use the invariant `"R"` format plus `.0`; UI text uses integer arithmetic | `format` with an explicit locale per host | `report.cljc`, `oracle.cljc`, `fitness.derive` |
| 13 | Culture-sensitive case changes (Turkish `I` → `ı` on ClojureCLR) for identifiers? | `cloview.core.text/ascii-lower` and `ascii-upper` for extensions, header and column names, and HTTP methods | `clojure.string` everywhere | `cloview.core.text` |
| 14 | ClojureCLR rejects type hints on struct-valued `let` locals. This was found by the WPF stand-ins in `fitness.wpf.native` and would have broken the fitness WPF app. | Fixed, and a static-check rule was added | — | `static_check.clj` (`clr-value-types`) |
| 15 | WPF's TabControl auto-selects its first tab outside the render pass, which dispatches an `:on-select` nobody made. | hostops selects the first tab inside the pass when `:selected` is nil or unknown (matches JavaFX and Flutter) | document that nil reports the first tab | `hostops/apply-tab-selection!` |
| 15a | Tabs and padded boxes broke Flutter's bounded-constraint rules: tab pages got an unbounded height, and a box around a stretching column inside a row an unbounded width. Found by review. | Canonical rule changes on all hosts: `:ui/tabs` grows in a column, and a `:ui/box` / `:ui/align` counts as its content. Flutter tab pages fill the height left by the tab bar. | add `:grow` by hand in the app (each app would hit it again) | `tree/effective-grow`, `adapters/tabs-adapter` |
| 15b | A node with both `:key` and `:test-id` lost its test-id on Flutter. | Both keys applied, identity outermost; tests build keys with the adapters' function | — | `adapters/with-identity` |
| 15c | A horizontal `:ui/scroll` grew in a column like a vertical one. The analytics panels sit in a vertical page scroll, where a flexing child is a Flutter layout error. | Canonical rule on all hosts: a horizontal scroll keeps its content's height in a column; it still grows in a row | `:grow false` by hand in the app | `tree/effective-grow` |

## Vocabulary and composites

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 16 | How should multi-select filter chips be expressed? | Two new canonical tags: `:ui/toggle` (toggle button / filter chip) and `:ui/wrap` (flow layout). A portable `chip-group` composite builds on them. | a `:ui/multi-select` canonical control; checkboxes in a column | `tree/v1-tags`, `components/chip-group` |
| 17 | What does a chip event carry? | **One event per toggle**, `(conj on-toggle id checked?)`. The benchmark's `catalog/filter-change` (a whole set) maps to it. | the recomputed set, which goes stale when two clicks land before a render | `components/chip-group`, event mapping in `examples/fitness/README.md` |
| 18 | Calendar cells: a new pressable-surface tag, or buttons? | Multi-line `:ui/button` cells whose text carries every value. `:ui/pressable` is deferred. | add `:ui/pressable` now (hostops branch, cljfx method, Flutter adapter) | `components/month-grid`; open question O3 |
| 19 | Where do generic composites live? | `cloview.core.components` (`chip-group`, `month-grid`), usable as function heads or as `:cloview/*` keywords after `register!` | in the fitness example only | — |
| 20 | Animated GIF on WPF (its `Image` does not animate)? | `cloview.wpf.gif`: frames decoded and composed per GIF disposal rules, swapped on a `DispatcherTimer` | the WpfAnimatedGif NuGet package (a dependency); `MediaElement` | `modules/wpf/src/cloview/wpf/gif.cljr` |
| 21 | Native views: how are events delivered? | As data in `:props :events`, dispatched by the renderer with its captured `dispatch!`. No core change. | a core extension passing a stable `emit` to `:create`/`:build` (open question O4) | `docs/NATIVE_CONTRACTS.md` D3 |

## Effects

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 22 | CSV/JSON parsing for imports? | Portable readers in pure `.cljc` (`cloview.fx.codec`), with number syntax validated before host parsing | host JSON libraries per platform | `cloview.fx.codec` |
| 23 | Storage results were not reported. The fitness app expected callbacks the runner ignored, so "Saving…" never finished. | `:storage/put` / `remove` take `:on-success` / `:on-failure`; `get` takes `:on-loaded` / `:on-failure` | — | `cloview.fx.storage` |
| 24 | How durable is desktop storage (EFFECT-001/002)? | Write a temp file, keep the previous file as `.bak`, replace atomically. An unreadable primary is preserved as `.corrupt-<ms>` and the app continues from the backup. If both are unreadable, the store refuses writes and touches nothing. Dart (shared_preferences) has no backup. | one file without a backup (Stage 2-1); a journal | `cloview.fx.storage` |
| 25 | `:file/pick` on Flutter (needs the `file_picker` plugin, which cannot be compiled here)? | **Deferred to Stage 3.** It reports `:unsupported`, and the app says "Choosing files is not available on this host yet". | write it blind | `cloview.flutter.effects`; capability map §4.3 |
| 26 | LAN sync network runners? | **Stage 3.** The protocol, codec, pairing code and LWW merge are kept, and the gap to the benchmark's optional LAN profile (invitation + AEAD, version vectors, bounded hashed media, active-session guard) is documented. No parity is claimed. | implement plain UDP/HTTP runners now, which the baseline says do not recreate the protocol | `cloview.fx.lan-sync`; capability map §4.2 |

## Fitness app scope and behaviour

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 27 | Port the reference core, or mount it? | Copied byte-identically into `examples/fitness/shared/src/clogem/fitness/` (hashes equal the manifest). Tests ported to `.cljc` with two mechanical edits. | reference the baseline folder directly from `deps.edn` (it is frozen and its tests are `.clj`) | `examples/fitness/README.md` |
| 28 | Demo data? | The demo document is embedded as `fitness.demo/document`; a test checks it equals `fixtures/demo-document.edn`. | read the fixture at run time (needs a file path on every host) | `fitness.demo` |
| 29 | Which screens in Stage 2-2? | Five tabs: catalog (search, chips, list, detail, media), plan (items, training), log (records, HR import), analytics (body, radar, comparison, calories), calendar. **Not built:** exercise editor, profile editor, plan date/name and item editors, export, free date-range input. | build editors now (more surface, less verification) | capability map UI-001/002/005, DOM-010 |
| 30 | Default body style? | **2D** (implemented, more accessible). Choosing 3D shows the 2D map and a note. | 3D first, the reference default (open question O6) | `fitness.app/initial-db` |
| 31 | Radar grouping (not in the package)? | The proposed partition. Trapezius, erector spinae and iliopsoas are unconfirmed. A previous-period overlay was added. | (open questions O1, O11) | `fitness.derive/radar-axes` |
| 32 | Calendar kcal? | Joined from `daily-calories` over the visible cells; unknown energy shows "? kcal", never 0 | no kcal in the calendar (open question O10) | `fitness.derive/calendar-kcal` |
| 33 | MP4? | Routed to a `:fitness/media-player` native contract (props built in shared code). No renderer yet, so every host shows an explicit placeholder in the fixed viewport. | `:ui/image` for everything (broken for video) | `fitness.derive/media-props`; NATIVE_CONTRACTS §8 |
| 34 | Managed media copy name? | `<app-dir>/media/<exercise-id>-<epoch-ms>.<ext>` | content-addressed SHA-256 names, as the contract draft suggested (dedup; needs hashing on every host) | `:media/app-dir` handler |
| 35 | Interrupted training after a reload (DOM-004)? | The open interval of unknown length is dropped and recorded as `:interrupted-at`; the session shows as interrupted; Resume opens a fresh interval | close it at reload time (counts the downtime as activity); discard the whole session | `fitness.events/recover-active` |
| 36 | HR import during active training (EFFECT-003)? | The commit is refused with a message; the preview stays so the user can confirm afterwards. The merge always applies to the current document. A late result of an earlier file pick is ignored. | allow the merge (samples don't touch the active record) | `:hr/confirm`, `:hr/read` |
| 37 | Migration of loaded documents (DOM-010)? | `migrate-document` runs on every load (it is idempotent) | migrate only the demo document | `:doc/loaded` |
| 38 | Memoization evidence (ARCH-004)? | `memo-last` on db slices, plus call counters asserted by the app test and both GUI smokes. No timing claims. | measure timings now | `fitness.derive` |

## Verification approach

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 39 | How to exercise WPF code without Windows? | A behavioural WPF stand-in library in the session scratchpad (not shipped): same types, members and event semantics, real GIF parsing, no rendering. The unchanged smokes run on ClojureCLR 1.12.6 and master. It found items 14 and 15. | trust the Windows run only | `WINDOWS_VERIFICATION_REPORT.md` §3.2 |
| 40 | ClojureCLR version for sandbox checks? | Built from source at the `clojure-1.12.6` tag (the NuGet version CloViewHost uses) and from master; every CLR check runs on both | master only | — |

## Open questions for the next stage

These need the project owner's input or the original application. Details are in
cloview-examples'
[`NATIVE_CONTRACTS.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/NATIVE_CONTRACTS.md)
§9.

1. Radar muscle → axis partition (O1); keep the previous-period overlay (O11)?
2. Volume comparison: hide all-zero muscle rows (O2)?
3. Add `:ui/pressable` as a canonical tag (O3)? It would enable shaded calendar cells and
   pressable comparison columns.
4. Native events: keep events-as-data, or add a core `emit` for `:ui/native` (O4)?
5. Should the muscle selection clear on a range change (O5)? Default body style 2D or 3D
   (O6)?
6. Flutter video on Windows: `video_player_win` or `media_kit` (O7)? GPU 3D vs a
   software painter fallback (O8)?
7. Overlap-absorbed days: `:known` 0 kcal or `:none` (O9)? Calendar kcal join (O10)?
8. Distribution for ARCH-002: git deps with `:deps/root`, Clojars/NuGet releases, or
   both?
9. Which optional integrations (LAN sync, Health Connect) are in scope for the first
   release?
10. Add an `:on-error` event to `:ui/image`? EXT-001 asks for a visible missing-media
    state; today a missing file leaves the viewport empty.

---

<a id="stage-2-2b"></a>
## Stage 2-2b

### The Windows run of 2026-10-25

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 41 | The note that came with the upload said everything passed except a skipped step 06 ("Flutter still 3.29.3"). The files show Flutter 3.47.8, step 06 passed, and step 07 failed in its Dart test compile. Which is authoritative? | **The files.** The report records the discrepancy, and the fix targets the real failure. | trust the note and treat Flutter as untested | WINDOWS_VERIFICATION_REPORT.md §7 |
| 42 | How to fix `with-redefs` in the ported reference test, which ClojureDart does not have? | Wrap that one assertion block in `#?(:cljd nil :default …)`, so JVM and CLR still assert the call count. This is the port's third mechanical change; the README and SHA are updated. | delete the assertion; rewrite it without `with-redefs` (changes the reference test's logic) | `examples/fitness/shared/test/clogem/fitness/domain_test.cljc` |
| 43 | How to catch this class of error without a Dart toolchain? | **A ClojureDart coverage rule.** Every symbol in Dart-compiled files must resolve to a public var of the pinned release (extracted from its sources), a special form, a local or Dart interop. | rely on the next Windows run; maintain a hand-written deny-list | `verify/static_check.clj`, `verify/cljd/` |

### Naming and project structure

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 44 | Target names: runtime (`jvm`/`clr`/`dart`), toolkit (`javafx`/`wpf`/`flutter`), or a `targets/` folder? | **Toolkit names** for UI modules, example folders, entry namespaces and bb tasks. A user picks a GUI toolkit, and "clr" does not say WPF (WinForms or Avalonia would also be CLR). Runtime names stay where they name the runtime: logic test suites, oracle envelopes, harness step ids (`02-jvm-unit-tests`, `04-clr-…`) and the `-SkipJvm`/`-SkipClr` switches, which got the aliases `-SkipJavaFX`/`-SkipWpf`. | runtime names everywhere (the Stage 2-2 layout); `targets/javafx` (one more folder level) | `modules/{javafx,wpf}`, `examples/*/{javafx,wpf}`, `bb.edn`, `deps.edn` |
| 45 | The CLR interop namespace `cloview.clr.wpf`? | Renamed `cloview.wpf.interop`; the alias `wpf` is unchanged in callers. | keep the old name | `modules/wpf/src/cloview/wpf/interop.cljr` |
| 46 | Why three target folders per example, and do users need them all? | Kept and documented: each holds a short entry point (under 70 lines in the examples and templates; the WPF files also carry the smoke and test entry points) plus optional `:ui/native` renderers. In the fitness example about 90% of the code is in `shared/` (2,622 lines against 220–280 per target, smokes and tests excluded). Apps generated by `bb init` start with only their primary target. | one example per target; generated examples | README "Start a project" |
| 47 | Logic modes: the brief called them both "Mode A/B" and "Mode 1/2". Which names? | **Mode A (same-host) / Mode B (decoupled backend)** in prose, and `--logic same-host` / `--logic backend` on the command line | Mode 1/2 | README, POLYGLOT_BACKEND_ARCHITECTURE.md |

### The initializer (`bb init`)

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 48 | Where do the generator and templates live, and in what form? | `starter/src/cloview/starter.clj`, plain Clojure that runs under babashka and on the JVM (`clojure -M:init`). Templates are **real sources** under `starter/templates/`, so the static check and the coverage rule check them. | string templates with `{{…}}` (not checkable); a separate repository | `starter/` |
| 49 | Default target and mode? | `javafx` (runs on Windows, macOS and Linux with only a JDK) and `same-host` | `wpf` (Windows only); asking interactively (blocks scripted use) | `cloview.starter/normalize-options` |
| 50 | Should generated projects reference cloview's tasks and WPF host, or own copies? | **Own copies.** The bb tasks are in `bb/cloview/starter/tasks.clj` and the host is `wpf/host/<AppName>.csproj`, both editable, and nothing breaks when cloview changes. | reference the checkout's files (updates flow in, but local edits are impossible) | the templates |
| 51 | How do generated deps point at cloview? | `:local/root` to the checkout, recorded in `cloview.edn :cloview :root`. The path is relative when it climbs at most three folders, absolute otherwise. Switching to git deps is documented. | git coordinates now (the repository is not public yet); Clojars/NuGet (no releases) | `cloview.starter/link-path` |
| 52 | Mode B with the Flutter target? | **Refused** with a message, by both `bb init` and `bb target:add`, until the bridge has a Dart process transport | generate it anyway (it cannot run) | `normalize-options`, `add-target!` |
| 53 | What may `bb target:add` touch? | The new target folder (and `shared/deps-clr.edn` for wpf when absent), and in `cloview.edn` only the `:targets :enabled` vector, edited in place so comments and hand-added keys stay. Every file is checked before the first write, so a conflict writes nothing; a vector it cannot edit safely is left alone and the exact change is printed. | regenerate the whole project; rewrite `cloview.edn` (the first version: comments and added `:modules` were lost) | `add-target!`, `set-enabled-text` |
| 54 | The template app? | Mode A: a small notes app that shows views, state, events and effects (storage). Mode B: an upper-case call to the backend, with backend status. Each has headless tests. WPF has a minimal off-screen smoke. | a counter (shows no effects); the Greeter (shows no effects) | `starter/templates/*/` |

### Backends (Mode B)

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 55 | Packaging of the bridge? | **An opt-in `modules/bridge`** in this repository | inside `modules/fx` (every app would load process code); a separate repository (versioning would split a protocol that must evolve with the effects) | POLYGLOT_BACKEND_ARCHITECTURE.md §2 |
| 56 | Wire protocol? | JSON-RPC 2.0, one message per line on stdio, with reserved `$/` methods and the bridge's codes in -32001..-32006; `tcp` reserved | gRPC (codegen per language); HTTP on localhost (ports, firewalls); MessagePack (not readable) | `cloview.bridge.protocol` |
| 57 | What does a backend do with requests in flight at `$/shutdown`? | **Finish them, answer them, then answer the shutdown.** Long ones end early with -32001 "backend stopping". All three reference backends do this. Found when a generated C# project lost an answer. | exit at once (answers lost; the GUI reports `:not-running`) | protocol rule 4; `backends/*` |
| 58 | Calls after a backend failed to start? | They fail with the start failure's own reason when it is `:unsupported` or `:manifest`. Every other cause, including a program that is not installed (the launcher's internal `:spawn`), gives `:not-running` with "backend could not start: …" in `:error`. A start failure is not restarted. | always `:crashed` / `:not-running` (the cause is lost); pass every internal reason through (apps that `case` on the documented reasons would throw) | `cloview.bridge.client/start-failure-reasons` |
| 59 | Restart policy defaults? | `on-crash`, at most 3 restarts in 60 s, backoff 0.5 s·2ⁿ capped at 10 s; a manual `:backend/start` revives | never restart; unlimited restarts | `cloview.bridge.manifest/defaults` |

### Licensing

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 60 | Module and composite names? | `modules/licensing` (`modules/license` reads like a LICENSE file). The dialog is the composite `:cloview/license-modal`, because `:ui/*` is reserved for canonical, host-rendered tags. | `modules/license`; `:ui/license-modal` (as in the brief) | `cloview.licensing` |
| 61 | Signature algorithm? | **ES256** (ECDSA P-256), built into JDK 11+ and every .NET | Ed25519 (JDK 15+, no built-in support in .NET 10); RSA (large keys and tokens); HMAC (the secret would ship in the app, so anyone could mint keys) | LICENSING.md §4 |
| 62 | Token format? | `CLV1.<base64url JSON>.<base64url r‖s>`, with the prefix signed. Canonical JSON (ordinal keys) makes the bytes identical on every host. | JWT (alg-confusion pitfalls, header parsing); EDN payloads (no external tooling) | `cloview.licensing.token` |
| 63 | Device binding? | Optional, per key. The device code is HMAC(product, machine-id), so it is per product and not reversible. Unbound keys are recommended by default. | always bind (support cost); hardware fingerprints (forbidden on Apple platforms, privacy) | LICENSING.md §5 |
| 64 | Trial storage and tampering? | Counts sealed with HMAC (keyed by the app secret and the machine id) in the app's storage, in one accepted shape (see 83). Fails closed, and a failed check is persisted as a sealed `void-state`, so later saves cannot launder edited counts. | plain counts; an OS keychain (Stage 3 option); a server | `cloview.licensing.trial` |
| 65 | A click before the stored licence data is read? | Gate reason `:loading`: the event is held (up to 16, in order) and run as soon as the data is read, even if the user closed the dialog | deny silently; allow and count (overwrites the stored counts); keep only the last event (the first version) | `gate/decide`, `:licensing/loaded` |
| 66 | A valid key that does not include the prompted feature? | Saved (it may unlock other features). The dialog stays open with an explanation, and the refused event is not re-run. | reject the key; close the dialog silently | `:licensing/activate` |
| 67 | Expiry semantics? | `expires` is the last valid day, in UTC. Clock rollback is documented as unpreventable offline, with perpetual keys as the alternative. | expiry instants with time zones; online time checks | LICENSING.md §3, §10 |
| 68 | Store builds? | Licence keys only for direct distribution and the Microsoft Store. Its policy lets non-game PC apps use "a secure third-party purchase API"; keys sold through your own shop fit that on our reading (the policy does not mention keys). Apple and Google stores need their in-app purchase (Stage 3 provider). | one build for all channels (rejected by those stores' rules) | LICENSING.md §8 |
| 69 | Where does the seller's key tool live? | `modules/licensing/tool/` (never on an app's classpath); `.gitignore` excludes `*.private.edn` and `license-keys/` | inside `src/` | `cloview.licensing.issue` |
| 70 | ClojureDart? | `install!` throws `{:reason :unsupported}` until the Dart crypto exists | stub crypto that accepts everything (unsafe) | `cloview.licensing` |

### R plots

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 71 | Integration approach? | **A2: a long-lived `Rscript` worker as a bridge backend.** Startup is paid once; it is a separate program for the GPL; crashes are isolated; it works on every process-capable host. | A1, Rscript per plot (~0.5–0.7 s each); B1, embedding libR (crash and GPL coupling, per-host bindings); B2, Rserve (Windows limits); B3, webR (no Wasm in JavaFX's WebView) | R_VISUALIZATION_INTEGRATION.md §2–3 |
| 72 | Requests for a plot that is still rendering? | Coalesced to the latest request: one in flight per plot slot, and the previous image stays on screen | cancel and resend (R would still draw the cancelled request) | `cloview.bridge.rplot` |
| 73 | Pixel density? | Logical size times `:scale` (default 2), with ragg `res = 96·scale` | 1x (blurry on HiDPI); per-host DPI queries (more host code) | `backend.R` |
| 74 | Could R be tested in the sandbox? | Yes: R 4.3.3 and the plotting packages were installed from the Ubuntu archive, so `backend.R` is tested for real through the launcher on the JVM and the CLR | ship it untested | R_VISUALIZATION_INTEGRATION.md §8 |
| 74a | The R worker's encoding under a non-UTF-8 locale (found by an edge-case probe: the first non-ASCII character ended the session) | Locale-independent I/O: read bytes marked UTF-8, write them unconverted, and prefer a UTF-8 `LC_CTYPE`. A regression test sends non-ASCII text through the launcher. The Dart reference backend also pins `stdout.encoding = utf8`. | require a UTF-8 locale in the manifest `env` (not portable: `C.UTF-8` is missing on older macOS) | `backend.R`, `rplot_test.cljc` |

### Documentation

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 75 | Where does the October feasibility study go? | **Archived verbatim** in `docs/research/01`, with its 55 sources restored as footnotes at the passages it cited, and a short "Since the study" appendix. The text is the chat's first deep-research report, found after the owner pointed to it; a check confirms the archived text equals the report once the markers are removed. The first version of the archive was a labelled reconstruction, because the session had not found the report. | paraphrase from memory (risk of invented citations) | `docs/research/01_FEASIBILITY_STUDY_OCT_2026.md` |
| 76 | Positioning numbers in the README? | Only sourced or measured ones: build size and RAM of an empty app from a public CI benchmark (same OS for both, and both of its memory measures), Electron runtime zip sizes from its release assets; all read 2026-10-25 | rounded marketing numbers | README "The problem" |
| 77 | Store packaging? | Documented as **Future / Non-Priority for V1**, with the current store rules | build packaging tasks now | ROADMAP_AND_EXTENSIONS.md §4 |

### Fixes from the pre-packaging reviews

Independent reviews were run on the new code (one per area, plus one that read the
ClojureDart paths against the pinned ClojureDart sources, since no Dart toolchain is
available here). Each fix comes with a test.

| # | Finding | Fix | Alternatives | Where |
|---|---|---|---|---|
| 78 | ClojureDart's `str/split` keeps trailing empty strings, so a key such as `CLV1.x.y.` would split differently than on the JVM and the CLR | Licence keys are matched with one regular expression on every host. Tests cover a trailing dot, an empty signature and a double dot. | `str/split` plus a count check | `cloview.licensing.token/parse` |
| 79 | The default backend was the first key of the registry map, but ClojureDart has no array maps (and JVM maps of more than 8 entries hash too) | The install order is kept in a vector. A test with 12 manifests checks that the first one is the default. | require `:backend` in every effect | `cloview.bridge` |
| 80 | Timers outlived their purpose: `stop!`'s kill check was never cancelled after an orderly exit, and a stop during a crash's restart backoff still restarted the backend | The kill check belongs to one process and is cancelled when that process exits. `stop!` cancels a pending restart, and a crashed backend that is stopped reports `:stopped`. Tests count every timer. | leave timers to expire (harmless for the kill check, but a stop that does not stop is a bug) | `cloview.bridge/stop!`, `cloview.bridge.client/stop!` |
| 81 | A stored licence key that stopped verifying was dropped from the db, so the next save (any trial use) deleted it, against LICENSING.md §3 | `:token` is the key kept on this machine, verified or not; saves write it back unchanged. Only activate and deactivate change it. A key whose `kid` an app version lacks comes back with an update that has it (tested). | delete keys that fail (an update could never restore them) | `:licensing/loaded`, `persisted` |
| 82 | The wrapper took the licensing slice from the db the handler returned: a handler returning a fresh db dropped it, saved a void trial and no key, and every gate then answered `:loading` | The slice is taken from the db the handler received and put back into whatever db it returns. A missing slice throws an explanation (no `[:licensing/init]`, a `path` interceptor, or a handler that dropped it). | trust handlers to keep it; treat a missing slice as `:loading` (a silent hang) | `wrap-gated-event` |
| 83 | The trial seal was not injective: the JSON writer encodes `:a` and `"a"` alike, so `"pro/x"`→`:pro/x` (0 uses) or `:void`→`"void"` (a fresh trial) kept a valid MAC; a stored value without `:trial` counted as a first run | Only the exact written shape is accepted (`:v :uses :mac` and an optional `:void true`, string ids, non-negative integer counts). Only "nothing stored" is a first run; anything else without a well-formed `:trial` is `:tampered`. | MAC a type-tagged encoding (the shape check is simpler and also rejects junk) | `cloview.licensing.trial/well-formed?`, `stored-trial` |
| 84 | Activating a key while the stored state was being read wrote a void trial, and the read then replaced the new key with the stored one | Saves wait for the read. `:licensing/activate` and `:licensing/deactivate` are held like gated events and run after it. | disable the Unlock button while reading (programmatic dispatches would still race) | `save-fx`, `hold`, `replay` |
| 85 | A failed save (read-only storage) failed open: every use ran, nothing was counted, and a restart read the old count again | The session records `:save-failed?`; trial gates then refuse with the new reason `:storage-failed`, and the badge says "Free uses unavailable". A licence still unlocks. | ignore save errors; count in memory only (still endless across restarts) | `gate/decide`, `:licensing/save-failed` |
| 86 | The seller tool ignored unknown options (`--expire` issued a perpetual key, `--device-code` an unbound one) and accepted `2027-02-30` and a product with trailing spaces; keygen wrote the key with umask permissions before tightening them | Each command takes only its own options; duplicates and stray arguments are refused (exit 2). Dates must be real (strict ISO parsing). Products must be trimmed. Key files and the folders keygen creates are owner-only from creation on POSIX. | warn and continue | `cloview.licensing.issue` |
| 87 | A 2.7 KB fake key with 1000 nested arrays ended a ClojureCLR process with a stack overflow (uncatchable on .NET) before the signature check | The JSON reader limits nesting (`:max-depth`, default 128; ClojureCLR overflows a 1 MB thread between 600 and 800 levels). Licence payloads allow 4 levels and keys 4096 characters. The bridge's line decoder gets the same default. | an iterative parser (more code for a limit no real message reaches) | `cloview.fx.codec/read-json` |
| 88 | Pasted keys: `\s` stripped NBSP and U+2028 on .NET but not on the JVM, and 16 spellings of the signature's last character verified | Characters outside the key alphabet are removed by one explicit class on every host, and both parts must be canonical base64url. ECDSA's `(r, n−s)` twin remains, so licences are identified by the payload `id`, never the token text (documented). | reject high-s signatures (keys signed before the change would fail) | `cloview.licensing.token/normalize`, `parse` |
| 89 | The R worker exited on a `plot/render` whose `plot` was not a string, and a plots file that printed while loading corrupted the `$/initialize` reply (startup timeout, restarts, then crashed for good) | Parameters are checked (-32602), an unexpected error inside a request is answered (-32603) instead of ending the worker, and output while plots load goes to stderr. Both cases run against real R in the test. | trust `:r/plot` to validate (a direct `:backend/call` does not) | `backend.R` |
| 90 | A generated JavaFX Mode B app could not find `backend/backend-manifest.json` under `bb dev`, which runs it from `javafx/` | JavaFX reads `-Dcloview.app.dir` (the generated `:run` alias passes `..`), else the working directory or its parent. WPF reads `CLOVIEW_APP_DIR`, else the executable's folder, the working directory or its parent, then the folders above the executable. Packaged apps set the property or variable to the install folder (for example jpackage `--java-options -Dcloview.app.dir=$APPDIR`). | require starting from the project root | the Mode B templates |
| 91 | Flutter's `dart` is `dart.bat` on Windows, which neither the JVM nor .NET starts without a shell | The generated manifest's `windows` command is `cmd /c dart …` | require a `dart compile exe` build for development too | `cloview.starter/backend-manifest` |
| 92 | Titles and names that break generated projects: `"` or `\` in a title (written into Clojure strings, JSON and comments), and names such as `java` or `cloview` that clash with existing namespaces | Titles with `"`, `\` or control characters are refused and others trimmed; the README escapes `& < >`. Ten reserved first segments are refused: java, javax, jdk, sun, clojure, cljd, cljs, cloview, dart, flutter. A path with spaces now works under babashka (no `.toURI` on its unescaped resource URLs), and the starter tests no longer assume POSIX paths. | escape titles per file type (more code for little gain) | `cloview.starter/normalize-options` |

### Licence

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 93 | The project's licence (open question 14, settled with the owner) | **EPL-2.0**, the official text in `LICENSE`. It is the licence family of Clojure and ClojureCLR, lets apps built with cloview be closed-source, keeps changes to cloview's own files open when distributed, and grants patents. *Superseded in part on 2026-10-25 by items 99–101: GPL-2.0-or-later was added as a Secondary License, the fitness app and its benchmark moved to cloview-examples under MIT, and the exclusion notes recorded here at first were withdrawn.* | MIT or Apache-2.0 (permissive: improvements to cloview may stay closed); EPL-2.0 with `GPL-2.0-or-later WITH Classpath-exception-2.0` as Secondary License (would let GPL projects use cloview) | `LICENSE`, README "License" |

### Repository split and licences (2026-10-25)

The owner's brief set the split, both licences, the copyright line and the removal of the
exclusion notes. The items record how they were carried out.

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 98 | How to split the monorepo into cloview-core and cloview-examples | **Two repositories, cloned side by side.** cloview-core keeps the modules, the initializer, `examples/greeter` (the smoke app of CI and the harness), verification, ops and the framework docs; namespaces stay `cloview.*`, and references to the repository say `cloview-core` (`io.github.echojustus/cloview-core`). cloview-examples gets the fitness app as `apps/fitness/` (a repository called examples needs no second `examples/` level, and `apps/` leaves room for more), `benchmarks/fitness-baseline/`, `FITNESS_CAPABILITY_MAP.md` and `NATIVE_CONTRACTS.md`. The apps reach the modules through `:local/root` paths into `../cloview-core`, and the examples' CI checks cloview-core out next to itself (input `core_ref`). Tooling that is code is reused, not copied: `static_check.clj` takes `--core`/`--app` (app files are checked, core files only feed the index), and `cloview.tasks` takes arguments (`wpf-sync!` folders, `wpf-build!` project and MSBuild properties, `flutter-prepare!` a spec map). The examples' Windows harness is a new script around a verbatim copy of the core harness's helpers, which three Windows runs have proven; the WPF host is built from a copy of the core template under `target/`, so the examples never write into the core checkout. | git dependencies on a pinned `:git/sha` (no commit exists before the first push; switch once cloview-core is tagged); a git submodule (same effect, heavier for contributors); one PowerShell helper module dot-sourced by both harnesses (a restructuring of the core harness that no Windows machine could test here) | both READMEs; cloview-examples `deps.edn`, `bb.edn`, `verify/` |
| 99 | How to make cloview-core EPL-2.0 with GPL-2.0-or-later as a Secondary License, legally and not cosmetically | `LICENSE` is the verbatim EPL-2.0 text with **Exhibit A filled in**: "GNU General Public License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any later version", which is Leiningen's default wording without its Classpath Exception. The brief's sample clause "including exceptions and additional permissions identified by the initial Contributor" was left out: it is the EPL's definition of a Secondary License, and in a notice it would leave open which exceptions apply (none do). The line `Copyright (c) 2026 EchoJustus (Jiapeng Ji)` heads the file; below it the text is byte-identical to the official one apart from the filled placeholder. The notice also reaches the sources: the framework's 81 Clojure, Python and PowerShell files carry `SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later`. Exhibit A allows the notice in "a LICENSE file in a relevant directory"; the per-file line makes machines and humans agree. Files that `bb init` copies into user projects get no per-file line (open question 15). Done before any outside contribution, so nobody else's consent was needed. | adding the GNU Classpath Exception, as Leiningen does (the brief asked for plain GPL-2.0-or-later; but see open question 16: without some linking permission, the GPL route does not cover programs that ship the EPL-1.0 Clojure runtimes); shipping the GPL-2.0 text too, as Jakarta EE does (not required: whoever distributes under the GPL passes its text on) | `LICENSE`, README "License", `CLAUDE.md` |
| 100 | cloview-examples' licence | **MIT**, the owner's text, with exactly one copyright line, plus a per-directory override convention: one app folder may ship its own `LICENSE` with a one-line entry under "Exceptions" in that README. The reasons are recorded once, in cloview-examples' README ("Why MIT"), so the choice is not reverted: examples exist to be copied; the patent-relevant code is in cloview-core under EPL-2.0's patent grant; the GPL would not stop resale and would deter enterprise developers. | EPL-2.0 like the core (raises the cost of copying); GPL (counterproductive, as above) | cloview-examples `LICENSE`, README "License" |
| 101 | How to withdraw the old exclusion notes (the owner is the sole author and rights holder of Clogem Fitness 0.3.0) | **Deleted, not reworded**, so no file says that anything is outside the licence: the core README's two bullets, the paragraph in `benchmarks/README.md`, the clause in `apps/fitness/README.md` (its SHA-256 tables, the baseline-versus-port hashes and the three port edits stay), and the licence paragraph of `README_FIRST.md`. That is the one edit to the frozen package: its manifest row was updated, and both hashes are recorded in `benchmarks/README.md`; the sentence on auditing dependency licences stays. `CLAIM_BOUNDARIES.md` is untouched (it scopes evidence; it is not a licence). No file carries a rights-reserved line. | keep the package outside the licence (the ambiguity the owner wanted gone); quote the old wording in the edit record (it would bring the ambiguity back) | cloview-examples `benchmarks/README.md` |

### Development workflow for Stage 3

| # | Question | Chosen | Alternatives | To change |
|---|---|---|---|---|
| 94 | Who runs builds and checks once Claude Code drives the repository? | **The cheapest reliable executor:** CI on GitHub-hosted runners first (free for public repositories), then a local operator agent (Codex CLI with a local model through Ollama, or with ChatGPT) for job tickets that need a real machine, then the owner for visual checks. Claude Code designs, codes, analyses and writes the tickets. | everything in Claude Code (tokens spent on waiting and logs); everything by hand (slow round trips) | DEVELOPMENT_WORKFLOW.md |
| 95 | How do results come back without flooding Claude Code's context? | Structured summaries first: `00-summary.json`, the CI job summary (`ops/tools/ci_summary.py`), and `ops/results/<id>/result.json`. The recorder (`ops/tools/result.py`) writes exit codes, timings, logs and failure excerpts itself and redacts user names in home paths. Logs are opened only for failing steps. | free-form operator reports (unverifiable); whole logs (20–60k tokens a round) | `ops/` |
| 96 | Instructions for the two kinds of agents? | `CLAUDE.md` for Claude Code (checks, portability rules, context rules), `AGENTS.md` for operators (run tickets exactly, never edit tracked files, never fix, stop rules). Codex reads `AGENTS.md` itself. | one shared file (the roles conflict: one writes code, the other must not) | `CLAUDE.md`, `AGENTS.md` |
| 97 | CI scope before the first push? | `ci.yml` runs the Linux checks on pushes and pull requests, with an experimental ClojureCLR job; `windows-harness.yml` runs the harness on demand on `windows-2025`. Neither could run here, so making them green is Stage 3's first task. No self-hosted runner: GitHub advises against them for public repositories. | no CI until Stage 3; the harness on every push from day one (untested on hosted Windows) | `.github/workflows/` |

### Open questions added in Stage 2-2b

11. Publish under Clojars/NuGet/pub.dev, or git dependencies only, for the first release?
    This decides what `bb init` writes instead of `:local/root`.
12. Should store builds get purchase providers (Microsoft Store, StoreKit, Play Billing)
    behind `wrap-gated-event` in Stage 3, or should the licensing module stay
    direct-distribution only?
13. Is a Linux/macOS CLR target (Avalonia 12) wanted, or do JavaFX and Flutter cover
    those platforms for the target users?
14. *Settled:* EPL-2.0 (item 93), with GPL-2.0-or-later as a Secondary License (item 99).
15. **The files `bb init` copies into a user's project** (`starter/templates/`,
    `modules/wpf/host-template/`, `modules/bridge/backends/`) are under cloview-core's
    licence. A generated `core.cljc` or `Program.cs` that the user then edits is, in
    EPL-2.0's terms, a Modified Work: a closed-source app that ships it must offer those
    files' source under the EPL. That weakens the closed-source route for exactly the
    users `bb init` serves. Recommendation: also offer these three folders under a
    permissive licence such as MIT-0 or 0BSD, through the documented-override pattern
    (a `LICENSE` in each folder and one line in the README), so generated code belongs
    entirely to the user. The licence choice is the owner's; nothing was changed, and
    the README's closed-source route states the caveat.
16. **A linking permission for the GPL route?** Clojure, ClojureCLR and ClojureDart are
    under EPL-1.0, which the FSF considers incompatible with the GPL. A program that
    ships cloview-core under the GPL together with one of those runtimes therefore
    needs permission to link cloview-core with it, and the plain GPL-2.0-or-later notice
    the brief specified gives none. Recommendation: adopt Leiningen's wording, which
    adds the GNU Classpath Exception to the Exhibit A notice (SPDX
    `EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0`), and update the README
    and the SPDX lines to match. Adding it later only adds permission, so nothing
    published now blocks it. The owner decides; the README's GPL route states the gap.
