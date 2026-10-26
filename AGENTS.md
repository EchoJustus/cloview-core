# Instructions for operator agents

This file is for agents that **execute job tickets** in this repository, such as Codex
CLI with a local model or with ChatGPT. You are the **operator**: you run what a ticket
says on a real machine and return a recorded result. Claude Code, the developer, follows
`CLAUDE.md` instead. The full workflow is in `docs/DEVELOPMENT_WORKFLOW.md`.

## Your job

1. **Read the ticket.** It is `ops/jobs/<id>.md`. Check its `needs:` list (tools and
   versions). If something is missing, record it and stop with verdict `blocked`. Do not
   install anything the ticket does not list.
2. **Start the record.** `python ops/tools/result.py new <id>`. Set `CLOVIEW_EXECUTOR`
   first, for example `codex --oss gpt-oss:20b`.
3. **Run every step through the recorder, in order, exactly as written:**
   `python ops/tools/result.py step <id> --title "<step title>" -- <command>`.
   A step the ticket runs in cloview-examples (cloned next to this checkout) gets
   `--cwd ../cloview-examples`.
   - Do not change the commands. If one cannot work as written (wrong path, missing
     file), stop and report it.
   - Follow the ticket's stop rules. Without one: stop at the first failing step.
4. **Collect.** Copy the files the ticket asks for into `ops/results/<id>/artifacts/`.
5. **Finish.** `python ops/tools/result.py finish <id>`, adding at most 10 short
   `--note` lines that answer the ticket's questions. Notes are facts: what you saw,
   with numbers. No guesses about causes, no suggested fixes.
6. **Pack and return.** `python ops/tools/result.py pack <id>` checks the record and
   writes `ops/results/<id>.zip`. Deliver it the way the ticket says: a results branch
   `ops-results/<id>`, or the zip handed to the owner.

## Rules

- **Never edit tracked files.** This covers code, tests, docs, `deps.edn`, `bb.edn`,
  workflows and the harness. The only files you write are under `ops/results/<id>/`, plus
  what the ticket's own commands write (build output, `verify/out/`).
- **Never commit to `main`,** never push anything except a results branch the ticket
  names, and never force-push.
- **Never "fix" a failure.** A failing step is a result, not a problem for you to solve.
  Record it and stop or continue as the ticket says.
- **Retry once at most,** and only for a network error (download timeout, connection
  reset). The recorder logs both attempts.
- **Interactive prompts.** If a command asks a question you cannot answer from the
  ticket, stop it, record `blocked`, and say what was asked.
- **No secrets** in notes, logs or artifacts: no tokens, passwords or keys. The recorder
  replaces user names in home paths. Do not undo that.
- **Run from a separate clone or worktree** on branch `ops/<id>`, at the ticket's
  `repo_ref`, never in the owner's development checkout.

## Summarizing logs (local-model task)

When a ticket asks for a log digest, write at most 15 lines:

- the step;
- the first error line, verbatim;
- the 3 lines before it;
- counts (tests, failures) when the log has them.

Never paraphrase an error message, and never summarize a log you did not read.
