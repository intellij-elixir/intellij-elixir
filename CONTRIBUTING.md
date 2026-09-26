<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [Contributing](#contributing)
  - [Changelog](#changelog)
    - [Which group](#which-group)
    - [Format](#format)
    - [Skipping the check](#skipping-the-check)
  - [Development](#development)
    - [Importing the project](#importing-the-project)
    - [Building and running](#building-and-running)
      - [Elixir and Erlang](#elixir-and-erlang)
    - [Windows Development Setup](#windows-development-setup)
      - [Prerequisites](#prerequisites)
      - [Building on Windows](#building-on-windows)
      - [Platform Support](#platform-support)
      - [Troubleshooting](#troubleshooting)
    - [From command line](#from-command-line)
      - [Testing in other IDEs](#testing-in-other-ides)
      - [Running the latest EAP snapshot](#running-the-latest-eap-snapshot)
      - [Testing](#testing)
        - [Test tasks](#test-tasks)
        - [Debugging a failing test](#debugging-a-failing-test)
      - [Which versions CI tests against](#which-versions-ci-tests-against)
        - [Widening Elixir support](#widening-elixir-support)
        - [Reading a leg in the checks list](#reading-a-leg-in-the-checks-list)
      - [Internal API usages](#internal-api-usages)
      - [Working with a different Elixir/OTP version](#working-with-a-different-elixirotp-version)
    - [From IntelliJ IDEA](#from-intellij-idea)
      - [Running the plugin in a specific IDE](#running-the-plugin-in-a-specific-ide)
      - [Verification](#verification)
    - [GrammarKit (Parser / PSI Generation)](#grammarkit-parser--psi-generation)
      - [Prerequisites](#prerequisites-1)
      - [Regenerating Parser Code](#regenerating-parser-code)
      - [Fixing CRLF Line Endings (Windows)](#fixing-crlf-line-endings-windows)
      - [Source Layout: `gen/` vs `src/`](#source-layout-gen-vs-src)
      - [Key BNF Concepts](#key-bnf-concepts)
      - [`ElixirPsiImplUtil` Method Resolution](#elixirpsiimplutil-method-resolution)
      - [Testing After BNF Changes](#testing-after-bnf-changes)
      - [Suppressing Warnings in the BNF](#suppressing-warnings-in-the-bnf)
    - [JFlex Lexer Regeneration](#jflex-lexer-regeneration)
      - [Prerequisites](#prerequisites-2)
      - [Regenerating the Lexer](#regenerating-the-lexer)
      - [⚠️ No Manual Post-Regeneration Step Required](#-no-manual-post-regeneration-step-required)
    - [Color Schemes](#color-schemes)
      - [Customizing Scheme](#customizing-scheme)
      - [Exporting Settings](#exporting-settings)
      - [Unpack Settings](#unpack-settings)
      - [Convert ICLS to Additional Text Attributes format](#convert-icls-to-additional-text-attributes-format)
      - [Add Additional Text Attributes to plugin](#add-additional-text-attributes-to-plugin)
  - [Building](#building)
    - [Plugin version scheme](#plugin-version-scheme)
    - [Documentation](#documentation)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# Contributing

## Changelog

**Every pull request needs an entry in [`CHANGELOG.md`](CHANGELOG.md) under `## [Unreleased]`.** CI
checks this and fails the `Changelog / changelog-entry` job if the file is untouched.

`CHANGELOG.md` is not just a record. The Gradle Changelog Plugin renders the entry for the version
being built into the plugin's `changeNotes`, which is the **"What's New"** users read on the
[Marketplace page](https://plugins.jetbrains.com/plugin/7522-elixir) and in the IDE's Plugins settings
dialog. A change that never reaches `CHANGELOG.md` is invisible to users, not merely undocumented.

### Which group

Not every group reaches users. Pick accordingly:

| Group | Published to users? |
|---|---|
| `### Breaking changes` | **yes** |
| `### Enhancements` | **yes** |
| `### Bug Fixes` | **yes** |
| `### Threading / Platform Hygiene` | no - recorded for contributors |
| `### Build / CI` | no - recorded for contributors |

If a change has no effect anyone using the plugin can observe, it belongs in one of the lower two. The
group vocabulary and which of them publish are declared in `gradle.properties` (`changelogGroups`,
`changelogPublishedGroups`), and CI checks your entry against them.

### Format

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Add your entry under
`## [Unreleased]`, in the group that matches, using a `-` bullet with a link to the PR and your
`@handle`. Nested sub-bullets are fine and render correctly, so one PR link can carry several items:

```markdown
## [Unreleased]

### Enhancements
- [#1234](https://github.com/intellij-elixir/intellij-elixir/pull/1234) - [@you](https://github.com/you)
  - **Go-to-Declaration now works on `foo`.** Lead with what a user can now do, then name the API if
    it helps - the readers are Elixir developers, so `ElixirPsiImplUtil` is fair game once the effect
    is clear.
```

Write for someone deciding whether to upgrade. Say what changed and why it matters; for a fix, say
what used to go wrong.

Two mechanical things to know:

- **Every entry must be a list item.** The changelog parser models list items and nothing else, so a
  bare paragraph under a group heading is silently dropped when the file is rewritten at release.
- **Do not add a version heading.** Only `## [Unreleased]` gets edited by hand; `./gradlew
  patchChangelog` creates the release section at release time.

### Skipping the check

Apply the **`no-changelog`** label when there is genuinely nothing to record - a test-only fix, or a
revert of something that never shipped. Dependabot pull requests are exempt automatically.

## Development

### Importing the project

1. **Fork** [`intellij-elixir/intellij-elixir`](https://github.com/intellij-elixir/intellij-elixir) on GitHub.
   Pull requests are opened from a fork, so do this before cloning.
2. In the IDE, choose **File > New > Project from Version Control...** (or **Clone Repository** from the
   Welcome screen).
3. Enter your fork's URL and clone it.
4. Open the clone and accept the trust prompt. IDEA detects the Gradle build and imports it, including
   the `:jps-shared` and `:jps-builder` subprojects.

For plugin development setup and background generally, see JetBrains'
[Developing plugins](https://plugins.jetbrains.com/docs/intellij/developing-plugins.html) documentation.

### Building and running

Gradle will handle all dependency management, including fetching the Intellij IDEA platform specified in `gradle.properties`, so you can use a normal JDK instead of setting up an "Intellij Platform Plugin SDK".

#### Elixir and Erlang

**The tests need Elixir and Erlang. The build does not compile them from source** - it *resolves* an
already-installed pair. The `resolveElixirErlangSdks` task looks in this order and stops at the first hit:

| # | Erlang                           | Elixir                                            |
|---|----------------------------------|---------------------------------------------------|
| 1 | `ERLANG_SDK_HOME` env var        | `ELIXIR_SDK_HOME` env var                         |
| 2 | `erl` on `PATH`                  | `elixir` on `PATH`                                |
| 3 | `mise install erlang@<expected>` | `mise install elixir@<expected>`                  |
| 4 | -                                | download + `make` from source (last resort, slow) |

It then exports `ELIXIR_LANG_ELIXIR_PATH`, `ELIXIR_EBIN_DIRECTORY`, `ELIXIR_VERSION`, `ERLANG_SDK_HOME`
and a `PATH` prefix to every test task, so you never set those by hand.

CI takes row 1 for both, via `setup-env`. Row 2 does not work for Elixir on `windows-2025` and the
runners have no mise, so otherwise the Windows leg falls to row 4 on every run.

The **expected** versions come from [mise](https://mise.jdx.dev), which this project requires:

```sh
mise install          # installs the pinned Elixir, Erlang and JBR
```

`mise.toml` is the committed pin. The build does not parse it - it asks mise (`mise current elixir`)
for the version that will actually be on your `PATH`, so a personal override in the gitignored
`mise.local.toml` is honoured rather than silently disagreeing with the build.

You can override per invocation, which takes priority over mise entirely:

```sh
./gradlew check -PelixirVersion=1.17.3 -PotpVersion=27.1.2
```

With neither mise nor both properties, the build stops and tells you so - it never guesses. If what
mise reports is not installed, the resolver works down the list above, and step 4 means it can turn
into a from-source Elixir build that takes many minutes. See
[Working with a different Elixir/OTP version](#working-with-a-different-elixirotp-version).

Building the quoter (see [Test tasks](#test-tasks)) additionally needs network access on a cold cache -
it downloads the `quoterRepo`/`quoterRef` pinned in `gradle.properties` and runs `mix local.hex` /
`mix local.rebar` / `mix deps.get`.

**NOTE:** Tests that need an Elixir SDK fail rather than skip when it is missing, so run them through
Gradle. Running one from the IDE's JUnit runner works only if you supply the environment variables
listed above yourself.

### Windows Development Setup

#### Prerequisites
- **Build Environment**: PowerShell, Git Bash, MSYS2, WSL, or cmd. PowerShell is fine for every Gradle
  task including the quoter build - see the argument-quoting note below.
- **Erlang/OTP** and **Elixir**: **required for running tests** (not just building), at the versions
  [above](#elixir-and-erlang). Install both with `mise install`, or install them yourself and put
  `erl`/`erl.exe` and `elixir` on `PATH`, or point `ERLANG_SDK_HOME`/`ELIXIR_SDK_HOME` at them.
- **JetBrains Runtime**: **21** for IDEA 2026.1, **25** for 2026.2 and later.
  `build.gradle.kts` picks the bytecode level from the platform build number (262+ -> 25), and
  `javac --release` validates the platform JARs against it, so the wrong JDK fails the compile rather
  than producing a bad build. `mise install` provisions the pinned JBR.
- **Make**: *not* required in the normal path. Only the last-resort from-source Elixir fallback uses it.

> [!IMPORTANT]
> In PowerShell, quote `-P` flags that contain a dot: `.\gradlew.bat "-PelixirVersion=1.17.3"`.
> Unquoted, PowerShell splits the argument and Gradle reports ``Task '.17.3' not found``.

#### Building on Windows
```powershell
# Everything: test + :jps-builder:test
.\gradlew.bat check

# Build the plugin zip only, no tests
.\gradlew.bat buildPlugin
```

#### Platform Support
The build system automatically detects your platform:
- **Windows**: Uses `.bat` executables for Elixir commands, ProcessBuilder for daemon management
- **Linux/macOS**: Uses standard executables, native daemon support

#### Troubleshooting

**Quoter fails to start on Windows:**
- Check that no antivirus is blocking the process
- Verify no orphaned Erlang processes: `tasklist | findstr erl`
- Kill orphaned processes: `taskkill /F /IM erl.exe`

**Deleting a checkout or worktree fails with "Device or resource busy":**
- A leftover `epmd` running from inside the tree pins the directory. The build starts `epmd` from the
  Erlang SDK to avoid this, but one started earlier can still be holding it.
- Find it with `Get-Process epmd | Select-Object Id,Path`; if the path is inside the checkout, stop it
  with `Stop-Process -Id <id> -Force`.
- Check `epmd -names` first: `epmd` is machine-wide, so stopping it deregisters every node and fails
  any test run in progress in another checkout.

**`erl` not found, or the build starts compiling Elixir from source:**
- The resolver could not find the *expected* version. Check what it decided - it logs
  `Expected versions (from ...): Elixir ..., Erlang ...` followed by `Resolved SDKs: ...` with the
  source of each (`env:ERLANG_SDK_HOME`, `path`, `mise`, `source-download`).
- Confirm what mise reports (`mise current elixir`, `mise current erlang`) is actually installed, or
  pass `-PelixirVersion=` / `-PotpVersion=` to match what you have.
- As a last resort, set `ERLANG_SDK_HOME` and `ELIXIR_SDK_HOME` explicitly - they take priority over
  everything else.

**Path with spaces warning:**
- Cosmetic only - Erlang installed in "Program Files" shows warnings but works correctly

**kerl build fails on Linux as of May 2026**

This affects only the from-source Elixir/Erlang fallback, which the normal path never reaches - if you
hit it, the real problem is that the resolver could not find your installed pair (see above).

If you see this:
```text
beam/dist.c:5678:15: error: two or more data types in declaration specifiers
 5678 |         Eterm bool = ((monitor_oflags & ERTS_ML_FLG_SPAWN_MONITOR)
                       ^~~~
```

OTP 24 uses a local variable named bool in beam/dist.c, which was legal C in 2022... but is not legal C now, because GCC 15 switched its default C standard to C23, which makes bool a reserved built-in type name. ArchLinux (btw) ships GCC 16.1.1, Deian 13 seems to be 14.2.0. The pin is OTP 29, so this only bites when building an older OTP - the 1.11.4 and 1.12.3 legs are on OTP 24.

You can work around this by forcing an older C standard, for example:

```sh
export CC=gcc
export CFLAGS="-O2 -g -std=gnu17 -Wno-error -fcommon"
export CPPFLAGS="-D_FORTIFY_SOURCE=0"
export KERL_CONFIGURE_OPTIONS="--disable-debug --without-odbc --without-wx"
unset KERL_USE_AUTOCONF
```

### From command line
> Let's assume that you don't have much knowledge of the Java/Kotlin ecosystem, and just want to help contribute some changes.
> Here are some tips on how make your changes and test them without hopefully too much fuss,

To launch an IDE, you can use [runIde](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#runIde),
which will launch IntelliJ IDEA:

```sh
./gradlew runIntellijIdea
```

#### Testing in other IDEs

> [!IMPORTANT]
> In the 2025 release, JetBrains "unified" the Professional and Community editions of IntelliJ IDEA into a single edition.
> See [The Unified IntelliJ IDEA: More Free Features, a Better Experience, Smoother Flow](https://blog.jetbrains.com/idea/2025/12/intellij-idea-unified-release/)
> Other IDEs, such as [PyCharm](https://www.jetbrains.com/help/pycharm/unified-pycharm.html) was also unified into a single edition.

To test another IDE, you can use one of the following tasks:

- `runIntellijIdea` / `runIntellijIdeaEAP`
- `runRubyMine` / `runRubyMineEAP`
- `runWebStorm` / `runWebStormEAP`
- `runPyCharm` / `runPyCharmEAP`
- `runRustRover` / `runRustRoverEAP`
- `runCLion` / `runCLionEAP`
- `runGoLand` / `runGoLandEAP`
- `runPhpStorm` / `runPhpStormEAP`

These task names are generated from the `platformVersion*` properties in `gradle.properties`, so that
file is the authoritative list if it drifts from this one.

#### Running the latest EAP snapshot

To use `runIde` or `runPyCharm` etc with the latest EAP snapshot, you need to pass the `useDynamicEapVersion` gradle property
when running the task (or Run Configuration in IntelliJ IDEA).

For example, to launch the latest RubyMine EAP:

```sh
./gradlew runRubyMineEAP -PuseDynamicEapVersion=true
```

#### Testing

##### Test tasks

| Task | Source root | What it covers | Quoter daemon | Network on a cold cache |
|---|---|---|---|---|
| `test` | `tests/` | the whole JUnit suite, parser tests included | **yes** (soft) | **yes** |
| `:jps-builder:test` | `jps-builder/tests/` | the JPS build process | no | no |
| `check` | - | both of the above | yes | yes |
| `testUI` | `testUI/kotlin` | IDE UI tests; needs a built plugin | no | no |

```sh
./gradlew check                                                        # everything
./gradlew test --tests "org.elixir_lang.psi.operation.PrefixTest"      # a single test
./gradlew test --tests "org.elixir_lang.parser_definition.*"           # just the parser suite
```

`test` runs on the JUnit Platform, with the JUnit 3 and 4 tests going through the Vintage engine, and
spreads the suite over several test JVMs: one per core beyond the first two, up to one per 2.5 GiB of memory available
when `test` starts. Set `testMaxForks` in `~/.gradle/gradle.properties` to leave a share of the machine for other
work. The build prints the count it chose. `-PtestForks=N`, a positive whole number, overrides both, and
`-PtestTimeline=<file>` records which fork ran each class and when; its times include the IDE warm-up and the
waits for earlier tests' background work that run before a class or test starts, which Gradle's per-test times
leave out. Each fork keeps its own index in the
sandbox's `system-test-fork-<n>` between builds, so a stale index is reset by deleting `system-test*`, not
just `system-test`.

A test fails on any error logged while it runs, any warning from the plugin's own (`org.elixir_lang`)
loggers, and any exception left uncaught, on any thread, that it did not expect. A platform warning does not fail
it: `checkUnexpectedLogs` lists those after `test` without failing the build. A test that exercises a warning or an error wraps the code in
`expectWarnings(TheLoggingClass::class.java, Regex("..."))` or `expectErrors(...)`
(`org.elixir_lang.junit.logs`), which return the matching messages for the test to validate and fail if there
were none. The test is failed once it has finished, tearDown included, so nothing is thrown into the code that
logged: JUnit 3 tests get this from the base classes in `org.elixir_lang.junit` (`LightTestCase`,
`HeavyTestCase`, `UnitTestCase`, and `PlatformTestCase` on top of `LightTestCase`), JUnit 4 tests from
`UnexpectedLogsRule`, and Jupiter tests from an auto-detected extension. A log no test was failed for - logged after its test
finished, outside any test, or in a class on another base - fails the build after `test`, in
`checkUnexpectedLogs`. `-PunexpectedLogs=report` lists them all without failing anything. Platform noise
nothing in the plugin can prevent goes in `IgnoredLogs`, marked `silent` if it should not reach the console or
`idea.log` either, for noise a test provokes on purpose. A test that installs its own
`LoggedErrorProcessor` extends `GuardedLoggedErrorProcessor`, or it switches the check off while installed.

`test` builds and starts the Elixir quoter daemon, because the parser tests
(`org.elixir_lang.parser_definition.*`) quote source through it and compare the result against the
plugin's own quoting. Gradle stops the daemon at the end of the build. On a warm cache this costs
about a second; the first run in a fresh clone downloads and builds the quoter.

The quoter is a **soft** requirement, because it may not build for every Elixir/OTP pair the pipeline
tests against. `releaseQuoter` records whether it produced a daemon and succeeds either way, so only
the tests that quote through it fail - immediately and by name - and the rest of the suite runs. They
fail rather than skip so the counts still say what was not asserted. A recorded failure is never up to
date, so the next build retries the quoter; recovering from an offline run or a killed compile needs
nothing from you. Dependency fetching (`getQuoterDeps`) is not soft: no network on a cold cache still
fails the build outright.

To stop at the quoter instead, which is what you want when debugging the quoter itself:

```sh
./gradlew check -PquoterRequired=true
```

The quoter is built with `MIX_ENV=prod`, so its release lands in
`cache/<pair>-quoter-<ref>/_build/prod/rel/quoter`. The environment is set by the
build rather than left to `mix release`, which has no preferred environment and assembles under `:dev`
when `MIX_ENV` is unset - compiling the quoter's dev/test-only dependencies, none of which the daemon
needs at runtime. Exporting `MIX_ENV` overrides this, and the path the build looks in follows the
same value, so the two cannot disagree. If you switch it, expect one extra `releaseQuoter` run; the
`_build` subtree for the previous environment is left behind and can be deleted.

`test` also parses and quotes every `.ex` and `.exs` file of the Elixir release under test
(`ElixirLangElixirParsingTestCase`). Which commit that is comes from the `corpus` of its pair in
`.github/ci-versions.json`: on a cold cache `elixirParsingCorpus` downloads the archive into
`cache/corpus/archives` and extracts it to `cache/corpus/<elixir version>`. Each test is named after the file
it parsed, as `elixir-lang/elixir@<commit>/lib/...`. An Elixir that no pair declares has no corpus, and the
suite reports a single failing test saying so.

Whole files never reach Elixir's hardest parser cases, which live in string literals inside its tests.
`ElixirSnippetParsingTestCase` covers those: every source string that Elixir's parser, tokenizer, formatter and
normalizer tests hand to the parser, from every release in `.github/ci-versions.json`, is committed once in
`testData/org/elixir_lang/parser_definition/elixir_snippets/snippets.jsonl`. Each snippet the leg's quoter
accepts is a test, named by the snippet's hash and where it first appears; snippets the quoter rejects are not
tests. `NOTICE.md` beside it carries the attribution the Apache License asks for.

When you add an Elixir release to `.github/ci-versions.json`, give its pair a `corpus` and regenerate the
snippets from the repository root, with every declared pair installed in mise:

```sh
mise exec -- elixir testData/org/elixir_lang/parser_definition/elixir_snippets/generate.exs
```

It reads each release's tests with that release's own Elixir, prints how many snippets came from each test
file, and lists helpers the tests define and call with a literal string that it does not read, which is how a
new way of handing source to the parser shows up. Commit `snippets.jsonl` and `NOTICE.md`.

A corpus file the plugin cannot yet parse or quote as Elixir does can be listed in
`testData/org/elixir_lang/parser_definition/corpus_known_failures.tsv`, and a snippet in `snippet_known_failures.tsv`
beside it, with the Elixir versions it fails on. A listed test must keep failing, and must exist, on each of those
versions, so the list cannot outlive the fix.

To build (so you get a .zip file):
```sh
./gradlew buildPlugin        # the zip, no tests - prefer this
./gradlew build              # the zip AND the full suite
./gradlew build -x test      # the zip, skipping the root suite
```

##### Debugging a failing test

- **Quoting mismatches** report only the first divergence, as
  `quoted forms diverge at {2}[0]{1}[0]: keyword key, expected :from_brackets, got :line`, where the
  path notation is `{i}` tuple, `[i]` list, `[:key]` keyword, `%{key}` map. Pass
  `-Delixir.quoter.fullDump=true` for the complete side-by-side terms.
- **The platform log** for a failing test is written to
  `build/idea-sandbox/.../log_*/splitTestLogs/` rather than stderr, and stderr carries a
  `Log saved to:` pointer. The `test` task sets `idea.split.test.logs=true` for this - without it the
  buffered log is duplicated into the JUnit XML and can grow past what report tooling can parse.

#### Which versions CI tests against

`.github/ci-versions.json` declares every version CI uses - the supported IDEA platforms (with the
JBR level each needs and the products to verify them against) and the Elixir/OTP pairs.
Edit that one file to add or bump a version: the test legs, the plugin verifier's IDE lists, and the
release build's default platform are all derived from it.

Three places in this document quote that data rather than deriving it - the JBR levels under
[Windows Development Setup](#prerequisites), and the worked check names under
[Reading a leg in the checks list](#reading-a-leg-in-the-checks-list). Nothing keeps them in sync, so
update them in the same commit as `.github/ci-versions.json`.

Tests always run against IntelliJ IDEA. The legs are every declared IDEA version on Ubuntu with
`beam.baseline`, plus one leg per `beam.additional` pair on the minimum supported IDEA, plus
`beam.baseline` on Windows.

Two scripts in `.github/scripts` read that file, so you can see what a change to it produces without
pushing:

| Script | Answers |
|---|---|
| `compose-legs.js` | which test and verification legs exist |
| `resolve-versions.js` | which versions a caller gets when it names none (`SETUP_ELIXIR=true` to include Elixir) |

```sh
node .github/scripts/compose-legs.js
SETUP_ELIXIR=true node .github/scripts/resolve-versions.js
```

Outside the runner they print what they would otherwise hand to the workflow - the leg tables and the
resolved versions - so a bad declaration is diagnosable locally rather than from a failed run.

| | Elixir | OTP | Status |
|---|---|---|---|
| `beam.baseline` | 1.20.4 | 29.0.6 | **supported** - must be green |
| `beam.additional` | the rest of the window either side of the baseline, plus pairs covering an OTP major no other leg covers | see the file | **supported** when the entry has no `continue-on-error`, otherwise informational |

`beam.baseline` is the newest supported pair, and the one every IDEA leg and the Windows leg run.
`beam.additional` covers the rest of the window, which reaches back to 1.11.4: `builds.hex.pm`
publishes OTP for `ubuntu-22.04` only from 24.2, and 1.11.4 is the oldest Elixir that runs on OTP 24,
so nothing below it can be tested.

`beam.additional` is not one-entry-per-Elixir-minor: a pair may exist to cover an **OTP major** no other
pair covers, because most of the decompiled surface is Erlang and the BEAM chunk formats track OTP
rather than Elixir. So the same Elixir can appear twice with different OTPs. Put a new Elixir on the
newest OTP major it supports, so a failure is about the Elixir; give a new OTP an Elixir that already
has a green leg, so a failure is about the OTP.

Check which OTP an Elixir supports against the
[compatibility table](https://elixir.hexdocs.pm/compatibility-and-deprecations.html) - it accounts for
support added in patch releases, e.g. 1.14 is "23 - 25 (and Erlang/OTP 26 from v1.14.5)". Don't use the
`otpVersions` list in
[`elixir-versions`](https://github.com/elixir-lang/elixir-lang.github.com/tree/main/src/content/elixir-versions)
to decide the range: it is per-minor, misses patch-level additions, and is published only from v1.15.
Only the current stable minor declares a `recommendedOtp`, and it is not the rule here either.

##### Widening Elixir support

`beam.additional` is a ratchet. Each entry moves one pair - an Elixir version, an OTP major, or both -
from unsupported to supported:

1. **Add the pair** with `"continue-on-error": true`. That declares it **unsupported**, and its leg is
   expected to be red. Adding a version is safe at any time - it cannot block a merge.
2. **Fix what it reports**, until the leg goes green.
3. **Delete its `continue-on-error`.** The version is now **supported**: from then on, any change that
   breaks it fails the pipeline.

`continue-on-error` covers the whole leg - toolchain setup, compile, sandbox, quoter build, corpus download and tests -
not just the test step, because an unsupported Elixir can fail at any of those and they all mean the
same thing. `setup-beam` may not publish the pair. The annotation on a failed informational leg names
the phase it died in, so you can tell those apart.

##### Reading a leg in the checks list

Each leg is named after the axis its group varies, so the part that distinguishes it survives the
checks list's truncation: `test (IDEA 2026.2.2)`, `test (1.19.5+28.4)` (Elixir + OTP),
`test (Win25, IDEA 2026.1.5)`. The same name is used for the leg's `Test Results (...)` check, so a
row in one list maps to the other without translating.

A leg that stopped **before its tests ran** says so in the name:

```
Test Results (1.19.5+28.4, INCOMPLETE - failed at compile)
```

That matters because the check's own title only ever describes the result files it found - a leg that
never compiled published the six `:jps-builder:test` cases and nothing else, and the title reads
`All 6 tests pass`. Treat `INCOMPLETE` as "these numbers cover only what got as far as running"; the
stage named after it is where the leg actually died.

The same information arrives as a comment on the pull request: one table of totals and one row per leg,
split by whether the leg can block a merge. The icons say only that:

| Icon | Meaning |
|---|---|
| ✅ | passed |
| ❌ | a **required** leg failed - tests, or an earlier stage; either way it blocks |
| ⚠️ | an **informational** leg failed - this version is not supported yet |
| ⬜ | the leg published no results at all |
| ❔ | the leg did not report its status, so no claim is made either way |

The status text names the reason: `tests failed` with a count beside it, versus
`failed at compile` where the counts cover only what ran. For *which* tests failed, follow a row
to its `Test Results (...)` check or read the **Failed tests** summary on the leg's own job - the
comment deals in counts only.

#### Internal API usages

JetBrains have agreed to ignore the internal platform APIs listed in
`.github/internal-api-usages-allowlist.txt`. **Any other internal API usage blocks the plugin from being
published**, so `.github/scripts/check-internal-api-usages.js` fails any verification leg that reports
one.

**The allowlist records that agreement; it does not suppress a warning.** Adding a line does not make a
usage publishable - that needs a new agreement with JetBrains, and removing the usage is the better
answer. A **moved usage** (an approved API called from code the allowlist does not name) is the exception:
the ceiling is unchanged, so just replace the line with the reported one, which pastes in verbatim.

To check a local run - `verifyPlugin` covers more IDEs than CI, so it may report more:

```sh
REPORTS_DIR=build/reports/pluginVerifier VERIFY_OUTCOME=success \
  node .github/scripts/check-internal-api-usages.js
```

#### Working with a different Elixir/OTP version

The pinned pair is the one you get by default. To work against another:

**Just for one run** - nothing on disk changes, and this is what CI does:

```sh
./gradlew check -PelixirVersion=1.17.3 -PotpVersion=27.1.2
```

**Switch your working copy** - `mise use` writes `mise.toml`, and the build follows it immediately:

```sh
mise use elixir@1.17.3-otp-27 erlang@27.1.2
```

To switch only for yourself without touching the committed pin, add `--env local`; that writes the
gitignored `mise.local.toml`, which outranks `mise.toml`. The build follows either, because it asks
mise for the effective version rather than reading a file.

> [!NOTE]
> You do not need the exact `-otp-N` suffix in `-PelixirVersion` - mise resolves `1.17.3` to the
> installed `1.17.3-otp-27`. If the version is not installed yet, the resolver runs `mise install` for
> you; installing it up front (`mise install elixir@1.17.3-otp-27 erlang@27.1.2`) just makes the first
> build faster.

**Expect the known failures** from the table above; compare against them before assuming you broke
something. `./gradlew check` on the pinned pair must stay at zero.

**Adding a version to CI** is one edit to `.github/ci-versions.json` - add a `beam.additional`
entry, or an `idea.additionalToTest` entry for a platform. The matrix, the JBR levels and the verifier's IDE
lists all follow from it.

Other IDEs appear only in plugin *verification*. Each IDEA version lists the products to verify it
against in a `verify` array, and CI runs one job per product/version pair (`.github/workflows/shared-verify.yml`),
each verifying the plugin zip that was built once. Adding a product is one entry in that array; the
values are `intellij-repository` artifact ids (`ideaIU`, `rubymine`, `pycharmPC`, `webstorm`, ...),
not marketing names.

### From IntelliJ IDEA
#### Running the plugin in a specific IDE
1. Open the Gradle Tool Window (`View > Tool Windows > Gradle` OR from the Gradle button on the right tool button bar)
2. Expand `intellij-elixir (root) > Tasks > intellij platform`
3. Double click `runIntellijIdea` (or the task for whichever IDE you want to test with - see
   [Testing in other IDEs](#testing-in-other-ides))
4. Now the Run Configuration will be selected, and you can click the green arrow at the top of the screen.

#### Verification
1. Expand `verification`
2. Double click `check` - it runs `test` and `:jps-builder:test`.

The committed **Run Tests** configuration under `.run/` already runs `check`.

### GrammarKit (Parser / PSI Generation)

The Elixir parser and PSI element classes in `gen/` are generated from `src/org/elixir_lang/Elixir.bnf` using the [GrammarKit](https://github.com/JetBrains/Grammar-Kit) plugin. If you modify the `.bnf` file (e.g. adding a `mixin`, changing a rule, or adding a new production), you must regenerate the parser code.

#### Prerequisites
- Install the **GrammarKit** plugin in IntelliJ IDEA (Settings -> Plugins -> search "Grammar-Kit").

#### Regenerating Parser Code
1. Open `src/org/elixir_lang/Elixir.bnf` in the editor.
2. Right-click inside the file -> **Generate Parser Code**.
3. The generator writes updated files into the `gen/` directory.

#### Fixing CRLF Line Endings (Windows)

The GrammarKit generator writes files with CRLF line endings, but the repository uses LF. After regenerating, convert line endings from Git Bash:

```bash
cd ~/IdeaProjects/intellij-elixir
find gen -type f | xargs dos2unix.exe
```

Then review the actual changes:
```bash
git diff --stat -- gen/
```

Many `gen/` files may show whitespace-only or formatting diffs from a generator version difference - these can be included in your commit or excluded as appropriate.

#### Source Layout: `gen/` vs `src/`

The `gen/` directory must contain **only** GrammarKit/JFlex-generated files. All hand-written code lives in `src/`. Both directories are source roots with the same package structure (`org.elixir_lang`), so files can be moved between them without changing imports.

Generated files have a header comment: `// This is a generated file. Not intended for manual editing.` (GrammarKit) or `/* The following code was generated by JFlex */` (JFlex). If you need to add hand-written code that lives alongside generated PSI classes (e.g. a new `PsiScopeProcessor`, stub type, or operation interface), place it in `src/` under the matching package path.

The full set of source roots:

| Root | Contents |
|---|---|
| `src/` | hand-written plugin code |
| `gen/` | GrammarKit/JFlex output - never edit by hand |
| `tests/` | the JUnit suite, run by `test` |
| `testUI/kotlin` | IDE UI tests run by `testUI` |
| `jps-shared/`, `jps-builder/` | JPS model and build-process modules |

#### Key BNF Concepts

**Rule names vs interface names:** GrammarKit generates PSI classes named after the BNF **rule** (e.g. rule `heredoc` -> `ElixirHeredoc`). The `implements` attribute on a rule specifies the hand-written **interface** the generated class implements. These are independent - do not confuse them.

**Visitor method generation:** For each rule, GrammarKit generates a `visitRuleName(ElixirRuleName)` method in `ElixirVisitor`. For each interface in `implements`, it generates a `visitInterfaceName(InterfaceName)` bridge method. If a rule name and an interface name (after stripping packages) are identical, the visitor generates a self-recursive method - causing a `StackOverflowError` at runtime.

**Example of the collision:**
```
// BAD: rule "heredoc" implements interface "Heredoc" - visitor generates:
//   visitHeredoc(ElixirHeredoc) { visitHeredoc(this); }  ← infinite recursion!

// GOOD: rule "heredoc" implements interface "HeredocLiteral" - visitor generates:
//   visitHeredoc(ElixirHeredoc) { visitHeredocLiteral(this); }  ← safe dispatch
```

**Resolution:** When adding a new `implements` interface to a rule, ensure the interface's simple name does not match any BNF rule name. If it would collide, rename the interface (e.g. `Heredoc` -> `HeredocLiteral`) or the rule.

**`extends` attribute:** Causes the child rule's generated interface to extend the parent rule's interface, AND collapses AST nodes. Use it for expression hierarchies where shallow AST is desired. Do **not** use it solely for visitor type compatibility - it changes the PSI tree shape and will break parsing tests that compare golden `.txt` files.

**`mixin` attribute:** Specifies a hand-written base class for the generated `*Impl` class. Use this to add custom behaviour (e.g. implementing `HintedReferenceHost`) without editing generated code. The mixin class must extend `ASTWrapperPsiElement` (or the appropriate stub base class) and live in `src/`.

**`fake` rules:** Define PSI interfaces and visitor methods without affecting the parser. Useful for creating intermediate types in the PSI hierarchy (e.g. `fake binary_expr ::= expr+` to group add/mul expressions under a common interface).

#### `ElixirPsiImplUtil` Method Resolution

GrammarKit resolves `methods=[...]` declarations by searching `psiImplUtilClass` (`ElixirPsiImplUtil`) for static methods whose first parameter type matches the rule's generated interface or one of its `implements` interfaces. It tries types in order: the concrete generated type first, then each `implements` interface.

**Ambiguity pitfall:** If a generated class implements two interfaces `A` and `B`, and `ElixirPsiImplUtil` has both `foo(A)` and `foo(B)`, the Java compiler reports an ambiguous call even though GrammarKit only emits a call to one. The fix is to consolidate the overloads:
- **Remove the more-specific overload** and fold it into the broader one using `instanceof`, OR
- **Make one interface extend the other** so Java's overload resolution picks the more-specific type

#### Testing After BNF Changes

After regenerating parser code:
1. **Compile first** - fix any ambiguity or type errors in `ElixirPsiImplUtil` before running tests.
2. **Run all parsing tests** - BNF changes often affect golden `.txt` files under `testData/`. If the PSI tree shape is intentionally unchanged but element type names changed (e.g. from a rename), bulk-update the golden files:
   ```bash
   find testData -name '*.txt' -exec grep -l oldName {} \; | xargs sed -i 's/oldName/newName/g'
   ```
3. **Run annotator/inspection tests** - these exercise the visitor and may hit `StackOverflowError` if a visitor collision exists.
4. **Check `base.txt`** - the plugin verifier baseline file also contains PSI element names.

#### Suppressing Warnings in the BNF

**`//noinspection BnfResolve` for JFlex tokens:** GrammarKit warns `Unresolved rule reference` for tokens that are defined by JFlex (e.g. `pin = DO`), not by BNF rules. These are false positives - suppress them with a comment on the line above:
```bnf
//noinspection BnfResolve
pin = DO
```

**`gen/` inspection suppression:** The build script marks `gen/` as generated sources via `idea.module.generatedSourceDirs` so that IntelliJ suppresses inspections (e.g. unused imports) on GrammarKit-generated code. There is no GrammarKit configuration to control which imports the generator emits.

### JFlex Lexer Regeneration

The Elixir lexer `gen/org/elixir_lang/ElixirFlexLexer.java` is generated from `src/org/elixir_lang/Elixir.flex` using [JFlex](https://jflex.de/). If you modify `Elixir.flex` (e.g. adding a new state, changing a rule, or fixing escape handling), you must regenerate the lexer.

#### Prerequisites
- Install the **GrammarKit** plugin in IntelliJ IDEA (it bundles JFlex). Settings -> Plugins -> search "Grammar-Kit".

#### Regenerating the Lexer

1. Open `src/org/elixir_lang/Elixir.flex` in the editor.
2. Right-click inside the file -> **Run JFlex Generator**.
3. The generator overwrites `gen/org/elixir_lang/ElixirFlexLexer.java` in place.
   The first time you run it (or on a fresh checkout) it may prompt you to select an output
   folder - point it at the repository root so it discovers `gen/` automatically.

`gen/` is declared as `generatedSourceDirs` in `build.gradle.kts`, so the IDE warns you if you try to manually edit it and suppresses inspections on it automatically.

#### ⚠️ No Manual Post-Regeneration Step Required

The stack is cleared automatically. `ElixirFlexLexerAdapter.start()` calls the generated
`clearStack()` method before each lex, so no hand-patching of the generated file is required
after regeneration.

### Color Schemes

JetBrains plugins are able to set the text attribute values for `TextAttributeKey`s that are unique to the plugin by using `additionalTextAttributes` entries in `resources/META-INF/plugin.xml` (the plugin ships two: `colorSchemes/ElixirDefault.xml` for the `Default` scheme and `colorSchemes/ElixirDarcula.xml` for `Darcula`).  If you have a Color Scheme for Elixir you like, you can propose it as the default for a named theme by extracting the `additionTextAttributes` `file` from an Exported Settings `.jar`.

#### Customizing Scheme

1. Preferences > Editor > Colors & Fonts > Elixir
2. Customize the colors
3. Click "Save As" to name the Scheme (`My $SCHEME_NAME`)_

#### Exporting Settings

1. File > Export Settings
2. Click "Select None"
3. Check "Editor Colors"
4. Change the "Export settings to:" path to a place you can easily access it in the terminal
5. Click "OK"

#### Unpack Settings

1. `mkdir settings`
2. `cd settings`
3. `jar xf $SAVE_DIRECTORY/settings.jar`

#### Convert ICLS to Additional Text Attributes format

1. `mv colors/colors/My\ $SCHEME_NAME.icls $INTELLIJ_ELIXIR/colorSchemes/Elixir$SCHEME_NAME.xml` (`$SCHEME_NAME` will be `Default`, `Darcula` or another shared theme name.)
2. Remove all elements except for `scheme attributes`.
3. Remove the outer `scheme` tag
4. Rename the `attributes` tag to `list`.
5. Add `<?xml version='1.0'?>` to the top of the file

#### Add Additional Text Attributes to plugin

1. In `resources/META-INF/plugin.xml` inside the `idea-plugin extensions[defaultExtensionNs="com.intellij"]` tag, add a new additionalTextAttribute tag: `<additionalTextAttributes file="colorSchemes/Elixir$SCHEME_NAME.xml" scheme="SCHEME_NAME"/>`

## Building

### Plugin version scheme

The built plugin's version depends on how it was produced:

| Build | Version | Example |
|---|---|---|
| Tagged release (`tag.yml`, `-PpluginVersionOverride`) | the git tag verbatim | `24.0.0` |
| Release channel (`-PpublishChannels=default`) | base version, no suffix | `24.0.0` |
| CI canary | `<base>-pre+<UTC commit time>.<commit>` | `24.0.1-pre+20260804164541.64e3d69a` |
| Local build | `<base>-dev+<UTC commit time>.<commit>` | `24.0.1-dev+20260804164541.64e3d69a` |
| `-PversionSuffix=<s>` | `<base>-<s>` | `24.0.1-rc1` |

`<base>` is `pluginVersion` from `gradle.properties`, with the patch bumped for any non-release
channel so the IDE does not offer to "update" a local build to the released one. A work tree that
differs from `HEAD` in any way adds `-wip` after the commit - **including untracked files**, since
an un-added source file is compiled into the plugin just like a committed one.

Why the commit is there: the version string is the **only** field identifying the built code in a
JetBrains Marketplace exception report. The IDE sends `IdeaPluginDescriptor.version` as
`plugin.version`, and the exception analyzer surfaces it as `pluginVersion` with no companion
metadata - so without the commit, a crash report cannot be traced to a source revision.
`-dev` vs `-pre` separates a maintainer's own sandbox reports from real canary users' reports.

The timestamp is the **commit's** committer date, not the build's clock. A build-clock stamp reads as
a source date without being one, which is how a report from a build stamped 30 June turned out to be
running source from three weeks earlier. Committer date rather than author date, so a rebased or
amended commit sorts later.

Three consequences worth knowing:

- The whole suffix is a pure function of (commit, dirty), so **rebuilding unchanged source produces an
  identical version** and leaves `patchPluginXml` - and everything downstream of the patched
  descriptor - `UP-TO-DATE`. A wall-clock stamp re-runs all of it on every reconfiguration.
- Reading `HEAD` is a configuration-cache input (`versioning.GitSourceIdValueSource`), so committing
  or flipping the tree's dirty state invalidates the configuration cache. Ordinary editing does not:
  a tree that was already dirty stays dirty, so the value is unchanged and the cache is reused.
  Release builds skip the lookup entirely.
- If `git` is unavailable the version falls back to a build-clock stamp with no commit. The two are
  told apart by the commit: a lone timestamp is a build time, a timestamp followed by a commit is that
  commit's time.

### Documentation

Three files carry a table of contents generated by [`doctoc`](https://github.com/thlorenz/doctoc):
[`AGENTS.md`](AGENTS.md), [`CONTRIBUTING.md`](CONTRIBUTING.md) and
[`COLOR_SCHEME_DESIGN.md`](COLOR_SCHEME_DESIGN.md). `CHANGELOG.md`, `README.md`, `RELEASING.md` and
`UPGRADING.md` have none - do not add one.

Regenerate with `mise`, which needs no global install:

```powershell
mise exec -c "npx doctoc AGENTS.md CONTRIBUTING.md COLOR_SCHEME_DESIGN.md" node@25.4.0
```

Use `-c` rather than `--`: PowerShell strips `--` before `mise` sees it.

> [!IMPORTANT]
> **Always name the files. Never run `doctoc .`** It recurses and ignores `.gitignore`, so it inserts a
> TOC into every markdown file it can reach.
