---
name: github-jar-release
description: >-
  Set up GitHub Actions that compile a Gradle plugin/library JAR and publish it
  to GitHub Releases on push. Use when the user asks to release a jar on push,
  attach build artifacts to GitHub Releases, replace semantic-release with a
  simple build pipeline, or configure CI for a Minecraft/Bukkit/Paper plugin
  fork that should not depend on upstream secrets.
---

# GitHub Actions: compile JAR → GitHub Release

Use this skill when adding or fixing CI that **builds a Gradle JAR and publishes it as a GitHub Release**. Prefer a self-contained workflow that only needs `GITHUB_TOKEN`. Do not copy upstream org pipelines that need semantic-release, Reposilite, Modrinth, Hangar, or Bukkit credentials.

## Discover first

Before writing YAML, inspect the repo:

1. List `.github/workflows/*.yml`. Note anything that already runs on `push` to `main` and creates releases or uploads to platforms.
2. Find how the JAR is versioned. In this stack the Gradle plugin reads **`GITHUB_VERSION`**. Confirm by searching workflows and by running:

   ```bash
   GITHUB_VERSION=build-1-test ./gradlew shadowJar
   ls build/libs/
   ```

   Expected: `build/libs/<artifact>-<GITHUB_VERSION>.jar` (for Multiverse-Core: `multiverse-core-build-1-test.jar`).
3. Confirm Java version (this repo: **21**), Gradle wrapper, and artifact name (`settings.gradle` `rootProject.name` or existing workflow `plugin_name`).
4. Pass `GITHUB_TOKEN` into `./gradlew` when dependencies come from GitHub Packages.

## Workflow to add

Create `.github/workflows/push.release.yml` from [references/push-release.yml](references/push-release.yml). Substitute `PLUGIN_NAME` and the release title.

Required pieces:

| Piece | Why |
| --- | --- |
| `on.push.branches: [main]` | Publish a prerelease JAR on every merge |
| `on.push.tags: ['v*']` | Publish a stable release from a version tag |
| `on.workflow_dispatch` | Manual rebuild without a git tag |
| `permissions.contents: write` | Create tags and GitHub Releases (`GITHUB_TOKEN` is otherwise read-only for contents) |
| `actions/setup-java@v4` + `cache: gradle` | Java 21 toolchain |
| `gradle/actions/setup-gradle@v4` | Wrapper + Gradle cache |
| Exact JAR path | Avoid picking up `-sources` / `-javadoc` jars |
| `fail_on_unmatched_files: true` | Fail if the JAR name does not match `GITHUB_VERSION` |
| `softprops/action-gh-release@v2` | Creates the tag when it does not exist |

Do **not** trigger this workflow on every feature-branch push. PR CI should keep uploading Actions artifacts only (see `pr.test.yml` / `generic.test.yml`).

## Version and tag naming

Compute version in a step and export outputs. Step outputs are **strings**.

| Trigger | `GITHUB_VERSION` (JAR suffix) | GitHub tag | Prerelease |
| --- | --- | --- | --- |
| Push tag `v1.2.3` | `1.2.3` (strip leading `v`) | `v1.2.3` (keep the git tag) | no |
| Push to `main` or `workflow_dispatch` | `build-<run_number>-<7-char-sha>` | `build-<run_number>` | yes |

Pass version into Gradle:

```yaml
env:
  GITHUB_VERSION: ${{ steps.version.outputs.version }}
  GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

JAR path must be exact:

```text
build/libs/<plugin-name>-${{ steps.version.outputs.version }}.jar
```

## Boolean pitfall

Never pass a string output straight into a boolean action input:

```yaml
# Bad — "false" may be treated as true depending on the action
prerelease: ${{ steps.version.outputs.prerelease }}

# Good
prerelease: ${{ steps.version.outputs.prerelease == 'true' }}
```

## Do not run two release pipelines

If `main` already has a release workflow (semantic-release, platform uploads, custom `benwoo1110/semantic-release-action`):

- Leave PR test / checkstyle workflows unchanged.
- Stop the old **push-to-main** release from firing. Change its `on:` to `workflow_dispatch` only (keep the file so the upstream pipeline can still be run by hand).
- Do not upload to Modrinth / Hangar / Bukkit / Reposilite from the fork workflow unless those secrets exist.

Two workflows creating GitHub Releases on the same push will fight over tags and assets.

## Fork vs upstream org

Upstream Multiverse-style pipelines (`generic.github_release.yml`, `call.platform_uploads.yml`) assume:

- `benwoo1110/semantic-release-action`
- Reposilite (`REPOSILITE_REPO_USERNAME` / `REPOSILITE_REPO_PASSWORD`)
- Modrinth / Hangar / DBO tokens
- PR-label version bumps

On a fork those jobs fail or no-op. The push-release workflow must succeed with **only** `GITHUB_TOKEN`.

## Verify locally before relying on CI

```bash
GITHUB_VERSION=build-1-test ./gradlew build
ls build/libs/<plugin-name>-build-1-test.jar
unzip -p build/libs/<plugin-name>-build-1-test.jar plugin.yml   # if a Bukkit plugin
```

Confirm `plugin.yml` `version:` matches `GITHUB_VERSION`.

## After merge

The workflow runs when this branch reaches `main`. Until then, use **Actions → Push: Release → Run workflow**, or push a `v*` tag.
