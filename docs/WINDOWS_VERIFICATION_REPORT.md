# Windows verification report: the Stage 2-1, 2-2 and 2-2b runs

This report covers the three `cloview-windows-verification-results.zip` uploads produced
by `verify\run-windows-checks.ps1`:

- **§1–§6:** the run of 2026-10-24 (Stage 2-1 harness), and the Stage 2-2 fixes.
- **§7:** the first run of 2026-10-25 (Stage 2-2 tree), and the Stage 2-2b fixes.
- **§8:** the second run of 2026-10-25 (Stage 2-2b tree): every step passed, on all
  three hosts, with no fix needed.

Each part lists what passed and what failed, the root cause of each failure, the fix and
how the fix was checked. How to run the harness: [`VERIFY_ON_WINDOWS.md`](../VERIFY_ON_WINDOWS.md).

> **After the repository split** (decision 98 in STAGE_2_2_DECISIONS.md), the fitness app
> and its benchmark, step 07 of these runs, are in
> [cloview-examples](https://github.com/echojustus/cloview-examples), which has its own
> harness (steps 02–05 there); this repository's harness keeps steps 01–06. Paths below
> are those of the monorepo at the time.

## 1. The run

| Item | Value (from `01-env-check.json`) |
|---|---|
| OS / shell | Windows 11 Home, build 26100; Windows PowerShell 5.1 |
| Repository | on a local drive (no UNC/WSL warning) |
| JDK | OpenJDK 11.0.32.1 |
| Clojure CLI | `clj` is an executable (`clj_kind: app`) |
| .NET | SDKs 9 and 10; WindowsDesktop runtimes 5, 6, 8, 9, 10, so `net10.0-windows` was selected |
| Flutter | 3.29.3 (Dart 3.7.2) |
| cljr | not installed (optional, recorded as `not-installed`) |
| Duration | about 5.5 minutes; all 10 expected files present |

## 2. Diagnostic summary

| Step | Result | What the files show | Root cause | Fix (§3) |
|---|---|---|---|---|
| 01 env check | **passed, with a warning** | All tools found. Flutter's version line is logged as `Flutter 3.29.3 鈥?channel stable`. | Mojibake: the console decoded UTF-8 output with the OEM code page. The bytes of `•` decode as GBK. | §3.4: UTF-8 console encoding |
| 02 JVM unit tests + static check | **passed** | 65 tests, 265 assertions, 0 failures; static check exit 0 | — | — |
| 03 JavaFX smoke | **passed** | 25/25 checks (cljfx 1.10.363 from Clojars, JDK 11) | — | — |
| 04 CLR build | **passed** | `dotnet build` of CloViewHost for `net10.0-windows`, 24 sources staged | — | — |
| 04 CLR unit tests | **failed** | 55 tests: 162 pass, **43 fail, 1 error**. `tree` and `style` pass. `store` has 15 failures, `reconcile` 11, `fx` 17 plus 1 error. | ClojureCLR 1.12.6 `compare-and-set!` compares the expected value by reference. The store's run-to-completion flag was never acquired, so queued events were never processed. | §3.1 |
| 05 WPF smoke | **failed** | 13/27 checks. `events/no-echo` saw no event at all (`[]`), `render/coalesced-into-one-pass` counted 0 passes, and every patch check kept its initial value. | Same root cause: neither the store queue nor the WPF render coalescer (same CAS pattern) ever ran. | §3.1 |
| 06 Flutter: app compile | **failed** | `Compilation error … compiling namespace cloview.flutter.app`. The reported faulty form is only the `ns` of `greeter.flutter.main`. | Flutter 3.29.3 is older than ClojureDart 0.9.20261002 requires (≥ 3.35). The real error was hidden by cljd's report. | §3.3 (b), (c) |
| 06 Flutter: effects compile | **failed** | `cloview_flutter from unknown source "path:.clojuredart/deps/…", version solving failed`, then a compile error in `cloview.fx.file` | The ClojureDart pubspec bridge breaks through `flutter.bat`, so `http`, `shared_preferences` and `path_provider` were never added. | §3.3 (a) |
| 06 Flutter: widget tests | **failed** | the same two errors | (a) and (b) | §3.3 |

In short, JVM and JavaFX already worked. Every CLR and WPF failure has one cause, a
ClojureCLR runtime quirk. Flutter needs a newer SDK on the machine plus two harness fixes.

## 3. Root causes and fixes

### 3.1 ClojureCLR 1.12.6: `compare-and-set!` with a boxed expected value

**Evidence.**

- `04-clr-unit-tests.json`: failures in `dispatch-runs-to-completion-in-order`, `errors`,
  `coeffects-and-interceptors`, `controlled-checkbox`, `controlled-text-guard-and-caret`
  and others.
- `04-clr-build-and-run.log`, `file-effects`: an `ArgumentNullException` in
  `clojure.string/lower-case`. Nothing had been listed, because the effect's result event
  was never processed.
- `05-clr-wpf-smoke.json`: no events were dispatched and no render pass happened.

**Cause.** `cloview.core.store` guarded its run-to-completion queue with
`(compare-and-set! draining? false true)`, and the WPF render coalescer used the same
pattern. ClojureCLR 1.12.6 implements `compare-and-set!` as a reference comparison
(`Interlocked.CompareExchange` on `object`), and a .NET boolean in an atom is a boxed
object. The boxed `false` held by the atom and the `false` passed as the expected value
are different boxes, so the exchange never happened. Events were queued and never drained.
On the JVM, `Boolean.FALSE` is canonical and the same code works.

**Fix.**

- `cloview.core.store/try-acquire!` and the coalescer in `cloview.wpf.dispatch` use a
  portable test-and-set:
  `(let [[old _] (swap-vals! flag (fn [_] true))] (not old))`. `swap-vals!` compares
  against the object it has just read, so this works on Clojure/JVM, ClojureCLR and
  ClojureDart alike. ClojureCLR fixed the CAS upstream after 1.12.6, but CloViewHost pins
  the NuGet 1.12.6.
- `verify/static_check.clj` gained an architecture rule. It rejects `compare-and-set!`
  whose expected value is a literal boolean, number or character, so the pattern cannot
  come back.

**Checked.** ClojureCLR was built from source twice in the sandbox: once at the
`clojure-1.12.6` tag, the version of the NuGet package CloViewHost uses, and once from
master. The failure reproduced on 1.12.6 before the fix. After it, the core and fx unit
tests pass on both builds (§4).

### 3.2 The WPF smoke

The 14 failed checks of `05-clr-wpf-smoke.json` all follow from §3.1:

- `typing/db-follows-user`, `external/textbox-text`, `greet/greeting-text`,
  `events/no-echo`: no event reached the store;
- `render/coalesced-into-one-pass`: the coalescer never rendered;
- `kitchen-sink/*-patched`: no re-render, so no patch.

The harness cannot run real WPF in the sandbox. Instead, a behavioural WPF stand-in
library (same type and member names as WPF) was built, and the unchanged
`greeter.wpf.main/run-headless-smoke!` and `fitness.wpf.main/run-headless-smoke!` were run
against it on ClojureCLR 1.12.6 (§4). This catches runtime errors in the WPF code paths,
such as wrong member names, arities or casts, but it is not WPF. Step 05 and the new step
07 on Windows remain the authority.

The stand-in runs caught two problems before they reached Windows:

1. **A struct-typed local.** `fitness.wpf.native` type-hinted a local whose value is a
   struct (`(let [^Point p (.GetPosition e c)] …)`). ClojureCLR treats every .NET value
   type as a primitive and rejects such a hint at compile time, so `fitness.wpf.main`
   would not have loaded on Windows.
   - Fixed by dropping the hint.
   - A new static-check rule now reports hints of value types on `let`/`loop` locals
     (rule 14 of [`ARCHITECTURE.md`](ARCHITECTURE.md) §6).
2. **A self-selecting TabControl.** With no tab selected, WPF's `TabControl` selects its
   first tab by itself on first layout, outside the render pass. Its `SelectionChanged`
   would reach the app as an `:on-select` nobody made. `cloview.wpf.hostops` now selects
   the first tab inside the pass, where the echo is swallowed, which matches JavaFX and
   Flutter.

The smoke itself was extended:

- `kitchen-sink/toggle-patched` and `kitchen-sink/no-echo` cover the new `:ui/toggle` and
  `:ui/wrap`;
- a fitness smoke (`--ns fitness.wpf.main --headless-smoke`) covers chips, natives, the
  calendar and ARCH-004.

### 3.3 Flutter: four stacked problems

**(a) The pubspec bridge breaks on Windows.** In Stage 2-1, `modules/flutter` and
`modules/fx` shipped `pubspec.yaml` files. ClojureDart turns such a file into a path
dependency by running `flutter pub add 'cloview_flutter:{"path":".clojuredart/deps/<sha>"}'`.
Through `flutter.bat` the JSON quotes are lost, and pub reads `path:.clojuredart/…` as an
unknown source. Version solving failed, so `http`, `shared_preferences` and `path_provider`
were never installed. `cloview.flutter.effects` then failed while compiling
`cloview.fx.file`, which imports `package:path_provider`.

*Fix.* The modules no longer declare a bridge, and their `pubspec.yaml` files were
removed. Apps add the three Dart packages with plain `flutter pub add http
shared_preferences path_provider`. The harness and `bb flutter:prepare` do this right after
`clj -M:cljd init`, and the README tells app authors. `modules/{fx,flutter}/deps.edn`
comments say the same.

**(b) Flutter 3.29.3 is too old.** ClojureDart 0.9.20261002, the pinned version, uses
`widgets/AlignmentGeometry.topLeft` in `cljd/flutter.cljd` (line 1420). Flutter added
that constant in 3.35. Every namespace that requires `cljd.flutter` fails on 3.29, which
is why `greeter.flutter.main` failed in `cloview.flutter.app`.

*Fix.* The harness (step 06) and `bb flutter:check` / `flutter:prepare` read
`flutter --version`. With anything older than 3.35.0 they stop the Flutter steps and
explain how to upgrade (3.47.x stable recommended). The new opt-in switch
`-FlutterUpgrade` runs `flutter upgrade` first; nothing changes your SDK without it.

*Rejected:* pinning an older ClojureDart. It would lose fixes that the adapters rely on,
and it would still be untested.

**(c) The real error was hidden.** cljd's compile report printed a random heading and the
outermost `ns` form. It does not print nested causes.

*Fix.* `verify/cljd/src/cloview/verify/cljd_diag.clj` wraps `cljd.build/print-exception`
and prints every cause in the chain, with its message, abbreviated `ex-data` and the head
of the emit stack. Every example's `deps.edn` has a `:cljd-diag` alias. After a failed
compile the harness runs `clj -M:cljd-diag compile --offline` automatically, and
`bb diag:flutter [example]` does the same by hand.

**(d) Found during the audit, hidden behind (a)–(c).** ClojureDart's host pass reads
`.cljc` files with the features `#{:cljd :cljd/clj-host :clj}`. A reader conditional whose
`:clj` branch comes before its `:cljd` branch therefore picks the JVM branch during that
pass. Two top-level forms broke this way: in `cloview.fx.timer` and `cloview.fx.http`, a
top-level `#?(:clj (def …))` ran JVM interop during the Dart build.

*Fix.*

- Every reader conditional under `modules/` and `examples/` now lists `:cljd` first.
- Top-level host-only forms carry an explicit `:cljd nil`.
- `verify/static_check.clj` gained a `cljd-host-pass` rule that reports both mistakes.

The Dart `:file/list` branch was also corrected: it now returns files only, as absolute
paths, like the JVM and CLR branches.

### 3.4 Console encoding

**Evidence.** `Flutter 3.29.3 鈥?channel stable` in `01-env-check.json` and
`06-flutter-compile-and-test.log`. Flutter writes `•` (U+2022) as the UTF-8 bytes
`E2 80 A2`. Windows PowerShell 5.1 decoded them with the console's OEM code page.

**Fix.** The harness now:

- sets `[Console]::OutputEncoding` and `$OutputEncoding` to UTF-8;
- runs Python with `-X utf8`. The checkers run isolated (`-I`), which makes Python ignore
  `PYTHONUTF8` and similar variables;
- adds `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8` (and
  the stderr variants) to `JAVA_TOOL_OPTIONS`, unless you already set a file encoding;
- sets the same console encoding inside the child PowerShell it uses for the ClojureTools
  `clj` function;
- restores the original values when it finishes, also when stopped with Ctrl+C
  (`try`/`finally`), so running it inside your own session leaves no trace.

### 3.5 Other changes from the audit

- **JDK 11 is fine.** The run used JDK 11.0.32 for steps 02 and 03. The docs now say
  JDK 11+, with 21+ recommended, instead of requiring 21.
- **Python checks are optional.** Step 07 uses the baseline's own Python checkers when a
  working Python 3 is found (`py -3`, `python`, `python3`, each tried with `--version`; the
  Microsoft Store alias is skipped).
- **ClojureCLR portability.** These were found while porting the fitness domain, not in
  the Windows run, but they would have shown up there:
  - **String order.** ClojureCLR compares strings by culture, while the JVM and Dart
    compare ordinally. `cloview.core.order` provides ordinal comparison and a stable sort,
    used for LAN-sync merges, file lists, JSON keys and UI option lists.
  - **Sort stability.** .NET and Dart sorts are unstable; the stable sort above covers it.
  - **Number text.** On ClojureCLR 1.12.6, `str` of a double drops a zero fraction
    (`(str 20.0)` is `"20"`), and .NET formatting (`format`, `ToString`) follows the
    current culture (`70,5` in de-DE). JSON reports now format doubles
    culture-invariantly, always with a fraction, and UI text uses integer arithmetic.
    `pr-str`, and so EDN storage, is unaffected.
  - **Regex groups.** `re-groups` returns `""` for an unmatched optional group, where the
    JVM and Dart return `nil`. The heart-rate parser normalizes this.

  [`ARCHITECTURE.md`](ARCHITECTURE.md) §6 lists the rules.

### 3.6 Found by review before the next run

Code that cannot run here — the PowerShell harness and the ClojureDart/Flutter layer — was
read line by line by two independent reviewers. Each checked it against the pinned
ClojureDart sources, the Flutter 3.47 framework sources and PowerShell 5.1 semantics.
Everything below has been fixed.

**Flutter (would have failed the new widget tests).**

- **Lost test-id keys.** A node with both `:key` and `:test-id` kept only the identity
  key, so widget tests could not find keyed chips, calendar cells or natives. Both keys
  are applied now, the identity key outermost. Tests build keys through the same
  function, because `ValueKey` equality also compares the runtime type.
- **Unbounded tab pages.** Tab pages got an unbounded height, which is an immediate
  layout error because the catalog page is a split view. `:ui/tabs` now grows in a
  column (a canonical rule on all hosts), and the Flutter tab pages fill the height that
  is left.
- **Unbounded analytics panels.** Analytics panels (a padded box around a stretching
  column, inside a row) got an unbounded width. A box or align now counts as its content
  in the grow rules, on all hosts.
- **Flexing horizontal scrolls.** The volume and calorie strips are horizontal scrolls in
  panels inside the vertical analytics page. They grew like vertical scrolls, and a
  flexing child under an unbounded height is a Flutter layout error. A horizontal scroll
  now keeps its content's height in a column, on all hosts.
- **Wrong body-map picks.** Taps were resolved with the nominal 440 × 340 size, while a
  stretching parent can widen the canvas. Picks now use the laid-out size.
- **Missed storage errors.** Some shared_preferences errors never reached `:on-failure`,
  and an exception in an app handler could be reported as a failed write. The Dart
  storage branches now use `.then … .onError`.
- **Early plugin access.** The fitness Flutter entry point used plugins before
  `WidgetsFlutterBinding.ensureInitialized`. That works on Windows but fails on
  Android/iOS. It is fixed.

**Harness.**

- **Developer Mode.** It is a missing prerequisite: without it `flutter pub add` exits 1
  at Flutter's plugin-symlink step. Step 01 now detects it and warns, and the step
  continues when the packages were added anyway (§5).
- **Interrupted runs.** On Ctrl+C the environment is restored, and the summary and zip of
  what ran so far are still written, marked `aborted`.
- **One exception no longer ends the run.** The `try`/`finally` added for Ctrl+C would
  have turned any unexpected exception, such as a log file briefly locked by an
  antivirus, into the end of the run: no later steps, no summary, no zip. Each step is
  now its own `try`/`catch` (marked `failed` with the message, the run goes on), and the
  file helpers retry once and never throw.
- **Python encoding.** Python now gets `-X utf8`; `PYTHONUTF8` is ignored under `-I`. It
  must be 3.7+.
- **Failed `flutter create`.** It is now detected: cljd's `init` exits 0 regardless, so
  the harness checks for `pubspec.yaml` instead.
- **Stale build output.** A Flutter version change cleans the ClojureDart output
  (`.cloview-flutter-version` stamp).
- **`partial` status.** Step 07 reports `partial` when a part could not run.
- **Diagnostic re-run.** It runs only after a real compile error.
- **Paths.** Literal paths are used throughout, so `[` `]` in paths are safe.

## 4. Checks run in the sandbox after the fixes

The sandbox has no Windows, WPF, Flutter SDK, Maven Central, Clojars or NuGet. JVM checks
use Clojure 1.11.1 and JavaFX 11 from the Ubuntu archive, plus cljfx from GitHub, under
Xvfb. ClojureCLR was built from source. ClojureDart and Flutter code is checked statically
only.

| Check | Result |
|---|---|
| JVM unit tests (core, fx, cljfx descriptions) | 86 tests, 427 assertions, 0 failures |
| Static check (calls, arities, architecture and portability rules, ClojureDart host-pass rule) | OK: 58 files read as `.clj`, 61 as `.cljd`, 46 `.cljc` for the host pass, 56 as `.cljr` |
| Greeter JavaFX smoke (Xvfb) | 27/27 checks, including the new toggle/wrap checks |
| ClojureCLR **1.12.6** unit tests (core, fx) | 74 tests, 352 assertions, 0 failures (the Windows run had 43 failures + 1 error) |
| ClojureCLR master unit tests (core, fx) | 74 tests, 352 assertions, 0 failures |
| Real `Program.cs` (WPF types stubbed), `--run-tests` on staged sources | 74 tests, 352 assertions, 0 failures |
| Fitness: reference unit tests + app tests + 37 DOM-001 oracles, JVM | 46 tests, 0 failures; 37/37 exact |
| Fitness, ClojureCLR 1.12.6 and master | 46 tests, 0 failures; 37/37 exact on both |
| Fitness through `Program.cs --ns fitness.verify --run-tests` | 37/37 exact |
| Baseline tools: `check_package.py`, `check_fixtures.py`, `--observed` on the JVM, CLR 1.12.6 and CLR master envelopes | all PASS |
| Fitness JavaFX smoke (Xvfb) | 21/21 checks; snapshots of all five tabs inspected |
| WPF code paths on behavioural WPF stand-ins (same types, members and event semantics as WPF; no rendering), ClojureCLR 1.12.6 and master | Greeter smoke 29/29; fitness smoke 19/19; 47 exploration checks (clicks, typing, splitter drag, modal, GIF playback, file picker, tab echo), on both builds |
| `bb` task logic (real babashka.fs/process sources, fake `flutter`) | 22 checks pass: project preparation, Python discovery, the baseline checkers, the refusal of Flutter 3.29.3, the clean after a Flutter version change, and the plugin-symlink tolerance of `flutter pub add` |
| `run-windows-checks.ps1` | parses (tree-sitter PowerShell grammar); ASCII only; reviewed line by line for PowerShell 5.1 (it cannot run here) |
| every `deps.edn` / `deps-clr.edn` / `bb.edn` | valid EDN; every `:local/root` exists |

**Not verifiable here:** real WPF rendering and input, the Flutter compile and widget
tests, NuGet restore, and the Windows Clojure CLI. Step 05/07 (WPF) and steps 06/07
(Flutter) of the next Windows run cover them.

## 5. Next Windows run

1. Upgrade Flutter to ≥ 3.35 (3.47.x stable recommended): `flutter upgrade`. Or run the
   harness with `-FlutterUpgrade`.
2. Switch on Windows Developer Mode (`start ms-settings:developers`). Flutter needs it
   for plugins with Windows implementations, and `bb run:flutter` cannot start without
   it. The harness warns and continues when it is off.
3. Optional: install Python 3 (`winget install Python.Python.3.12`) so step 07 also runs
   the baseline's own checkers.
4. From the repository root, run:
   `powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1`.
5. Upload `verify\cloview-windows-verification-results.zip`. It now also holds
   `07-fitness.log` and the `07-fitness-*.json` files.

Expected result:

- steps 01–05 `ok`;
- step 06 `ok` once Flutter ≥ 3.35 is installed;
- step 07 `ok`, with three `--observed` PASS lines (JVM, CLR, Dart).

Anything else is new information for the next stage. The logs now carry the full cause
chain of any ClojureDart compile error.

## 6. Files changed for the Windows findings

| Area | Files |
|---|---|
| CAS fix | `modules/core/src/cloview/core/store.cljc`, `modules/wpf/src/cloview/wpf/dispatch.cljr`, `verify/static_check.clj` |
| Flutter bridge | removed `modules/flutter/pubspec.yaml` and `modules/fx/pubspec.yaml`; `modules/{fx,flutter}/deps.edn` comments; `verify/bb/cloview/tasks.clj` (`flutter-prepare!`, `dart-packages`) |
| Flutter version | `verify/bb/cloview/tasks.clj` (`check-flutter!`), `bb.edn` (`flutter:check`), harness step 06 |
| Compile diagnostics | `verify/cljd/` (new), `:cljd-diag` aliases in `examples/*/flutter/deps.edn`, `bb diag:flutter`, harness |
| Host pass | all `.cljc` reader conditionals (`:cljd` first), `modules/fx/src/cloview/fx/{timer,http}.cljc`, `modules/fx/test/cloview/fx/fx_test.cljc`, static-check rule `cljd-host-pass` |
| Encoding | `verify/run-windows-checks.ps1` |
| Harness | `verify/run-windows-checks.ps1` (step 07, `-SkipFitness`, `-FlutterUpgrade`, Python discovery, environment restore), `bb.edn` (fitness tasks) |
| CLR portability | `modules/core/src/cloview/core/order.cljc` (new), `modules/fx/src/cloview/fx/{lan_sync,file}.cljc`, `verify/src/cloview/verify/report.cljc` |
| Smoke coverage | `examples/greeter/{javafx,wpf}` smokes, `examples/greeter/flutter/test`, `examples/fitness/{javafx,wpf}` smokes |
| Found on the WPF stand-ins | `examples/fitness/wpf/src/fitness/wpf/native.cljr` (struct local), `modules/wpf/src/cloview/wpf/hostops.cljr` (tab selection), `verify/static_check.clj` (rule) |

## 7. The second run: 2026-10-25 (Stage 2-2 harness)

### 7.1 Environment

| Item | Value (from `01-env-check.json`) |
|---|---|
| OS / shell | Windows 11 Home, build 26300; Windows PowerShell 5.1 |
| Flutter | **3.47.8**, minimum 3.35.0: ok; plugin symlink support: ok (Developer Mode on) |
| .NET | `net10.0-windows` selected |
| Python | found as `py -3` |
| Console | the version line is clean, so the UTF-8 console fix (§3.4) works |

### 7.2 Results

| Step | Result | Evidence |
|---|---|---|
| 01 env check | **ok** | as above |
| 02 JVM unit tests + static check | **ok** | 86 tests, 427 assertions, 0 failures; static check exit 0 |
| 03 JavaFX smoke | **ok** | 27/27 checks (cljfx from Clojars) |
| 04 CLR build, unit tests, smoke | **ok** | `net10.0-windows` build; 74 tests, 352 assertions, 0 failures inside the real host (the first run had 43 failures + 1 error: the CAS fix of §3.1 holds) |
| 05 WPF smoke | **ok** | 29/29 checks (first run: 13/27) |
| 06 Flutter | **ok**: the first real Flutter confirmation | `cljd init`, `flutter pub add`, the app compile, the effects compile and the Greeter + kitchen-sink widget tests all exit 0 |
| 07 fitness | **failed, in its Dart part only** | JVM: 37/37 oracles, smoke 21/21. CLR: 37/37 oracles, WPF smoke 19/19. `check_package` and `check_fixtures`: 0. `--observed`: PASS for the JVM and CLR envelopes. Dart: the app compiled (`dart_compile_exit` 0), but `dart_tests_exit` 1, so there is no Dart envelope. |

**Correction to the note that came with the upload.** The note said everything passed
except step 06, which was skipped because Flutter was "still 3.29.3". The files say
otherwise:

- Flutter was 3.47.8.
- Step 06 ran and passed.
- The one failure is step 07's Dart test compile.

### 7.3 Root cause and fixes (Stage 2-2b)

| Finding (log) | Cause | Fix |
|---|---|---|
| `Unknown symbol: with-redefs at line: 28 … clogem/fitness/domain_test.cljc` (`07-fitness.log`) | ClojureDart has no `with-redefs`. The ported reference test used it to count calls of `validate-hr-samples`. | That one assertion block is wrapped in `#?(:cljd nil :default (with-redefs …))`, so JVM and CLR still check it (`examples/fitness/README.md`, now "three changes"; the port's SHA is updated). |
| `DYNAMIC WARNING: can't resolve member withAlpha on target type dynamic` (`fitness/flutter/native.cljd:143`; `07-fitness.log`) | an untyped return value | the colour helper is hinted `^m/Color` |
| "A tag was used that wasn't specified in dart_test.yaml" (`06-flutter-compile-and-test.log`) | `:tags [:widget]` on the Flutter tests | removed |

**Prevention.** A new static rule, **ClojureDart coverage**, checks every symbol of the
files ClojureDart compiles. Those files are the portable module sources, the shared app
sources and tests, the Flutter entry points and the initializer templates. Each symbol
must resolve to:

- a public var of the pinned ClojureDart release, extracted from its sources
  (`verify/cljd/extract_publics.clj` → `verify/cljd/cljd-publics.edn`);
- a special form;
- a local or Dart interop.

The rule would have reported this failure before the run, and it passes on all 69 such
files now.

### 7.4 Next run

The tree now also runs these suites in step 04 (CLR) and step 02 (JVM):

- `bridge`, including a real Java child process, and real R when `Rscript` is on the
  PATH (otherwise that test is skipped with a passing marker);
- `licensing`;
- on the JVM only, `licensing-tool` and `starter`.

Expected results:

- steps 01–07 `ok`;
- three `--observed` PASS lines (JVM, CLR, **Dart**).

Worth trying by hand after the harness, from the repository root (one command per line,
because Windows PowerShell 5.1 has no `&&`):

```powershell
bb init ../try-app --target wpf
cd ../try-app
bb test
bb test:clr
bb dev
```

## 8. The third run: 2026-10-25 (Stage 2-2b tree)

The first run of the tree that was packaged for GitHub. Every step is `ok`, and the
harness ran for 8 min 52 s.

### 8.1 Environment

| Item | Value (from `01-env-check.json` and the result files) |
|---|---|
| OS / shell | Windows 11 Home, build 26300; Windows PowerShell 5.1 |
| JVM | JDK 11.0.32 (a full JDK that bundles JavaFX 11.0.32); Clojure 1.12.6 |
| .NET | SDKs 9 and 10; `net10.0-windows` selected; runtime 10.0.13 |
| Flutter | 3.47.8; plugin symlink support ok |
| Python | `py -3` |

### 8.2 Results

| Step | Result | Evidence |
|---|---|---|
| 01 env check | **ok** | as above |
| 02 JVM unit tests + static check | **ok** | 136 tests, 1378 assertions, 0 failures; static check exit 0. New here: `bridge` with a real Java child process, `licensing`, the key tool, and the initializer (8 tests, 550 assertions: the Windows-path fixes of the pre-packaging review hold) |
| 03 JavaFX smoke | **ok** | 27/27 checks |
| 04 CLR build, unit tests, smoke | **ok** | build with 0 warnings and 0 errors; 114 tests, 720 assertions in the real host, `bridge` and `licensing` included |
| 05 WPF smoke | **ok** | 29/29 checks |
| 06 Flutter | **ok** | init, `pub add`, the app and effects compiles; Greeter and kitchen-sink widget tests: "All tests passed!" |
| 07 fitness | **ok, on all three hosts** | JVM: 37/37 oracles, smoke 21/21. CLR: 37/37 oracles, WPF smoke 19/19. **Dart: compile exit 0; 47 Dart tests passed (the shared tests, the widget test and DOM-001); 37/37 oracles.** `check_package` and `check_fixtures`: 0. `--observed`: PASS for the JVM, CLR and Dart envelopes |

This is the first run in which every host passes the fitness benchmark. The §7 fixes hold:
the `with-redefs` block, the `withAlpha` hint and the removed test tags. No log has a
warning.

### 8.3 Differences from the sandbox numbers

All are expected skips, not failures:

| Suite | Windows | Sandbox | Why |
|---|---|---|---|
| `rplot-test` | 31 assertions | 59 | no `Rscript` on the PATH, so the real-R test is skipped with a passing marker |
| `issue-test` (key tool) | 31 assertions | 33 | the key-file permission check is POSIX only |

Hence 1378 instead of 1408 JVM assertions, and 720 instead of 748 on the CLR.

### 8.4 Reporting notes

- `00-summary.json` said `"stage": "2-2"`. The label is fixed to `2-2b`.
- `07-fitness-dart-summary.json` lists its unit tests as skipped. That file holds only the
  oracle results; the Dart tests (47) are counted by `flutter test` in `07-fitness.log`.

### 8.5 What the harness does not check yet

These are candidates for the Stage 3 job list
([`DEVELOPMENT_WORKFLOW.md`](DEVELOPMENT_WORKFLOW.md)):

- `bb init` on Windows: generating a project and running its own `bb test`, `bb test:clr`
  and `bb dev` (the commands at the end of §7.4);
- R on Windows: installing R with `jsonlite` lets the real-R test run;
- the Dart reference backend through the launcher (`cmd /c dart …`);
- visual checks of the running apps (screenshots), which no smoke replaces.
