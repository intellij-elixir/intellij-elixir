package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.JCheckBoxUi
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.checkBoxWithName
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import org.elixir_lang.jps.shared.ElixirSdkTypeId.ELIXIR_SDK_TYPE_ID
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.addElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.isSdk
import org.elixir_lang.ui.sdk_settings.Home
import org.elixir_lang.ui.sdk_settings.Homes
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.addElixirOnPage
import org.elixir_lang.ui.sdk_settings.addErlangOnPage
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.dialogShown
import org.elixir_lang.ui.sdk_settings.elixirVersion
import org.elixir_lang.ui.sdk_settings.openInThisWindow
import org.elixir_lang.ui.sdk_settings.openSettings
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A copy of the Elixir install where mise keeps installs under the private home, named by its version; its home. A copy,
 * never a link: the guard empties the private home following links.
 */
internal fun stageElixirInstall(): String {
    val elixir = Path.of(IdeTestContext.elixirSdkPath).normalize()
    val version = elixirVersion(elixir)
    val installs = NoScreen.privateHome.resolve(".local/share/mise/installs/elixir").createDirectories()
    return Homes.copy(elixir, installs.resolve(version)).toString()
}

/**
 * A project under the private home with one module, [name], whose `.iml` is [iml], and [files], by path from the
 * project's folder; its folder. It has its own `.idea`, so opening it keeps what the module file says.
 */
internal fun stageProject(name: String, iml: String, files: Map<String, String> = emptyMap()): Path =
    stageSources(name, files + mapOf(".idea/modules.xml" to modulesXml(listOf("$name.iml")), "$name.iml" to iml))

/** A project's `.idea/modules.xml`, listing the modules whose `.iml` files are at [imlPaths] from the project's folder. */
private fun modulesXml(imlPaths: List<String>): String {
    val entries = imlPaths.map { $$"""      <module fileurl="file://$PROJECT_DIR$/$$it" filepath="$PROJECT_DIR$/$$it" />""" }
    // Lines joined, not a trimmed template: a multi-line insert would defeat `trimIndent`.
    val head = listOf("""<?xml version="1.0" encoding="UTF-8"?>""", """<project version="4">""", """  <component name="ProjectModuleManager">""", "    <modules>")
    return (head + entries + listOf("    </modules>", "  </component>", "</project>")).joinToString("\n")
}

/** A project's `.idea/misc.xml`, whose project SDK is the SDK [sdk] of type [type]. */
internal fun projectSdkXml(sdk: String, type: String): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project version="4">
      <component name="ProjectRootManager" version="2" project-jdk-name="$sdk" project-jdk-type="$type" />
    </project>
""".trimIndent()

/** A `mix.exs` of one app, [app]. */
internal fun mixExs(app: String): String = """
    defmodule ${app.replaceFirstChar(Char::uppercase)}.MixProject do
      use Mix.Project

      def project do
        [app: :$app, version: "0.1.0"]
      end
    end
""".trimIndent()

/** The app [app]'s `mix.exs`, and an empty module named for it as its `lib/<app>.ex`, by path from the project's folder. */
internal fun mixAppFiles(app: String, folder: String = ""): Map<String, String> = mapOf(
    "${folder}mix.exs" to mixExs(app),
    "${folder}lib/$app.ex" to "defmodule ${app.replaceFirstChar(Char::uppercase)} do\nend",
)

/** A folder under the private home's projects holding [files], by path from it; the folder. */
internal fun stageSources(name: String, files: Map<String, String>): Path {
    val folder = NoScreen.privateHome.resolve("projects/$name")
    files.forEach { (path, text) -> folder.resolve(path).apply { parent.createDirectories() }.writeText(text + "\n") }
    return folder
}

/** A module's SDK entry in its `.iml`: [Inherit] the project SDK, [None], or its [Own] SDK named [Own.name]. */
internal sealed interface ModuleSdkEntry {
    val xml: String

    data object Inherit : ModuleSdkEntry {
        override val xml = """<orderEntry type="inheritedJdk" />"""
    }

    data object None : ModuleSdkEntry {
        override val xml = ""
    }

    data class Own(val name: String) : ModuleSdkEntry {
        override val xml = """<orderEntry type="jdk" jdkName="$name" jdkType="$ELIXIR_SDK_TYPE_ID" />"""
    }
}

/**
 * A module of [type] whose SDK entry is [sdk], by default inheriting a project SDK there is none of, with [facet] as its
 * Elixir facet's configuration when given.
 */
internal fun moduleIml(type: String, facet: String? = null, sdk: ModuleSdkEntry = ModuleSdkEntry.Inherit): String {
    val facets = facet?.let { """<component name="FacetManager"><facet type="Elixir" name="Elixir">$it</facet></component>""" }
    return $$"""
        <?xml version="1.0" encoding="UTF-8"?>
        <module type="$$type" version="4">
          $${facets.orEmpty()}
          <component name="NewModuleRootManager">
            <content url="file://$MODULE_DIR$">
              <sourceFolder url="file://$MODULE_DIR$/lib" isTestSource="false" />
            </content>
            $${sdk.xml}
            <orderEntry type="sourceFolder" forTests="false" />
          </component>
        </module>
    """.trimIndent()
}

/** A module of a staged project: its [name], its folder [dir] from the project's, and its `.iml`'s text [iml]. */
internal class StagedModule(val name: String, val dir: String, val iml: String = moduleIml("ELIXIR_MODULE")) {
    /** [dir] as the start of a path from the project's folder. */
    val folder: String get() = if (dir.isEmpty()) "" else "$dir/"

    val imlPath: String get() = "$folder$name.iml"
}

/**
 * The project [name] under the private home, with its own `.idea` listing [modules], each with a `mix.exs` and a source
 * file in its folder, and the project SDK [projectSdk], a name and an SDK type, or none; its folder.
 */
internal fun stageModules(name: String, modules: List<StagedModule>, projectSdk: Pair<String, String>? = null): Path {
    val files = buildMap {
        put(".idea/modules.xml", modulesXml(modules.map(StagedModule::imlPath)))
        projectSdk?.let { (sdk, type) -> put(".idea/misc.xml", projectSdkXml(sdk, type)) }
        for (module in modules) {
            put(module.imlPath, module.iml)
            putAll(mixAppFiles(module.name, module.folder))
        }
    }
    return stageSources(name, files)
}

/** An Erlang SDK and an Elixir SDK paired with it, added from the welcome screen; their names. */
internal fun Driver.addSdksOnWelcomeScreen(erlangHome: String, elixirHome: String): Pair<String, String> {
    ensureWelcomeScreenInForeground()
    closeInternalErrors("setup")
    lateinit var sdks: Pair<String, String>
    welcomeScreen {
        val erlang = addErlangSdkFromDisk(erlangHome)
        sdks = erlang to addElixirSdkFromDisk(elixirHome, erlang)
    }
    return sdks
}

/** An Erlang SDK and an Elixir SDK paired with it, added on a small IDE's SDK pages; their names. */
internal fun Driver.addSdksOnPage(erlangHome: String, elixirHome: String): Pair<String, String> {
    closeInternalErrors("setup")
    val erlang = addErlangOnPage(erlangHome)
    return erlang to addElixirOnPage(elixirHome, erlang)
}

/** The editor banner's label that asks the chooser for the Elixir SDK. */
internal const val CHOOSE_LABEL = "Choose Elixir SDK..."

/** The editor banner's [text] label. */
internal fun Driver.bannerLabel(text: String): UiComponent = ui.x("//div[@visible_text='$text' or @accessiblename='$text']")

/** The editor banner's [text] label, clicked once the banner offers it. */
internal fun Driver.clickBannerLabel(text: String) {
    val label = bannerLabel(text)
    waitFor("the banner's '$text'", 2.minutes) { label.present() }
    label.click()
}

/** The file at [sourcePath] opened and the banner's "Choose Elixir SDK..." clicked: the chooser shows. */
internal fun Driver.askTheChooser(rows: Rows, row: String, sourcePath: String) {
    openFile(sourcePath, waitForCodeAnalysis = false)
    clickBannerLabel(CHOOSE_LABEL)
    rows.check(row, "the banner asks the chooser", chooserShows(30.seconds))
}

/** The staged [project] opened from the welcome screen. */
internal fun Driver.open(project: Path) {
    ensureWelcomeScreenInForeground()
    closeInternalErrors("setup")
    openInThisWindow(project)
}

/** The chooser's [list] lists [elixir] paired with [erlang], and nothing is picked. */
internal fun Rows.listsPairedWithNothingPicked(row: String, list: JListUiComponent, elixir: String, erlang: String) {
    val entries = list.items
    check(row, "the chooser lists '$elixir' paired with '$erlang': $entries", entries.entryOf(elixir)?.contains("Erlang SDK '$erlang'") == true)
    check(row, "nothing is picked: ${list.selectedItems}", list.selectedItems.isEmpty())
}

/** Picks [elixir] in the chooser's list. */
internal fun JListUiComponent.pickEntry(elixir: String) {
    val entries = items
    clickItemAtIndex(entries.indexOf(checkNotNull(entries.entryOf(elixir)) { "no entry '$elixir' in the chooser: $entries" }))
}

private fun List<String>.entryOf(elixir: String): String? = firstOrNull(isSdk(elixir))

/** Cancels each dialog titled one of [titles] that is shown, in order. */
internal fun Driver.cancelDialogs(vararg titles: String) {
    for (title in titles) {
        if (dialogShown(title)) ui.x(xQuery { byTitle(title) }).clickButtonText("Cancel")
    }
}

/** The lines of the module file [iml], trimmed, that start with one of [tags]. */
internal fun imlLines(iml: Path, vararg tags: String): List<String> =
    iml.readText().lines().map(String::trim).filter { line -> tags.any(line::startsWith) }

/** The folder [stubMise] writes `mise` in. */
private val miseStubs: Path get() = NoScreen.privateHome.resolve("bin")

/** The PATH with [stubMise]'s `mise` first, for the IDE. */
internal fun stubbedPath(): String = "$miseStubs${File.pathSeparator}${System.getenv("PATH")}"

/**
 * `ls --current --json`'s entry for [tool] pinned at [version] in [project]'s `.tool-versions` and installed at
 * [installPath], or, unless [installed], only to be installed there.
 */
internal fun misePin(tool: String, version: Any, installPath: Any, project: Path, installed: Boolean = true): String =
    """"$tool":[{"version":"$version","requested_version":"$version","install_path":"$installPath",""" +
        """"source":{"type":".tool-versions","path":"${toolVersions(project)}"},"installed":$installed,"active":true}]"""

private fun toolVersions(project: Path): Path = project.resolve(".tool-versions")

/** The `.tool-versions` pinning the Elixir at [install] and the Erlang at [erlang]'s B. */
private fun miseToolVersions(install: String, erlang: Home): String = "elixir ${Path.of(install).fileName}\nerlang ${erlang.bVersion}"

/**
 * `ls --current --json` as mise answers it in [project] with the Elixir at [install] and the Erlang at [erlang]'s B
 * pinned and installed.
 */
private fun misePins(project: Path, install: String, erlang: Home): String {
    val elixir = misePin("elixir", Path.of(install).fileName, install, project)
    return "echo '{$elixir,${misePin("erlang", erlang.bVersion, erlang.b, project)}}'"
}

/** [project]'s `.tool-versions` pins the Elixir at [install] and the Erlang at [erlang]'s B, and [stubMise] answers so. */
internal fun pinWithMise(project: Path, install: String, erlang: Home) {
    toolVersions(project).writeText(miseToolVersions(install, erlang))
    stubMise(project, misePins(project, install, erlang), listOf("elixir", "erlang"))
}

/** The chooser here offers mise's Elixir at [install] and its [erlang], and the offer is taken; the box keeping the SDKs in step. */
internal fun UiComponent.takeMisesOffer(rows: Rows, row: String, install: String, erlang: Home): JCheckBoxUi {
    val text = "Use mise's Elixir ${Path.of(install).fileName} and Erlang ${erlang.bVersion}"
    val offer = x(xQuery { byAccessibleName(text) })
    rows.check(row, "the chooser offers '$text'", appears("the offer", 30.seconds) { offer.present() })
    offer.click()
    return inStepBox()
}

/** Whether Settings > Languages & Frameworks > Elixir > Tool Managers has mise ticked; Settings is cancelled after. */
internal fun Driver.toolManagersShowMiseOn(): Boolean = toolManagersShowMise().first

/**
 * Whether Settings > Languages & Frameworks > Elixir > Tool Managers has mise ticked, and its environment ticked;
 * Settings is cancelled after.
 */
internal fun Driver.toolManagersShowMise(): Pair<Boolean, Boolean> {
    openSettings()
    var shown = false to false
    ideFrame {
        settingsDialog {
            openTreeSettingsSection("Languages & Frameworks", "Elixir", "Tool Managers")
            val on = appears("mise ticked", 10.seconds) { checkBoxWithName("mise").isSelected() }
            shown = on to checkBoxWithName("Apply mise environment").isSelected()
            clickButtonText("Cancel")
        }
    }
    return shown
}

/**
 * `mix new` of [name] in the private home's projects, run with [homes]' A installs, and with [umbrella], `mix new` of its
 * apps `alpha` and `beta`; its folder.
 */
internal fun mixNew(homes: Homes, name: String, umbrella: Boolean = false): Path {
    val root = NoScreen.privateHome.resolve("projects").createDirectories()
    val mix = CliTool.MIX.getExecutableFilepath(homes.elixir.a)
    homes.run(root, mix, "new", name, *if (umbrella) arrayOf("--umbrella") else emptyArray())
    val project = root.resolve(name)
    if (umbrella) {
        for (app in listOf("alpha", "beta")) homes.run(project.resolve("apps"), mix, "new", app)
    }
    return project
}

private fun Homes.run(dir: Path, vararg command: String) = IdeTestContext.runInPrivateHome(elixir.a, erlang.a, dir, *command)

/** One folder's pins: Elixir [elixirVersion] at [elixirHome], and Erlang [erlangVersion] at [erlangHome], [erlangInstalled] or not. */
internal data class Pins(
    val elixirVersion: String,
    val elixirHome: String,
    val erlangVersion: String,
    val erlangHome: String,
    val erlangInstalled: Boolean = true,
)

/** The folders [stubMiseByFolder]'s `mise` has answered `ls --current` in since it was stubbed, one a line. */
private val miseAnswersLog: Path get() = NoScreen.privateHome.resolve("mise-answered")

/** Whether [stubMiseByFolder]'s `mise` has answered `ls --current` in [folder] since it was stubbed. */
internal fun miseAnswered(folder: Path): Boolean =
    miseAnswersLog.takeIf { it.exists() }?.readLines()?.contains(folder.toString()) == true

/**
 * `mise` first on [stubbedPath], answering by the folder it runs in: each of [pins]' folders its pins, `ls --current`
 * after [delaySeconds], and any other folder nothing. Each pinned folder gets its `.tool-versions`.
 */
internal fun stubMiseByFolder(pins: Map<Path, Pins>, delaySeconds: Int = 0) {
    miseAnswersLog.deleteIfExists()
    val cases = pins.entries.joinToString("\n") { (folder, pin) ->
        toolVersions(folder).writeText("elixir ${pin.elixirVersion}\nerlang ${pin.erlangVersion}\n")
        val ls = "{" + misePin("elixir", pin.elixirVersion, pin.elixirHome, folder) + "," +
            misePin("erlang", pin.erlangVersion, pin.erlangHome, folder, pin.erlangInstalled) + "}"
        """  "$folder") LS='$ls'; CONFIG='${configLs(folder, listOf("elixir", "erlang"))}' ;;"""
    }
    writeMiseStub(
        ls = $$"""sleep $$delaySeconds; echo "$LS"; echo "$PWD" >> '$$miseAnswersLog'""",
        config = "\"\$CONFIG\"",
        prelude = listOf("LS='{}'; CONFIG='[]'", $$"""case "$PWD" in""", cases, "esac"),
    )
}

/**
 * The `mise` first on [stubbedPath], running [prelude], then answering `ls --current --json` with [ls], a shell command,
 * `config ls --json` with [config], a shell word, and `doctor --json` with its `dirs`. Anything else fails, as the plugin
 * would see a broken mise.
 */
private fun writeMiseStub(ls: String, config: String, prelude: List<String> = emptyList()) {
    // Lines joined, not a trimmed template: a multi-line prelude would defeat `trimIndent`.
    val script = listOf("#!/bin/sh") + prelude + listOf(
        $$"""case "$1 $2" in""",
        """  "ls --current") $ls ;;""",
        """  "config ls") echo $config ;;""",
        """  "doctor --json") echo '${doctor()}' ;;""",
        """  *) echo "mise stub: $* is not answered" >&2; exit 1 ;;""",
        "esac",
    )
    miseStubs.createDirectories().resolve("mise").apply { writeText(script.joinToString("\n") + "\n") }.toFile().setExecutable(true)
}

/** `config ls --json`'s answer in [project]: its `.tool-versions`, configuring [tools]. */
private fun configLs(project: Path, tools: List<String>): String =
    """[{"path":"${toolVersions(project)}","tools":[${tools.joinToString(",") { "\"$it\"" }}],"override":false}]"""

/** `doctor --json`'s `dirs`, under the private home. */
private fun doctor(): String {
    val home = NoScreen.privateHome
    return """{"dirs":{"state":"$home/.local/state/mise","data":"$home/.local/share/mise"}}"""
}

/**
 * `mise` first on [stubbedPath], answering as mise 2026.10.22 does: [lsCurrent], a shell command, answers
 * `ls --current --json`, `config ls --json` names [project]'s `.tool-versions` with [tools], and `doctor --json` gives
 * its `dirs`. Anything else fails, as the plugin would see a broken mise.
 */
internal fun stubMise(project: Path, lsCurrent: String, tools: List<String>) =
    writeMiseStub(ls = lsCurrent, config = "'${configLs(project, tools)}'")
