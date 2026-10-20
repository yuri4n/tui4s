# Rulesets

<!-- claude 2026-10-09: created this file and the ruleset JSON files in this directory; 2026-10-20: changed (L6): the tags of the six modules; one ruleset for main with no approval and no bypass; no review ruleset (one maintainer); when to import main-invariants.json (review fix) -->

This directory holds the GitHub rulesets of tui4s. GitHub does not read these
files. A human admin imports them; until then, no rule is active.

## Files

<!-- claude 2026-10-20: changed: the row of release-tags.json: the release workflow creates the tags (ADR 0020) -->

| File                   | Target                                                                 | Bypass | What it does |
| ---------------------- | ---------------------------------------------------------------------- | ------ | ------------ |
| `main-invariants.json` | branch `main`                                                          | none   | Blocks force pushes and the deletion of `main`. A change goes through a pull request, with a linear history (squash or rebase merge), resolved threads and a green CI job `Format, compile, test, compatibility, packaging`. A pull request needs no approval. |
| `release-tags.json`    | tags `runner-v*`, `app-v*`, `testkit-v*`, `core-v*`, `use-v*`, `widgets-v*` | none   | Blocks the deletion and the move of a release tag. It does not block the creation of a tag: the release workflow (`.github/workflows/release.yml`) creates each tag with `GITHUB_TOKEN`. |

The project has one maintainer, so no ruleset asks for an approval: a review
rule blocked the author of a pull request, and the rulesets were removed on
2026-10-14 for that reason. The CI job is the gate. An admin bypass is not
given either: a bypass applies to a full ruleset, so it would also skip CI.

## The environment of the release

<!-- claude 2026-10-20: created: this section (ADR 0020) -->
The release starts from the Actions form on `main`, and its job `publish`
uses the environment `maven-central`. A ruleset does not configure an
environment. Set it once by hand in **Settings > Environments >
`maven-central`**:

- Deployment branches and tags: `main`. The job runs on `main`, so the
  policy must allow `main`. The tag patterns (`runner-v*` and the others) of
  the old release are not necessary.
- Required reviewers: the maintainer. GitHub asks the maintainer to approve
  each deployment: one approval for all the modules of a run.
- Environment secrets: `PGP_SECRET`, `PGP_PASSPHRASE`, `SONATYPE_USERNAME`,
  `SONATYPE_PASSWORD`.

## Apply or update the rules

1. Open **Settings > Rules > Rulesets** of `yuri4n/tui4s`.
2. Click **New ruleset > Import a ruleset**.
3. Select one JSON file. Do this for each file.

Import `main-invariants.json` only after the release work is on `main`
(a direct fast-forward merge), or merge that work through a pull request.
The ruleset blocks a direct push to `main`, so it also blocks a direct
fast-forward merge.

To update a ruleset, delete the old ruleset in the UI, then import the new
file. Alternatively, change the ruleset in the UI, then export it into this
directory. Keep the files and the live rules the same.

On a private repository, GitHub enforces rulesets only on a Pro, Team or
Enterprise plan.

## Notes

- The name of the required check is the `name` of the `sbt` job in
  `.github/workflows/ci.yml`. If you change that name, change
  `main-invariants.json` too. If you do not, no pull request can merge.
- `integration_id` 15368 is GitHub Actions.
- `require_extra_approval_for_unattributed_changes` was `true` after the
  first import (2026-10-09), and no GitHub documentation for it was found.
  It is `false` here: with one maintainer and no approval rule, an extra
  approval would block a merge.
- A tag with no GitHub release means: its publish is not complete. Re-run
  the failed job of the release run (CONTRIBUTING.md, "Releasing"); do not
  delete the tag. To delete a release tag all the same, set `release tags` to
  **Disabled**, delete the tag, then set it to **Active** again.
  <!-- claude 2026-10-20: changed: this item: re-run before a deletion (ADR 0020); changed: CONTRIBUTING.md (ADR 0021) -->
