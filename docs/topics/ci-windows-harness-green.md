# Topic `ci/windows-harness-green`

Branch `ci/windows-harness-green`, in cloview-core and then in cloview-examples, opened on
2026-10-26 from cloview-core `main` at 5775013
([DELIVERY_DESIGN.md](../DELIVERY_DESIGN.md) §5).

## Scope

The windows-harness workflows are green only when every harness step passes, and
cloview-core's run is green with the real-R bridge test running on Windows.

Why: cloview-core's run of 2026-10-26 (on 09d4afe) went red only because its summary
step crashed on a character that cp1252, the code page Python on Windows uses for
redirected output, cannot encode. Steps 02 and 04 had failed underneath: the hosted
runner has R but not the jsonlite package, so `cloview.bridge.rplot-test` failed on the
JVM and on the CLR. `run-windows-checks.ps1` exits 0 and `ops/tools/ci_summary.py` exited
0 by design, so without the crash the job would have been green.

The rule for the verdict: **in CI only "ok" passes.** A failed or skipped step, an
aborted run or a missing harness summary fails the job. Run locally, the harness keeps
reporting skips without failing: `run-windows-checks.ps1` and its exit code stay as they
are, and only the workflows ask for the verdict (`ci_summary.py --require-ok`).

The changes, in order:

1. cloview-core 0001: this file; `ci_summary.py` writes UTF-8 whatever encoding Python
   would pick for its output, and gains `--require-ok`, which exits 1 unless every
   harness step is "ok"; tests for both.
2. cloview-core 0002: `.github/workflows/windows-harness.yml` installs the R packages the
   bridge test uses (jsonlite, ggplot2, ragg, svglite), logs the versions of R and of the
   packages, and uses `--require-ok`.
3. The owner runs windows-harness on this branch. What real R on Windows shows is fixed
   in further changes of this topic; a large fix becomes a topic of its own
   (`bridge/...`). That run also answers ticket `ops/jobs/20261026-r-on-windows.md`, which
   this topic then retires.
4. cloview-examples 0001, after the cloview-core pull request has merged: its
   `windows-harness.yml` uses `--require-ok` as well.

## Out of scope

- Making `run-windows-checks.ps1` exit non-zero: local runs keep their behaviour.
- Running windows-harness on pushes or pull requests: it stays dispatch-only.
- The "fitness dart: unit tests 0" line in cloview-examples' harness log (its 47 Dart
  tests pass; the Dart oracle line carries no test counts).
- Fixes to the R bridge too large for this topic.

## Dependencies

- cloview-examples' change needs `--require-ok` on cloview-core's `main`: its workflows
  check out cloview-core's default branch unless a run names another `core_ref`. The
  cloview-core pull request merges first.

## Done when

- windows-harness on cloview-core is green with the real-R bridge test running, not
  skipped, and `--require-ok` is covered by the ops tool tests;
- cloview-examples' windows-harness uses the same verdict and is green;
- ticket 20261026-r-on-windows is retired.
