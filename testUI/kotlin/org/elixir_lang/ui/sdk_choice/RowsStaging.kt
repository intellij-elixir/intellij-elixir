package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import org.elixir_lang.jps.shared.ElixirSdkTypeId.ELIXIR_SDK_TYPE_ID
import org.elixir_lang.jps.shared.ErlangSdkTypeId.ERLANG_SDK_TYPE_ID
import org.elixir_lang.ui.framework.core.CHOOSER_TITLE
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.chooser
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.popupList
import org.elixir_lang.ui.sdk_settings.Homes
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.cancelSettings
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.elixirVersion
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.frameOf
import org.elixir_lang.ui.sdk_settings.jdkXml
import org.elixir_lang.ui.sdk_settings.openInThisWindow
import org.elixir_lang.ui.sdk_settings.otpVersionFile
import org.elixir_lang.ui.sdk_settings.setElixirVersion
import org.elixir_lang.ui.sdk_settings.writeJdkTable
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * The SDK table the rows' tests start from, written before launch: an Erlang SDK, two working Elixir SDKs paired with
 * it, [NEW] at the Elixir install the build resolves and [OLD] at a copy whose version is a minor before, and [BROKEN],
 * on [NEW]'s install, naming an Erlang SDK there is none of; with a JDK, [JDK]; and with [GONE], an Elixir SDK paired
 * with [ERLANG] whose own home is gone.
 */
internal object RowsTable {
    const val ERLANG = "Erlang Rows"
    const val NEW = "Elixir New"
    const val OLD = "Elixir Old"
    const val BROKEN = "Elixir Broken"
    const val GONE = "Elixir Gone"
    const val JDK = "JDK 21"
    const val JDK_TYPE = "JavaSDK"

    /** The Elixir install a row's Detected or from-disk pick makes an SDK on. */
    const val INSTALL = "1.20.4-rows"

    val erlangHome: String get() = Path.of(IdeTestContext.erlangSdkPath).normalize().toString()
    val newHome: String get() = Path.of(IdeTestContext.elixirSdkPath).normalize().toString()
    val oldHome: String get() = NoScreen.privateHome.resolve("sdk-homes/elixir-old").toString()

    /** [GONE]'s home, which is never made. */
    val goneHome: String get() = NoScreen.privateHome.resolve("sdk-homes/elixir-gone").toString()

    /** The OTP release of [erlangHome], as its `OTP_VERSION` says it. */
    val erlangVersion: String get() = otpVersionFile(Path.of(erlangHome)).readText().trim()

    /** The version of [NEW]'s install, as its `elixir.app` says it. */
    val newVersion: String get() = elixirVersion(Path.of(newHome))

    /** The table written in [configDir], with [JDK] when [jdk] and [GONE] when [gone]. */
    fun seed(configDir: Path, jdk: Boolean = false, gone: Boolean = false) {
        Homes.copy(Path.of(newHome), Path.of(oldHome).also { it.parent.createDirectories() })
        val (major, minor) = newVersion.split('.')
        val oldVersion = "$major.${minor.toInt() - 1}.0"
        setElixirVersion(Path.of(oldHome), oldVersion)
        val paired = """<additional erlang-sdk-home-path="${escapeXmlEntities(erlangHome)}" erlang-sdk-name="${escapeXmlEntities(ERLANG)}" />"""
        val jdks = listOfNotNull(
            jdkXml(ERLANG, ERLANG_SDK_TYPE_ID, erlangVersion, erlangHome, ebins(erlangHome)),
            jdkXml(NEW, ELIXIR_SDK_TYPE_ID, newVersion, newHome, ebins(newHome) + ebins(erlangHome), paired),
            jdkXml(OLD, ELIXIR_SDK_TYPE_ID, oldVersion, oldHome, ebins(oldHome) + ebins(erlangHome), paired),
            jdkXml(BROKEN, ELIXIR_SDK_TYPE_ID, newVersion, newHome, ebins(newHome), """<additional erlang-sdk-name="Erlang Gone" />"""),
            if (gone) jdkXml(GONE, ELIXIR_SDK_TYPE_ID, newVersion, goneHome, ebins(erlangHome), paired) else null,
            if (jdk) jdkXml(JDK, JDK_TYPE, "java version 21.0.8", jdkHome()) else null,
        )
        writeJdkTable(configDir, jdks)
    }

    /** A folder with a `release` file, which names no real JDK. */
    private fun jdkHome(): String {
        val home = NoScreen.privateHome.resolve("sdk-homes/jdk-21").createDirectories()
        home.resolve("release").writeText("JAVA_VERSION=\"21.0.8\"\n")
        return home.toString()
    }

    /** The `file://` URLs of [home]'s `lib/<app>/ebin` folders. */
    private fun ebins(home: String): List<String> =
        Path.of(home).resolve("lib").listDirectoryEntries().map { it.resolve("ebin") }.filter(Files::isDirectory).sorted().map { "file://$it" }
}

/** The umbrella-shaped project `hub`: modules `hub`, at its folder, and `alpha` and `beta` under `apps`, each inheriting. */
internal const val HUB = "hub"

internal fun hubModules(vararg overrides: StagedModule): List<StagedModule> {
    val standard = listOf(StagedModule(HUB, ""), StagedModule("alpha", "apps/alpha"), StagedModule("beta", "apps/beta"))
    val byName = overrides.associateBy(StagedModule::name)
    return standard.map { byName[it.name] ?: it } + overrides.filter { override -> standard.none { it.name == override.name } }
}

/** The staged [project] opened in this window; its frame. A chooser the open asks by itself is cancelled. */
internal fun Driver.openStaged(project: Path): IdeaFrameUI {
    closeInternalErrors("setup")
    openInThisWindow(project)
    val frame = frameOf(HUB)
    if (chooserShows(15.seconds)) {
        cancelDialogs(CHOOSER_TITLE)
        chooserCloses()
    }
    return frame
}

/**
 * The `.iml` of each of [modules] in [project], as on disk once the IDE has saved everything: once every one reads the
 * same over [STABLE_READS] reads a second apart, and [changed]'s, when given, differs from [before]'s.
 */
internal fun Driver.savedImls(
    project: Path,
    modules: List<StagedModule>,
    changed: StagedModule? = null,
    before: Map<StagedModule, String> = emptyMap(),
): Map<StagedModule, String> {
    invokeAction("SaveAll", now = false)
    fun read() = modules.associateWith { project.resolve(it.imlPath).readText() }
    var saved = read()
    var same = 0
    appears("every .iml to be saved" + (changed?.let { ", ${it.name}'s changed" } ?: ""), 30.seconds) {
        val now = read()
        same = if (now == saved) same + 1 else 0
        saved = now
        same >= STABLE_READS && (changed == null || now.getValue(changed) != before.getValue(changed))
    }
    return saved
}

private const val STABLE_READS = 3

/** As row [row] of [rows]: [changed]'s `.iml` is saved changed from [before], the control, and each of [unchanged]'s is as before. */
internal fun Driver.checkOnlyChanged(
    rows: Rows,
    row: String,
    project: Path,
    modules: List<StagedModule>,
    changed: StagedModule,
    before: Map<StagedModule, String>,
    unchanged: List<StagedModule>,
) {
    val after = savedImls(project, modules, changed, before)
    rows.check(row, "${changed.name}'s .iml is saved changed, the control", after.getValue(changed) != before.getValue(changed))
    for (module in unchanged) rows.check(row, "${module.name}'s .iml is unchanged byte for byte", after.getValue(module) == before.getValue(module))
}

/** [body] as row [row]: a throw is that row's failure, with what it left open closed, so the next row still runs. */
internal fun Driver.row(rows: Rows, row: String, body: () -> Unit) {
    runCatching(body).onFailure { failure ->
        runCatching { popupList().keyboard { key(KeyEvent.VK_ESCAPE) } }
        failedErlangStepRow(rows, row, failure) {
            runCatching { cancelDialogs(CHOOSER_TITLE) }
            runCatching { cancelSettings() }
        }
    }
}

/**
 * The Mix project [name], with no SDK, opened by File > Open, shows the chooser, which stays for 60 seconds; then
 * [close], "OK" or "Cancel", closes it.
 */
internal fun Driver.fileOpenStaysOpen(rows: Rows, check: String, name: String, close: String) {
    val project = stageSources(name, mixAppFiles(name))
    closeInternalErrors("setup")
    openInThisWindow(project)
    rows.check(check, "the chooser shows after File > Open", chooserShows())
    for (tick in 1..6) {
        Thread.sleep(10_000)
        rows.check(check, "the chooser is still showing at ${tick * 10}s", chooser().present())
    }
    chooser().clickButtonText(close)
    rows.check(check, "$close closes the chooser", chooserCloses())
}
