---
id: YYYYMMDD-short-name
executor: operator          # operator | owner | ci
repo_ref: main              # a branch, tag or commit; run exactly this
timeout_min: 60
needs:                      # tools and versions that must already be installed
  - Windows 11, PowerShell 5.1
  - babashka (bb), Clojure CLI (clj)
return: zip to owner        # or: results branch ops-results/<id>
---

# <What this job finds out, in one sentence>

## Why

One or two sentences: what decision the result feeds.

## Steps

Run from the repository root unless a step says `--cwd`. Record each one with
`python ops/tools/result.py step <id> --title "<title>" [--cwd DIR] -- <command>`.

1. **<title>:** `<exact command>`
2. **<title>:** `<exact command>`

## Collect

Copy into `ops/results/<id>/artifacts/`:

- `<path>`: <what it is>

## Stop rules

- Stop at the first failing step, unless a step says "continue on failure".
- <other conditions, e.g. "stop if a window stays open after the timeout">

## Questions the notes must answer

- <a fact the result must state, with a number>
