---
id: 20261026-visual-check
executor: owner
repo_ref: main
timeout_min: 45
needs:
  - the toolchains of VERIFY_ON_WINDOWS.md (JDK, .NET, Flutter, bb, clj)
  - cloview-examples cloned next to this checkout (../cloview-examples), for steps 4-6
return: zip to Claude Code
---

# Do the apps look right on each host?

## Why

Smokes check structure and events, never pixels (NATIVE_CONTRACTS.md in cloview-examples,
note ¹). A person has to look once per host, and again after visual changes.

## Steps

For each command:

- start the app;
- take a screenshot of the whole window (Win+Shift+S, then save the PNG);
- for the fitness app, take one per tab: Catalog, Plan Builder, Execution Log,
  Anatomy & Analytics, Calendar;
- close the app.

In this checkout:

1. `bb run:javafx` (Greeter on JavaFX)
2. `bb run:wpf` (Greeter on WPF)
3. `bb run:flutter` (Greeter on Flutter)

In `../cloview-examples` (record these steps with `--cwd ../cloview-examples`):

4. `bb run:fitness:javafx`
5. `bb run:fitness:wpf`
6. `bb run:fitness:flutter`

## Collect

Name the screenshots `<host>-<app>-<screen>.png`, for example
`wpf-fitness-calendar.png`, and put them in `ops/results/<id>/artifacts/`.

## Questions the notes must answer

- Anything clipped, overlapping, unreadable or missing, with the screenshot name.
- Differences between the hosts that look like bugs rather than native style (the
  reference renders are in `../cloview-examples/benchmarks/fitness-baseline/visuals/`).
