# R visualization in cloview views

Status: **implemented and tested (Approach A2), Stage 2-2b.**

- `cloview.bridge.rplot` provides the `:r/plot` effect, the plot state and `plot-view`.
- `modules/bridge/backends/r/backend.R` is the R worker.
- `rplot_test.cljc` covers the effect, the coalescing and the view against an
  in-process stand-in for the worker.
- The same test then runs the **real** `backend.R` through cloview's launcher. It passed
  on Linux with R 4.3.3 (Ubuntu 24.04 packages: jsonlite 1.8.8, ggplot2 3.4.4, ragg 1.2.7,
  svglite 2.1.3) on Clojure/JVM and on ClojureCLR 1.12.6 and master.
- Not yet run: Windows, macOS, R 4.6.
- Facts were checked on 2026-10-25. The current R release is 4.6.1 (2026-06-24); 4.6.2 is
  due 2026-10-29 ([r-project.org](https://www.r-project.org/)).

## 1. The question

Some cloview apps (statistics tools, lab utilities, quant calculators) want R's plotting:
ggplot2, patchwork and the CRAN ecosystem, inside a native window. The plot must:

- appear in an ordinary view;
- stay crisp on HiDPI screens;
- update when the data or the window size changes;
- never freeze the UI;
- ship with the app, without assuming the user installed R.

## 2. Options

| | A1: Rscript per plot | **A2: long-lived R worker (bridge backend)** | B1: embed libR in-process | B2: Rserve | B3: webR (WebAssembly) |
|---|---|---|---|---|---|
| How | spawn `Rscript plot.R` for each figure | one `Rscript backend.R` speaking the cloview bridge protocol on stdio | RInside (C++), rJava/JRI (JVM), R.NET (.NET) | R as a TCP server, client libraries | R compiled to Wasm, in a JS engine |
| Startup per plot | full R start plus package loads, every time (§7) | once per session; then a request per plot | once | once per connection | once; a large download or bundle |
| Per-plot cost (§7) | ~0.5–0.7 s with ggplot2 | ~0.1–0.2 s | lowest | low | slower than native R |
| UI isolation | a crash ends one plot | a crash ends the worker; the launcher restarts it | **a crash or a hang takes the app down**; "Embedded R is designed to be run in the main thread" ([R-exts §8.1.5](https://cran.r-project.org/doc/manuals/r-release/R-exts.html)) | separate process | separate engine |
| Host coverage | any host that starts processes | the same; reuses the bridge's lifecycle, timeouts, restarts, logs | a binding per host: JRI is JVM only; R.NET 1.9.0 is MIT, last updated Oct 2020, and its page says "on Linux only 3.5.y is supported" ([NuGet](https://www.nuget.org/packages/R.NET/), read 2026-10-25); no Dart FFI binding found | Java client (LGPL); the .NET client was last updated 2015; no Dart client. On Windows: "no concurrent connections", no sessions ([Rserve on Windows](https://rforge.net/Rserve/rserve-win.html)) | needs a Wasm-capable JS engine. JavaFX WebView is built with WebAssembly off ([OptionsJava.cmake](https://github.com/openjdk/jfx/blob/master/modules/javafx.web/src/main/native/Source/cmake/OptionsJava.cmake)); `webview_flutter` has no Windows/Linux ([pub.dev](https://pub.dev/packages/webview_flutter)); in practice a Node.js sidecar |
| Licence boundary (§6) | separate program | separate program | one program with GPL R ("linked together in a shared address space") | separate program; Rserve is GPL-2 | webR binaries are GPL-3 |
| Effort in cloview | small | **small: a backend script plus ~150 lines of `.cljc`** | large, per host | medium; a client per host | large |

Also rejected:

- **Compiling R code to a native library.** It is not possible for ggplot2 pipelines:
  ggplot2 and patchwork are pure R, and R's compiler produces byte code for R's own VM
  ([compiler](https://stat.ethz.ch/R-manual/R-devel/library/compiler/html/compile.html)).
- **Alternative R runtimes.** FastR is in maintenance mode
  ([GraalVM docs](https://docs.oracle.com/en/graalvm/jdk/23/docs/reference-manual/r)) and
  Renjin's last stable release was in 2019.
- **Standalone Rmath.** It covers distribution functions and the RNG only: no graphics
  ([R-admin §9](https://cran.r-project.org/doc/manuals/r-release/R-admin.html)).

## 3. Recommendation: A2, R as a bridge backend

R becomes one more Mode B backend (docs/POLYGLOT_BACKEND_ARCHITECTURE.md): a long-lived
`Rscript --vanilla backend.R` that reads JSON-RPC lines from stdin and answers on
stdout. Every hard part already exists in `modules/bridge`:

- start on demand;
- the `$/initialize` handshake, with a startup timeout;
- request timeouts;
- crash detection with restart backoff;
- `$/shutdown` and EOF on exit, so the worker never outlives the GUI;
- stderr forwarded to the app's log.

This choice has three effects:

- **The UI thread never touches R.** A plot is an asynchronous effect whose result is a
  file path.
- **Startup is paid once.** R plus ggplot2 starts in ~0.6 s; later plots take ~0.1–0.2 s
  (§7).
- **The process boundary is the licence boundary** that the FSF FAQ describes for
  "pipes, sockets and command-line arguments" (§6).

Approach A1 (a fresh `Rscript` per plot) is the fallback for apps that plot rarely: the
same `backend.R` can serve one request and exit. B1 is worth revisiting only for an app
that needs R in a hot loop and runs on one host.

## 4. The contract

### In the app (`cloview.bridge.rplot`)

```clojure
(bridge/install! {:manifests [(manifest/parse (slurp "backend/r/backend-manifest.json"))]
                  :vars {:appDir app-dir :tempDir (System/getProperty "java.io.tmpdir")}
                  :dispatch! #(st/dispatch! store %)})
(rplot/install!)                                   ; :backend :r by default

;; an event handler
(fn [{:keys [db]} _]
  {:r/plot {:id :weight-trend
            :plot "weight_trend"                   ; R function plot_weight_trend
            :data {:date (mapv :date (:entries db)) ; columns
                   :kg (mapv :kg (:entries db))}
            :options {:colour "#3367D6"}
            :width 640 :height 360                 ; logical px
            :scale 2}})                            ; 1280x720 px PNG (the default scale)

;; the view
(rplot/plot-view db :weight-trend {})
```

| Key | Default | Meaning |
|---|---|---|
| `:id` | required | keyword; the plot's slot under `[:cloview/r-plots id]` |
| `:plot` | required | name of an R function `plot_<name>` |
| `:data` | `{}` | columns, `{name [values…]}`; JSON `null` becomes `NA` |
| `:options` | `{}` | passed to the R function as a list |
| `:width` `:height` | 640 × 400 | logical pixels: the size the view shows |
| `:scale` | 2 | device pixel ratio of the rendered PNG |
| `:format` | `"png"` | or `"svg"` |
| `:timeout-ms` | manifest's `request_timeout_ms` | per request |

Slot state:

```clojure
{:status :rendering|:ready|:failed
 :seq n
 :spec spec
 :pending spec
 :image {:path :width :height :width-px :height-px :format :ms}
 :error "..."}
```

**One request per slot is in flight at a time.** An `:r/plot` for a slot that is still
rendering replaces the waiting request, which is sent when the current render ends.

- **Coalescing.** A resize that asks for twenty sizes renders at most two; the test
  suite asserts this. It matters more than cancellation: R handles requests one by one,
  so a cancelled request that has already been read would still be drawn.
- **No flicker.** The previous image stays visible, with a small "Updating the plot…"
  note, until the new one arrives.

Once a plot has an image, `plot-view` returns a `:ui/column` that holds a canonical
`:ui/image` (`:src {:file path}`, `:fit :contain`, at the logical size) and, while a
newer image renders or after a failed update, a one-line note. Before the first image
it returns a one-line `:ui/text`. It needs no host code (test ids `r-plot-<id>`,
`r-plot-<id>-note`). Use `request-fx` to return the effects directly from a handler
instead of the `:r/plot` effect.

### In R (`backend.R` + your `plots.R`)

```r
plot_weight_trend <- function(data, options) {      # data: a data.frame of the columns
  data$date <- as.Date(data$date)
  ggplot2::ggplot(data, ggplot2::aes(date, kg)) + ggplot2::geom_line()   # returned ggplots are printed
}
```

`Rscript --vanilla backend.R [plots.R …]` loads every `plot_*` function from the files
it is given (default: `plots.R` next to it).

| Method | Params → result |
|---|---|
| `$/initialize` | → `{protocol, name: "cloview-r", version, methods, r: R.version.string, packages: {ggplot2, ragg, svglite}}` |
| `plot/render` | `{plot, data, options, width, height, scale, format, tag}` → `{path, width_px, height_px, format, ms}`, or error -32602 (unknown plot, with the list of plots) or -32050 (the R error message) |
| `plot/list` | → the plot names |
| `$/ping`, `$/shutdown` | standard |

How the worker behaves:

- **Exact pixels.** PNG uses `ragg::agg_png(width = w·s, height = h·s, units = "px",
  res = 96·s)`, which gives exact pixel sizes with text sized as on a 96-dpi screen
  ([agg_png](https://ragg.r-lib.org/reference/agg_png.html)). Verified: a 640 × 360
  request at scale 2 yields a 1280 × 720 PNG. Without ragg it falls back to
  `grDevices::png(type = "cairo")`.
- **SVG** uses svglite, or falls back to `grDevices::svg`.
- **Clean stdout.** Console output produced while a plot is drawn is captured and
  written to stderr, and warnings go to stderr, so stdout carries only protocol lines.
- **Output files** go to `$CLOVIEW_R_OUT` (the manifest sets `${tempDir}/cloview-r`). Each
  file name is new, so image widgets never show a cached old image, and the worker
  keeps the last two files per plot.

## 5. Shipping R with an app

| OS | Route | Size | Notes |
|---|---|---|---|
| Windows | CRAN installer with `/CURRENTUSER /VERYSILENT /DIR=…`, or a portable R folder | installer ~80 MB; installs "up to 175MB" ([rw-FAQ](https://cran.r-project.org/bin/windows/base/rw-FAQ.html)); [portable-r](https://github.com/portable-r/portable-r-windows) zip ≈104 MB | "A basic R installation is relocatable", so an `R/` folder inside the app works (the manifest's Windows command points there) |
| macOS | bundle `R.framework`; the CRAN .pkg hardcodes `/Library/Frameworks` paths that must be rewritten (`@rpath`), then re-signed and notarized | [portable-r macOS](https://github.com/portable-r/portable-r-macos) 83–86 MB, signed and notarized | jamovi puts `R.framework` inside its app bundle ([Makefile](https://github.com/jamovi/jamovi/blob/main/engine/Makefile)); JASP patches the .pkg ([guide](https://github.com/jasp-stats/jasp-desktop/blob/master/Docs/development/jasp-build-guide-macos.md)) |
| Linux | Posit [r-builds](https://github.com/rstudio/r-builds) portable tarballs (manylinux, glibc ≥ 2.34; experimental), conda-pack, or the distribution's R as a declared dependency | — | jamovi on Flathub is 436 MiB ([Flathub](https://flathub.org/apps/org.jamovi.jamovi)) |

- **Packages.** ggplot2, patchwork, ragg, svglite and jsonlite pull in 24 non-base
  packages, about 45 MB of Windows binaries for R 4.6. Budget **~220 MB** installed for
  R plus a plotting stack, the same order as an empty Electron app (about 385 MB on
  Windows in the benchmark the README cites). R suits apps whose users want R's
  statistics, not decoration charts. For simple charts use `:ui/native` canvases
  or the host's chart widgets.
- **Bring-your-own R.** Pointing the manifest at the user's `Rscript` is fine for
  technical audiences. Report missing packages from `$/initialize`'s `packages` map in
  the UI.
- **Pin the versions.** Use a CRAN snapshot date (as jamovi does with R 4.5.0) so plots
  look the same on every machine.

## 6. Licensing

| Component | Licence |
|---|---|
| R | GPL-2 \| GPL-3; headers for third-party front ends are not LGPL ([COPYRIGHTS](https://svn.r-project.org/R/trunk/doc/COPYRIGHTS)) |
| ggplot2 4.0.3, patchwork 1.3.2, ragg 1.5.2, jsonlite 2.0.0 | MIT |
| **svglite 2.2.2** | **GPL-2 \| GPL-3** |
| Rserve | GPL-2 (with an OpenSSL exception) |
| webR | GPL-3 |

- **R Core's view.** The R FAQ §2.11 says the GPL "only restricts distribution of R or
  of other programs containing code from R"
  ([R FAQ](https://cran.r-project.org/doc/FAQ/R-FAQ.html)).
- **The FSF's view.** The FSF treats "pipes, sockets and command-line arguments" as
  communication between separate programs, and modules "linked together in a shared
  address space" as one program. Nonfree and GPL programs may be shipped together when
  they "communicate at arms length"
  ([GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation)).
- **What A2 means for your app.** It keeps R in its own process behind a line protocol,
  so your app is a separate program. You still distribute R itself, so ship its licence
  text and offer its source as the GPL requires.
- **Get a lawyer.** The subprocess-versus-linking line is the FSF's interpretation; no
  court has ruled on it, and R Core has no statement about it specifically. Get a legal
  review before a commercial release.

## 7. Performance (measured 2026-10-25, 2-vCPU Xeon @ 2.1 GHz, Linux, R 4.3.3)

| Step | Time |
|---|---|
| `Rscript --vanilla -e 'q()'` | 133–144 ms |
| `Rscript … library(ggplot2); library(ragg)` | 506–569 ms |
| spawn → `$/initialize` answered (loads ggplot2, ragg, svglite) | 625 ms |
| `plot/render`, 300-point line, 1280 × 720 PNG, round trip | 115–192 ms (first render the slowest) |
| same as SVG | 97 ms |

The only published comparison found is from 2014: Rscript ≈144 ms per launch versus
littler ≈26 ms ([Eddelbuettel](https://dirk.eddelbuettel.com/blog/2014/09/02)). That
agrees with the first row. Approach A1 would pay ~0.5–0.7 s for every plot, while A2 pays
it once.

## 8. Verification

- **`rplot_test.cljc`, portable** (JVM, ClojureCLR):
  - the effect and the slot state;
  - the defaults and a file-name-safe tag;
  - coalescing: 700/800/900 requested while 700 renders gives renders of 700 and 900,
    never 800;
  - the previous image stays during an update;
  - failures;
  - `plot-view` trees, with no structural problems;
  - invalid specs throw;
  - the shipped manifest is valid and resolves on Windows and Linux.
- **`rplot_test.cljc`, real R** (needs `Rscript` on the PATH; skipped otherwise): the
  shipped manifest with the repository's `backend.R`, started on demand by the bridge.
  A render gives a 1280 × 600 PNG in `CLOVIEW_R_OUT`, an unknown plot gives a failure,
  and the worker stops in an orderly way. Non-ASCII text crosses the pipe byte for byte in
  both directions, even though the test JVM runs in the C locale. It passed on the JVM and
  on both ClojureCLR builds.
- **Encoding finding.** The first version opened stdin with `encoding = "UTF-8"`. In a
  non-UTF-8 locale (LANG unset, as a GUI app on macOS or a minimal container may start
  the worker), R converted each line to the native encoding. It stopped at the first
  non-ASCII character, and the worker then saw end of input and exited.
  The fix makes the worker independent of the locale:
  - it reads bytes marked as UTF-8 (`readLines(…, encoding = "UTF-8")` on an unconverted
    connection);
  - it writes with `useBytes = TRUE`;
  - it switches `LC_CTYPE` to a UTF-8 locale when one exists.
- **Robustness findings (pre-packaging review).** A `plot/render` whose `plot` was an
  array, an object or a number made the worker exit without answering, and a plots file
  that printed while loading corrupted the `$/initialize` reply, so the worker never
  became ready. Now parameters are checked (-32602), an unexpected R error inside a
  request is answered (-32603) instead of ending the worker, and output while plots load
  goes to stderr. The real-R test covers both: five malformed calls come back as -32602
  with the worker still `:ready`, and a plots file that prints at load time still serves
  its plot.
- **Raw-pipe run of `backend.R`.** It answered initialize, list, PNG, SVG, unknown plot,
  R error, parse error, `$/cancel` (ignored), ping and shutdown, then exited 0. R's
  warnings stayed on stderr and no stray `Rplots.pdf` was created.

## 9. Stage 3

1. Run the real-R test on Windows and macOS, both with R 4.6 + ggplot2 4.x and with a
   bundled portable R (the manifest's Windows command).
2. Add a `bb` task that assembles a portable R plus a CRAN snapshot into
   `backend/r/R` for a target.
3. Flutter: the bridge's Dart process transport (`dart:io` `Process.start`), so the same
   effect works on Flutter desktop.
4. Optional extras: `plot/render` with `"format": "pdf"` for exports, and a `plot/data`
   method for plots that compute their own data.
5. If a single-host app ever needs R in a hot loop, evaluate B1 (JRI on the JVM) behind
   the same `:r/plot` contract.
