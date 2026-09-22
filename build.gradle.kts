/*
 * Main Build Script
 * Purpose: Orchestrates the build of the IntelliJ Elixir plugin.
 *
 * Platform Support:
 * - POSIX (Linux, macOS): Full support
 * - Windows: Full support (Git Bash, MSYS2, WSL, or native)
 *
 * Key Components:
 * 1. resolveElixirErlangSdks: resolves the Elixir/Erlang SDK (mise/PATH/env aware) for the quoter
 *    build and the test tasks; shared with testUI. No from-source Elixir build in the normal path.
 * 2. QuoterService (BuildService): Manages Quoter daemon lifecycle with guaranteed cleanup
 * 3. Platform abstraction: Automatic detection and platform-specific implementations
 * 4. Tasks: Thin wrappers for CI caching and developer discoverability
 *
 * Version Catalog: Uses libs.* for dependency management (gradle/libs.versions.toml)
 * Configuration Cache: Fully compatible with Gradle configuration cache
 */

import com.adarshr.gradle.testlogger.TestLoggerExtension
import com.adarshr.gradle.testlogger.theme.ThemeType
import cache.CachePathsTask
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import corpus.CorpusEntry
import corpus.corpusFor
import de.undercouch.gradle.tasks.download.Download
import deps.registerResolveExternalDependenciesTasksForAllProjects
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.Constants
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import quoter.QuoterService
import quoter.tasks.GetQuoterDepsTask
import quoter.tasks.ReleaseQuoterTask
import quoter.tasks.StartQuoterTask
import sdk.ElixirErlangSdkArgumentProvider
import sdk.MiseCurrentVersionValueSource
import sdk.ErlangAvailabilityCheckAction
import sdk.ResolveElixirErlangSdksTask
import sdk.mixPairDir
import sdk.pairToken
import sdk.quoterReleaseExecutablePath
import sdk.resolveMixEnv
import sdk.versionWithoutBuildTag
import sdk.elixirTestEnvironment
import versioning.ChangelogSettings
import versioning.GitSourceIdValueSource
import versioning.PluginVersion
import versioning.VersionFetcher
import java.text.SimpleDateFormat
import java.util.*

// Uses the Version Catalog defined in gradle/libs.versions.toml
plugins {
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.download)
    alias(libs.plugins.test.logger)
    alias(libs.plugins.versions)
    id("java")
    id("idea")
}

project.configurations.all {
    exclude(Constants.Configurations.Dependencies.BUNDLED_MODULE_GROUP, "com.intellij.kubernetes")
    exclude(Constants.Configurations.Dependencies.BUNDLED_PLUGIN_GROUP, "com.intellij.kubernetes")
}

// --- Version Catalog Captures ---
// Capture these early to avoid "Extension 'libs' not found" errors in subproject blocks
val libJunit = libs.junit
val libOpentest4j = libs.opentest4j
val libCommonsIo = libs.commons.io
val libMockitoCore = libs.mockito.core

// --- Configuration Properties ---

// Which Elixir/OTP the build targets. Explicit properties win; otherwise mise is asked what this
// project resolves to (mise.toml, overridable per-developer in the gitignored mise.local.toml).
//
// Properties are authoritative rather than a fallback: -PelixirVersion means "use this one", and
// consulting mise first would let a local override silently outrank an explicit request. It also
// keeps mise off the critical path for CI, which passes both flags and has no mise installed.
fun miseVersionOf(tool: String): Provider<String> =
    providers.of(MiseCurrentVersionValueSource::class) {
        parameters.tool.set(tool)
        parameters.workingDir.set(layout.projectDirectory.asFile)
    }

val expectedElixirVersion: Provider<String> =
    providers.gradleProperty("elixirVersion").orElse(miseVersionOf("elixir"))
val expectedOtpVersion: Provider<String> =
    providers.gradleProperty("otpVersion").orElse(miseVersionOf("erlang"))
val expectedVersionSource: String =
    if (providers.gradleProperty("elixirVersion").isPresent) "-PelixirVersion/-PotpVersion" else "mise"

// The Elixir half of the cache directory names below (the quoter tree pairs this with the OTP version;
// see quoterUnzippedPath), so a quoter built under one Elixir is never reused for another. The -otp-N
// build tag is stripped: mise reports "1.13.4-otp-24" where CI passes "1.13.4", and they are the same
// Elixir - keeping the tag would give the two a cache tree each. Note that tag is not the OTP version:
// it records which OTP that Elixir release was built for, and the pair keying uses the real one.
// Only "unresolved" when neither properties nor mise answered; resolveElixirErlangSdks then fails
// with instructions before anything writes to that path.
val elixirVersion: String = versionWithoutBuildTag(expectedElixirVersion.getOrElse("unresolved"))

val quoterRepo = providers.gradleProperty("quoterRepo").getOrElse("intellij-elixir/intellij-elixir-quoter")
val quoterRef = providers.gradleProperty("quoterRef").getOrElse("v3.0.0")
// Cache namespace for the quoter, derived from the ref ('/' is illegal in a path segment). Keeps
// each repo/ref's downloaded zip, build dir, and daemon tmp dir separate, so switching source
// never reuses another's artifacts.
val quoterRefSlug = quoterRef.replace('/', '-')

// Publish channel: "default" for release, "canary" for pre-release
val publishChannel: String = providers.gradleProperty("publishChannels").getOrElse("canary")

// Calculate the plugin version; for canary builds, the patch is bumped to the next version
// so that the IDE's plugin manager doesn't offer to "update" it to the released version.
val basePluginVersion: String = PluginVersion.getBaseVersion(
    providers.gradleProperty("pluginVersion").get(),
    publishChannel
)

val useDynamicEapVersion: Boolean = project.property("useDynamicEapVersion").toString().toBoolean()
val skipSearchableOptions: Boolean = project.property("skipSearchableOptions").toString().toBoolean()

val actualPlatformVersion: String = if (useDynamicEapVersion) {
    // Calling the helper from buildSrc
    VersionFetcher.getLatestEapBuild(platformType = providers.gradleProperty("platformType").getOrElse("IU"))
} else {
    project.property("platformVersion").toString()
}

// IntelliJ Platform 262 (2026.2) ships JARs compiled for Java 25, and 261 is Java 21.
// `javac --release` validates the class-file version of everything on the compile classpath,
// so the Java level must follow the platform being built against -- a fixed level cannot
// serve both 261 and 262.
//
// The actual released JAR stays at Java 21 because buildPlugin runs against platformVersion's
// default (gradle.properties), which tracks the minimum supported platform.
//
// This functionality copies what the IntelliJ Platform Gradle Plugin's PlatformJavaVersions does (permalinks
// pinned to the 2.18.1 tag; repin when bumping the plugin):
// https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/d59281043510b321093f75fec7892d1774ac3060/src/main/kotlin/org/jetbrains/intellij/platform/gradle/utils/PlatformJavaVersions.kt#L11-L17
// https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/d59281043510b321093f75fec7892d1774ac3060/src/main/kotlin/org/jetbrains/intellij/platform/gradle/plugins/project/IntelliJPlatformBasePlugin.kt#L500-L516
// https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/d59281043510b321093f75fec7892d1774ac3060/src/main/kotlin/org/jetbrains/intellij/platform/gradle/plugins/project/IntelliJPlatformModulePlugin.kt#L79-L89
// Background: https://github.com/JetBrains/intellij-platform-gradle-plugin/commit/467e0c7d
val platformBuildNumber: Int = actualPlatformVersion
    .substringBefore('-') // strip EAP/SNAPSHOT suffixes, e.g. "262-EAP-SNAPSHOT"
    .split('.')
    .let { parts ->
        val major = parts[0].toIntOrNull() ?: 0
        when {
            // Marketing version, e.g. "2026.2" or "2026.1.5" -> 262, 261
            major >= 2000 -> (major - 2000) * 10 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
            // Already a build number, e.g. "262.8665.258"
            else -> major
        }
    }
val javaVersionStr: String = if (platformBuildNumber >= 262) "25" else libs.versions.java.get()

// Setup Paths
val cachePath: Directory = layout.projectDirectory.dir("cache")
val elixirPath: Directory = cachePath.dir("elixir-$elixirVersion")
// Keyed on the Elixir/OTP PAIR, via the same rule as MIX_HOME/MIX_ARCHIVES below. `_build` and `deps`
// hold BEAM code and a release, so the reason MIX_ARCHIVES is pair-keyed - the loader
// rejects a chunk layout built by a different OTP - applies here unchanged. Keyed on Elixir alone,
// 1.13.4/24.3.4.6 and 1.13.4/25.3.2.21 shared one tree and overwrote each other's build, while their
// hex/rebar sat in correctly separated pair directories.
val quoterUnzippedPath: Directory =
    cachePath.dir("${pairToken(elixirVersion, expectedOtpVersion.getOrElse("unresolved"))}-quoter-$quoterRefSlug")
// MIX_ENV for both quoter mix tasks AND the launcher path below - one value, so the directory the build
// looks in is the one mix wrote. Read through `providers` rather than System.getenv so the
// configuration cache treats it as an input and re-resolves when it changes. Defaults to prod; see
// sdk.resolveMixEnv for why the build pins this instead of taking mix's default.
val quoterMixEnv: String = resolveMixEnv(providers.environmentVariable("MIX_ENV").orNull)
val quoterExe: RegularFile = quoterUnzippedPath.file(quoterReleaseExecutablePath(quoterMixEnv))
// Whether the daemon could be built, written by releaseQuoter and read by startQuoter and the test
// tasks. Beside the release it describes, so it is keyed on the Elixir/OTP pair like the rest of that
// tree; in the shared cache root it would describe whichever pair built last.
val quoterAvailabilityFile: RegularFile = quoterUnzippedPath.file("quoter-availability.properties")
// Written by `startQuoter` rather than `releaseQuoter`: whether the daemon actually came up, which is
// a different question from whether it compiled, and the one the tests care about.
val quoterStartedFile: RegularFile = quoterUnzippedPath.file("quoter-started.properties")
// Opts back in to a hard failure at releaseQuoter, for debugging the quoter itself.
val quoterRequired: Boolean = providers.gradleProperty("quoterRequired").getOrElse("false").toBoolean()
val quoterTmpPath: Directory = cachePath.dir("quoter_tmp_$quoterRefSlug")
// Distributed Erlang node names for the quoter daemon and for the test JVM that talks to it. Erlang
// registers a node by name with the machine-wide epmd, and epmd allows exactly one node per name - so
// with a fixed name a second checkout starting its own quoter gets "the name quoter@127.0.0.1
// seems to be in use by another Erlang node" and exits 0, which `startQuoter` reports as the daemon
// dying immediately. Deriving both names from the checkout path lets worktrees run tests concurrently.
// Unset (a plain `java -jar` of the plugin, or any non-test use) the plugin falls back to fixed names.
val quoterNodeToken: String = rootDir.absolutePath.lowercase().hashCode().toUInt().toString(16)
val quoterNodeName: String = "quoter_$quoterNodeToken@127.0.0.1"
val quoterClientNodeName: String = "quoter_client_$quoterNodeToken@127.0.0.1"
// hex/rebar + fetched deps are cached under the project (used by the quoter mix build).
val mixHomePath: Directory = cachePath.dir("mix_home")
val mixArchivesPath: Directory = cachePath.dir("mix_archives")

// MIX_HOME and MIX_ARCHIVES for the pair this build targets. getQuoterDeps declares both as outputs, so
// each must be the PAIR directory and never the shared root: with a root declared, a sibling pair's
// install changed this task's output snapshot, so the other pair's entry could not be restored and a
// version switch reinstalled hex and rebar over the network. Pair-keying also makes anything present
// provably this pair's, which is what lets the task skip an install it already has.
//
// Keyed on the *expected* versions so the paths are known at configuration time - safe because
// resolveElixirErlangSdks rejects any SDK whose actual version is not isCompatibleVersion with the
// expected one. Both quoter tasks are wired from these two values, so a declared output and the
// directory mix actually writes cannot drift apart.
val mixHomeForPair: File =
    mixPairDir(mixHomePath.asFile, elixirVersion, expectedOtpVersion.getOrElse("unresolved"))
val mixArchivesForPair: File =
    mixPairDir(mixArchivesPath.asFile, elixirVersion, expectedOtpVersion.getOrElse("unresolved"))

// EXPORT FOR SUBPROJECTS (Required for jps-builder to access this path)
extra["elixirPath"] = elixirPath.asFile.absolutePath
// Declared as test-task inputs in both projects - see the root `test` task for why.
extra["expectedElixirVersion"] = expectedElixirVersion.getOrElse("unresolved")
extra["expectedOtpVersion"] = expectedOtpVersion.getOrElse("unresolved")

// Version suffix logic:
// - "default" channel = no suffix (release build)
// - explicit versionSuffix property = use that
// - CI, untagged = "-pre+<commit time>.<commit>" (canary build)
// - local        = "-dev+<commit time>.<commit>"
//
// The version string is the ONLY field identifying the built code in a Marketplace exception report:
// the IDE sends IdeaPluginDescriptor.version as `plugin.version`, and the exception-analyzer API
// surfaces it as `pluginVersion` with no companion metadata. So it has to say what the build was made
// from, and a build-clock timestamp reads as a source date without being one.
//
// `-dev` vs `-pre` separates a maintainer's own sandbox reports from real canary users, which is
// otherwise only recoverable by looking for debugger frames and home directories in the stack trace.
//
// The timestamp is the commit's, so the whole suffix is a pure function of the source: ordering still
// works (committer dates advance along a branch, and PluginVersion's patch bump keeps any untagged
// build ahead of the released one), and rebuilding unchanged source no longer churns the version and
// with it patchPluginXml. See versioning.GitSourceIdValueSource for the rest of the reasoning.

// Left as a Provider and only resolved in the branch that uses it: reading HEAD makes the commit a
// configuration-cache input, and neither a release (versioned from its tag) nor an explicit
// -PversionSuffix build should pay that invalidation for a value it discards.
val sourceIdProvider = providers.of(GitSourceIdValueSource::class) {
    parameters.workingDir.set(layout.projectDirectory.asFile)
}

val versionSuffix: String = when {
    publishChannel == "default" -> ""
    providers.gradleProperty("versionSuffix").isPresent &&
        providers.gradleProperty("versionSuffix").get().isNotEmpty() ->
            "-${providers.gradleProperty("versionSuffix").get()}"
    else -> {
        // GitHub Actions (and most CI) set CI=true; absence means a developer's machine.
        val channelTag = if (System.getenv("CI").isNullOrBlank()) "dev" else "pre"
        // Without git there is no commit and no commit time, so the build clock is all that is left
        // to order builds by. The absent commit is what marks the timestamp as a build time.
        val metadata = sourceIdProvider.orNull
            ?: SimpleDateFormat("yyyyMMddHHmmss").apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
        "-$channelTag+$metadata"
    }
}

// The Tag Release workflow (.github/workflows/tag.yml) passes the release tag (minus the leading "v") as
// pluginVersionOverride so the built plugin's version matches the git tag exactly; the computed
// base+suffix version is only for builds that are never tagged (local dev, push-to-main CI).
version = providers.gradleProperty("pluginVersionOverride").orNull?.takeIf(String::isNotEmpty)
    ?: "$basePluginVersion$versionSuffix"

logger.lifecycle("[elixir-build] platform=$actualPlatformVersion version=$version channel=$publishChannel dynamicEap=$useDynamicEapVersion skipSearchableOptions=$skipSearchableOptions quoterExe=$quoterExe quoterTmpPath=${quoterTmpPath.asFile.absolutePath}")

// --- Changelog ---
// CHANGELOG.md is the single source of the plugin's change notes. resources/META-INF/changelog.html
// used to hold a separately hand-written copy for users, and it went stale: 24.0.0 shipped with
// v23.9.0 as its newest section, so everyone who installed it read notes for a version two releases
// old. Deriving changeNotes from CHANGELOG.md makes that failure unreachable.
//
// What publishes is declared in gradle.properties and validated in ChangelogSettings, so that the
// per-pull-request check in changelog.yml reads the same declaration from a properties file.
val changelogSettings = ChangelogSettings.from(
    groups = providers.gradleProperty("changelogGroups").get(),
    publishedGroups = providers.gradleProperty("changelogPublishedGroups").get(),
    publishedVersions = providers.gradleProperty("changelogPublishedVersions").get(),
)
val changelogVersion: String = ChangelogSettings.marketingVersion(version.toString())

changelog {
    // Keep a Changelog format is this plugin's native format, so the parser needs no configuration:
    // `path`, `headerParserRegex`, `unreleasedTerm` and `itemPrefix` all default correctly, and the
    // IntelliJ Platform Gradle Plugin supplies `repositoryUrl` and an empty version prefix.
    version = changelogVersion
    groups = changelogSettings.groups
}

// A lazy provider, NOT an eager read. This matters: rendering eagerly at configuration time produced
// a plain String that the configuration cache stored without recording CHANGELOG.md as an input, so
// editing the changelog left the cache valid and the build kept publishing the previous notes - the
// same staleness bug in a new place. Going through the extension's own providers makes the file a
// tracked input. This is also the shape the IntelliJ Platform Plugin Template uses.
// <change-notes> is capped at 65535 characters in the plugin descriptor, and the plugin verifier
// rejects the whole plugin when it is exceeded - which surfaces as four red verification legs saying
// "Invalid plugin descriptor", not as a changelog problem. Truncating keeps the notes valid however
// large a release grows, so the cap stops being a build failure waiting to happen.
//
// The cut lands on a whole </li> and then closes every list and item left open, because a prefix
// ending inside a <ul> is malformed and the verifier is entitled to reject that too. Those closers
// count against the cap, so the cut moves back until prefix, closers and tail all fit.
val changeNotesLimit = 65535

// main rather than the tag being built: a canary has no tag yet, so a tag-pinned link would 404 for
// exactly the readers most likely to follow it.
val changelogUrl = "https://github.com/intellij-elixir/intellij-elixir/blob/main/CHANGELOG.md"

fun truncateChangeNotes(html: String, limit: Int = changeNotesLimit): String {
    if (html.length <= limit) return html

    val total = Regex("<li>").findAll(html).count()
    fun tail(dropped: Int) =
        """<p>&#8230;and $dropped more. <a href="$changelogUrl">Full changelog</a></p>"""

    // Closing tags for every <ul>/<ol>/<li> still open at the end of kept, innermost first.
    fun closers(kept: String): String {
        val open = mutableListOf<String>()
        Regex("<(/?)(ul|ol|li)>").findAll(kept).forEach { match ->
            val (slash, tag) = match.destructured
            if (slash.isEmpty()) open.add(tag) else open.removeLastOrNull()
        }
        return open.reversed().joinToString("") { "</$it>" }
    }

    var cut = html.lastIndexOf("</li>", limit)
    while (cut >= 0) {
        val kept = html.substring(0, cut + "</li>".length)
        val dropped = total - Regex("<li>").findAll(kept).count()
        val truncated = kept + closers(kept) + tail(dropped)
        if (truncated.length <= limit) return truncated
        cut = html.lastIndexOf("</li>", cut - 1)
    }
    return tail(total)
}

val renderedChangeNotes: Provider<String> = providers.provider {
    // getAll() returns file order, newest first, with Unreleased leading when present.
    val all = changelog.getAll()

    // The version being built, or Unreleased when it has no section of its own - which is the normal
    // case for a canary, whose patch is bumped past the last release.
    val startKey = sequenceOf(changelogVersion, changelog.unreleasedTerm.get())
        .firstOrNull(all::containsKey)

    val ordered = all.keys.toList()
    val startIndex = startKey?.let(ordered::indexOf)?.takeIf { it >= 0 } ?: 0

    ordered.drop(startIndex)
        .asSequence()
        .map { key ->
            val item = all.getValue(key)
            // Group-level selection has to be expressed through an item-level filter: `groups` does
            // not filter, and withFilter's predicate receives each item's text, never its section
            // name. So take the item strings belonging to the published groups and keep exactly
            // those. Keeping rather than excluding biases a duplicated line toward being published,
            // which is the safer mistake.
            val published = item.sections
                .filterKeys { it in changelogSettings.publishedGroups }
                .values.flatten().toSet()
            changelog.renderItem(
                item
                    // A version heading per section, since more than one version is listed. Plain
                    // withHeader renders it bracketed ("[v23.9.0]"); withLinkedHeader(false) gives
                    // the bare version, which is what a reader wants.
                    .withHeader(true)
                    .withLinkedHeader(false)
                    .withLinks(false)
                    .withEmptySections(false)
                    .withFilter { it in published },
                Changelog.OutputType.HTML,
            )
        }
        // A version whose entries were all in unpublished groups renders as a lone heading, so drop it
        // rather than showing an empty one - and do not let it consume one of the slots.
        .filter { it.contains("<li>") }
        .take(changelogSettings.publishedVersions)
        .joinToString("\n")
        .let(::truncateChangeNotes)
}

//// --- Dependency Updates Configuration ---
//// Run with: ./gradlew dependencyUpdates
tasks.withType<DependencyUpdatesTask> {
    gradleReleaseChannel = "current"

    filterConfigurations = Spec<Configuration> {
        !(
                it.name.startsWith("intellij") ||
                        it.name.startsWith("jetbrainsRuntime") ||
                        it.name.startsWith("marketplace") ||
                        it.name.startsWith("compose")
                )
    }


    // Only report stable -> stable upgrades; reject pre-releases (RC, Beta, Alpha, SNAPSHOT, M1, etc.)
    rejectVersionIf {
        val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { kw ->
            candidate.version.uppercase().contains(kw)
        }
        val isStable = stableKeyword || "^[0-9,.v-]+(-r)?$".toRegex().matches(candidate.version)
        isStable.not()
    }
}

// --- Global Project Configuration ---
allprojects {
    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("java") {
        dependencies {
            "testImplementation"(libJunit)
            "testImplementation"(libOpentest4j)
        }

        configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.valueOf("VERSION_$javaVersionStr")
            targetCompatibility = JavaVersion.valueOf("VERSION_$javaVersionStr")
        }

        tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

        tasks.withType<Test>().configureEach {
            inputs.dir(rootProject.layout.projectDirectory.dir("testData"))
                .withPropertyName("testData")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    pluginManager.withPlugin("com.adarshr.test-logger") {
        configure<TestLoggerExtension> {
            theme = ThemeType.MOCHA
            showExceptions = true
            showStackTraces = true
            showFullStackTraces = false
            slowThreshold = 2000
            showSummary = true
            showStandardStreams = false
            showFailedStandardStreams = true
        }
    }
}

// --- Subprojects (JPS) ---
subprojects {
    pluginManager.withPlugin("org.jetbrains.intellij.platform.base") {
        repositories {
            intellijPlatform { defaultRepositories() }
        }

        dependencies {
            intellijPlatform {
                create(providers.gradleProperty("platformType"), providers.provider { actualPlatformVersion })
            }
        }
    }

    pluginManager.withPlugin("java") {
        sourceSets {
            main {
                java.srcDirs("src")
                resources.srcDirs("resources")
            }
        }
    }

    // Kotlin subprojects (jps-shared) must use the same platform-derived Java level as the
    // rest of the build, otherwise their published variants mismatch the root's toolchain.
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        configure<KotlinJvmProjectExtension> {
            jvmToolchain(javaVersionStr.toInt())
        }
    }
}

// --- Root Project Repositories ---
repositories {
    intellijPlatform { defaultRepositories() }
}

// --- Source Sets ---
sourceSets {
    main {
        java.srcDirs("src", "gen")
        resources.srcDirs("resources")
    }
    test {
        java.srcDir("tests")
    }
    create("testUI", Action<SourceSet> {
        kotlin.srcDir("testUI/kotlin")
        resources.srcDir("testUI/resources")
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    })
}

idea {
    module {
        // Mark gen/ as generated sources so IntelliJ suppresses inspections (e.g. unused imports)
        // on Grammar-Kit-generated PSI classes and the JFlex-generated ElixirFlexLexer.
        generatedSourceDirs.add(file("gen"))
        testSources.from(sourceSets["testUI"].kotlin.srcDirs)
        testResources.from(sourceSets["testUI"].resources.srcDirs)
    }
}

val testUIImplementation = configurations.getByName("testUIImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

val testUIRuntimeOnly = configurations.getByName("testUIRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

// --- IntelliJ Platform Configuration ---
intellijPlatform {
    if (skipSearchableOptions) {
        buildSearchableOptions = false
    }
    // Disable auto-reload by default: it is noisy during runIde and has been unreliable.
    // Re-enable via `ideaAutoReload=true` in `gradle.properties` or `-PideaAutoReload=true`.
    autoReload = providers.gradleProperty("ideaAutoReload")
        .map { it.toBoolean() }
        .orElse(false)

    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginName")
        version = project.version.toString()

        val stripTag = { text: String, tag: String -> text.replace("<${tag}>", "").replace("</${tag}>", "") }
        val bodyInnerHTML = { path: String -> stripTag(stripTag(file(path).readText(), "html"), "body") }

        changeNotes = renderedChangeNotes
        description = bodyInnerHTML("resources/META-INF/description.html")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.provider { null }
        }
        vendor {
            name = providers.gradleProperty("vendorName")
            email = providers.gradleProperty("vendorEmail")
            url = providers.gradleProperty("pluginRepositoryUrl")
        }
    }

    publishing {
        token = providers.environmentVariable("JET_BRAINS_MARKETPLACE_TOKEN")
        channels = listOf(publishChannel)
    }

    pluginVerification {
        // Match CI failure levels: only fail on compatibility problems and invalid plugin.
        // INTERNAL_API_USAGES is excluded because it is all-or-nothing: it would fail on the usages
        // JetBrains have agreed to ignore. Those are capped by .github/internal-api-usages-allowlist.txt,
        // which CI enforces; to check a local run against it (broader IDE set than CI, so it can report
        // more):
        //   REPORTS_DIR=build/reports/pluginVerifier VERIFY_OUTCOME=success \
        //     node .github/scripts/check-internal-api-usages.js
        // See https://platform.jetbrains.com/t/stricter-plugin-verification-in-intellij-platform-gradle-plugin-2-15-0/4169
        failureLevel.set(
            listOf(
                VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            )
        )
        verificationReportsFormats.set(
            listOf(
                VerifyPluginTask.VerificationReportsFormats.MARKDOWN,
                VerifyPluginTask.VerificationReportsFormats.HTML,
                VerifyPluginTask.VerificationReportsFormats.PLAIN
            )
        )
        ides {
            select {
                types = providers.gradleProperty("pluginVerifierIdeTypes")
                    .get()
                    .split(",")
                    .map { IntelliJPlatformType.valueOf(it.trim()) }

                channels = providers.gradleProperty("pluginVerifierChannels")
                    .get()
                    .split(",")
                    .map { ProductRelease.Channel.valueOf(it.trim()) }

                sinceBuild = providers.gradleProperty("pluginVerifierVersion").get()
            }
        }
        // This allows us to verify small IDEs without errors about the JPS plugin.
        // It's not ideal, but there doesn't seem to be any other way to deal with it right now.
        externalPrefixes.add("org.jetbrains.jps")
    }
    sourceSets {
        val testUI = project.sourceSets.getByName("testUI")
        add(testUI)
    }
}

val openVerificationReports = tasks.register("openVerificationReports") {
    description = "Opens plugin verification markdown reports in the IDE"
    group = "verification"

    // Always run when triggered (no up-to-date checking)
    outputs.upToDateWhen { false }

    // Locals (not top-level script properties): the doLast action must capture plain File values -
    // a top-level val is a field on the script object, and capturing it drags the whole script into
    // the configuration cache, which cannot serialize script object references.
    val verifierReportsDir: File = layout.buildDirectory.dir("reports/pluginVerifier").get().asFile
    val projectDirFile: File = projectDir

    doLast {
        val mdFiles = verifierReportsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> dir.resolve("report.md").takeIf { it.exists() } }
            ?: emptyList()

        val htmlFiles = verifierReportsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> dir.resolve("report.html").takeIf { it.exists() } }
            ?: emptyList()

        if (mdFiles.isNotEmpty() || htmlFiles.isNotEmpty()) {
            logger.lifecycle("")
            logger.lifecycle("=== Plugin Verification Reports ===")
            mdFiles.forEach { md ->
                logger.lifecycle("  ${md.toRelativeString(projectDirFile)}")
            }
            htmlFiles.forEach { html ->
                logger.lifecycle("  file:///${html.absolutePath.replace("\\", "/")}")
            }
            logger.lifecycle("")
        }
    }
}

tasks.named("verifyPlugin") {
    finalizedBy(openVerificationReports)
}

intellijPlatformTesting.runIde.configureEach {
    plugins {
        // Run-IDE sandbox only: Kubernetes plugin consistently fails on startup.
        disablePlugin("com.intellij.kubernetes")
        // Run-IDE sandbox only: Sass plugin logs missing color scheme resources.
        disablePlugin("org.jetbrains.plugins.sass")
    }

    // On Windows, the sandboxed IDEA process holds OS-level file locks on its native binaries
    // (fsnotifier.exe, DLLs, etc.). If a concurrent Gradle task triggers a transforms-cache
    // refresh for the same IDEA distribution, Gradle cannot delete the locked files and fails.
    //
    // Setting localPath makes the task run IDEA from your locally installed copy rather than
    // the extracted Gradle transforms cache, so there is no lock conflict.
    //
    // Set in ~/.gradle/gradle.properties (not committed):
    //   runIdeLocalPath=C:\\Program Files\\JetBrains\\IntelliJ IDEA 2026.1.1
    if (getName() == "runIde") {
        localPath.set(layout.dir(providers.gradleProperty("runIdeLocalPath").map { project.file(it) }))
    }
}

// --- Kotlin Configuration ---
kotlin {
    jvmToolchain(javaVersionStr.toInt())
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.valueOf("JVM_$javaVersionStr")
        freeCompilerArgs.add("-jvm-default=enable")
        // Restricts stdlib references to what 2026.1 (minimumSupported) bundles, independent of
        // the newer compiler in gradle/libs.versions.toml's kotlin entry. Doesn't cover
        // jps-shared - a separate project, its own tasks.withType block needed there too.
        apiVersion = KotlinVersion.KOTLIN_2_3
    }
}

// --- Mockito Agent Configuration (Root project only) ---
val mockitoAgent: Configuration = configurations.create("mockitoAgent")

// --- Dependencies ---
dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.provider { actualPlatformVersion })
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(",") })
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(",") })
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        // com.intellij.xml.testFramework.XmlElementTypeServiceHelper - needed by HeexParsingTestCase
        // to register the XML PSI services a multi-root (HEEx + HTML) ParsingTestCase requires. Not
        // part of the IDE distribution's own jars, unlike the rest of the XML platform modules.
        testFramework(TestFrameworkType.Plugin.XML)
        // UI Test framework dependencies
        testFramework(TestFrameworkType.Starter, configurationName = "testUIImplementation")
        testFramework(TestFrameworkType.JUnit5, configurationName = "testUIImplementation")
    }

    implementation(project(":jps-shared"))
    runtimeOnly(project(":jps-builder"))
    implementation(files("lib/OtpErlang.jar"))
    implementation(libCommonsIo)

    @Suppress("AvoidDuplicateDependencies")
    testImplementation(libMockitoCore)
    @Suppress("AvoidDuplicateDependencies")
    mockitoAgent(libMockitoCore) { isTransitive = false }

    // Explicit here (not pulled in via ide-starter) because the IntelliJ Platform Gradle Plugin
    // strips anything the target IDE already bundles from ide-starter's own dependencies, but
    // testUI's tests run in the host Gradle JVM, not the sandboxed IDE - so nothing else supplies them.
    testUIImplementation(libs.kodein.di.jvm)
    testUIImplementation(libs.kotlinx.coroutines.core.jvm)

    // JUnit 5 is required for UI tests. Both dependencies are declared without versions so they
    // resolve to whatever the IntelliJ test-framework-junit5/ide-starter artifacts request (see
    // the consistent-resolution wiring below), keeping the Jupiter API/engine matched to what
    // JetBrains compiled the Starter framework against.
    testUIImplementation(libs.junit.jupiter)
    testUIRuntimeOnly("org.junit.platform:junit-platform-launcher")

}

// The IntelliJ test-framework/starter artifacts carry their JUnit dependencies in runtime scope
// only, so the versionless junit-jupiter declaration above has no version opinion to inherit on
// the compile classpath. Consistent resolution makes the compile classpath adopt the versions
// resolved on the runtime classpath, where the JetBrains-requested JUnit version is visible.
configurations.named("testUICompileClasspath") {
    @Suppress("UnstableApiUsage")
    shouldResolveConsistentlyWith(configurations["testUIRuntimeClasspath"])
}

// --- Run IDE Configuration ---
tasks.withType<RunIdeTask>().configureEach {
    jvmArguments.addAll(
        "-Didea.debug.mode=true",
        "-Didea.is.internal=true",
        "-Dlog4j2.debug=true",
        "-Dlogger.org=TRACE",
        "-XX:+AllowEnhancedClassRedefinition",
        "-Didea.ProcessCanceledException=disabled"
    )

    systemProperty("idea.log.debug.categories", "org.elixir_lang")
    maxHeapSize = "7g"

    val compatiblePluginsList = providers.gradleProperty("runIdeCompatiblePlugins")
        .getOrElse("")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    // runIde uses "required plugins" mode; for IU builds that can drop com.intellij.modules.ultimate,
    // which cascades into bundled plugin dependency warnings. Force Ultimate to stay required,
    // and include any compatible plugins so they can load under required-plugins mode.
    val requiredPluginsId = listOf(
        providers.gradleProperty("pluginGroup").get(),
        "com.intellij.modules.ultimate"
    ).plus(compatiblePluginsList)
        .distinct()
        .joinToString(",")
    val platformTypeValue = providers.gradleProperty("platformType").get()
    val isRunIdeTask = name == "runIde"
    val isUltimateTask = name.contains("IntellijIdeaUltimate")
    if ((isRunIdeTask && platformTypeValue == "IU") || isUltimateTask) {
        // Ensure required-plugins mode keeps Ultimate enabled for IU runs.
        jvmArguments.add("-Didea.required.plugins.id=$requiredPluginsId")
    }

    if (project.hasProperty("runIdeWorkingDirectory") && project.property("runIdeWorkingDirectory").toString().isNotEmpty()) {
        workingDir = file(project.property("runIdeWorkingDirectory").toString())
    }

    // Development SDK paths - allows devs to autoconfigure SDKs when running the plugin
    // Usage: ./gradlew runIde -PrunIdeSdkErlangPath='/path/to/erlang' -PrunIdeSdkElixirPath='/path/to/elixir'
    if (project.hasProperty("runIdeSdkErlangPath")) {
        systemProperty("runIdeSdkErlangPath", project.property("runIdeSdkErlangPath").toString())
    }
    if (project.hasProperty("runIdeSdkElixirPath")) {
        systemProperty("runIdeSdkElixirPath", project.property("runIdeSdkElixirPath").toString())
    }

    // Dynamic plugin loading
    // Usage: -PrunIdeCompatiblePlugins="PsiViewer,com.google.ide-perf,org.jetbrains.action-tracker"
    if (compatiblePluginsList.isNotEmpty()) {
        dependencies {
            intellijPlatform { compatiblePlugins(compatiblePluginsList) }
        }
    }
}

// In gradle.properties, define platform versions like:
//
// platformVersionIntellijIdeaEAP=261-EAP-SNAPSHOT
// platformVersionIntellijIdea=2026.1.2
//
// excluding the base "platformVersion" and EAP variants (handled separately below).
//
// You can also then run with either gradlew (CLI) or using a Run Configuration in IntelliJ IDEA via:
// `gradlew runRubyMine -PplatformVersionRubyMine=2026.1.4` to override a specific platform version at runtime,
// which is useful for testing against multiple IDE versions without changing the build script.
val platformVersionPrefix = "platformVersion"
val runIdePlatformsList: List<String> = providers.gradlePropertiesPrefixedBy(platformVersionPrefix).get().keys
    .map { it.removePrefix(platformVersionPrefix) }
    .filter { it.isNotEmpty() && !it.endsWith("EAP") }
    .sorted()

// Reduces having to download the IDEs when testing.
val enableEAP = providers.gradleProperty("enableEAPIDEs").get().toBoolean()

runIdePlatformsList.forEach { platform ->
    intellijPlatformTesting.runIde.register("run${platform}", Action {
        type = IntelliJPlatformType.valueOf(platform)
        version = providers.gradleProperty("platformVersion${platform}").get()
        prepareSandboxTask {
            sandboxDirectory = layout.buildDirectory.dir("${platform.lowercase()}-sandbox")
        }
    })

    if (enableEAP && providers.gradleProperty("platformVersion${platform}EAP").isPresent) {
        // If EAP is enabled in gradle.properties, and useDynamicEapVersion is enabled,
        // use the major version with "-EAP-SNAPSHOT" suffix.
        // Otherwise, use the specified EAP version.
        val platformVersionEAPValue = providers.gradleProperty("platformVersion${platform}EAP").get()
        val idePlatformTypeCode = IntelliJPlatformType.valueOf(platform)
        val ideEapVersion: String = if (useDynamicEapVersion) {
            val foundIdeVersion = VersionFetcher.getLatestEapBuild(platformType = idePlatformTypeCode.code)
            // Get the Major version, by splitting at the first dot, then appending "-EAP-SNAPSHOT"
            val majorVersion = foundIdeVersion.split(".").firstOrNull() ?: foundIdeVersion
            "$majorVersion-EAP-SNAPSHOT"
        } else {
            platformVersionEAPValue
        }
        intellijPlatformTesting.runIde.register("run${platform}EAP", Action {
            type = idePlatformTypeCode
            version = ideEapVersion
            useInstaller = false
            prepareSandboxTask {
                sandboxDirectory = layout.buildDirectory.dir("${platform.lowercase()}_eap-sandbox")
            }
        })
    }
}

// --- External Tools (Elixir & Quoter) ---
// Elixir/Erlang for the quoter build come from the mise/PATH/env-aware `resolveElixirErlangSdks`
// (shared with testUI) - no from-source Elixir build. Its output (elixir.sdk.path / erlang.sdk.path)
// is read by the quoter tasks and the test tasks.

// Must stay in quoterCachePaths: overwrite(false) skips the download only when this zip exists.
val quoterZip: RegularFile = cachePath.file("quoter-$quoterRefSlug.zip")

val getQuoter = tasks.register<Download>("getQuoter") {
    description = "Downloads the Quoter tool"
    src("https://github.com/$quoterRepo/archive/$quoterRef.zip")
    dest(quoterZip)
    overwrite(false)
    retries(3)
}


val unzipQuoter = tasks.register<Copy>("unzipQuoter") {
    description = "Unzips the Quoter tool"
    dependsOn(getQuoter)

    // 1. Target the final destination directly
    into(quoterUnzippedPath)

    from(zipTree(getQuoter.get().dest)) {
        // 2. Strip the archive's single top-level directory (name varies by tag/branch) on the fly
        eachFile {
            relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
        }

        // 3. Prevent creating the empty top-level directory itself
        includeEmptyDirs = false
    }
}

val getQuoterDeps = tasks.register<GetQuoterDepsTask>("getQuoterDeps") {
    description = "Prepares the Quoter dependencies"
    dependsOn(unzipQuoter, resolveElixirErlangSdks)

    // Configure the task
    quoterDir.set(quoterUnzippedPath)
    depsDir.set(quoterUnzippedPath.dir("deps"))
    sdkProperties.set(sdkPropertiesFile)
    mixHome.set(mixHomeForPair)
    mixArchives.set(mixArchivesForPair)
    mixEnv.set(quoterMixEnv)

    // INPUTS: mix.exs and mix.lock define requirements
    inputs.file(quoterUnzippedPath.file("mix.exs"))
        .withPropertyName("mixExs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(quoterUnzippedPath.file("mix.lock"))
        .withPropertyName("mixLock")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val releaseQuoter = tasks.register<ReleaseQuoterTask>("releaseQuoter") {
    description = "Builds the Quoter tool"
    dependsOn(getQuoterDeps)

    // Configure the task
    quoterDir.set(quoterUnzippedPath)
    buildDir.set(quoterUnzippedPath.dir("_build"))
    availabilityFile.set(quoterAvailabilityFile)
    required.set(quoterRequired)
    sdkProperties.set(sdkPropertiesFile)
    mixHome.set(mixHomeForPair)
    mixArchives.set(mixArchivesForPair)
    mixEnv.set(quoterMixEnv)

    // INPUTS: mix.exs, lockfile, source code, and dependencies
    inputs.files(
        quoterUnzippedPath.file("mix.exs"),
        quoterUnzippedPath.file("mix.lock")
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(quoterUnzippedPath.dir("lib")).withPathSensitivity(PathSensitivity.RELATIVE)
    // A file tree rather than inputs.dir, which fails validation when the quoter has no config/.
    inputs.files(quoterUnzippedPath.dir("config").asFileTree).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(quoterUnzippedPath.dir("deps")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// Written by resolveElixirErlangSdks below. Declared here because a build service's `parameters {}`
// runs at registration rather than deferred like a task lambda, so a val declared further down is
// still null at that point.
val sdkPropertiesFile = layout.buildDirectory.file("elixir-erlang-sdks.properties")

// Register the QuoterService - Gradle calls close() at build end regardless of failure.
// The daemon is a self-contained mix release (bundled ERTS), so it needs no Elixir/Erlang SDK to run.
// sdkProperties is only for epmd's sake: started from the SDK, the machine-wide daemon stays out of
// cache/, where a leftover pins the checkout against deletion. See quoter.Epmd.
// See: https://docs.gradle.org/current/userguide/build_services.html
val quoterService = gradle.sharedServices.registerIfAbsent("quoter", QuoterService::class) {
    parameters {
        executable.set(quoterExe)
        tmpDir.set(quoterTmpPath)
        nodeName.set(quoterNodeName)
        sdkProperties.set(sdkPropertiesFile)
    }
}

// Consumed by CI, which must name the directory this build writes rather than re-derive it - see the
// task's own documentation. Repo-relative and forward-slashed: the same value feeds the Windows legs,
// and actions/cache exclusion patterns are forward-slashed regardless of runner.
tasks.register<CachePathsTask>("quoterCachePaths") {
    description = "Reports the actions/cache path patterns for the quoter build tree and everything that feeds it"
    outputName.set("paths")
    val projectPath = layout.projectDirectory.asFile.toPath()
    patterns.set(
        listOf(
            quoterUnzippedPath.asFile,
            quoterZip.asFile,
            // Without these getQuoterDeps reinstalls hex and rebar from builds.hex.pm on every leg.
            mixHomeForPair,
            mixArchivesForPair,
        ).map { projectPath.relativize(it.toPath()).joinToString("/") } +
            // Sockets, not build output - actions/cache cannot archive them.
            "!cache/**/tmp/pipe/**"
    )
}

// --- Elixir parsing corpus ---
// ElixirLangElixirParsingTestCase parses and quotes every .ex and .exs file of the corpus
// .github/ci-versions.json declares for this Elixir. Read through `providers` so the configuration cache
// is invalidated when the declaration changes.
val elixirParsingCorpus: List<CorpusEntry> = corpusFor(
    providers.fileContents(layout.projectDirectory.file(".github/ci-versions.json")).asText.get(),
    elixirVersion
)
val elixirParsingCorpusArchives: Directory = cachePath.dir("corpus/archives")
val elixirParsingCorpusRoot: Directory = cachePath.dir("corpus/$elixirVersion")

// One task per entry: with a single source, Download treats a destination directory that does not exist yet
// as the file to write, and refuses eachFile renames.
val downloadElixirParsingCorpus = elixirParsingCorpus.map { entry ->
    tasks.register<Download>("downloadElixirParsingCorpus-${entry.owner}-${entry.name}-${entry.sha.take(12)}") {
        description = "Downloads ${entry.git} at ${entry.sha} for the parser tests"
        src(entry.archiveUrl)
        dest(elixirParsingCorpusArchives.file(entry.archiveFileName))
        overwrite(false)
    }
}

val elixirParsingCorpusTask = tasks.register<Sync>("elixirParsingCorpus") {
    description = "Extracts the .ex and .exs files of the parser tests' corpus"
    into(elixirParsingCorpusRoot)
    includeEmptyDirs = false

    elixirParsingCorpus.zip(downloadElixirParsingCorpus).forEach { (entry, download) ->
        dependsOn(download)
        val directory = entry.directory.split('/')
        from(tarTree(resources.gzip(elixirParsingCorpusArchives.file(entry.archiveFileName)))) {
            include("**/*.ex", "**/*.exs")
            // The archive's top directory is named after the commit; the entry's directory replaces it.
            eachFile {
                relativePath = RelativePath(true, *(directory + relativePath.segments.drop(1)).toTypedArray())
            }
        }
    }
}

// Consumed by CI like quoterCachePaths. Empty when no corpus is declared for this Elixir.
tasks.register<CachePathsTask>("elixirParsingCorpusCachePaths") {
    description = "Reports the actions/cache path patterns and key for the parser tests' corpus archives"
    outputName.set("paths")
    patterns.set(elixirParsingCorpus.map {
        layout.projectDirectory.asFile.toPath()
            .relativize(elixirParsingCorpusArchives.file(it.archiveFileName).asFile.toPath())
            .joinToString("/")
    })
    key.set(elixirParsingCorpus.joinToString("-") { "${it.owner}-${it.name}-${it.sha}" })
}

val startQuoter = tasks.register<StartQuoterTask>("startQuoter") {
    description = "Starts the Quoter tool"
    dependsOn(releaseQuoter)

    availabilityFile.set(quoterAvailabilityFile)
    startedFile.set(quoterStartedFile)
    required.set(quoterRequired)
}

registerResolveExternalDependenciesTasksForAllProjects()

// --- Test Configuration ---

// ALL test tasks in ALL projects use the QuoterService (ensures cleanup on any failure)
allprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = javaVersionStr.toInt()
    }

    tasks.withType<Test>().configureEach {
        // Validate Erlang is available before running tests.
        doFirst(ErlangAvailabilityCheckAction())
    }
}

// The whole JUnit suite. The parser tests (org.elixir_lang.parser_definition) quote source through
// the external Elixir quoter daemon and compare it against the plugin's own quoting, so this task
// owns the daemon's lifecycle - hence startQuoter and usesService below.
tasks.named<Test>("test") {
    // ELIXIR_LANG_ELIXIR_PATH/EBIN/VERSION come from the resolved SDK (mise/PATH/env): the BEAM
    // decompiler tests read the Elixir stdlib ebin, and the parser tests read stdlib sources.
    // Resolved paths are only known at execution, so set env in a doFirst reading the resolver
    // output.
    dependsOn("prepareTestSandbox", resolveElixirErlangSdks, startQuoter)
    usesService(quoterService)

    val sdkProps = sdkPropertiesFile
    val quoterAvailability = quoterAvailabilityFile.asFile
    val quoterStarted = quoterStartedFile.asFile
    val corpusRoot = elixirParsingCorpusRoot.asFile.takeIf { elixirParsingCorpus.isNotEmpty() }
    doFirst {
        // `startQuoter` writes the start marker every run and copies the build marker's reason when it
        // skips, so where it exists it is the later and more complete answer. Falling back to the build
        // marker keeps `-x startQuoter` working.
        val effective = if (quoterStarted.isFile) quoterStarted else quoterAvailability
        environment(elixirTestEnvironment(sdkProps.get().asFile, effective))
        corpusRoot?.let { environment("ELIXIR_PARSING_CORPUS", it.absolutePath) }
    }

    inputs.files(elixirParsingCorpusTask)
        .withPropertyName("elixirParsingCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // QUOTER_AVAILABLE reaches the test JVM through that doFirst, so - as with the versions below -
    // Gradle cannot see it. Undeclared, a run that gains or loses a daemon stays UP-TO-DATE and
    // reports the other run's results. Optional: `-x releaseQuoter` leaves no marker.
    inputs.file(quoterAvailability)
        .withPropertyName("quoterAvailability")
        .withPathSensitivity(PathSensitivity.NONE)
        .optional(true)

    inputs.file(quoterStarted)
        .withPropertyName("quoterStarted")
        .withPathSensitivity(PathSensitivity.NONE)
        .optional(true)

    // The Elixir/OTP versions reach the test JVM as environment variables set in that doFirst, which
    // Gradle cannot see, so they have to be declared or a version switch does not invalidate this task.
    // Without them `mise use erlang@X elixir@Y && gradlew check` reports the *previous* pair's results:
    // the task is UP-TO-DATE, and because the cache key is equally blind the build cache restores those
    // results even after a cleanTest. Measured before this was added - a 1.18.4/OTP-28 run "passed" in
    // 28 s with 1.13.4/OTP-25's results.
    inputs.property("elixirVersion", expectedElixirVersion)
    inputs.property("otpVersion", expectedOtpVersion)

    // The test JVM has to dial the same node the daemon registered, and register its own node under a
    // name no other checkout is using either - two test JVMs collide exactly as two daemons do.
    // Declared as inputs so a rename invalidates the task rather than reporting the old run's results.
    systemProperty("elixir.quoter.remoteNode", quoterNodeName)
    systemProperty("elixir.quoter.localNode", quoterClientNodeName)
    inputs.property("quoterNodeName", quoterNodeName)
    inputs.property("quoterClientNodeName", quoterClientNodeName)

    // The variable oracle corpus narrows itself to the fixtures matching this regex; a different subset is a
    // different run, or the task reports the previous subset's results as this one's.
    inputs.property("elixirOracleCases", System.getenv("ELIXIR_ORACLE_CASES") ?: "")

    // The parsing tests are JUnit 3 (com.intellij.testFramework.ParsingTestCase -> TestCase),
    // discovered by the JUnit 4 runner.
    useJUnit()

    // Add Mockito as javaagent to avoid dynamic loading warnings (root project only)
    jvmArgs("-javaagent:${mockitoAgent.asPath}")

    // On failure the platform's TestLoggerFactory dumps its whole buffered log - the failing test's
    // exception and full stack included - to stderr, which Gradle then stores in the JUnit XML's
    // <system-err> next to the same stack already in <failure>. That doubled every failure and, on a
    // suite that fails widely, produced a result file too large for the report publisher to parse.
    // idea.split.test.logs writes the buffer to a per-test file under the sandbox log dir
    // (log_*/splitTestLogs/) and leaves a "Log saved to:" pointer on stderr, so the stack is stored
    // once, nothing is discarded, and other stderr still reaches the XML. The files are picked up by
    // the test-reports artifact in .github/workflows/shared-test.yml. (idea.log.path cannot redirect
    // them here - the IntelliJ Platform Gradle Plugin sets the sandbox log path itself and wins.)
    systemProperty("idea.split.test.logs", "true")
}

// Kotlin makes this jar a friend path of compileTestKotlin, and the file name is part of that task's
// build-cache key. With the per-commit version in it, test compilation never came from the cache on
// CI. The distribution is named by composedJar, which sets its own version.
tasks.jar {
    archiveVersion.set("")
}

tasks.named<Zip>("buildPlugin") {
    doLast {
        println("Note: Timestamps in version strings and filenames of build artifacts do not change on every build due to gradle config caching.")
        println("Built artifact path: ${archiveFile.get().asFile.absolutePath}")
    }
}


val resolveElixirErlangSdks = tasks.register<ResolveElixirErlangSdksTask>("resolveElixirErlangSdks") {
    description = "Resolves Erlang and Elixir SDKs for testing"
    group = "verification"
    projectDir.set(layout.projectDirectory)
    elixirVersion.set(expectedElixirVersion)
    otpVersion.set(expectedOtpVersion)
    versionSource.set(expectedVersionSource)
    outputFile.set(sdkPropertiesFile)
}

tasks.register<Test>("testUI") {
    dependsOn(tasks.buildPlugin, tasks.prepareSandbox, unzipQuoter, resolveElixirErlangSdks)
    description = "Runs only the UI tests that start the IDE"
    group = "verification"

    testClassesDirs = sourceSets["testUI"].output.classesDirs
    classpath = sourceSets["testUI"].runtimeClasspath

    useJUnitPlatform()

    // UI tests should run sequentially (not in parallel) to avoid conflicts
    maxParallelForks = 1

    // Increase memory for UI tests
    minHeapSize = "1g"
    maxHeapSize = "4g"

    systemProperty("path.to.build.plugin", tasks.buildPlugin.get().archiveFile.get().asFile.absolutePath)
    systemProperty("idea.home.path", tasks.prepareTestSandbox.get().getDestinationDir().parentFile.absolutePath)
    systemProperty("uiPlatformBuildVersion", actualPlatformVersion)
    systemProperty("projectPath", unzipQuoter.get().destinationDir.absolutePath)
    // Keep Allure outputs under build/ instead of the repo root.
    systemProperty("allure.results.directory", layout.buildDirectory.dir("results/allure").get().asFile.absolutePath)

    // Disable IntelliJ test listener that conflicts with standard JUnit
    systemProperty("idea.test.cyclic.buffer.size", "0")

    jvmArgumentProviders += ElixirErlangSdkArgumentProvider(sdkPropertiesFile.get().asFile)

    // Add required JVM arguments
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing=ALL-UNNAMED"
    )

}

// Uncomment to allow using build-scan.
//if (hasProperty("buildScan")) {
//    extensions.findByName("buildScan")?.withGroovyBuilder {
//        setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
//        setProperty("termsOfServiceAgree", "yes")
//    }
//}
