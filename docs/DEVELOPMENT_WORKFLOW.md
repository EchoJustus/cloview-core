# Development workflow: who does what

From Stage 3 on, the repository is advanced by a Claude Code master session. This
document divides the work so that Claude Code's tokens and context go to work that needs
judgment, while running, waiting, logging and packaging go to cheaper executors.

## 1. The rule: the cheapest executor that can do the job reliably

| Executor | Use it for | Never for |
|---|---|---|
| **1. A script or CI job** (no model) | every check that a fixed command can run: tests, static check, oracles, the Windows harness, summaries, packaging | — |
| **2. A local operator**: Codex CLI with a local model through Ollama, or with ChatGPT | job tickets that need a real machine but no new code: running given commands, collecting results, reproducing a reported bug with given steps | changing code, configuration or toolchains; deciding what a failure means |
| **3. Claude Code** | designing, writing code and tests, CI and workflows, analysing results, fixing bugs, evaluating options online, writing tickets | waiting on long builds, reading whole logs, repeating checks CI already runs |
| **4. The owner** | looking at the running apps (visual checks), approvals, credentials, pushing and releasing | routine runs |

Two consequences:

- **Prefer CI to a ticket.** Before asking a person or a local model to run something,
  check whether a GitHub-hosted runner can run it (§2). A CI job costs no tokens.
- **Results are files, not chat.** Every executor returns a short structured summary
  first (`00-summary.json`, `result.json`, the CI job summary) and keeps the raw logs
  next to it. Claude Code reads the summary and opens a log only where a step failed.

## 2. Lane 1: CI on GitHub-hosted runners

"GitHub Actions usage is free for self-hosted runners and for public repositories that
use standard GitHub-hosted runners"
([GitHub billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions),
read 2026-10-25). The repository ships two workflows:

| Workflow | Runs | What |
|---|---|---|
| `.github/workflows/ci.yml`, job `linux` | every push to `main` and every pull request (not for Markdown-only changes) | JVM unit tests with real R, static check, the Greeter's off-screen JavaFX smoke under Xvfb (experimental until its first green run), the tests of the ops tools (result recorder, CI summary) |
| `ci.yml`, job `clr` | same; experimental (`continue-on-error`) until its first green run | the portable suites (core, fx, bridge, licensing) on ClojureCLR through the `Clojure.Main` tool |
| `.github/workflows/windows-harness.yml` | by hand: Actions → Run workflow, or `gh workflow run windows-harness.yml` | the full Windows harness (steps 01–06) on `windows-2025`; its artifact is the zip the owner used to upload by hand |

cloview-examples has its own CI, which checks out cloview-core next to it
(`../cloview-core`): its `ci.yml` runs the fitness unit tests and the 37 oracles, the
static check of `apps/` and the benchmark's checkers, plus an experimental JavaFX smoke
and an experimental CLR job, and
its `windows-harness.yml` runs its own Windows harness (steps 01–05) on demand. Started
by hand, both take a `core_ref` input: the cloview-core branch, tag or commit to test
against.

All four workflows have run on GitHub since 2026-10-26. Both `ci.yml` workflows and
cloview-examples' `windows-harness.yml` were green that day; cloview-core's
`windows-harness.yml` is green since the topic `ci/windows-harness-green` gave it the R
packages of the real-R bridge test and a verdict that fails on any step that is not "ok"
([topics/ci-windows-harness-green.md](topics/ci-windows-harness-green.md)). If the hosted
Windows runner cannot run a GUI step, record why in WINDOWS_VERIFICATION_REPORT.md and
move that step to a ticket (§4).

**Reading CI results without flooding the context:**

```bash
gh run list --limit 5                          # the latest runs and their status
gh run view <run-id>                           # jobs and steps, with the failing step marked
gh run view <run-id> --log-failed | tail -n 80 # only the failing step's log, last lines
gh run download <run-id> -n linux-results      # the JSON summaries (verify/out)
```

Every job also writes a Markdown summary (`ops/tools/ci_summary.py`), shown on the run's
page. The windows-harness job passes only when every harness step is "ok" (its Verdict
step, `ci_summary.py --require-ok`), since the harness itself exits 0. Artifacts of a
public repository are public; the harness logs hold versions and paths, no secrets.

## 3. Lane 2: your own PC as a self-hosted runner (optional)

For what a hosted runner cannot do: your monitor, GPU or phone, long benchmarks, or a
local model in the loop. GitHub's guidance is blunt: "self-hosted runners should almost
never be used for public repositories on GitHub", "because any user can open pull
requests against the repository and compromise the environment"
([secure use](https://docs.github.com/en/actions/reference/security/secure-use), read
2026-10-25). If you use one anyway:

- trigger its workflows only with `workflow_dispatch`, which needs write access, never
  with `pull_request`;
- give its jobs an environment that requires your approval;
- run the runner as a normal (non-admin) user, from its own folder;
- or register it on a private mirror of the repository instead.

## 4. Lane 3: the local operator

### What it does

An operator executes a **job ticket** (`ops/jobs/<id>.md`): exact commands, what to
collect, and when to stop. It records every step with `ops/tools/result.py`, which
writes exit codes, timings, logs and failure excerpts itself, so nothing is retyped.
Then it returns the packed result. It does not change code, configuration or toolchains,
and it does not interpret failures. Its rules are in [`AGENTS.md`](../AGENTS.md), which
Codex reads automatically.

### Setting it up

- **Codex CLI with a local model.** `codex --oss` uses Ollama's default local model,
  gpt-oss:20b; `codex --oss -m gpt-oss:120b` uses the larger one. Ollama recommends "a
  context window of at least 32K tokens" ([Ollama: Codex](https://docs.ollama.com/codex),
  read 2026-10-25). This lane costs no cloud tokens at all.
- **Codex CLI with ChatGPT.** For tickets where the local model loses track. This uses
  your ChatGPT quota, not Claude's.
- **Unattended.** `codex exec "Execute the job ticket ops/jobs/<id>.md as AGENTS.md
  describes"` runs a ticket non-interactively, for example from a scheduled task.
- **Without a model.** Most tickets are plain command lists, and `result.py` can be
  driven by a short PowerShell loop. Use a model when a ticket needs small judgments,
  such as "stop if the window does not appear".
- **Working copy.** Use a separate clone or worktree on a branch `ops/<id>`, never your
  development checkout.

### How Claude Code checks an operator's work

Claude Code trusts the recorded facts, not the operator's prose:

- exit codes and excerpts written by `result.py`;
- raw logs;
- artifact hashes, checked by `result.py check`.

When the notes and the logs disagree, the logs win. A ticket that came back with a
problem in `check` is re-run, not repaired by hand.

## 5. The interface: tickets and results

### Tickets

`ops/jobs/<id>.md`, with the id `YYYYMMDD-short-name`. The template is
[`ops/jobs/TEMPLATE.md`](../ops/jobs/TEMPLATE.md):

- front matter: `id`, `executor` (`ci` | `operator` | `owner`), `repo_ref`, `timeout_min`,
  `needs`;
- the steps, as exact commands;
- what to collect;
- the stop rules;
- the questions the result must answer.

### Results

`ops/results/<id>/`:

| Path | Content |
|---|---|
| `result.json` | schema `cloview-job-result/1`: job, executor, repo ref, start and end, machine, steps (`n`, `title`, `cmd`, `cwd`, `exit`, `seconds`, `log`, `excerpt` of at most 40 lines), artifacts with SHA-256, verdict (`pass` / `fail` / `blocked`), at most 10 notes |
| `logs/NN.log` | the full output of step NN, with the user name in home paths replaced by `<user>` |
| `artifacts/` | files the ticket asked for (screenshots, JSON, zips) |

```text
python ops/tools/result.py new    20261026-bb-init-windows
python ops/tools/result.py step   20261026-bb-init-windows --title "generate" -- bb init ../try-app --target wpf
python ops/tools/result.py finish 20261026-bb-init-windows
python ops/tools/result.py pack   20261026-bb-init-windows     # checks, hashes, zips
```

### Getting results back to Claude Code

In order of preference:

1. **CI artifacts**, for CI runs.
2. **A results branch**, `ops-results/<id>`, with only `ops/results/<id>/` committed.
   Never merge it into `main`, and remember that branches of a public repository are
   public. For anything private, push to a private repository that Claude Code can read.
3. **The result files** (`result.json`, `logs/`), unpacked into
   `<DROPBOX>/cloview-core/_inbox/tickets/<id>/`: the session's Dropbox connector reads
   text files, not zips ([DELIVERY_DESIGN.md](DELIVERY_DESIGN.md) §2, §3.4).

## 6. Claude Code's rules for tokens and context

These are also in [`CLAUDE.md`](../CLAUDE.md), which Claude Code loads in every session.

- **Read summaries first.** `00-summary.json`, `result.json` or the job summary come
  first. Open a log only for a failing step, with `grep -n` or `tail -n 80`, never whole.
- **Delegate log triage.** When more than about 200 lines must be read, give that to a
  subagent and keep only its conclusion.
- **Batch.** One ticket or CI run should check everything that a change might break, not
  one thing per round trip.
- **Do not wait in the conversation.** Deliver the work and let CI run, on the pull request
  or on a run the owner starts by hand on the branch; do not poll in short loops or stream
  long build output.
- **Keep state in files.** Decisions go to the decision record, open work to the
  roadmap, and a short handoff note goes at the end of each session. Start a new session
  per milestone rather than letting one session compact again and again.

## 7. One loop, end to end

1. Claude Code changes code and tests, runs the fast Linux checks in its own container,
   and delivers the work as files. It never commits or pushes: you review, commit and push
   ([DELIVERY_DESIGN.md](DELIVERY_DESIGN.md), [COLLABORATION.md](COLLABORATION.md)).
2. It reads the job summary of the CI run on the pull request, or of a run you started by
   hand on the branch. A failure costs one `--log-failed` excerpt.
3. For Windows behaviour, you dispatch `windows-harness.yml` (or run the harness yourself)
   and it reads the result. What CI cannot run, it writes as a ticket.
4. An operator (or you) runs the ticket and returns the result.
5. Claude Code reads `result.json`, fixes, and repeats.
6. You look at screenshots and the running apps when a visual judgment is needed, and
   release.

**Rough cost of one Windows verification round:**

- **Today:** you run the harness and upload about 200 KB of logs, and Claude Code reads
  the summary plus several logs, roughly 20–60k tokens.
- **With this workflow:** a CI run plus `00-summary.json`, and one failing excerpt when
  something breaks, roughly 2–5k tokens.

These are estimates from the size of the last result zip, not measurements.

## 8. First Stage 3 work

For the owner, first: push both repositories, cloview-core first (cloview-examples' CI
checks it out), and dispatch both `windows-harness.yml` workflows once.

For Claude Code, delivering fixes as files (DELIVERY_DESIGN.md):

1. Make both `ci.yml` workflows green. Then confirm the experimental `clr` jobs and either
   keep them or remove them.
2. Make both `windows-harness.yml` workflows green (the owner dispatches them), or record
   which steps a hosted runner cannot run.

For the operator (tickets in `ops/jobs/`):

- `20261026-bb-init-windows`: generate a project for each target, then run its own
  `bb test`, `bb test:clr` and a timed `bb dev` start.

For the owner:

- `20261026-visual-check`: screenshots of the Greeter and of the fitness app (run from
  cloview-examples) on the three hosts, compared by eye with cloview-examples'
  `benchmarks/fitness-baseline/visuals/`.
