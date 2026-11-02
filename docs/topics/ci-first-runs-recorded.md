# Topic `ci/first-runs-recorded`

Branch `ci/first-runs-recorded`, in cloview-core and then in cloview-examples, opened on
2026-11-01 from cloview-core `main` at 50a2565
([DELIVERY_DESIGN.md](../DELIVERY_DESIGN.md) §5).

## Scope

The texts that still describe the CI workflows, their experimental parts or the cloud
setup script as not yet run say what has run since 2026-10-26 and what is still open, and
the experimental parts of both `ci.yml` workflows become required.

Why: these texts were written before the repositories were on GitHub. Since 2026-10-26
both `ci.yml` workflows and both windows-harness workflows have run green, and the first
cloud session found the setup script's hooks and contract in place
([DELIVERY_DESIGN.md](../DELIVERY_DESIGN.md) §9). The JavaFX smokes and the `clr` jobs
were to stay experimental until their first green run on a hosted runner; they have
passed every run (cloview-core three of three, cloview-examples one of one).

The owner's choices of 2026-11-01:

- The experimental parts become required: no `continue-on-error`, and no "(experimental)"
  in the `clr` jobs' names (decision 102 in
  [STAGE_2_2_DECISIONS.md](../STAGE_2_2_DECISIONS.md)).
- windows-harness stays started by hand in both repositories, with no push trigger
  (decision 103).
- The Verdict line stays as it is when the harness's `aborted` text spans lines
  (decision 104), and DEVELOPMENT_WORKFLOW.md §7 keeps its "Today" label.

The changes, in order:

1. cloview-core 0001: this file; decisions 102 and 103, and a note on item 97 pointing to
   102; the JavaFX smoke and the `clr` job of `ci.yml` required; the header of
   `windows-harness.yml`; the introduction of VERIFY_ON_WINDOWS.md, which called the run
   of 2026-10-25 the last one; DEVELOPMENT_WORKFLOW.md §2 (the table) and §8 (the owner's
   first step and item 1); the roadmap's P1 item 0; COLLABORATION.md, Day 1 step 9.
2. cloview-examples 0001, after cloview-examples' `ci/windows-harness-green` has merged
   (its change adds two lines right below the header lines this topic rewrites): the
   JavaFX smoke and the `clr` job of its `ci.yml` required, the header of its
   `windows-harness.yml`, and the introduction of its VERIFY_ON_WINDOWS.md, which calls
   the run of 2026-10-25 the last recorded one.
3. cloview-core 0002, once cloview-examples' harness is green with the verdict and its
   change 0001 here is delivered: DEVELOPMENT_WORKFLOW.md §2 (cloview-examples' CI and
   both verdicts) and §8 (items 1 and 2), and the roadmap's P1 item 0, say what
   cloview-examples has; §7 dates the result zip its estimates come from; decision 104
   records the choice on the Verdict line.

## Out of scope

- A push trigger for windows-harness (decision 103).
- Dated records stay as written: WINDOWS_VERIFICATION_REPORT.md, and the decisions before
  102; item 97 only gets a note pointing to item 102, as item 93 has for items 99–101.
- `run-windows-checks.ps1` and the verdict rule of the topic `ci/windows-harness-green`.
- A one-line Verdict for an `aborted` text that spans lines (decision 104), and
  DEVELOPMENT_WORKFLOW.md §7's "Today" label: its cost comparison compares two routes
  that both still exist.

## Dependencies

- cloview-examples' change waits until cloview-examples' `ci/windows-harness-green` has
  merged.
- cloview-core's pull request merges first, then cloview-examples' (COLLABORATION.md,
  "Topics that touch both repositories").

## Done when

- both `ci.yml` workflows run the JavaFX smoke and the `clr` job as required parts and are
  green on `main`;
- no text in either repository says that a CI workflow, a part of one or the cloud setup
  script has not run yet, or is experimental until its first green run, and none calls
  the run of 2026-10-25 the last one;
- both windows-harness headers say that they are started by hand only.
