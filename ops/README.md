# ops: job tickets and their results

Execution work that CI cannot do is written down here as **tickets**, run by a local
operator (`AGENTS.md`) or by the owner, and returned as **recorded results**. Why and how:
[`docs/DEVELOPMENT_WORKFLOW.md`](../docs/DEVELOPMENT_WORKFLOW.md).

| Path | What |
|---|---|
| `jobs/TEMPLATE.md` | the ticket format |
| `jobs/<id>.md` | tickets; the id is `YYYYMMDD-short-name` |
| `results/<id>/` | a recorded result: `result.json`, `logs/`, `artifacts/` (on a results branch, never on `main`) |
| `tools/result.py` | records steps, checks and packs a result (Python 3, standard library) |
| `tools/ci_summary.py` | the Markdown summary of `verify/out` that CI shows |
| `CLAUDECODE_BOOTSTRAP_PROMPT.md` | the prompt that starts a Claude Code session on the web ([`docs/COLLABORATION.md`](../docs/COLLABORATION.md)) |

Recording a ticket:

```text
python ops/tools/result.py new    <id>
python ops/tools/result.py step   <id> --title "<title>" [--cwd DIR] -- <command>
python ops/tools/result.py finish <id> [--note "<fact>"]
python ops/tools/result.py pack   <id>        # ops/results/<id>.zip
```
