# Releasing

## Update README

Any changes to the README are delayed until the last PR before release because in the past new users have read the
README and assumed that any features in the README MUST exist in the version they can install from the JetBrains
repository, so documenting `main` features in the README leads to just more support work.

## Promote the changelog

`CHANGELOG.md` is in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format and is the single
source of the plugin's change notes: the Gradle Changelog Plugin renders it into `changeNotes`, which is
the "What's New" on the [Marketplace page](https://plugins.jetbrains.com/plugin/7522-elixir) and in the
IDE's Plugins settings dialog. Contributors add entries under `## [Unreleased]` as they go (see the
Changelog section of [`CONTRIBUTING.md`](CONTRIBUTING.md)), so there is nothing to write at release -
only to promote.

The panel shows the version being built plus the five before it, and publishes only the
`### Breaking changes`, `### Enhancements` and `### Bug Fixes` groups; `### Threading / Platform
Hygiene` and `### Build / CI` are recorded for contributors and filtered out. All three settings live in
`gradle.properties`: `changelogGroups`, `changelogPublishedGroups` and `changelogPublishedVersions`.

**Do this before dispatching Tag Release** - the zip that workflow builds embeds the rendered
notes, and it renders the release body from the same file.

Only a release is promoted. A pre-release is cut before promotion: set `pluginVersion` to the
version being worked toward, leave `## [Unreleased]` where it is, and both the canary's
`changeNotes` and the GitHub release body come from it.

1. Set `pluginVersion` in `gradle.properties` to the release version.
2. Promote `## [Unreleased]` into a dated section for it:

   ```sh
   ./gradlew patchChangelog -PpublishChannels=default
   ```

   `-PpublishChannels=default` is **required**. Without it the build bumps the patch for canary
   versioning, and `patchChangelog` would stamp that bumped number instead of the release: a `24.1.0`
   release would be recorded as `## [24.1.1]`.

3. Review and commit the result. The task adds the dated release heading and opens a fresh
   `## [Unreleased]` scaffolded with the five group headings.

If `## [Unreleased]` is empty the task skips with `missing release note in the 'Unreleased'` and
changes nothing - which means the release has no notes, so check why before continuing.

## Tag release

The **Tag Release** workflow (`.github/workflows/tag.yml`) is what builds a release. Dispatching it
validates the tag, runs the test matrix, renders the release body from `CHANGELOG.md`, builds the
plugin zip, uploads a pre-release from `main` to the Marketplace canary channel, creates the GitHub
release and attaches the artifact. Every step below works from what it produced, so there is
no local `buildPlugin` step in a release: an unqualified local build stamps a `-dev+<timestamp>`
version and compiles against whatever `gradle.properties` currently pins, which is not what users
install.

Do **not** edit `resources/META-INF/plugin.xml` - Gradle patches its `<version>` and
`<change-notes>` during `patchPluginXml`, so any value committed there is overwritten. The version
in the zip comes from `-PpluginVersionOverride=<tag without the v>` and the channel from the
`prerelease` input - canary for a pre-release, default for a release - both of which the workflow
passes for you.

Its `validate-tag` job runs before anything is built, so a mistake costs seconds instead of the
whole test matrix, and it checks:

| Check | Applies to |
|---|---|
| `v<major>.<minor>.<patch>` for a release, `...-pre-<n>` for a pre-release | both, per the `prerelease` input |
| Dispatched from `main` | releases only |
| Tag does not already exist | both |
| Version higher than every existing tag | both |
| Tag version matches `pluginVersion` in `gradle.properties` | both |
| The Marketplace accepts the `JETBRAINSMARKETPLACETOKEN` secret, and the canary channel does not already hold the version | pre-releases from `main`, unless `publish_canary` is unticked |

There is deliberately no change-notes check here. The notes are rendered from `CHANGELOG.md`, so no
separate file can go stale against the tag, and that every change is recorded at all is enforced per
pull request by `changelog.yml`.

One input is worth understanding rather than just satisfying: **the `prerelease` checkbox defaults to
ticked.** Leaving it ticked for a release would build the release version on the canary channel, so the
tag shape and the flag must agree.

The leading `v` is not decoration: `refs/tags/v*` globs and `git describe` both rely on it, and the
repository carries tags that got this wrong four different ways (a missing `v`, a doubled `vv`, one
that is just `v`, and four-component versions).

Tagging by hand **bypasses every check above and builds nothing** - creating the release, rendering
its notes and attaching a correctly versioned zip all become yours to get right. Use the workflow.

1. `git tag -a vVERSION -m "Version VERSION"`
2. `git push`
3. `git push --tags`

## Smoke Test the Released Build

The zip attached to the release is the artifact users install, so smoke test that one rather than a
local build.

1. Download `intellij-elixir-VERSION.zip` from the release the workflow created.
2. Install it from disk
   1. Preferences > Plugins
   2. Click "Install plugin from disk..."
   3. Select the downloaded zip
   4. Click Open
   5. Click Apply
   6. Click Restart
3. Ensure no errors are raised during re-indexing and reparsing of any previously open files.
4. Try out new features for this release

## Release Notes

The workflow already published the release: titled with the tag, body rendered from `CHANGELOG.md`,
zip attached, pre-release flag set. Unlike the plugin's "What's New", that body keeps every group,
including `### Threading / Platform Hygiene` and `### Build / CI`. What is left is what the
changelog does not carry.

1. Open [releases](https://github.com/intellij-elixir/intellij-elixir/releases) and edit the new release
2. Add thanks for the bug reporters for the release (use the Milestone filter to find issues fixed
   for the release version)
3. Add the README updates (copy directly from `README.md`)
4. Click "Update release"

## Publish to JetBrains Repository

A pre-release dispatched from `main` is uploaded to the canary channel by Tag Release (the
`publish_canary` input, ticked by default), using the `JETBRAINSMARKETPLACETOKEN` repository secret.
A pre-release from any other branch, or from a fork, gets its GitHub release and zip but no upload,
since it is cut for one reporter rather than every canary subscriber. The upload happens before the
smoke test above, so a canary build that fails it is hidden on the Marketplace instead
(<https://plugins.jetbrains.com/docs/marketplace/hidden-plugin.html>). It waits on Marketplace
moderation; it is live once it appears at
<https://plugins.jetbrains.com/plugin/7522-elixir/versions/canary> without signing in. Skipping the
tests also skips CI's internal-API allowlist check, so the Marketplace's own verification is then
the first to see a new internal usage. If the upload succeeded but creating the GitHub release
failed, re-dispatch the same tag from a branch at the same commit with `publish_canary` unticked and
`skip_tests` ticked, since the Marketplace already holds that version and rejects it a second time
and the tests already passed on the commit; if the tag was created before the failure, delete the
release and the tag first. The GitHub release then carries a fresh build of the same commit, not the
byte-identical zip the Marketplace holds.

For a release, the upload is by hand:

1. Go to https://plugins.jetbrains.com/plugin/7522
2. Click Update Plugin
3. Click "Choose File" and select the `intellij-elixir-VERSION.zip` downloaded from the GitHub release
4. Add a brief summary of important enhancements or bug fixes for the RSS feed
5. Click "Upload New Build"

## Announce on Elixir Forums

1.
Open [IntelliJ Elixir - Elixir plugin for JetBrain's IntelliJ Platform](https://elixirforum.com/t/intellij-elixir-elixir-plugin-for-jetbrains-intellij-platform/1697)
2. Click "+ Reply" at the bottom of the thread
3. Put version as title of post
   ```
   # Version VERSION
   ```
4. Add Sponsor link
   ```
   [❤️ Sponsor](https://github.com/sponsors/KronicDeth)
   
   Historical One-time/Monthly Donations:
   
   | Stat    | Amount  |
   |:--------|--------:|
   | Minimum |   $1.00 |
   | Median  |   $6.25 |
   | Mean    |  $12.52 |
   | Maximum | $200.00 |
   ```
5. Paste Release Notes from GitHub in message body
6. Add Installation Instructions link
   ```
   [Installation Instructions](https://github.com/intellij-elixir/intellij-elixir/blob/VERSION/README.md#installation)
   ```
7. Click "Reply" to post reply

## Announce on Twitter

1. Tweet

  ```
  IntelliJ Elixir vVERSION
  SUMMARY
  https://plugins.jetbrains.com/plugin/7522
  https://github.com/intellij-elixir/intellij-elixir/releases/tag/vVERSION
  #myelixirstatus
  ```

2. Pin Tweet

## Announce on ElixirStatus.com

1. Open [http://elixirstatus.com/](http://elixirstatus.com/)
2. Click Sign in and Post
3. Put "IntelliJ Elixir VERSION" for the title
4. Put in brief bullet-points of enhancements and bug fixes
5. Add Installation Instructions link
   ```
   [Installation Instructions](https://github.com/intellij-elixir/intellij-elixir/blob/VERSION/README.md#installation)
   ```
6. Click "Post this"
7. Click "Retweet this!"
