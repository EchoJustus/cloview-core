# Verify cloview on Windows

Some checks cannot run in Claude's Linux sandbox: real WPF, Flutter/ClojureDart, the
Windows Clojure CLI, and Clojars/NuGet downloads. A single PowerShell script runs them
and packs the results into one zip to upload for review.

What the last run found, and what was fixed, is in
[`docs/WINDOWS_VERIFICATION_REPORT.md`](docs/WINDOWS_VERIFICATION_REPORT.md). The last run
(2026-10-25, Stage 2-2b tree, before the repository split) passed every step on all
three hosts, including the fitness benchmark on Dart (report §8).

```powershell
powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1
```

The result is `verify\cloview-windows-verification-results.zip`.

This harness checks cloview-core: the modules, the Greeter and the kitchen sink on the
three hosts (steps 01–06). The fitness app and its acceptance benchmark are verified by
the harness of [cloview-examples](https://github.com/echojustus/cloview-examples)
(`verify\run-windows-checks.ps1` there, steps 01–05). It needs both repositories cloned
side by side, for example `C:\src\cloview-core` and `C:\src\cloview-examples`, and its
step 05, the benchmark's own Python checkers, needs Python 3.7+.

---

## 1. Prerequisites

| Tool | Version | Used by step | Install | Check |
|---|---|---|---|---|
| Windows | 10 22H2 or 11, x64 | all | — | `winver` |
| PowerShell | 5.1 (built in) or 7.x | all | — | `$PSVersionTable.PSVersion` |
| Git for Windows | any recent | 03 fallback, 06 (ClojureDart is a git dep) | `winget install Git.Git` | `git --version` |
| JDK | **11 or newer** (21+ recommended) | 02, 03, 06 | `winget install EclipseAdoptium.Temurin.21.JDK` | `java -version` |
| Clojure CLI | 1.12.x | 02, 03, 06 | clj-msi (see below) | `clojure --version` |
| .NET SDK | **10** (9 or 8 accepted) | 04, 05 | `winget install Microsoft.DotNet.SDK.10` | `dotnet --list-sdks` |
| Flutter SDK | **3.35 or newer** (3.47.x stable recommended) | 06 | https://docs.flutter.dev/get-started/install/windows, or `flutter upgrade` | `flutter --version` |
| Windows Developer Mode | on | 06, `bb run:flutter` | `start ms-settings:developers` → *Developer Mode* | step 01 reports `symlink_support` |
| Babashka *(optional)* | any recent | `bb` tasks | https://github.com/babashka/babashka#installation | `bb --version` |
| cljr *(optional)* | `Clojure.Cljr` 0.1.0-beta1 | 04 (tooling check only) | see below | `cljr --version` |

**Clojure CLI.** For native Windows, clojure.org recommends
[clj-msi](https://github.com/casselc/clj-msi). Download the latest `.msi` from its
releases page and install it; it puts `clojure.exe` on your PATH. Then open a **new**
PowerShell window and check `clojure --version`.

Other installs work too, because the harness detects which kind of `clj`/`clojure` you
have and calls it correctly:

- Scoop: `scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure`, then
  `scoop install clojure`;
- the older ClojureTools PowerShell module (`win-install.ps1`, no longer maintained
  upstream), including one installed only for PowerShell 7;
- a `clj.exe` or `deps.exe` from deps.clj.

**.NET.** On Windows, the .NET SDK includes the *Microsoft.WindowsDesktop.App* runtime
that WPF needs. The harness picks `net10.0-windows` when it can, and falls back to
`net9.0-windows` or `net8.0-windows` automatically.

**Flutter.** ClojureDart 0.9.20261002, the pinned version, uses Flutter APIs that appeared
in **Flutter 3.35** (for example `AlignmentGeometry.topLeft`). With an older Flutter every
ClojureDart compile fails, which is what happened with 3.29.3 in the Stage 2-1 run. Run
`flutter upgrade`, or start the harness with `-FlutterUpgrade` to let it upgrade first.
The harness checks the version and stops the Flutter steps with instructions when it is
too old.

**Developer Mode.** Flutter on Windows creates symlinks for plugins that have Windows
implementations, such as `shared_preferences` and `path_provider`, which the effect
runners use. Without Windows Developer Mode, or an elevated shell, `flutter pub add`
stops with "Building with plugins requires symlink support".

- The harness detects this in step 01 and warns.
- It continues when the packages were added anyway: compiling and `flutter test` do not
  need the symlinks.
- Running the Flutter Windows app (`bb run:flutter`) does need them, so switch Developer
  Mode on: `start ms-settings:developers`.

The widget tests (`flutter test`) need neither Visual Studio nor Android.
Visual Studio 2022 with *Desktop development with C++* is only needed to *run* the
Flutter Windows app (`bb run:flutter`). Warnings from `flutter doctor` about Android or
Visual Studio can be ignored for verification.

**cljr (optional).**

```powershell
dotnet tool install -g Clojure.Main
dotnet tool install -g Clojure.Cljr --version 0.1.0-beta1
```

If cljr is missing, step 04 records `not-installed`, which is not a failure. The WPF host
never uses cljr.

**Where to put the repository.** Use a short path without spaces, for example
`C:\src\cloview-core`; Flutter and Gradle dislike spaces, and the Flutter build creates
deep paths. To clone it there:
`git clone https://github.com/echojustus/cloview-core C:\src\cloview-core`. Keep the
folder name `cloview-core`: cloview-examples expects it next to its own folder. If you
extract an archive instead, Explorer's *Extract All* suggests a folder named after the
zip, which can add a level; the repository folder must directly contain `deps.edn` and
`bb.edn`.

**If you develop in WSL2.**

- Run the harness from Windows PowerShell, with the repository on a Windows drive such as
  `C:\src\cloview-core`. Do not run it from a `\\wsl.localhost\...` path (the harness
  warns, and Flutter's `.bat` tools cannot run there), and do not run it from inside WSL.
- Tools installed only inside WSL are invisible to the harness, so install the Windows-side
  prerequisites above. A step whose tools are missing is skipped and reported, so even a
  partial run produces a useful zip.
- If you would rather keep Flutter only in WSL, run the harness with `-SkipFlutter`. Then,
  in a Linux copy of the repository (for example `~/src/cloview-core`, with bb, the
  Clojure CLI and Flutter installed in WSL), run the Flutter step there:

  ```bash
  bb test:flutter 2>&1 | tee 06-flutter-compile-and-test.log
  ```

  Upload that log next to the zip. The ClojureDart compile and the widget tests do not
  depend on the host OS.

**Network.** The first run downloads:

- Maven/Clojars artifacts (Clojure 1.12.6, cljfx 1.10.363 with JavaFX);
- the NuGet `Clojure` 1.12.6 package;
- ClojureDart from GitHub;
- pub packages (`http`, `shared_preferences`, `path_provider`, test).

If Clojars is unreachable, step 03 retries automatically with cljfx from GitHub.

---

## 2. Run the verification

Open PowerShell in the repository root (the folder containing `deps.edn` and `bb.edn`):

```powershell
cd C:\src\cloview-core
powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1
```

`-ExecutionPolicy Bypass` applies to this one process only; nothing is changed
system-wide. The first run takes a while because of the downloads; later runs are much
faster.

Every step runs even when an earlier one failed, or threw an unexpected error (the step
is then `failed`, with the message in `00-summary.json`), and the script always finishes
with the zip. A failed step is still valuable: its log is exactly what the review needs.
If you stop the run with Ctrl+C, the summary and the zip of what ran so far are still
written, marked `aborted`. The console ends with a per-step summary (`ok` / `failed` /
`skipped`). The console is
switched to UTF-8 for the run, and restored afterwards, so tool output is logged without
mojibake.

Options:

| Option | Effect |
|---|---|
| `-SkipJvm` (alias `-SkipJavaFX`) | skip steps 02 and 03 |
| `-SkipClr` (alias `-SkipWpf`) | skip steps 04 and 05 |
| `-SkipFlutter` | skip step 06 |
| `-FlutterUpgrade` | run `flutter upgrade` first when the installed Flutter is older than 3.35 (changes your Flutter SDK, so it is opt-in) |
| `-ClrTargetFramework net8.0-windows` | force a target framework for the WPF host |
| `-NoZip` | leave the results in `verify\out\` only |

What each step does:

| Step | What runs | Output | Passes when |
|---|---|---|---|
| 01 | OS, PowerShell and toolchain diagnostics; installed SDKs and Desktop runtimes; the chosen TFM | `01-env-check.json` | always (informational) |
| 02 | `clj -M:test` (core, fx, bridge, licensing, the licence key tool, the project initializer, cljfx descriptions), then `clj -M:static-check` (incl. the ClojureDart coverage rule) | `02-jvm-unit-tests.log`, `02-jvm-unit-tests.json` | both exit 0 |
| 03 | `clj -M:smoke-javafx`: hidden JavaFX stage; Greeter typing, `[:app/set-name "Ada"]`, `[:app/greet]`; kitchen sink with every V1 tag | `03-jvm-cljfx-smoke.json`, `03-jvm-cljfx-smoke.log` | every critical check passes |
| 04 | `cljr -Spath`; stage sources into `target\clr-src`; `dotnet build` of `CloViewHost.csproj`; `CloViewHost.exe --run-tests` (core, fx, bridge, licensing on ClojureCLR inside the WPF host) | `04-clr-build-and-run.log`, `04-clr-unit-tests.json` | build and tests exit 0 |
| 05 | `CloViewHost.exe --headless-smoke` on the `[STAThread]`: off-screen WPF Greeter; dispatch `[:app/set-name "Ada"]` and `[:app/greet]`; TextBox.Text == "Ada", CaretIndex intact, greeting == "Hello, Ada!"; kitchen sink | `05-clr-wpf-smoke.json` | exit 0 |
| 06 | check Flutter ≥ 3.35; prepare `target\flutter-greeter`; `clj -M:cljd init` (flutter create); `flutter pub add http shared_preferences path_provider`; `clj -M:cljd compile` (on failure, also `clj -M:cljd-diag compile` for the full cause chain); `clj -M:cljd compile cloview.flutter.effects`; `clj -M:test:cljd test` (widget tests) | `06-flutter-compile-and-test.log` | Flutter ≥ 3.35; compile and tests exit 0 |
| 00 | step summary | `00-summary.json` | — |

Windows may briefly show the hidden test windows, or the taskbar may flicker. That is
expected.

The bridge suite starts real child processes:

- **Java.** `java` must be on PATH. The test is skipped with a passing marker otherwise.
- **R.** The R plot test runs only when `Rscript` is on PATH. Without R it is skipped,
  which is normal.

---

## 3. Optional: look at the apps yourself

With Babashka, from the repository root:

```powershell
bb tasks                 # list all tasks
bb run:javafx            # Greeter on JavaFX
bb run:wpf               # Greeter on WPF (stages sources and builds CloViewHost first)
bb run:wpf --repl 5555   # ... with a socket REPL on localhost:5555
bb run:flutter           # Greeter on Flutter for Windows (needs the VS C++ workload)
bb run:flutter -d <id>   # ... or on an Android device/emulator (`flutter devices`)
```

Without Babashka:

```powershell
clj -M:run-javafx
.\target\clr-host\CloViewHost.exe --src .\target\clr-src    # after the harness has run step 04
cd target\flutter-greeter; clj -M:cljd flutter -d windows   # after the harness has run step 06
```

Type a name, press **Greet**, and you get `Hello, <name>!`. The same `.cljc` file
(`examples/greeter/shared/src/greeter/core.cljc`) drives all three.

The fitness app is in cloview-examples. With both repositories side by side, run
`bb run:fitness:javafx`, `bb run:fitness:wpf` or `bb run:fitness:flutter` from the
cloview-examples folder; its
[`apps/fitness/README.md`](https://github.com/echojustus/cloview-examples/blob/main/apps/fitness/README.md)
describes the app.

To try the project initializer, generate a project next to the repository:

```powershell
bb init ..\try-app --target wpf
cd ..\try-app
bb test        # shared tests, JVM
bb test:clr    # the same tests inside the app's own WPF host
bb dev         # the app
```

---

## 4. What to upload

Upload **`verify\cloview-windows-verification-results.zip`**. It contains:

```
00-summary.json
01-env-check.json
02-jvm-unit-tests.log            02-jvm-unit-tests.json
03-jvm-cljfx-smoke.json          03-jvm-cljfx-smoke.log
04-clr-build-and-run.log         04-clr-unit-tests.json
05-clr-wpf-smoke.json
06-flutter-compile-and-test.log
```

If some files are missing because a tool was not installed or a step was skipped, upload
the zip anyway. `00-summary.json` records what ran, what was skipped and why
(`missing_files` lists the gaps). If you ran the Flutter step in WSL instead (§1), also
upload that `06-flutter-compile-and-test.log`.

The files contain tool versions, the OS version and local paths, for example your user
folder in tool locations and the repository path. Nothing else is collected.

---

## 5. Troubleshooting

| Symptom | Fix |
|---|---|
| "running scripts is disabled on this system" | use the exact command above (`powershell -ExecutionPolicy Bypass -File …`) |
| `WARNING: The repository is on a network or WSL path` | copy the repository to a local drive (for example `C:\src\cloview-core`) and run the script from there |
| step 02/03/06 skipped with `clj not found` | install the Clojure CLI, then open a **new** PowerShell; `Get-Command clojure` (or `clj`) must find it |
| step 03 cannot resolve `cljfx/cljfx` | the harness retries with `:cljfx-git` automatically (needs `git` on PATH) |
| step 04 `build_exit: no-tfm` or `no-dotnet` | install the .NET 10 SDK; or force a framework with `-ClrTargetFramework net8.0-windows` |
| step 04 NuGet restore errors | check proxy/firewall; `dotnet nuget list source` should list nuget.org |
| step 04 says it could not delete `target\clr-host` | close any running `CloViewHost.exe` (for example one started by `bb run:wpf`) and run again |
| step 05 fails while 04 passes | a WPF-specific problem; `05-clr-wpf-smoke.json` names the failing check, which is what the review needs |
| step 06 says Flutter is too old | `flutter upgrade` (3.47.x recommended), or run the harness with `-FlutterUpgrade` |
| step 06 compile error | the log now contains a second run, `clj -M:cljd-diag …`, which prints the full cause chain; that part is what the review needs |
| step 06 is slow the first time | ClojureDart downloads its compiler and runs `flutter create`; later runs reuse `target\flutter-greeter` |
| a step is `failed` with an `exception` entry | something unexpected was thrown (for example a log file locked by an antivirus); the step's log ends with the message and the script line; the other steps still ran |
| `Building with plugins requires symlink support` | switch on Windows Developer Mode (`start ms-settings:developers`); the harness continues without it, but `bb run:flutter` needs it |
| garbled characters in logs | the harness switches the console to UTF-8; if logs are still garbled, run it from Windows Terminal or a new PowerShell window |
| path-too-long errors in step 06 | move the repository to a short path such as `C:\src\cloview-core`, or enable Windows long paths |
| want a clean re-run | delete `target\` and run the script again (`bb clean` does the same) |
