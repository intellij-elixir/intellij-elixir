package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.addElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.addElixirOnPage
import org.elixir_lang.ui.sdk_settings.addErlangOnPage
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.dialogShown
import java.io.File
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

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
    val project = NoScreen.privateHome.resolve("projects/$name")
    val modules = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="4">
          <component name="ProjectModuleManager">
            <modules>
              <module fileurl="file://${'$'}PROJECT_DIR${'$'}/$name.iml" filepath="${'$'}PROJECT_DIR${'$'}/$name.iml" />
            </modules>
          </component>
        </project>
    """.trimIndent()
    (files + mapOf(".idea/modules.xml" to modules, "$name.iml" to iml)).forEach { (path, text) ->
        project.resolve(path).apply { parent.createDirectories() }.writeText(text + "\n")
    }
    return project
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

/**
 * `mise` first on [stubbedPath], answering as mise 2026.10.22 does: [lsCurrent], a shell command, answers
 * `ls --current --json`, `config ls --json` names [project]'s `.tool-versions` with [tools], and `doctor --json` gives
 * its `dirs`. Anything else fails, as the plugin would see a broken mise.
 */
internal fun stubMise(project: Path, lsCurrent: String, tools: List<String>) {
    val home = NoScreen.privateHome
    val config = """[{"path":"${toolVersions(project)}","tools":[${tools.joinToString(",") { "\"$it\"" }}],"override":false}]"""
    val doctor = """{"dirs":{"state":"$home/.local/state/mise","data":"$home/.local/share/mise"}}"""
    val script = """
        #!/bin/sh
        case "${'$'}1 ${'$'}2" in
          "ls --current") $lsCurrent ;;
          "config ls") echo '$config' ;;
          "doctor --json") echo '$doctor' ;;
          *) echo "mise stub: ${'$'}* is not answered" >&2; exit 1 ;;
        esac
    """.trimIndent()
    miseStubs.createDirectories().resolve("mise").apply { writeText(script + "\n") }.toFile().setExecutable(true)
}
