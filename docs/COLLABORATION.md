# Working with a Claude Code session: the owner's manual

This is the maintainer's side of [DELIVERY_DESIGN.md](DELIVERY_DESIGN.md), written against
its recommendations. A tag such as **[D2]** marks a step that changes if you choose another
option for that decision; the last section says how. The session itself follows
`CLAUDE.md` ("Collaboration contract") and the decision table of DELIVERY_DESIGN.md. (The
"operator" of DEVELOPMENT_WORKFLOW.md is a different role: the local agent that runs job
tickets.)

The short version: the session writes one text file per commit into your Dropbox; you
apply it with one pasted command, review it in GitHub Desktop, commit, and push. You open
pull requests when a topic is done. The session never commits, pushes or opens anything.

## Where things live

| What | Where |
|---|---|
| Rules every session follows | `CLAUDE.md`, "Collaboration contract", in both repositories |
| The design and its decision table | `docs/DELIVERY_DESIGN.md`, as it is on `main` |
| The prompt that starts a session | `ops/CLAUDECODE_BOOTSTRAP_PROMPT.md` |
| A topic's agreed scope | `docs/topics/<area>-<topic>.md`, added by the topic's first change **[D4]** |
| Changes from the session | `<DROPBOX>/<repository>/_outbox/<area>/<topic>/NNNN-<slug>.patch`; `_outbox/main/` instead for a change you asked to commit directly on `main` **[D1] [D10]** |
| Pull request text, when asked for | `PR.md`, in the topic's folder |
| Changes you committed or discarded | `applied/` and `rejected/`, in the change's folder: the topic's, or `main/` (you move them) **[D8]** |
| Finished topics | `<DROPBOX>/<repository>/_outbox/_archive/<area>/<topic>-<date>/` (you move them) |
| The session's handoff notes | `<DROPBOX>/cloview-core/_outbox/handoff/` |
| What you give the session | `<DROPBOX>/<repository>/_inbox/`, text only: `<area>/<topic>/` for a topic's inputs; in cloview-core's also `tickets/<id>/` for ticket results and `notes/` for the rest **[D10]** |
| Your working copies | outside Dropbox, side by side: `cloview-core/` and `cloview-examples/` |

`<DROPBOX>` is the root of your Dropbox, and each repository has its own folder there
(`<repository>` is `cloview-core` or `cloview-examples`). The session's Dropbox connector
sees the same folders as `/cloview-core/_inbox/`, `/cloview-core/_outbox/`,
`/cloview-examples/_inbox/` and `/cloview-examples/_outbox/`. **[D10] [D11]**

## Day 1

### The repositories

1. Create two empty public repositories on GitHub, `echojustus/cloview-core` and
   `echojustus/cloview-examples` (cloview-examples' CI checks out the first by that name).
2. Clone both with GitHub Desktop into one parent folder outside Dropbox, and copy the
   extracted archives into them. Work on these checkouts with Windows Git and Desktop only,
   not with WSL's git: its different line-ending and file-mode settings make every file
   look modified.
3. Before you make the first commit, open `docs/DELIVERY_DESIGN.md` §0 in cloview-core and
   mark each decision CHOSEN, or write your variant, so that your choices are on `main`
   from the start. Sessions read the table from `main`; what you leave PROPOSED, the first
   session asks about.
4. Commit and push both, cloview-core first.

### Your machine

5. Install **Git for Windows**. GitHub Desktop cannot apply a patch, and its own Git is not
   available to shells. Any line-ending choice in the installer is fine: the repositories'
   `.gitattributes` decide for their files. Then in Desktop: File → Options → Integrations
   → Shell: **Git Bash**. **[D2]**
6. **Dropbox**: create `cloview-core/_inbox`, `cloview-core/_outbox`,
   `cloview-examples/_inbox` and `cloview-examples/_outbox` at the root of your Dropbox
   (the session creates the subfolders). **[D10]** Then tell Git Bash where your Dropbox
   is, once, in a Command Prompt, with forward slashes:

   ```text
   setx CLOVIEW_DROPBOX "<your Dropbox folder>"
   ```

   Restart GitHub Desktop so that the shells it opens see the variable. **[D2]**

### GitHub

7. GitHub → Settings → Applications → Installed GitHub Apps → **Claude** → Repository
   access: cloview-core and cloview-examples must **not** be included ("Only select
   repositories" without them, or no installation at all). Sessions can still clone them,
   since they are public; GitHub then refuses any write from a session (DELIVERY_DESIGN.md
   §2.2). Do not connect with `/web-setup`, which would hand sessions your own token.
   **[D9]**
8. In each repository, Settings → General → Pull Requests: squash merging only (untick
   merge commits and rebase merging), default commit message "Pull request title and commit
   details", and "Automatically delete head branches". **[D6]** Optional: a ruleset on
   `main` that requires a pull request.

### claude.ai

9. **Cloud environment** (the environment selector at claude.ai/code):
   - Network access: Custom, with the defaults included, plus `clojars.org` and
     `repo.clojars.org` (Clojure libraries).
   - Setup script: the Clojure CLI, the contract loaded at launch, and the hooks that keep
     git history read-only **[D9]**:

     ```bash
     curl -fsSL -o /tmp/linux-install.sh https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
     bash /tmp/linux-install.sh
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

     This has not run in a cloud environment yet; the first session checks it
     (DELIVERY_DESIGN.md §9). Add the .NET SDK only if you want the CLR suites in sessions.
10. **Start a session**: select cloview-core (and cloview-examples when the work needs it)
    on `main`, enable the Dropbox connector, choose Accept edits or Auto, and paste the
    prompt from `ops/CLAUDECODE_BOOTSTRAP_PROMPT.md` with the first task at its end. The
    session first reports the checks of DELIVERY_DESIGN.md §9. If decisions are still
    PROPOSED, it asks about them and delivers your answers as a change for a short topic
    (`docs/delivery-decisions`): apply it like any change, and merge it before the next
    session.

## Every change

1. **Ask.** Say what you want and on which topic, or let the session propose one. A new
   topic: it proposes a branch name and a scope and waits for your yes; its first change
   adds the scope file. **[D4] [D5] [D8]**
2. **Wait for the note.** The session tells you when a change is in Dropbox and repeats its
   Apply line. You can read the file already, on any device: the cover note is at the top,
   the diff below.
3. **Get on the branch.** In Desktop: Current Branch → the change's branch; Fetch origin;
   Pull. For a new topic, create the branch as the cover note says: Branch → New Branch,
   based on `main`; or, if `main` has moved since the session fetched, History →
   right-click the base commit → Create Branch from Commit. The Changes tab must be empty.
4. **Apply.** Repository → Open in Git Bash; paste the Apply line (right-click → Paste, or
   Shift+Insert); Enter. It prints APPLIED, or NOT APPLIED with the reason, in which case
   nothing changed. **[D2]**
5. **Review** in Desktop's Changes tab: every file. Discard what you do not want
   (right-click → Discard changes, for a file or selected lines) or edit it in your editor.
   Desktop lists as many files as the cover note's Files line says (a rename counts
   twice).
6. **Commit** in Desktop: Summary and Description from the cover note. Leave the
   co-authors field empty. **[D7] [D8]**
7. **Push**: Push origin, or Publish branch for a new topic. **[D3]**
8. **File it**: move the change file into `applied/`, or into `rejected/` if you discarded
   all of it. **[D8]**
9. Tell the session, or just ask for the next thing. It fetches, tells you whether the
   change landed exactly or with your edits, and builds on what you pushed. **[D11]**

### When the Apply line says NOT APPLIED

| Message | Meaning | What to do |
|---|---|---|
| no file at … | The file is not there, or `CLOVIEW_DROPBOX` is not set in this shell | Check the path; after `setx`, restart GitHub Desktop |
| the file was saved with Windows line ends | An editor resaved it | Take it from Dropbox again; never edit change files |
| the change file is incomplete | Dropbox has not finished, or the file was cut | Wait, or take the file again |
| check out *branch* first | You are on another branch | Step 3 |
| commit or discard your local changes first | Something is in flight, or an untracked file is lying around | Finish it, or Discard all changes (they go to the Recycle Bin) |
| this branch is not at the base of change *NNNN* | The previous change is not committed yet, or you or `main` moved on after the session fetched | Commit the previous change first. Otherwise ask the session to rebuild, or run the "apply anyway" command it prints: every other check has passed, and git still refuses anything that overlaps |
| `patch does not apply` (from git) | Your files differ from what the session saw | Ask for a rebuild |

## Several changes on one topic

Changes on a topic are numbered and apply in order: 0001, then 0002, and so on, each
committed before the next is applied. **[D4]** Normally the session builds each change on
what you pushed, so your review edits are carried forward. If you want several changes
queued before you push, say so: the session then builds each one on the previous one as
delivered, and the Apply line refuses if you changed that previous one during review.
**[D3]**

Keep one change in flight: finish (commit or discard) the one you are on before applying
another, even for the same topic. When a change for another topic arrives, it waits in its
own folder. If you must switch branches in the middle of a review, choose **Leave my
changes on** the current branch, never "Bring my changes to" the other one: bringing them
is exactly how topics mix.

## Opening a pull request

When a topic's scope is done, and only when you say so: **[D6]**

1. Ask the session for the pull request text. It writes `PR.md` into the topic's folder:
   the title (with its area prefix), what changes for users, the commits, the evidence per
   platform, and review notes. **[D8]**
2. If the topic touches the hosts, run the Windows checks: locally
   (`verify\run-windows-checks.ps1`), copying `verify\out\00-summary.json` and the log of
   any failed step into the repository's `_inbox/<area>/<topic>/`; or on GitHub, with
   Actions → windows-harness → Run workflow on the topic's branch.
3. In Desktop: Branch → Preview Pull Request (the whole topic against `main`) → Create
   Pull Request. On GitHub, paste the title and the description from `PR.md`.
4. CI runs on the pull request. The session can read the results and delivers any fix as a
   further change on the same topic.
5. Merge with **Squash and merge**. Read the commit message in the dialog: no emoji, no
   attribution, no co-authors. GitHub deletes the branch; delete it in Desktop as well.
6. Move the topic's folder to `_outbox/_archive/<area>/<topic>-<date>/`, in the same
   repository's root.

## Reviewing before anything leaves your machine

- **Each change** is reviewed before its commit (steps 5 and 6). Nothing is ever committed
  for you.
- **Before a push**, Desktop's History tab shows the commits that will go, and the push
  button shows how many.
- **A whole topic**, once published: Branch → Preview Pull Request shows it against
  `main`, before any pull request exists.

## Giving the session what it needs

The session always reads `<DROPBOX>/cloview-core/_inbox/`, and
`<DROPBOX>/cloview-examples/_inbox/` when cloview-examples is attached, before asking you
for anything. Put there, as text files: notes and decisions (cloview-core's `notes/`),
`00-summary.json` and failing logs from the Windows harness (in the topic's folder), the
`result.json` and logs of a ticket (cloview-core's `tickets/<id>/`). It cannot read zips,
images or other binary files there. Harness results contain local paths: they belong in
`_inbox`, never in a commit. A decision made in chat only lasts if it reaches a repository
file, so ask the session to deliver it as a change.

## When the session could not write to Dropbox

The session then puts the complete file into the chat, with its name, its folder, its line
count and its SHA-256.

1. Copy it into a new file with that name in that folder (any editor).
2. In Git Bash: `dos2unix "<file>"` (copying can turn line ends into CRLF), then
   `sha256sum "<file>"`.
3. If the hash differs from the one in the chat, do not apply the file: ask the session to
   try the Dropbox write again. Copying can strip trailing spaces, which some changes need.
4. Otherwise apply it as usual.

## When something goes wrong

| Situation | Fix |
|---|---|
| You applied a change on the wrong branch (by bypassing the Apply line) | Switch branches with "Bring my changes to" the right branch (they belong there), or Discard all changes |
| You committed on the wrong branch, not pushed | Undo in Desktop, switch bringing the changes, commit there |
| You pushed to the wrong branch | Revert the commit in Desktop; ask the session to rebuild the change for the right branch |
| You want a change back after discarding it | It is in the Recycle Bin; or move its file back from `rejected/` and apply it again |
| A topic turns out to need another topic | Tell the session; the dependent topic restarts from the other topic's branch, and the other one merges first |
| A long topic conflicts with `main` | Branch → Update from main; resolve in Desktop; push; the session rebuilds its pending changes |
| You abandon a topic | Close its pull request if any, delete the branch in Desktop and on GitHub, move its folder to `_outbox/_archive/` |
| A session wants to commit, push or open a pull request | Say no; that is a breach of the contract. With the GitHub setting of Day 1 step 7 and the hooks of step 9 it would fail anyway |

## Topics that touch both repositories

Use an area that exists in both repositories (`feature`, `milestone`, `docs`, `ci` or
`verify`) and the same branch name in each. Changes for each repository go into that
repository's own root (`cloview-core/_outbox/<area>/<topic>/` and
`cloview-examples/_outbox/<area>/<topic>/`) and have their own numbering. Merge the
cloview-core pull request first: cloview-examples' CI checks out cloview-core's `main`
unless a run is started with another `core_ref`.

## What changes under another option

| Decision | If you choose differently |
|---|---|
| D1, D2 (snapshots instead of patches) | Every change, step 4: copy the delivered files over your checkout and delete the listed files, then review as usual. Day 1 step 5 and the variable in step 6 (Git for Windows, `CLOVIEW_DROPBOX`) fall away; no binary files can be delivered |
| D2 (a helper script) | Every change, step 4 becomes `bash ops/<script> <file>`; everything else stays |
| D3 (stacking by default) | Every change, step 7 becomes optional between changes; push when you like |
| D4, D6 (merge commits, permanent module branches) | A topic continues after its merge; "Opening a pull request" repeats per milestone, without deleting the branch |
| D5, D7 (other naming) | Only the names in steps 1 and 6 of every change differ |
| D8 A (the session files) | Every change, step 8 falls away: the session moves the file once it has verified the landing; it then needs to move files in Dropbox |
| D8 B (you keep the pen) | You write the summary, description and `PR.md` yourself; the session's notes carry only the diff and the test report |
| D9 (without the App setting or the hooks) | Skip Day 1 step 7 or the hooks in step 9; the contract then rests on fewer guards |
| D10 (one shared root instead of a root per repository) | cloview-examples' changes and inputs live under `<DROPBOX>/cloview-core/` too, in `_outbox/cloview-examples/…` and `_inbox/cloview-examples/…` |
| D11 (fetching counts as touching the remote) | After every push, start a new session (bootstrap prompt again) instead of step 9 |
