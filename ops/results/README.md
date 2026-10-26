# Job results

`ops/tools/result.py` writes one folder per job here: `<id>/result.json`, `<id>/logs/`,
`<id>/artifacts/`, and `pack` adds `<id>.zip`. Results travel on a results branch
(`ops-results/<id>`) or as the zip. They are never committed to `main`, which keeps only
this file.

`result.json` (schema `cloview-job-result/1`):

| Key | Content |
|---|---|
| `job`, `executor`, `repo_ref` | which ticket, who ran it, at which commit |
| `started`, `finished`, `machine` | when, and on what (OS, Python) |
| `steps` | `n`, `title`, `cmd`, `cwd`, `exit` (null after a timeout), `seconds`, `log`, `excerpt` (failure lines with context, at most 40) |
| `artifacts` | `path` and `sha256` of each collected file |
| `verdict` | `pass`, `fail` or `blocked` |
| `notes` | at most 10 lines of facts |

`python ops/tools/result.py check <id>` validates a result; the developer trusts the
recorded exit codes, logs and hashes, not the notes.
