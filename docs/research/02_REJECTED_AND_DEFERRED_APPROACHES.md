# Rejected and deferred approaches

What cloview decided **not** to do, and why, so later stages do not re-open settled
questions without new evidence. Each entry lists:

- the approach;
- the decision (rejected, or deferred with a trigger);
- the evidence: repository code or documents, or a source checked on the date given;
- what would re-open it.

Companion: [`01_FEASIBILITY_STUDY_OCT_2026.md`](01_FEASIBILITY_STUDY_OCT_2026.md), the
original study, archived verbatim with its sources and a "Since the study" appendix.

## 1. Option A: views as `.edn` templates (rejected for Option B)

- **Approach.** UI trees as EDN data files, filled from state by a template engine on
  each host, with logic kept outside the views.
- **Why rejected:**
  - **Views need logic.** Conditionals, lists and derived values would need an
    expression language inside the data, which means a second language to design,
    document and port to three runtimes.
  - **Option B already gives the data.** Plain `.cljc` functions return the same
    canonical data (`[:ui/column …]`), and tests inspect it directly. See
    `cloview.core.tree/normalize`, `t/problems` and `find-by-test-id`, used by every
    app test, including the generated ones in `starter/templates/*/shared/test`.
  - **Templates need an interpreter on ClojureDart.** Functions compile normally.
- **Kept from the idea.** The vocabulary itself is data (`t/v1-tags`). Coverage tests,
  static checks and documentation are generated from it.
- **Re-open if** non-programmers must edit screens without a build. Even then, a template
  layer would compile to Option B data, not replace it.

## 2. Option C: a cross-platform macro DSL (rejected for Option B)

- **Approach.** Macros that expand a view language into host code (cljfx descriptions,
  Flutter widgets, WPF object graphs) at compile time.
- **Why rejected:**
  - **Expansion runs in ClojureDart's macro host pass, which reads `.cljc` with `:clj`
    active.** That host pass caused real errors in this project. A macro DSL would put
    every view through it (WINDOWS_VERIFICATION_REPORT.md §3.3 (d); the `:cljd`-first
    rule in `verify/static_check.clj`).
  - **No single intermediate value.** Expanded code differs per host, so a view cannot be
    tested once as data. Option B's tree is one value checked the same way everywhere.
  - **Hard to read, for people and for code assistants.** Readers and language models
    see the macro call, not what it becomes. Data in, data out is the most inspectable
    form (the "AI-native ergonomics" goal in the README).
- **Re-open if** profiling shows the per-render interpretation is a bottleneck. Even then,
  the first remedies are memoized subtrees and `:ui/native` islands (STAGE_2_1_DESIGN.md
  §7).

## 3. `Function.apply` or mirrors to find adapters on ClojureDart (rejected for a static registry)

- **Approach.** Find a tag's adapter by name at run time, through reflection
  (`dart:mirrors`), and call it with dynamic arguments (`Function.apply`).
- **Why rejected:**
  - **No mirrors in release builds.** Flutter release builds are AOT-compiled, and AOT
    Dart has "No support for `dart:mirrors`"
    ([dart.dev, dart compile](https://dart.dev/tools/dart-compile), checked 2026-10-25).
    A lookup that works in debug would fail in release.
  - **`Function.apply` does not solve lookup.** It is available in AOT, but it only
    invokes a function value the code already holds. ClojureDart calls Clojure functions
    natively anyway.
  - **A def'd map does the job.** A map from tag keyword to adapter fn
    (`builtin-adapters` in `cloview.flutter.adapters`) gives lookup without reflection.
    App adapters join through `reg-adapter!`, consulted first
    (STAGE_2_1_DESIGN.md §3.3).
- **Related finding.** ClojureDart compiles definitions only, so top-level `reg-*` calls
  never run. The same data-first rule applies to handlers (`register-handlers!`,
  STAGE_2_1_DESIGN.md §5.1).

## 4. Porting cljfx's lifecycle model to the CLR (rejected for the portable `HostOps` reconciler)

- **Approach.** Re-implement cljfx's description → lifecycle → instance machinery in
  ClojureCLR against WPF dependency properties, so WPF would behave like the JavaFX
  host.
- **Why rejected:**
  - **cljfx is JVM code built for JavaFX.** It is written for JavaFX's property and
    observable model. A port would mean maintaining a fork of cljfx's core per host.
  - **The portable reconciler is small and testable everywhere.**
    `cloview.core.reconcile` is a few hundred lines of portable `.cljc` behind five
    operations (`create-element`, `patch-props!`, `replace-children!`,
    `attach-child-slot!`, `destroy-element!`). It runs headless against a deterministic
    `MockHostOps` on the JVM and the CLR, and is pinned by op-log tests (identity, keyed
    moves, the controlled-input guard, re-entrancy, destroy order;
    STAGE_2_1_DESIGN.md §2.5).
  - **The WPF side keeps WPF's own invariants.** `cloview.wpf.hostops` never detaches a
    surviving child, so focus, caret and scroll survive reorders. Grid tracks implement
    gap and grow (STAGE_2_1_DESIGN.md §2.6).
- **Evidence it holds.**
  - On Windows (2026-10-25): the Greeter and kitchen-sink WPF smoke passed 29/29, the
    fitness WPF smoke 19/19, and the CLR unit tests 74 tests / 352 assertions inside the
    real host.
  - On Linux, the WPF stand-ins exercise the same code (STAGE_2_2_DECISIONS.md item 39).
- **Re-open if** a host appears with its own declarative diffing (then it gets a pure
  translator, like cljfx and Flutter today).

## 5. Avalonia 12 and WinForms as the CLR GUI (deferred and rejected for WPF on net10.0-windows)

| | Decision | Evidence (checked 2026-10-25) | Re-open if |
|---|---|---|---|
| **WinForms** | rejected | Supported and improving on .NET 10: dark mode and async forms are complete ([what's new](https://learn.microsoft.com/dotnet/desktop/winforms/whats-new/net100)). But its layout model (absolute position, anchors and docking, plus Flow and TableLayoutPanel) maps poorly to the canonical gap/grow/stretch rules that WPF's `Grid` expresses exactly. | never for the canonical vocabulary; a `:ui/native` island can still host a WinForms control through `WindowsFormsHost` |
| **Avalonia 12** | deferred | 12.0 was released 2026-04-07. It targets .NET 10, runs on Windows, macOS, Linux, iOS, Android and WebAssembly, and turns compiled bindings on by default ([release post](https://avaloniaui.net/blog/avalonia-12)). For cloview it would add CLR apps on macOS and Linux, which the JavaFX and Flutter targets already provide. Driving it from ClojureCLR (code-only construction) is unevaluated. | users need CLR (.NET) logic on macOS/Linux in-process; or WPF stops receiving fixes |
| **WinUI 3** | not evaluated | Microsoft's FAQ recommends WinUI 3 for new general-purpose Windows apps and keeps WPF "supported" with ongoing improvements ([FAQ](https://learn.microsoft.com/en-us/windows/apps/get-started/windows-developer-faq)). | the same triggers as Avalonia; it is the other candidate for a second CLR target |

## 6. Committing or shipping compiled binaries (rejected)

- **Approach.** Ship prebuilt `CloViewHost.exe`, DLLs, jars or ClojureDart output in the
  repository or the release zip, so users skip toolchain setup.
- **Why rejected:**
  - **Binaries go stale.** They would drift from the sources, and they cannot be reviewed.
  - **They bloat the repository.** Every change would add another binary.
  - **Users already build them.** The NuGet `Clojure` package is restored at build time,
    and the host is built in seconds (`bb wpf:build`, harness step 04).
- **How it is enforced:**
  - `.gitignore` excludes `*.dll *.exe *.pdb *.jar *.class *.nupkg` and `bin/ obj/
    target/`.
  - The packaging step scrubs caches before zipping.
- **Re-open for** release artifacts built by CI from a tagged commit, attached to a
  release rather than committed (ROADMAP_AND_EXTENSIONS.md §4).

## 7. Pubspec "bridges" passed through the Flutter CLI (rejected)

- **Approach.** Let ClojureDart add each module's Dart dependencies with
  `flutter pub add name:{"path":…}`, driven by a `pubspec.yaml` in each cloview module.
- **Why rejected.** On Windows, the JSON quoting of that argument does not survive
  `flutter.bat`, so the command failed for every user with the standard installation
  (STAGE_2_2_DECISIONS.md item 2; WINDOWS_VERIFICATION_REPORT.md).
- **Replacement.** The module `pubspec.yaml` files were removed. Apps, the harness, the
  bb tasks and the generated projects run `flutter pub add http shared_preferences
  path_provider`, which needs no quoting.
- **Re-open if** ClojureDart passes the bridge through a file instead of a command-line
  argument.

## 8. Empirical findings that ruled out "obvious" code

These are not design options, but each removed a construct from cloview's portable code.
Each is now enforced by a rule, so it cannot come back unnoticed.

| Finding | Where it surfaced | Rule now |
|---|---|---|
| ClojureCLR 1.12.6 `compare-and-set!` compares boxed literals by reference, so `(compare-and-set! a false true)` never succeeds | first Windows run (Stage 2-2) | `swap-vals!` test-and-set (`store/try-acquire!`); the static check rejects `compare-and-set!` with a literal |
| ClojureDart's host pass reads `.cljc` with `:clj` active, so a `:clj` branch before `:cljd` is compiled for Dart | first Windows run | `:cljd` branch first, and `:cljd nil` for top-level host-only forms (`check-cljd-host-pass`) |
| `with-redefs` (and `slurp`, `spit`, `format`, `pmap`, `locking`, `agent`, refs and others) do not exist in ClojureDart; the fitness Dart tests failed to compile on it | second Windows run, step 07 (2026-10-25) | `#?(:cljd nil :default …)` around it; the new **ClojureDart coverage** check resolves every symbol of the Dart-compiled files against the pinned release's public vars (`verify/cljd/cljd-publics.edn`) |
| A ClojureDart dynamic call warning (`withAlpha`) on an untyped return | the same run | a `^m/Color` return hint in `fitness.flutter.native` |
| Culture-sensitive string order and case on ClojureCLR | Stage 2-2 tests | `cloview.core.order`, `cloview.core.text` |
| ClojureCLR rejects type hints on struct-valued locals | the WPF stand-ins (Stage 2-2) | static rule `clr-value-types` |
| `Environment.OSVersion` reports "Unix" on both macOS and Linux under .NET | Stage 2-2b, licensing and bridge platform detection | `System.OperatingSystem/IsWindows` / `IsMacOS` |
| Reference backends answered `$/shutdown` while a request was still running, so its answer was lost | Stage 2-2b, a generated C# project | protocol rule 4: drain in-flight requests first (POLYGLOT_BACKEND_ARCHITECTURE.md §3) |

## 9. Deferred in Stage 2-2b

| Approach | Decision | Where |
|---|---|---|
| Ed25519 licence keys | deferred: ES256 needs no extra packages on JDK 11+ or .NET; the token's `alg`/`v` fields leave room | LICENSING.md §4 |
| Embedding R in-process (RInside, JRI, R.NET), Rserve, webR | rejected or deferred, for a long-lived `Rscript` worker behind the bridge | R_VISUALIZATION_INTEGRATION.md §2 |
| Bridge `tcp` transport | reserved in the manifest; stdio only for now | POLYGLOT_BACKEND_ARCHITECTURE.md §5 |
| Mode B on Flutter | refused by `bb init` until the Dart process transport exists | POLYGLOT_BACKEND_ARCHITECTURE.md §9 |
| Bridge as part of `modules/fx`, or as a separate repository | rejected for an opt-in `modules/bridge` | POLYGLOT_BACKEND_ARCHITECTURE.md §2 |
| Store packaging (MSIX, Mac App Store, Flatpak/Snap) | Future / Non-Priority for V1 | ROADMAP_AND_EXTENSIONS.md §4 |
