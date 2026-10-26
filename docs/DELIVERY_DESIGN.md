# Delivery design: from a read-only session to reviewed commits

**Status: proposal.** This note answers five open questions about how work travels from a
Claude Code session on the web to commits on the maintainer's machine. It is written to be
argued with: each recommendation says what would make me change it. Nothing here binds a
session until the decision table (§0) marks a row CHOSEN.

The four hard constraints are settled and are not reopened here. They are the
"Collaboration contract" in [`CLAUDE.md`](../CLAUDE.md), summarised in §1.1. The owner's
side of the recommended options is written out in [`COLLABORATION.md`](COLLABORATION.md).

Terms used throughout:

- **session**: a Claude Code session on claude.ai/code, running in a cloud VM, with
  read-only git.
- **owner**: the maintainer, on Windows, with GitHub Desktop. (The "operator" of
  [DEVELOPMENT_WORKFLOW.md](DEVELOPMENT_WORKFLOW.md) is a different role: the local agent
  that runs job tickets.)
- **change**: one delivered unit of work, meant to become exactly one commit.
- **topic**: a deliberate, named piece of work (a feature, a milestone of a module, a
  release milestone) with one branch and, in the end, one pull request.

## 0. Decisions

To decide, replace PROPOSED with CHOSEN, or with "CHOSEN: " and your variant. A session
follows the CHOSEN rows. Before its first delivery it asks about every row that is still
PROPOSED, then delivers your answers as a change to this table.

Sessions read this table from `main`. Edit it on `main` (the first commit is the natural
moment), or merge the change that records your answers before you start the next session:
on a topic branch it reaches no later session.

| # | Question | Recommendation | Status |
|---|---|---|---|
| D1 | What is a delivery? (Q1) | One text file per intended commit: a cover note, the output of `git diff --binary --full-index -M`, an end line (§3.4) | CHOSEN |
| D2 | How does it become a commit? (Q1) | The owner pastes one guarded `git apply` command, reviews the result in GitHub Desktop and commits there (§3.5) | CHOSEN |
| D3 | How do both sides agree on a commit? (Q2) | Each change names its branch, its base (a pushed commit, by commit and tree id) and its expected result tree; the owner pushes after each commit; stacking on unpushed changes only on request (§4) | CHOSEN |
| D4 | Branch lifecycle (Q3) | Long-lived topic branches with a committed scope file, many changes per topic, one change in flight in the working copy; a topic ends with its pull request (§5) | CHOSEN |
| D5 | Branch names (Q3) | `<area>/<topic>`, the area from a fixed list (§5.4) | CHOSEN |
| D6 | Unit of review (Q4) | One pull request per topic branch, squash-merged (§6) | CHOSEN |
| D7 | Commit messages | `<area>: <imperative summary>`, a body that says why, no trailers (§7) | CHOSEN |
| D8 | Who does what (Q5) | The session drafts, the owner decides and files (§8) | CHOSEN |
| D9 | Guards for constraint 1 | G1 + G2 + G4 (§2.2) | CHOSEN |
| D10 | Dropbox layout | A root per repository: `<DROPBOX>/<repository>/_outbox/<area>/<topic>/NNNN-<slug>.patch`, with `main/` in place of `<area>/<topic>/` for a change committed directly on `main`, and `_inbox` laid out alike (§3.4) | CHOSEN |
| D11 | Readings of the constraints | I1–I6 as written (§1.2) | CHOSEN |

## 1. The problem and what is fixed

A read-only session produces work. The owner, on Windows with GitHub Desktop, turns it into
commits that he reviews before committing and before pushing. Dropbox sits between them.
The mechanism has to survive binary files, renames, deletions and line-ending differences;
let several deliveries share one deliberate branch without topics interleaving; make it
obvious what has and has not been applied, also after a partial failure; and give both
sides a reliable name for the commit they are talking about.

### 1.1 The hard constraints

1. The session never mutates git history or touches the remote.
2. Dropbox is the transport, one root per repository: `<DROPBOX>/<repository>/_inbox/`
   towards the session, `<DROPBOX>/<repository>/_outbox/` towards the owner, the chat as
   the fallback when a write fails.
3. Naming red lines for branches and commit messages.
4. Nothing agreed in chat travels forward; what is settled lands in repository files.

A single rehearsal on the owner's machine showed that delivered files can become ordinary
local commits without being pushed. Nothing else from it is assumed here.

### 1.2 Readings this design depends on

Confirm or correct these (D11). Each says what changes under a different reading.

- **I1. "Touch the remote" means writing to GitHub in any form**: pushing, creating or
  deleting branches and tags, opening or merging pull requests, reviews, comments,
  issues, releases, workflow dispatches. Reading is "read-only git": cloning, `git fetch`,
  and REST reads of commits, pull requests and CI runs. *If fetching counts as touching, a
  session cannot see your pushes while it runs, and Q2 falls back to candidate C, a new
  session for every new base.*
- **I2. "Read-only git" includes commands that change only the session's own working
  directories or add unreferenced objects**: `status`, `log`, `show`, `diff`, `ls-tree`,
  `rev-parse`, `worktree add --detach`, `apply --check`, and building a patch through a
  throwaway index (§3.4). The session never creates, moves or deletes a branch or a tag
  and never edits `.git/config`. *If even the throwaway index is too much, new files can
  be diffed one at a time with `git diff --no-index`; the result is the same, the
  procedure clumsier.*
- **I3. The commit-message red lines also bind pull request titles and descriptions.** A
  squash merge makes the title the summary line on `main`, and under GitHub's "title and
  description" setting the description becomes the body; keeping both clean keeps every
  setting safe.
- **I4. `<DROPBOX>` is the Dropbox root, and each repository has its own folder in it.**
  For the connector these are `/cloview-core/_inbox/`, `/cloview-core/_outbox/`,
  `/cloview-examples/_inbox/` and `/cloview-examples/_outbox/`. What belongs to no single
  repository (handoff notes, ticket results, general notes) goes to cloview-core's root,
  where every session starts. *Going back to one shared root
  (`cloview-core/_outbox/<repository>/…`) would change D10 and constraint 2.*
- **I5. The branch red lines apply to every branch that can reach your machine or
  GitHub.** A session creates no branches at all (I2); the branch named `claude/…` that a
  cloud session starts on is never pushed, never named in a delivery and never leaves the
  VM.
- **I6. Dropbox holds working material, not agreements.** Drafts (`PR.md`), handoff notes
  and the deliveries themselves live there; anything settled (a decision, a topic's scope)
  reaches a repository file through a change. *If you also want handoff notes in the
  repository, they become a change at the end of each working session.*

## 2. What the products actually do

Checked on 2026-10-26 against the vendors' documentation, their issue trackers and source
code, the tool descriptions of the Dropbox connector, and git 2.43 in throwaway
repositories. The git experiments used the settings GitHub Desktop has on Windows
(`core.autocrlf=true`, `core.fileMode=false`) and committed the way Desktop commits
(staging area cleared, files staged, commit). Sources are in §11.

### 2.1 Facts that shape the design

| # | Fact | Consequence |
|---|---|---|
| F1 | A cloud session clones the repository fresh from GitHub, at the branch selected when it starts ("Claude clones the repo fresh every session"). Started from a terminal, the docs add: "not your local checkout, so push first if you have local commits". | Only pushed commits can be named by both sides (D3). |
| F2 | The session's default is to publish: "when Claude reaches a stopping point, it pushes its branch to GitHub". In Accept edits mode "Claude makes changes and pushes a branch without stopping for approval". Cloud sessions offer Accept edits, Plan and Auto; Manual is not available. | Constraint 1 runs against the product's default, and the permission modes do not stop a push. It needs guards (§2.2). |
| F3 | Auto mode's classifier allows "pushing to any branch of the repository you're working in" by default. It treats a boundary stated in the conversation ("don't push") as a block signal, but such a boundary "can be lost if context compaction removes the message that stated it". CLAUDE.md is "context, not enforced configuration". A session with several repositories "starts above the clones", and CLAUDE.md files in subdirectories load only once Claude reads, writes or edits a file there. | Instructions alone are not a guarantee. The bootstrap prompt makes the session read every `CLAUDE.md` first, and the environment can load the contract at launch from `~/.claude/CLAUDE.md` (G1). |
| F4 | A repository's `.claude/settings.json` permission rules are read in a session with one repository; a session with several repositories reads none of its permission rules or hooks. A deny rule matches the command as written: `git -C . push` escapes `Bash(git push *)`. | Deny rules are a seatbelt for single-repository sessions, not a boundary. Work on cloview-examples needs both repositories, hence a multi-repository session. |
| F5 | With the browser GitHub connection "a session can clone any public repository"; private ones need the Claude GitHub App. A GitHub App's user token "can only access resources in an account where it is installed", within that installation's permissions. `/web-setup` instead gives sessions your own `gh` token. | If the App's repository access excludes these two public repositories and `/web-setup` is not used, sessions can read them but GitHub refuses their writes (G2). |
| F6 | The Changes card and the diff view of a session appear "after Claude pushes its changes to a branch". | Not available in this flow. Review happens in GitHub Desktop. |
| F7 | An idle session stops and "the session's VM is reclaimed"; reopening restores the conversation, not running work. Waiting for the approval of an MCP connector call counts as idle. | Whatever has not reached Dropbox can be lost: the session delivers at every stopping point. Approve the Dropbox tool promptly. |
| F8 | The VM ships OpenJDK 21, git and `gh`, but no Clojure CLI and no .NET SDK, and clojars.org is not on the default network allowlist. An environment's setup script runs as root and can also write `~/.claude/CLAUDE.md`, which every session of that environment then loads. | The session runs the Linux checks only after a setup script and a custom allowlist ([COLLABORATION.md](COLLABORATION.md), Day 1); the same script can carry G1 and G4. |
| F9 | The Dropbox connector's `create_file` creates a new UTF-8 text file from a JSON string, at most 5 MB, in an existing folder, and warns against content "that must preserve control bytes". `fetch` reads text up to 5 MiB. The connector can neither overwrite nor edit a file, and cannot upload a binary one. The content is an argument of the tool call, which the session writes out as its own output; output per response is limited ("defaults and caps vary by model"; 32,000 tokens by default for a model Claude Code does not recognise). | A delivery must be one file of ordinary text, and its practical size is set by one response's output, far below 5 MB: on the order of 100 KB of diff text, less for binary content (§3.4). Because the session copies the file into the call itself, it compares the read-back with its local file byte for byte. Patches here contain no CR bytes, since the repositories store text with LF (frozen `-text` paths excepted). Status cannot live in a file the session rewrites: it lives in git and in where you move files. |
| F10 | When two edits of a Dropbox file clash, Dropbox keeps both and renames one a "conflicted copy"; it does not merge. | Nobody edits a delivered file, names are unique, and no git working copy lives inside Dropbox. |
| F11 | GitHub Desktop cannot apply a patch; its maintainers declined the feature because "our focus is on the pull request development flow" (desktop#16417). Its bundled Git sits in a versioned folder inside the application and is not on PATH: a shell opened from Desktop without a separate Git reports "Unable to locate Git" (desktop#15160, #22821). | The one extra install is Git for Windows, used for one command per change. |
| F12 | When Desktop commits it first clears the staging area, because "our diffs reflect the difference between the working directory and the last commit (if any) so our commits should do the same thing", then stages the files you selected. | Anything staged beforehand, for example by `git apply --index`, is ignored. With `core.fileMode=false` an executable bit added by a change never reaches a Desktop commit (verified: mode 100755 became 100644). Changes therefore never change file modes. |
| F13 | Desktop's bundled Git on Windows is built with `core.autocrlf true` and `core.symlinks false` in its system config (dugite-native's `build-win32.sh`; desktop#16522). | The repositories' `.gitattributes` decide line endings instead: `* text=auto eol=lf`, CRLF in the working tree only for `*.ps1`, `*.cs` and `*.csproj`, and `-text` for the frozen benchmark of cloview-examples. Verified: a Desktop-like checkout and the Linux session end with identical trees. Symlinks would arrive as plain files, so none are used. |
| F14 | Desktop: switching branches with uncommitted changes asks whether to leave the changes on the current branch (stashed, one stash at a time) or bring them to the other branch; Undo works on commits not yet pushed; Revert, Cherry-pick, Squash and Amend exist; History offers Create Branch from Commit; Preview Pull Request shows the diff against the base branch, then the browser finishes the pull request; discarded changes go to the Recycle Bin; a rename not yet committed shows as a deletion plus a new file (desktop#8420). | Every recovery step in this design is a Desktop button. |
| F15 | `git apply` "fails the whole patch and does not touch the working tree when some of the hunks do not apply". But "when running from a subdirectory in a repository, patched paths outside the directory are ignored", and a file cut exactly at a file boundary is a shorter valid patch (verified: 4 of 7 files applied). | A change is applied completely or not at all only when the command runs at the repository root on a complete file: the Apply line enforces both (§3.5). |
| F16 | `git apply` reads working-tree files through the `.gitattributes` conversion and writes them back the same way. | One patch serves the LF session and a Windows checkout with CRLF scripts (verified with a `.ps1` change). |
| F17 | `git diff --full-index` records the full blob ids before and after each file. For binary files git checks both ids when applying (verified: a wrong result id makes it refuse the whole patch). `git apply --3way` uses the ids to fall back to a three-way merge when those blobs exist locally; it implies `--index`, so it stages the result and can leave unmerged entries. | Conflicts can be resolved locally, but how Desktop presents unmerged entries left by `--3way` is untested: a rebuild by the session is the default (§4.4). |
| F18 | `git apply` ignores text before the first `diff --git` header and after the last hunk (verified, even with a cover-note line shaped like a `---`/`+++` header). It refuses a patch file saved with CRLF line ends ("git diff header lacks filename information") and tolerates blank context lines whose single space was stripped. | Cover note, diff and end line fit in one file, and the usual ways of damaging the file fail safely. |
| F19 | Web sessions install a Stop hook, `~/.claude/stop-hook-git-check.sh`, that refuses to let the session stop while the repository has modified, staged or untracked files or unpushed commits, and it is reinstalled in every session (reported in anthropics/claude-code#86379, unanswered). | The session keeps its main checkout clean: it edits only in detached worktrees outside it and writes every scratch file outside the repository (§3.4), so the hook has nothing to object to. It never commits to satisfy the hook. The first session confirms this (§9). |

### 2.2 Making constraint 1 hold (D9)

None of the four constraints is unworkable, but constraint 1 does not come free: the
session's default (F2) is to commit and push, no cloud permission mode asks first, and a
Stop hook (F19) nudges towards commits. The guards, strongest first:

| Guard | What it stops | Limits |
|---|---|---|
| **G2. Credentials.** The Claude GitHub App's repository access excludes cloview-core and cloview-examples (GitHub → Settings → Applications → Installed GitHub Apps → Claude → Repository access), and GitHub is connected through the browser, not with `/web-setup`. | Every write from a session to these repositories: pushes, pull requests, comments, dispatches (F5). | Holds only while those settings hold. Check the setting; do not test it by letting a session push. |
| **G4. Hooks in the cloud environment.** The environment's setup script installs system-wide git hooks that refuse to run (below). | `git commit`, merges that create a commit, `git rebase`, `git pull --rebase` and `git push`, in every session of that environment, with one repository or several (verified with git 2.43). | Pass anyway: `--no-verify`, `cherry-pick`, `revert`, `reset`, `am`, `stash`, `tag`, branch creation, and fast-forward merges (so a fast-forward `git pull`). Configured in your claude.ai environment, outside the repository. |
| **G1. Instructions.** The contract in each `CLAUDE.md`, restated by the bootstrap prompt, and written by the setup script to `~/.claude/CLAUDE.md` so that it loads at launch even in multi-repository sessions (F3, F8). | The session's own choices; in Auto mode the classifier also blocks actions that cross a boundary stated in the conversation, such as the bootstrap prompt (F3). | Instructions, not a lock. |
| G3. Deny rules in `.claude/settings.json` for the eight commands. | Their usual spellings, in single-repository sessions. | Not read in multi-repository sessions; `git -C . push` escapes (F4); also binds any local Claude Code session in these repositories. |
| G5. A ruleset on `main` that requires a pull request. | Direct pushes to `main` by anyone, you included. | Nothing on other branches. |

Recommendation: **G1 + G2 + G4.** G2 is the only real boundary: it decides what can reach
GitHub. G4 is a cheap belt for what G2 cannot see, commits made inside the VM, which would
also break patch generation (a change is computed against its base). G1 keeps the rules in
view. All three need nothing in the repository beyond what is there, and G1 and G4 share
the setup script the environment needs anyway. G5 is good practice whatever is decided
here. I have not added G3: it covers only single-repository sessions and also binds local
sessions; it is one small file if you want it.

G1 and G4, as lines of the environment's setup script (which runs as root):

```bash
mkdir -p /root/.claude /etc/git-hooks-readonly
cat > /root/.claude/CLAUDE.md <<'EOF'
For cloview-core and cloview-examples: never commit, push, merge, rebase, reset or
cherry-pick, and never open or merge pull requests. Deliverables are files in Dropbox.
Before anything else, read CLAUDE.md ("Collaboration contract") of every attached
repository.
EOF
for h in pre-commit pre-merge-commit pre-rebase pre-push; do
  printf '#!/bin/sh\necho "git history is read-only in this environment (CLAUDE.md)" >&2\nexit 1\n' \
    > /etc/git-hooks-readonly/$h
  chmod +x /etc/git-hooks-readonly/$h
done
git config --system core.hooksPath /etc/git-hooks-readonly
```

*Overrule* if you would rather not depend on the GitHub App setting: G4 and G1 then carry
constraint 1, with the gaps listed above.

## 3. Q1: how the session's work becomes local commits

### 3.1 Candidates

**A. A patch per commit, applied by git, committed in Desktop.** The session writes one
text file per intended commit: a cover note (commit message, branch, base, tests), the
output of `git diff --binary --full-index -M` against the base, and an end line. The owner
pastes one guarded `git apply` command (§3.5). The change appears in Desktop's Changes tab
as uncommitted edits; he reviews it, discards or edits what he does not want, and commits
with the message from the cover note.

**B. Whole-file snapshots, copied by hand, committed in Desktop.** The session writes the
changed files at their repository paths under a delivery folder, plus a list of files to
delete. The owner copies the folder over his working copy, deletes the listed files,
reviews in Desktop and commits. No command line at all.

**C. A patch per commit, applied and committed in one step.** As A, but one command also
commits: `git am` on a mail-formatted patch, or `git apply` followed by `git commit -F`
with the message taken from the file. The review happens on the patch text before running
it, or in Desktop's history afterwards.

Not candidates: anything in which the session pushes (constraint 1); a git bundle (needs
commits made by the session); a base64 archive of the changed files (a snapshot with an
extra decoding step, so B's weaknesses plus a tool).

### 3.2 How they compare here

| | A: patch, Desktop commits | B: snapshot, Desktop commits | C: patch, one step |
|---|---|---|---|
| Review before the commit | Full: Desktop diff, discard by line, edit | Full | Only by reading the patch text |
| Binary files | Small ones, as base85 text inside the patch | No: the connector writes text only (F9) | Small ones |
| Size the session must write out (F9) | Only the changed lines and their context | Every changed file, whole | As A |
| Renames and deletions | Recorded in the patch | By hand: a deletion list; a rename is a copy plus a deletion | Recorded in the patch |
| Line endings | Converted by git both ways (F16) | Copied bytes; harmless while `.gitattributes` holds | As A |
| Notices that you have moved on | Yes: the guard compares the base tree, git checks every hunk (F15, F17) | No: an older copy silently reverts your newer edit | As A |
| Partial failure | All or nothing, enforced by the Apply line (F15) | A half-finished copy or sync leaves a mixed tree | As A, plus a commit to undo |
| Files per change | One | Many; a partial sync can show some of them | One |
| Readable before applying | Yes, in Dropbox's preview or any editor | File by file, without a diff | Yes |
| Needs | Git for Windows; one pasted command | Nothing | Git for Windows; `git am` also needs an author line in the file |

### 3.3 Recommendation: A

A is the only candidate that keeps the review before the commit and also survives
binaries, renames, deletions and line endings without bookkeeping by hand. Product facts
decide it. The Dropbox connector writes only text, and only as much as the session can
write out in one response (F9): that rules B out for any binary file and makes it costly
for large files, while a diff is the smallest thing that can carry a change. And git
refuses to apply a change to content it was not built on, which B cannot do at all: B
silently reverts edits you made after the session's base. C fails the first goal, since it
commits before you have seen the change in Desktop; and `git am` needs an author line,
which would be either your identity written by the session or the session's own, the
second against the commit red lines.

The price of A: one install (Git for Windows, F11) and one pasted command per change.

*Overrule* if you never want a command line, the work is text-only and small, and you
accept checking every diff for reverted lines yourself: then B, with the session refusing
to deliver binaries. Or, after some weeks, if pasting the command is the only friction:
the guard can become a short script in `ops/` that reads the cover note. That changes D2,
not D1.

### 3.4 The change file

One file per intended commit, `NNNN-<slug>.patch`. `NNNN` counts the changes of one topic
(or of `main/`) from `0001`; `<slug>` is a few words of the commit summary, without its
area prefix, in lowercase and joined by hyphens. Layout (D10), one root per repository:

```text
<DROPBOX>/
├── cloview-core/                    the root of cloview-core
│   ├── _inbox/                      owner → session, text only
│   │   ├── core/keyed-lists/        inputs for one topic: harness summaries, logs, notes
│   │   ├── tickets/<id>/            result files of a ticket (result.json, logs/)
│   │   └── notes/                   anything else
│   └── _outbox/                     session → owner
│       ├── core/keyed-lists/        one folder per topic, named like its branch
│       │   ├── 0001-add-key-index.patch
│       │   ├── 0002-reconcile-keyed-children.patch
│       │   ├── PR.md                the pull request text, when asked for
│       │   ├── applied/             the owner moves a change here after committing it
│       │   └── rejected/            ... or here, after discarding it
│       ├── main/                    changes to commit directly on main, only when asked
│       ├── handoff/                 the session's notes at the end of a working session
│       └── _archive/<area>/<topic>-<YYYY-MM-DD>/   finished topics, moved by the owner
└── cloview-examples/                laid out alike, without tickets/, notes/ and handoff/
    ├── _inbox/
    │   └── fitness/calendar-week-view/
    └── _outbox/
        └── fitness/calendar-week-view/
```

`main/` is for the rare change you ask to have committed directly on `main`, such as a fix
needed before any topic exists; it has its own `applied/` and `rejected/`. Handoff notes,
ticket results and general notes belong to no single repository and go to cloview-core's
root (I4).

A topic's scope is not kept in Dropbox (I6): the topic's first change adds
`docs/topics/<area>-<topic>.md` to the repository (scope, what is out of scope,
dependencies), later changes update it when the scope moves, and it stays on `main` after
the merge as the record of what the topic set out to do.

Contents of a change file, in this order (ids shortened here; the file carries full
40-character ids):

```text
Change 0002 of core/keyed-lists (cloview-core)

Commit summary
core: reconcile keyed children by identity

Commit description
Children that carry a :key are matched by key instead of by position,
so reordering a list moves native nodes instead of recreating them.

Repository   cloview-core
Branch       core/keyed-lists (on GitHub)
Base         commit 3c4d5e6…, tree 0a1b2c3… (origin/core/keyed-lists, fetched 2026-10-27 09:12 UTC)
Result tree  9f8e7d6…
After        0001, landed exactly as 3c4d5e6
Supersedes   none
Files        4 in the diffstat, 1 of them renamed: Desktop lists 5

Tested by the session (Linux): clojure -M:test, 0 failures; clojure -M:static-check, clean.
Not tested here: the WPF host (Windows harness steps 04-06).
Review hints: the move logic is in reconcile.cljc.

Apply (Git Bash, right-click → Paste):
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"; f="$CLOVIEW_DROPBOX/cloview-core/_outbox/core/keyed-lists/0002-reconcile-keyed-children.patch"; if ...

 modules/core/src/cloview/core/reconcile.cljc | 48 +++++++++++++++++++++-------
 ...
 4 files changed, 120 insertions(+), 30 deletions(-)

diff --git a/modules/core/src/cloview/core/reconcile.cljc b/modules/core/src/cloview/core/reconcile.cljc
index …
End of change 0002.
```

Size: plan for change files of up to about 50 KB (a few hundred changed lines, or a binary
file of a few tens of KB). The first session measures the real ceiling (§9) and records it
here; larger work is split into more changes, and larger binary files need another route
(§10).

What the session does, every time:

- **It works outside the main checkout.** Each topic gets a detached worktree outside the
  clone the session started in (`git worktree add --detach <dir outside the clone> <base>`),
  and every scratch file goes outside the repository. The main checkout stays clean, so the
  Stop hook (F19) stays quiet, and topics stay apart (T6).
- **It builds the diff through a throwaway index, in one Bash call** (an `export` does not
  survive from one Bash call to the next, and a bare `git read-tree` would rewrite the real
  index). Verified: HEAD, refs and `git status` are identical before and after.

  ```bash
  idx="$(mktemp -u)"; out="$(mktemp)"
  GIT_INDEX_FILE="$idx" git read-tree <base> &&
  GIT_INDEX_FILE="$idx" git add -A &&
  GIT_INDEX_FILE="$idx" git diff --cached --stat -M <base> &&           # the file list, to check
  GIT_INDEX_FILE="$idx" git diff --cached --binary --full-index -M <base> > "$out" &&
  GIT_INDEX_FILE="$idx" git write-tree                                  # prints the Result tree
  rm -f "$idx"
  ```

  It checks the file list against what it meant to change; stray files (build output that
  `.gitignore` misses) are removed first.
- **Before delivering**, it applies the finished file to a scratch clone at the base and
  checks that the resulting tree equals Result tree; it checks the size, that the diff has
  no mode changes, no symlinks and no CR bytes outside frozen `-text` paths, and the commit
  message against the red lines.
- **After writing the file to Dropbox**, it reads it back with the connector and compares it
  with its local copy byte for byte (a long result may arrive as a saved file; compare
  that). A mismatch means writing it again.
- **It never edits or reuses a delivered file.** A correction gets a new number and the line
  "Supersedes NNNN".
- The cover note never contains a line starting with `diff --git`, `--- `, `+++ ` or `@@`.

### 3.5 Applying a change

Once: the user environment variable `CLOVIEW_DROPBOX` points to your Dropbox folder, the
one that holds `cloview-core` and `cloview-examples`, written with forward slashes
([COLLABORATION.md](COLLABORATION.md), Day 1).

1. In Desktop, the change's branch is checked out (created as the cover note says if the
   topic is new), Fetch and Pull are done, and the Changes tab is empty.
2. Repository → Open in Git Bash; paste the Apply line from the cover note (right-click →
   Paste, or Shift+Insert).
3. The Apply line moves to the repository root, then checks, in this order: the file
   exists; it was not saved with Windows line ends; it ends with its end line; the branch;
   the working copy has no changes, untracked files included; your files are exactly the
   change's base (by tree). A failed check prints NOT APPLIED with the reason and changes
   nothing. Otherwise `git apply` runs, all or nothing.
4. Review in Desktop: every file. Discard or edit what you do not want. Desktop lists as
   many files as the cover note says (a rename counts twice).
5. Commit in Desktop with the summary and the description from the cover note.
6. Optional: if `git log -1 --format=%T` prints the Result tree, your commit is exactly the
   delivered change.
7. Push, then move the change file into `applied/`, or into `rejected/` if you discarded
   it.

The Apply line as the session fills it in (verified with bash, the shell of Git Bash,
including from a subdirectory, with the variable unset, and with a CRLF-saved, a
truncated and a stale file):

```bash
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"; f="$CLOVIEW_DROPBOX/cloview-core/_outbox/core/keyed-lists/0002-reconcile-keyed-children.patch"; if [ ! -f "$f" ]; then echo "NOT APPLIED: no file at $f (is CLOVIEW_DROPBOX set? restart GitHub Desktop after setx)"; elif head -n 1 "$f" | grep -q $'\r'; then echo "NOT APPLIED: the file was saved with Windows line ends; take it from Dropbox again"; elif [ "$(tail -n 1 "$f")" != "End of change 0002." ]; then echo "NOT APPLIED: the change file is incomplete"; elif [ "$(git branch --show-current)" != "core/keyed-lists" ]; then echo "NOT APPLIED: check out core/keyed-lists first"; elif [ -n "$(git status --porcelain)" ]; then echo "NOT APPLIED: commit or discard your local changes first"; elif [ "$(git log -1 --format=%T)" != "<base tree id>" ]; then echo "NOT APPLIED: this branch is not at the base of change 0002. Ask for a rebuild, or apply anyway with: git apply --verbose \"\$f\""; else git apply --verbose "$f" && echo "APPLIED: review change 0002 in GitHub Desktop"; fi
```

The base check comes last on purpose: when it fails, every other check has passed, so the
"apply anyway" it offers is safe; git still refuses any hunk that overlaps your changes.
Never run `git apply` from a subdirectory (F15).

Why a tree and not a commit id: the tree check also works for a change stacked on one you
have not pushed yet (§4.2), and it states exactly the condition under which the session's
tests mean something: your files are the files it built on.

### 3.6 Special cases

- **Binary files** travel as git binary patches (compressed, then base85 text), and git
  checks their blob ids when applying (F17). Only small ones fit (§3.4).
- **Renames** are recorded by `-M`. Desktop shows a deletion plus a new file until the
  commit (F14); git stores no renames, and its rename detection shows the pair as a rename
  in history (verified: R090).
- **Deletions** are in the patch (verified).
- **Line endings**: F13 and F16. Never resave a change file in an editor; the Apply line
  refuses one saved with CRLF (F18). Take it from Dropbox again.
- **File modes and symlinks** never appear in a change (F12, F13). Scripts run through
  their interpreter (`python3 x.py`, `bash x.sh`), as every script here already does. If a
  mode change is ever needed, the cover note says so and gives the commands
  (`git update-index --chmod=+x <path>` and a commit from the command line), because a
  Desktop commit would drop it.
- **Case-only renames** are avoided: Windows file systems ignore case, so such a rename
  needs two commits.
- **A change to `.gitattributes`** needs a renormalisation step (`git add --renormalize .`)
  that Desktop does not do; the cover note gives the commands.
- **Frozen content**: `benchmarks/fitness-baseline/` and the reference-core copy in
  cloview-examples are never part of a change (that repository's `CLAUDE.md`). Their CRLF
  files are also the one kind of text the connector might alter (F9).

## 4. Q2: synchronisation

### 4.1 Candidates

**A. Fetch and anchor per change.** Before each change the session fetches and builds on
the current tip of the topic's branch on GitHub (for a new topic, on the tip of `main`).
The owner pushes after each commit.

**B. Stacked anchors.** The session builds change N+1 on its own expected result of change
N, whether or not you have pushed N. You push when you like.

**C. One session per base.** No fetching: a session works only from the commit it started
on. After you push, a new session continues.

| | A | B | C |
|---|---|---|---|
| Unpushed local commits | Invisible to the session (F1): push first | Only the session's own changes may be unpushed | As A |
| You edited change N during review | N+1 is built on your version | N+1 no longer fits: rebuild after you push | As A, at the cost of a new session |
| What the session tested | Your pushed files | Its own expectation of your files | Your pushed files |
| Topic branch visible on GitHub before its pull request | Yes | Only once you push | Yes |
| Context | One session for many changes | One session | Lost at every push |
| Depends on | I1 (fetching is reading) | Nothing | Nothing |

### 4.2 Recommendation: A, with B on request

A keeps one rule: a change is built on what both sides can see. It also makes your review
edits flow back without effort: the session fetches, sees your commit as you made it, and
builds on it. B is the exception for when you want several changes queued before pushing.
The guard (§3.5) compares trees, so a stacked change applies only if you committed its
predecessor unchanged, and tells you so otherwise.

The cost of A in a public repository: a topic branch is visible on GitHub before you open
its pull request. It runs no CI (`ci.yml` runs on pushes to `main` and on pull requests),
and nothing leaves your machine unreviewed: the review comes before the commit, the commit
before the push.

*Overrule with B* if topic branches must stay unpublished until their pull request; *with
C* if you read I1 strictly.

### 4.3 Who is authoritative

| Question | Authority |
|---|---|
| What exists | GitHub, the only history both sides can read |
| What is accepted | You: a change is accepted when you commit it, as you edited it |
| What the session proposed | The change file, never edited, only superseded by a new number |
| What a change was built on and should produce | Its Base and Result tree lines |
| What the session may assume about your machine | Nothing beyond what you pushed |

### 4.4 Divergence

- **You have unpushed commits of your own** on the topic: the next change's base is older
  than your branch, and the guard refuses (the tree differs). Push and ask for a rebuild,
  or apply anyway as the refusal suggests: git still refuses anything that overlaps your
  commits.
- **The session read a stale commit** because you pushed after it fetched: the same
  refusal. The session fetches right before building each change and states when it
  fetched.
- **A half-applied change** cannot come from the Apply line: it runs `git apply` at the
  repository root on a file it has checked for completeness, and `git apply` is then all or
  nothing (F15). What can be half-done is the workflow around it: applied but not
  committed (Desktop shows the files: finish, or Discard all), committed but not pushed
  (Desktop's push button shows the count), committed with some files left out (they stay in
  the Changes tab: commit or discard them before the next change).
- **A conflict you want to resolve yourself**: `git apply --3way` merges using the blob ids,
  stages the result and marks conflicts (F17). How Desktop shows its unmerged entries is
  untested, so the default is a rebuild by the session; Discard all backs out either way.
- **You rewrote pushed history** (an amend or rebase and a force-push on a topic branch):
  pending changes still apply if your files match, since the guard checks trees, not
  commit ids, and the session recognises landed changes by content, not by id.
- **Two sessions deliver for the same topic**: avoided by rule (one session per topic at a
  time). If it happens, the second change's base check fails.

### 4.5 Seeing what has been applied

| State | What you see | What the session sees |
|---|---|---|
| Delivered, not applied | The file at the top of the topic folder | The file, and no matching tree on the branch |
| Applied, not committed | Desktop's Changes tab, with as many files as the cover note says Desktop lists | Nothing |
| Committed, not pushed | Desktop's History, and the count on its push button | Nothing |
| Pushed | The branch on GitHub; optionally `git log -1 --format=%T` equal to Result tree | A commit whose tree is Result tree ("landed exactly"), or one that contains the change's new file versions with differences it lists ("landed with your edits") |
| Rejected | The file in `rejected/` | The folder: it never delivers the same change again unasked |
| Refused by the guard or by git | NOT APPLIED and the reason; nothing changed | What you tell it, or a base that has moved |

Before building anything, the session reports the state of every pending change of the
topic.

## 5. Q3: branch lifecycle

### 5.1 What the preference costs

A short-lived branch per change gives isolation for free: nothing can interleave because
nothing shares a branch, and abandoning work means deleting a branch. Long-lived branches
give that up, so the isolation has to come from rules. The rules below are cheap because a
machine checks each one at the moment it matters; nobody has to remember them.

### 5.2 Candidates

**A. Topic branches.** One branch per deliberate topic: a feature, a milestone of a module,
a release milestone. Changes land on it in sequence; you open its pull request when the
topic is done. Several topics can be open at once; only one change is in flight in the
working copy.

**B. One integration branch.** Everything lands on one long-lived branch (say `next`), and
pull requests are cut from it now and then. Fewest branches, but topics interleave by
construction, and a pull request carries whatever happened to land.

**C. A branch per change.** What you do not want, listed as the baseline: perfect isolation
and trivial abandonment, at the price of one branch and one pull request per delivery.

### 5.3 Recommendation: A, the topic model

The rules, each with what enforces it:

- **T1. A topic is opened deliberately.** You name it, or the session proposes a name and a
  one-paragraph scope and waits for your yes. The topic's first change then adds its scope
  file, `docs/topics/<area>-<topic>.md`, so the agreement lives in the repository (I6).
  *Enforced by:* the session asks before opening a topic.
- **T2. Each change belongs to exactly one topic and names it**: in its first line, in its
  folder, in its Branch line. *Enforced by:* the guard refuses on any other branch.
- **T3. One change in flight.** A change is applied only to a clean working copy and is
  committed or discarded before the next one. *Enforced by:* the guard's clean-tree check,
  untracked files included.
- **T4. The changes of a topic are sequential.** Change N+1 is built on change N as you
  committed it (D3 A) or as delivered (D3 B). *Enforced by:* the guard's tree check.
- **T5. No hidden dependencies between topics.** A change for topic B never builds on
  topic A's unmerged work. If B needs A, B's branch starts from A's branch, B's scope file
  says so, and A's pull request merges first. *Enforced by:* the session's base is always
  B's own branch.
- **T6. The session keeps topics apart as well**: one detached worktree per topic, outside
  its main checkout, each starting at that topic's base (§3.4).
- **T7. A topic ends with its pull request.** After the merge its branch is deleted and its
  folder archived; follow-up work is a new topic, which may reuse the name, starting from
  the new `main`.

How several changes share a branch, end to end: topic `core/keyed-lists` opens from
`main`; change 0001 (with the scope file) is applied, reviewed, committed and pushed; the
session fetches, sees that 0001 landed exactly (or with your edits) and builds 0002 on it;
and so on. Nothing about the branch changes between changes. You decide when it becomes a
pull request (§5.7).

### 5.4 Branch names (D5)

`<area>/<topic>`:

- **`<area>`** from a fixed list.
  - cloview-core: `core`, `fx`, `javafx`, `wpf`, `flutter`, `bridge`, `licensing` (the
    modules), `starter`, `verify`, `ci`, `docs`; `feature` for work across modules,
    `milestone` for release-shaped work.
  - cloview-examples: `fitness` (and the folder name of each new app, itself lowercase,
    hyphenated and not starting with `my-`), `showcase`, `verify`, `ci`, `docs`, `feature`,
    `milestone`. There is no area for `benchmarks/`: it is frozen.
  - A topic that spans both repositories uses an area valid in both (`feature`,
    `milestone`, `docs`, `ci`, `verify`) and the same name in each.
- **`<topic>`**: two to five lowercase words joined by hyphens that name the outcome, not
  the activity or a ticket: `core/keyed-lists`, `wpf/high-dpi-text`, `feature/dark-theme`,
  `milestone/first-public-release`.
- Only `a-z`, `0-9`, `-` and one `/`; at most 40 characters.

Why areas and not change types (`feat/`, `fix/`): a long-lived topic gathers features,
fixes, tests and docs; a type describes a single change, which is the ticket shape you
want to avoid. The fixed list also puts every red line out of reach: no area is `claude`,
`tmp`, `temp`, `wip` or `dev`, nothing can start with `my-`, and the slash rules out bare
`patch-N` and `test-N`.

The ticket lane of [DEVELOPMENT_WORKFLOW.md](DEVELOPMENT_WORKFLOW.md) §4–5 has its local
operator agents use `ops/<id>` working branches and `ops-results/<id>` results branches.
They keep the red lines but are exactly the per-ticket branches you dislike; see §10.

*Overrule* if you prefer type prefixes, or flat names without an area; only D5 changes.

### 5.5 Failure modes

| What happens | What stops it | What you do |
|---|---|---|
| A change arrives for a branch you do not have checked out | The guard: wrong branch | Commit or discard what is in flight, switch in Desktop (for a new topic, create the branch from the stated base, with Create Branch from Commit if `main` has moved since), Pull, paste the Apply line again |
| You are reviewing a change for topic A when one for topic B arrives | Nothing needs to: B's file waits in its own folder | Finish A first. If you must switch now, choose "Leave my changes on" A's branch, never "Bring my changes to" B's: bringing them is exactly how topics mix |
| B's Apply line is pasted on topic A's branch | The guard: wrong branch; failing that, the tree check | Nothing was applied; switch |
| You bypassed the guard and applied on the wrong branch | Desktop shows the files before any commit | Switch with "Bring my changes to" the right branch (they belong there), or Discard all |
| You committed on the wrong branch, not pushed | Nothing yet left your machine | Undo in Desktop, switch bringing the changes, commit there |
| You pushed to the wrong branch | — | Revert that commit in Desktop; ask the session to rebuild the change for the right branch |
| Topic B turns out to need topic A | T5 | Declare it: B starts from A's branch, A merges first |
| A long topic drifts from `main` | Desktop or the pull request shows conflicts | Branch → Update from main, in Desktop; the session rebuilds its pending changes on the new tip |
| A change is superseded before you apply it | The new one says "Supersedes NNNN" | Move the old file to `rejected/` |
| A change file is truncated, damaged or missing | The guard's first three checks | Take the file again |

### 5.6 A clean way out

- **One change**: before the commit, Discard all in Desktop (the files go to the Recycle
  Bin) and move the file to `rejected/`. After the commit: Undo if not pushed, Revert if
  pushed. With squash merges a commit and its revert both vanish from `main`.
- **A whole topic**: close its pull request if there is one, delete the branch in Desktop
  and on GitHub, and move its folder to `_outbox/_archive/`. Its scope file never reaches
  `main`.
- **The good part of a topic**: cherry-pick those commits onto another topic's branch in
  Desktop, then abandon the rest.

### 5.7 Who decides when a branch becomes a pull request

You, always. The session may write in a cover note that the scope in the topic's scope
file is complete, and it writes the pull request text (`PR.md` in the topic's folder) when
you ask for it. It never opens, comments on or merges pull requests (constraint 1, I1).

## 6. Q4: units of review

There are two reviews: of each change before you commit it (§3.5), and of each pull
request before it reaches `main`. The question is what the second one covers.

### 6.1 Candidates

| A pull request per | For | Against |
|---|---|---|
| change | Smallest diffs | One branch per change: the noise you do not want |
| **topic branch** | Matches D4: one scope sentence, reviewed, tested and reverted as one thing | Grows large unless topics are kept bounded |
| milestone | Few pull requests | Mixes topics and is too large to review; a GitHub milestone can group topic pull requests instead |
| issue | Natural when issues exist | Issues are inputs, topics are outputs: one topic may close several issues ("Closes #12, #15") |
| module | Stable names | A module is never finished: its branch drifts and its pull request never closes |

### 6.2 Recommendation: one pull request per topic branch

Four questions decide whether some work is one topic: can you state its scope in one
sentence; would you revert it as a whole; does `main` work with it alone; can you review it
in one sitting. When a topic fails the last question, close it with a pull request and
open a follow-up topic.

*Overrule* with a pull request per milestone if you prefer few, large reviews and accept
that topics then meet inside one pull request; with one per change only if you give up
long-lived branches.

### 6.3 A pull request that spans several changes

- Its description lists the commits (short id and summary), says what changes for users,
  and records the evidence per platform: CI for Linux, the Windows harness (on your
  machine or by the windows-harness workflow) for WPF, the Flutter checks where Flutter is
  touched. The template is
  [`.github/pull_request_template.md`](../.github/pull_request_template.md); the session
  fills it in on request (`PR.md`).
- Review it in Desktop with Preview Pull Request (the whole branch against `main`), then on
  GitHub.
- After the merge the individual commits stay readable on the pull request's Commits tab.

### 6.4 Merge method

| | Squash | Merge commit | Rebase |
|---|---|---|---|
| History of `main` | One commit per topic, each a state CI tested | Every change commit plus a merge commit; the change commits were never tested by CI or on Windows | Every change commit, rewritten |
| Bisecting `main` | Lands on tested states | Can land on untested ones | As merge commit |
| The branch after the merge | Ends: follow-ups are new topics | Can continue | Ends |
| The change commits | On the pull request, and listed in the squash commit's body | On `main` | On `main` |

Recommendation: **squash**, with these repository settings: squash merging only (merge
commits and rebase merging unticked), default commit message "Pull request title and
commit details" (the squash body then lists the reviewed change commits), and
"Automatically delete head branches". You still see and can edit the message in the merge
dialog.

*Overrule with merge commits* if you want permanent module branches that continue after
each merge. T7 then changes: a module branch lives on, and each pull request carries
whatever landed since the last merge.

## 7. Commit messages and pull request text (D7)

Candidates:

- **A plain imperative summary** ("Reconcile keyed children by identity") and a body.
- **An area prefix** ("core: reconcile keyed children by identity"), as the git and Linux
  kernel projects write them.
- **Conventional Commits** ("feat(core): reconcile keyed children by identity"), which
  tools such as release-please read to compute versions and changelogs.

Recommendation: **the area prefix**, with the areas of the branch names; the summary in
the imperative, at most 72 characters; a body wrapped at 72 that says why and what changes
for users; no trailers of any kind. With squash merges the pull request title becomes the
summary line on `main` and, with the recommended setting, the change commits' messages its
body; the area keeps seven modules apart when you scan the history.

*Overrule with Conventional Commits* if you plan to derive versions or changelogs from the
history; tools read summary lines, so with squash merges only pull request titles need the
format.

Where the red lines bite in practice:

- Desktop's co-authors field adds `Co-Authored-By` trailers: leave it empty.
- GitHub credits "every commit author in the pull request as a co-author on the squash
  commit". All commits here are yours, so it adds nobody; read the message in the merge
  dialog anyway.
- The session drafts every summary, description and pull request text, and checks them for
  emoji, attribution and remarks about how the change was produced before delivering. It
  never mentions change numbers, Dropbox or itself in them.

## 8. Q5: who does what (D8)

Constraint 1 fixes the core of the split: commits, pushes and everything on GitHub are
yours. What is open is the paperwork around them.

### 8.1 Candidates

- **A. The session does the paperwork.** It drafts every message and text, opens topics,
  suggests when a topic is ready, and files changes into `applied/` after verifying that
  they landed. You apply, review, commit, push and merge. Fewest chores for you; but the
  session then moves files in Dropbox, so its Dropbox access is no longer "create only".
- **B. You keep the pen.** You write commit messages and pull request text and decide every
  topic; the session delivers diffs and test reports only. Everything in your voice and
  under your control, at the cost of the most work, and messages drift from what the diff
  does.
- **C. The session drafts, you decide and file.** The session drafts messages, scope files
  and pull request text and proposes topics; you approve or edit them, and you move files
  in Dropbox, which keeps the session's Dropbox access to creating files.

Recommendation: **C**. It costs you one file move per change and keeps two simple
invariants: the session only ever creates files in Dropbox, and nothing becomes settled
without your yes. *Overrule* towards A for filing if moving files becomes a chore (one row
below), or towards B for messages if you want your own voice in the history.

### 8.2 Always the owner

- Create, switch, update and delete branches, and publish them.
- Apply changes with the guarded command, review them, edit or discard, commit with your
  own identity, and push.
- Decide what a topic is and when it becomes a pull request; open, review, merge or close
  pull requests; settle the merge message.
- Run what only your machine can run (the Windows harness, visual checks), or dispatch the
  windows-harness workflow, and put text results into `_inbox`.
- Move change files to `applied/` or `rejected/` and finished topics to `_archive/`.
- GitHub and claude.ai settings: the App's repository access, rules on `main`, the cloud
  environment.
- Mark decisions CHOSEN in this file (§0), on `main`.

### 8.3 Always the session

- Never mutate history or write to GitHub (constraint 1, I1).
- Before each change: fetch, report what has landed, build on the declared base, in a
  worktree outside the main checkout.
- Deliver one change file per intended commit, with its cover note, in the topic's folder;
  never edit or reuse a delivered file.
- Verify each change before delivering it: it applies at its base and gives Result tree,
  the Linux checks ran and are reported, the red lines hold, the size fits; read it back
  after writing.
- Draft topics and their scope files, branch names and commit messages within the red
  lines.
- Rebuild a change that no longer applies; never ask you to merge by hand.
- Keep topics apart (T5, T6) and say when a request does not fit the current topic.
- Read `_inbox` before asking you for anything, and name the exact path when a read fails.
- End each working session with a handoff note in cloview-core's `_outbox/handoff/`.

### 8.4 Negotiable (default first)

| Step | Default (C) | Alternative |
|---|---|---|
| Commit messages | The session drafts, you may edit | You write them (B) |
| Stacking on unpushed changes | Only when you ask | Always (D3 B) |
| Applying | The guarded command | Plain `git apply` at the repository root; a helper script in `ops/` |
| Moving files to `applied/` | You | The session, once it has verified the landing on GitHub (A) |
| Updating a topic from `main` | You, in Desktop | None: the session cannot merge |
| Pull request text | The session drafts it on request | You write it (B) |
| CI on topic pushes | By hand (Actions → ci → Run workflow) | A `push` trigger for all branches in `ci.yml` |
| Windows checks | You run the harness or dispatch the workflow | None |
| Opening topics | You name them, the session proposes | The session opens topics within a milestone you set (A) |

## 9. What the first session verifies

Facts this design relies on that could only be checked inside a cloud session. The first
session reports each one before its first delivery, using only reads:

- the guards: `git config --show-origin --get core.hooksPath` and the files in
  `/etc/git-hooks-readonly/` (G4); that `~/.claude/CLAUDE.md` loaded (G1);
- the Stop hook: whether `~/.claude/stop-hook-git-check.sh` exists and stays quiet while the
  main checkout is clean (F19);
- the toolchain: `clojure -M:test` runs (F8);
- the connector: the largest change file it can write and read back unchanged, starting
  with a test file in `_outbox/` (F9); the result goes into §3.4.

## 10. Open points, and what would change my mind

- **Results of the ticket lane** (DEVELOPMENT_WORKFLOW.md §5) travel on `ops-results/<id>`
  branches or as a zip. A session reads neither a zip (F9) nor a branch it does not know
  about. Proposal: the result files, unpacked, in cloview-core's `_inbox/tickets/<id>/`,
  the folder D10 provides; which route comes first is still to be decided.
- **CI before a pull request.** Today a pushed topic branch runs nothing unless you start
  `ci.yml` by hand. If topics grow long, a `push` trigger for all branches is the fix.
- **Larger files.** If the connector's ceiling (F9) becomes the bottleneck, a byte-exact
  route exists: the session uploads through the Dropbox HTTP API from its VM, with a
  Dropbox token held as a network secret of the environment. The costs: Dropbox
  credentials inside the environment, and short-lived access tokens to renew. Not
  recommended until needed.
- **A second maintainer** would break T3 and the assumption that every commit is yours:
  per-person working copies and a review rule would be needed.
- **If the guard proves tedious**, a short script that reads the cover note replaces it;
  the change format stays.

## 11. Sources

Read 2026-10-26.

- Claude Code docs: [Use Claude Code in the cloud](https://code.claude.com/docs/en/claude-code-on-the-web)
  (F1, F6, F7), [Get started in the cloud](https://code.claude.com/docs/en/web-quickstart)
  (F1, F2, F5), [Permission modes](https://code.claude.com/docs/en/permission-modes) (F2,
  F3), [How Claude remembers your project](https://code.claude.com/docs/en/memory) (F3),
  [Configure permissions](https://code.claude.com/docs/en/permissions) (F4),
  [Settings: settings in cloud sessions](https://code.claude.com/docs/en/settings#settings-in-cloud-sessions)
  (F3, F4), [Cloud environments](https://code.claude.com/docs/en/cloud-environments) (F8,
  setup scripts, network access and secrets),
  [Environment variables](https://code.claude.com/docs/en/env-vars) (F9, output limits);
  the report [anthropics/claude-code#86379](https://github.com/anthropics/claude-code/issues/86379)
  (F19).
- GitHub docs: [Authenticating with a GitHub App on behalf of a user](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-with-a-github-app-on-behalf-of-a-user)
  (F5); GitHub Desktop: [stashing changes](https://docs.github.com/en/desktop/making-changes-in-a-branch/stashing-changes-in-github-desktop),
  [managing branches](https://docs.github.com/en/desktop/making-changes-in-a-branch/managing-branches-in-github-desktop),
  [options for managing commits](https://docs.github.com/en/enterprise-server@3.22/desktop/managing-commits/options-for-managing-commits-in-github-desktop),
  [creating a pull request from Desktop](https://docs.github.com/en/enterprise-cloud@latest/desktop/working-with-your-remote-repository-on-github-or-github-enterprise/creating-an-issue-or-pull-request-from-github-desktop)
  (F14); [configuring commit squashing](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/configuring-commit-squashing-for-pull-requests)
  and the changelog on [attribution when squashing](https://github.blog/changelog/2019-12-19-improved-attribution-when-squashing-commits/)
  (§6.4, §7).
- GitHub Desktop issues and pull requests: [#16417](https://github.com/desktop/desktop/issues/16417)
  (applying a diff, declined), [#15160](https://github.com/desktop/desktop/issues/15160)
  and [#22821](https://github.com/desktop/desktop/issues/22821) (bundled Git not on PATH),
  [#16522](https://github.com/desktop/desktop/issues/16522) (autocrlf),
  [#8420](https://github.com/desktop/desktop/issues/8420) (renames),
  [#13904](https://github.com/desktop/desktop/issues/13904) (Recycle Bin); Desktop's
  [`commit.ts`](https://github.com/desktop/desktop/blob/development/app/src/lib/git/commit.ts)
  (F12); dugite-native's
  [`build-win32.sh`](https://github.com/desktop/dugite-native/blob/main/script/build-win32.sh)
  (F13).
- Dropbox: [What's a conflicted copy?](https://help.dropbox.com/organize/conflicted-copy)
  (F10); the connector's tool descriptions for `create_file` and `fetch`, as offered to
  Claude sessions (F9).
- git: [git-apply](https://git-scm.com/docs/git-apply) (F15, F17); the other git facts
  were reproduced with git 2.43 in throwaway repositories.
