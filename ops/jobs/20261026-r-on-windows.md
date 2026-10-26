---
id: 20261026-r-on-windows
executor: operator
repo_ref: main
timeout_min: 30
needs:
  - Windows 11
  - Clojure CLI (clj), JDK 11 or newer
  - R 4.3 or newer, with Rscript on the PATH (installed by the owner, for example the CRAN
    installer), and the R packages jsonlite (required), ggplot2, ragg and svglite
return: zip to owner
---

# Does the R plot worker run on Windows?

## Why

The real-R test of the bridge (`rplot-test`) passed on Linux with R 4.3.3, but it was
skipped on Windows, which had no `Rscript` (WINDOWS_VERIFICATION_REPORT.md §8.3). Windows
adds its own paths, encodings and process handling.

## Steps

1. **R version:** `Rscript --version`
2. **R packages:** `Rscript -e "for (p in c('jsonlite','ggplot2','ragg','svglite')) cat(p, requireNamespace(p, quietly = TRUE), '\n')"`
3. **bridge suite with real R:** `clj -M:test bridge --out verify/out/r-on-windows.json`

## Collect

- `verify/out/r-on-windows.json`

## Stop rules

- Stop if step 1 fails: verdict `blocked` ("no Rscript on the PATH").
- If step 2 shows `jsonlite FALSE`, stop: verdict `blocked`.

## Questions the notes must answer

- The R version.
- Which packages are present.
- Assertions passed in `cloview.bridge.rplot-test`: 59 means the real-R test ran; 31 means
  it was skipped.
