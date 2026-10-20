---
title: "0020. Release from the Actions form"
description: "A release starts from the form of the workflow Release on main; every check runs before a tag exists; one approval of the environment publishes all the modules of the run in the order of the dependencies; CHANGELOG.md is the one source of the release notes."
---

<!-- claude 2026-10-20: created: ADR 0020 (the release from the Actions form) -->

## Status

Accepted. It changes the trigger of [ADR 0014](/decisions/0014-release-per-module-tags-and-rc1): the versions, the tags and the order of 0014 stay.

## Date

2026-10-20.

## Context

- [ADR 0014](/decisions/0014-release-per-module-tags-and-rc1) released one module per tag push. `scripts/release.sh` made the checks on the computer of the maintainer, asked for a confirmation, and pushed the tag. The workflow then tested and published that module.
- That design needs a computer with `gh`, sbt and JDK 25, a clean checkout of `main`, and six runs of the script for a release of all the modules. GitHub ignores a push of more than three tags, so the tags go one at a time, in the order of the dependencies.
- The gate was a prompt of the script. The tag existed before the release checks of the workflow, and the ruleset of the release tags blocks a move or a deletion. A tag that failed in the workflow stayed.
- The release notes came only from the pull requests (`--generate-notes`). The first release has no pull-request history, and `CHANGELOG.md` already holds the text.
- `project/ModuleVersioning.scala` takes the version of a module from git (sbt-dynver with the prefix `<module>-v`). The version is `x.y.z` only when a tag `<module>-v<x.y.z>` is on HEAD and the tree is clean, or when the last tag of the module is older but no path of the module changed since then. A path of a module is its directory, `build.sbt` and `project` (the composite `core` also watches `runner`, `app` and `testkit`). In all other cases the version is a snapshot. `assertReleasable` fails when the version of the module or of an inter-project dependency is a snapshot, or when it is not `RELEASE_VERSION`. Thus the check needs the tag on HEAD: `RELEASE_VERSION` alone only compares.
- sbt-ci-release 1.12.1 publishes a release only when it sees a tag (`CI_COMMIT_TAG`, or a `GITHUB_REF` under `refs/tags`). With no tag and a snapshot root version, it publishes snapshots of all the projects.

## Decision

- The only trigger of `.github/workflows/release.yml` is `workflow_dispatch`, with the inputs `module` (one module, or `all`), `version` and `dry_run`. A tag that a person pushes starts no release.
- The job `prepare` has no environment and no secret. It checks, before a tag exists: the branch is `main`; the version form; for each module, the version is greater than its last tag (the Maven order of `scripts/release-lib.sh`) and the new tag does not exist; `CHANGELOG.md` has the notes of each module; a check run `Format, compile, test, compatibility, packaging` passed on the commit (any successful run counts, so a cancelled duplicate run does not block). Then it tags the commit in its own checkout only, in the order of the dependencies, and runs `stageModule <m>` (`assertReleasable` and an unsigned publish into `target/sona-staging`) and `<m>/versionCheck` for each module. The local tags let the dependency checks of `core` and `widgets` see the new versions of `runner`, `app`, `testkit` and `use`. The job cannot push. A dry run stops after this job, with a summary.
- The job `publish` uses the environment `maven-central`: its deployment policy allows `main`, and its required reviewer (the maintainer) approves the run. One approval covers all the modules. One step loops over the modules in the order of the dependencies. For each module, it pushes the tag with `GITHUB_TOKEN` (such a push starts no workflow), runs `sbt ci-release` as before (`CI_RELEASE`: `assertReleasable`, `versionCheck`, `publishSigned`; `CI_SONATYPE_RELEASE`: `project <m>; sonaRelease`) with `CI_COMMIT_TAG` set to the tag, and creates the GitHub release.
- A failure stops the loop. "Re-run failed jobs" continues: a tag on the same commit stays, a module with a GitHub release is skipped, and a version that is on Maven Central is not published again. A tag on another commit stops the job.
- `CHANGELOG.md` is the one source of the release notes. The release pull request renames `## [Unreleased]` to `## [<version>] - <YYYY-MM-DD>`, with a subsection `### tui4s-<module>` for each module. `scripts/changelog-section.sh` takes the text of a subsection; `prepare` fails when it is missing, and `publish` puts it before the notes that GitHub makes from the pull requests.
- `scripts/release.sh <module|all> <version> [--dry-run]` only fills the form (`gh workflow run`) and shows the run. It has no `--yes`. The rules of the versions and of the modules are in `scripts/release-lib.sh`, which the script and the workflow both source; `scripts/version-order-test.sh` and `scripts/changelog-section-test.sh` test them in CI.
- An agent can start a dry run at any time. A real release needs a human: the gate is the required reviewer of the environment, not a prompt of a script.

## Alternatives considered

- **The script with a tag push (ADR 0014).** Replaced: it needs a computer with `gh` and a clean checkout, and six runs for all the modules. Its gate was a prompt, and the tag existed before the checks of the workflow.
- **GitHub Packages as the registry.** Rejected: its Maven registry needs a token for each read, also for a public package. Only Maven Central is resolved by default by sbt, coursier, Scala CLI and Mill, and Scaladex indexes only Maven Central.
- **release-please with conventional commits.** Rejected for one maintainer at RC1: a convention on every commit, a bot pull request for each module, and the order of the dependencies needs more work.
- **The GitHub Release UI as the trigger.** Rejected: the tag exists before the checks, and the ruleset of the release tags makes it permanent.
- **Release notes from the labels of the pull requests.** Rejected: RC1 has no pull-request history, and `CHANGELOG.md` already holds the text. One source is sufficient.
- **Release assets (the zip of the examples).** Deferred: the examples are not a module.
- **`publishLocal` as the packaging check of `prepare`.** Not used: `stageModule` publishes into the directory of the real bundle with the same settings, so it is the nearer check.

## Consequences

- Good: a release needs only a browser (or `gh`). All the checks run before a tag exists, so a failed check leaves no tag.
- Good: one run and one approval release all the modules in the order of the dependencies.
- Good: the release notes come from `CHANGELOG.md`, and a missing section stops the run before the tag.
- Good: the rules of the versions are in one sourced file with a table test; the workflow does not copy them.
- Bad: a failed publish leaves a tag with no GitHub release. Re-run the failed job. A new run stops on the tags that exist.
- Bad: Central can show a new version on `repo1.maven.org` some minutes after it accepts the upload. A re-run in that time publishes again, and Central refuses it. Wait, then re-run.
- Bad: the job `publish` builds each module again after `clean` (one sbt run for each module), so a release of all the modules takes longer than one sbt run.
- Bad: the environment `maven-central` needs a one-time manual setup (the branch `main`, the reviewer, the four secrets). A ruleset file cannot hold it.

## References

- `.github/workflows/release.yml`, `scripts/release.sh`, `scripts/release-lib.sh`, `scripts/changelog-section.sh`, `project/ModuleVersioning.scala`.
- AGENTS.md: "Releasing", "Branch and tag rules". `.github/rulesets/README.md`: "The environment of the release".
- [ADR 0014](/decisions/0014-release-per-module-tags-and-rc1).
