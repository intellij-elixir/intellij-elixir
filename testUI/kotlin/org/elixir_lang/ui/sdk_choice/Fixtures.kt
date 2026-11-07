package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
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
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.addElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.sdk_settings.Home
import org.elixir_lang.ui.sdk_settings.Homes
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.addElixirOnPage
import org.elixir_lang.ui.sdk_settings.addErlangOnPage
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.dialogShown
import org.elixir_lang.ui.sdk_settings.openSettings
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A copy of the Elixir install where mise keeps installs under the private home, named by its version; its home. A copy,
 * never a link: the guard empties the private home following links.
 */
@OptIn(ExperimentalPathApi::class)
internal fun stageElixirInstall(): String {
    val elixir = Path.of(IdeTestContext.elixirSdkPath).normalize()
    val app = elixir.resolve("lib/elixir/ebin/elixir.app").readText()
    val version = Regex("""\{vsn,"([^"]+)"}""").find(app)!!.groupValues[1]
    val installs = NoScreen.privateHome.resolve(".local/share/mise/installs/elixir").createDirectories()
    return elixir.copyToRecursively(installs.resolve(version), followLinks = true, overwrite = false).toString()
}

/**
 * A project under the private home with one module, [name], whose `.iml` is [iml], and [files], by path from the
 * project's folder; its folder. It has its own `.idea`, so opening it keeps what the module file says.
 */
internal fun stageProject(name: String, iml: String, files: Map<String, String> = emptyMap()): Path {
    val modules = $$"""
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="4">
          <component name="ProjectModuleManager">
            <modules>
              <module fileurl="file://$PROJECT_DIR$/$${name}.iml" filepath="$PROJECT_DIR$/$${name}.iml" />
            </modules>
          </component>
        </project>
    """.trimIndent()
    return stageSources(name, files + mapOf(".idea/modules.xml" to modules, "$name.iml" to iml))
}

/** A folder under the private home's projects holding [files], by path from it; the folder. */
internal fun stageSources(name: String, files: Map<String, String>): Path {
    val folder = NoScreen.privateHome.resolve("projects/$name")
    files.forEach { (path, text) -> folder.resolve(path).apply { parent.createDirectories() }.writeText(text + "\n") }
    return folder
}

/** A module of [type] inheriting a project SDK there is none of, with [facet] as its Elixir facet's configuration when given. */
internal fun moduleIml(type: String, facet: String? = null): String {
    val facets = facet?.let { """<component name="FacetManager"><facet type="Elixir" name="Elixir">$it</facet></component>""" }
    return $$"""
        <?xml version="1.0" encoding="UTF-8"?>
        <module type="$$type" version="4">
          $${facets.orEmpty()}
          <component name="NewModuleRootManager">
            <content url="file://$MODULE_DIR$">
              <sourceFolder url="file://$MODULE_DIR$/lib" isTestSource="false" />
            </content>
            <orderEntry type="inheritedJdk" />
            <orderEntry type="sourceFolder" forTests="false" />
          </component>
        </module>
    """.trimIndent()
}

/** An Erlang SDK and an Elixir SDK paired with it, added from the welcome screen; their names. */
internal fun Driver.addSdksOnWelcomeScreen(erlangHome: String, elixirHome: String): Pair<String, String> {
    ensureWelcomeScreenInForeground()
    closeInternalErrors("setup")
    lateinit var sdks: Pair<String, String>
    welcomeScreen {
        val erlang = addErlangSdkFromDisk(erlangHome)
        sdks = erlang to addElixirSdkFromDisk(elixirHome, erlang).name
    }
    return sdks
}

/** An Erlang SDK and an Elixir SDK paired with it, added on a small IDE's SDK pages; their names. */
internal fun Driver.addSdksOnPage(erlangHome: String, elixirHome: String): Pair<String, String> {
    closeInternalErrors("setup")
    val erlang = addErlangOnPage(erlangHome)
    return erlang to addElixirOnPage(elixirHome, erlang).name
}

/** The editor banner's [text] label, clicked once the banner offers it. */
internal fun Driver.clickBannerLabel(text: String) {
    val label = ui.x("//div[@visible_text='$text' or @accessiblename='$text']")
    waitFor("the banner's '$text'", 2.minutes) { label.present() }
    label.click()
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

private fun List<String>.entryOf(elixir: String): String? = firstOrNull { it.startsWith("$elixir ") }

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

/** `ls --current --json`'s entry for [tool] pinned at [version] in [project]'s `.tool-versions` and installed at [installPath]. */
internal fun misePin(tool: String, version: Any, installPath: Any, project: Path): String =
    """"$tool":[{"version":"$version","requested_version":"$version","install_path":"$installPath",""" +
        """"source":{"type":".tool-versions","path":"${toolVersions(project)}"},"installed":true,"active":true}]"""

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
    return checkBoxWithName("Keep the SDKs in step with mise")
}

/** Whether Settings > Languages & Frameworks > Elixir > Tool Managers has mise ticked; Settings is cancelled after. */
internal fun Driver.toolManagersShowMiseOn(): Boolean {
    openSettings()
    var on = false
    ideFrame {
        settingsDialog {
            openTreeSettingsSection("Languages & Frameworks", "Elixir", "Tool Managers")
            on = appears("mise ticked", 10.seconds) { checkBoxWithName("mise").isSelected() }
            clickButtonText("Cancel")
        }
    }
    return on
}

/**
 * `mix new` of [name] in the private home's projects, run with [homes]' A installs, and with [umbrella], `mix new` of its
 * apps `alpha` and `beta`; its folder.
 */
internal fun mixNew(homes: Homes, name: String, umbrella: Boolean = false): Path {
    val root = NoScreen.privateHome.resolve("projects").createDirectories()
    val mix = Path.of(homes.elixir.a, "bin", "mix").toString()
    homes.run(root, mix, "new", name, *if (umbrella) arrayOf("--umbrella") else emptyArray())
    val project = root.resolve(name)
    if (umbrella) {
        for (app in listOf("alpha", "beta")) homes.run(project.resolve("apps"), mix, "new", app)
    }
    return project
}

/** [command] run in [dir] with the A installs first on the PATH and the private home as its home, which must succeed. */
private fun Homes.run(dir: Path, vararg command: String) {
    val home = NoScreen.privateHome
    val path = listOf(Path.of(elixir.a, "bin"), Path.of(erlang.a, "bin"), "/usr/bin", "/bin").joinToString(File.pathSeparator)
    // Into a file, not a pipe, so a stalled command can't block a read and the timeout always runs.
    val output = createTempFile("command", ".log")
    val process = ProcessBuilder(*command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).apply {
        environment().putAll(mapOf("HOME" to "$home", "MIX_HOME" to "${home.resolve(".mix")}", "PATH" to path))
    }.start()
    try {
        check(process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) { "${command.joinToString(" ")} failed: ${output.readText()}" }
    } finally {
        process.destroyForcibly()
        output.deleteIfExists()
    }
}

/**
 * `mise` first on [stubbedPath], answering as mise 2026.10.22 does: [lsCurrent], a shell command, answers
 * `ls --current --json`, `config ls --json` names [project]'s `.tool-versions` with [tools], and `doctor --json` gives
 * its `dirs`. Anything else fails, as the plugin would see a broken mise.
 */
internal fun stubMise(project: Path, lsCurrent: String, tools: List<String>) {
    val home = NoScreen.privateHome
    val config = """[{"path":"${toolVersions(project)}","tools":[${tools.joinToString(",") { "\"$it\"" }}],"override":false}]"""
    val doctor = """{"dirs":{"state":"$home/.local/state/mise","data":"$home/.local/share/mise"}}"""
    val script = $$"""
        #!/bin/sh
        case "$1 $2" in
          "ls --current") $$lsCurrent ;;
          "config ls") echo '$$config' ;;
          "doctor --json") echo '$$doctor' ;;
          *) echo "mise stub: $* is not answered" >&2; exit 1 ;;
        esac
    """.trimIndent()
    miseStubs.createDirectories().resolve("mise").apply { writeText(script + "\n") }.toFile().setExecutable(true)
}
