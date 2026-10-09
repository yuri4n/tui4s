# Rulesets

<!-- claude 2026-10-09: created this file and the three ruleset JSON files in this directory -->

This directory holds the GitHub rulesets of tui4s. GitHub does not read these
files. They are the source of truth for the rules: a human admin applies them.

## Files

| File                    | Target                            | Bypass                      | What it does                                                                                    |
| ----------------------- | --------------------------------- | --------------------------- | ----------------------------------------------------------------------------------------------- |
| `main-invariants.json`  | branch `main`                     | none                        | Blocks direct pushes, force pushes and deletion. Requires a pull request, squash merge, linear history, resolved threads and a green CI job. |
| `main-review.json`      | branch `main`                     | repository admin, in a PR   | Requires 1 approval. An admin can merge their own pull request without approval.                 |
| `release-tags.json`     | tags `core-v*`, `zio-v*`, `cats-v*` | none                      | Blocks deletion and move of a release tag. You can still create a tag.                           |

The `main` rules are in two rulesets because a bypass applies to a full
ruleset. If the approval and the CI check were in one ruleset, the admin bypass
would also skip CI. With two rulesets, the admin skips only the approval.

## Apply or update the rules

1. Open **Settings > Rules > Rulesets** of `yuri4n/tui4s`.
2. Click **New ruleset > Import a ruleset**.
3. Select one JSON file. Do this for each file.

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
- To delete a release tag (for example, a tag that failed before publication),
  set `release tags` to **Disabled**, delete the tag, then set it to
  **Active** again.
