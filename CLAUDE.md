# cloview-core: notes for Claude Code

One declarative `.cljc` UI rendered natively by cljfx/JavaFX (JVM), WPF (ClojureCLR) and
Flutter (ClojureDart). Start with `README.md` ("Working in this repository") and
`docs/ARCHITECTURE.md`. Decisions are recorded with alternatives in
`docs/STAGE_2_2_DECISIONS.md`; open work is in `docs/ROADMAP_AND_EXTENSIONS.md`.

The example apps (the fitness app and its frozen benchmark) live in **cloview-examples**,
usually cloned next to this checkout (`../cloview-examples`). It uses these modules, this
repository's `verify/static_check.clj` (`--core`/`--app`) and `verify/bb/cloview/tasks.clj`
(see that namespace's docstring); keep those interfaces stable.

## Collaboration contract

Settled rules. They bind every Claude Code session in this repository, whatever a tool or
a default of the product suggests.

1. **Never mutate git history or touch the remote.** No `git commit`, `git push`,
   `gh pr create`, `gh pr merge`, `git merge`, `git rebase`, `git reset`,
   `git cherry-pick`. Read-only git is fine. Deliverables are files on disk.
2. **Dropbox is the transport.** Prefer reading `<DROPBOX>/cloview-core/_inbox/…` before
   asking the owner for anything; deliver artifacts under `<DROPBOX>/cloview-core/_outbox/`.
   Never open a turn with "please upload …"; if a read fails, name the path you tried. If a
   write fails, present the artifact in chat so the owner can download it by hand.
3. **Naming red lines.** Branch names are lowercase ASCII, hyphen-separated, descriptive
   and reasonably short; they never start with `claude/`, `tmp/`, `temp/`, `wip/`, `dev/`
   or `my-`, and are never a bare `patch-N` or `test-N`. Commit messages carry no emoji, no
   AI attribution, no `Co-Authored-By` and no statement about how the change was produced.
4. **Nothing agreed in chat travels forward.** A new session starts empty and reads only
   committed files: whatever is settled must land in a repository file.

How work travels from a session to commits (its format, branches, pull requests) is
designed in [`docs/DELIVERY_DESIGN.md`](docs/DELIVERY_DESIGN.md). Re-read it before your
first delivery in a session, follow the options its decision table marks CHOSEN, and ask
about any still marked PROPOSED.

## Checks that must stay green

```bash
clojure -M:test            # JVM unit tests (all suites); R tests skip without Rscript
clojure -M:static-check    # calls and arities per platform, portability rules, ClojureDart coverage
```

On Windows, these also apply: `verify/run-windows-checks.ps1`, steps 01–06. CI runs the
Linux checks (`.github/workflows/ci.yml`); the harness runs on `windows-harness.yml`. A
change to a module's API should also keep cloview-examples green (`clojure -M:fitness`
and `clojure -M:static-check` there).

## Portability rules (details: ARCHITECTURE.md §6)

- `:cljd` branch first in reader conditionals.
- Only ClojureDart-defined symbols in Dart-compiled files: no `with-redefs`, `format` or
  `slurp` without a reader conditional.
- Ordinal string order, ASCII case changes for identifiers, no `str` of doubles in UI or
  JSON.
- No top-level side effects, no type hints on struct locals in `.cljr`.

## Licences (README, "License"; decisions 98–101)

- This repository is **EPL-2.0 with GPL-2.0-or-later as a Secondary License**: `LICENSE`
  is the verbatim EPL-2.0 text with Exhibit A filled in. cloview-examples is **MIT** on
  purpose. Never "harmonise" the two.
- A new source file starts with `;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later`
  (`#` in Python, inside `.NOTES` in the harness). Exception: files that `bb init` copies
  into user projects (`starter/templates/`, `modules/wpf/host-template/`,
  `modules/bridge/backends/`) get no per-file line.
- Never add a rights-reserved line next to a licence, and never add notes that exclude
  files from the licence.

## Saving tokens and context (docs/DEVELOPMENT_WORKFLOW.md)

- **Delegate execution.** Prefer a CI job to asking anyone to run something. What CI
  cannot run becomes a ticket in `ops/jobs/` (template: `ops/jobs/TEMPLATE.md`) for the
  local operator (`AGENTS.md`) or the owner. Tickets for cloview-examples live here too.
- **Summaries first.** Read `00-summary.json`, `ops/results/<id>/result.json` or the CI
  job summary first. Open logs only for failing steps, with `grep -n` or `tail -n 80`,
  never whole files.
- **Hand off big reads.** Give log reading over about 200 lines to a subagent, and keep
  its conclusion only.
- **Batch.** One run or ticket per change set. Do not poll; do not stream long builds.
- **Keep state in files.** Record each decision in the decision record when it is made,
  and end every session with a short handoff note (what changed, what is open, what to
  run next).
