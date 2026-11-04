package org.elixir_lang.ui.sdk_settings

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.settings.SettingsDialogUiComponent
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.remote.Component
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitNotNull
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import org.elixir_lang.ui.framework.core.AddedSdk
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.OrderRootTypesRef
import org.elixir_lang.ui.framework.core.SdkTable
import org.elixir_lang.ui.framework.core.cancelErlangStep
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.pickInErlangStep
import org.elixir_lang.ui.framework.core.sdksAt
import org.junit.jupiter.api.Assertions.fail
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JDialog
import javax.swing.JTextField
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** An Elixir SDK page: its name under Settings > Elixir, and the title of its home chooser for Add and Browse. */
enum class SdkPage(val label: String, val chooserTitle: String) {
    ERLANG("Internal Erlang SDKs", "Select Home Directory for Erlang SDK for Elixir SDK"),
    ELIXIR("SDKs", "Select Home Directory for Elixir SDK"),
}

/** An SDK page's own Add, not the roots tab's Add in the editor beside it. */
internal const val LIST_ADD =
    "//div[@class='ActionButton' and (@accessiblename='Add' or @tooltiptext='Add' or @myicon='add.svg') and not(ancestor::div[@class='JBEditorTabs'])]"
/** An SDK page's own Remove. */
private const val LIST_REMOVE =
    "//div[@class='ActionButton' and (@accessiblename='Remove' or @tooltiptext='Remove' or @myicon='remove.svg') and not(ancestor::div[@class='JBEditorTabs'])]"
/** The Elixir SDK editor's Erlang chooser (`AdditionalDataConfigurable`), the only plain `ComboBox` on an SDK page. */
private const val ERLANG_COMBO = "//div[@class='ComboBox']"
private const val SETTINGS_TREE = "//div[@accessiblename='Settings categories']"
private const val TAB_SETTING = "Show settings window as an editor tab"
private const val INTERNAL_ERRORS_CLOSE =
    "//div[@class='MyDialog' and @accessiblename='IDE Internal Errors']//div[@class='JButton' and @accessiblename='Close']"

/**
 * One PyCharm launch under the guard. The rows' homes: A, the installs the build resolves, and B, copies of them in the
 * private home whose version files name another version.
 */
fun launchPyCharm(name: String, body: Driver.(Homes) -> Unit) {
    val homes = Homes.stage()
    val context = IdeTestContext.setupTestContext(name, IdeProductProvider.PY).applyVMOptionsPatch {
        // The private home sits under the real one, so mise's config walk would reach the real home's configs.
        withEnv("MISE_CEILING_PATHS", NoScreen.privateHome.toString())
        // The logger mutes an exception's repeats, so a second row's identical throw would reach no error check.
        addSystemProperty("idea.logger.exception.expiration.minutes", 0)
    }
    try {
        context.runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde { body(homes) }
    } finally {
        keepLog(context, name)
    }
}

/** The homes a row moves a Model between: [a] and [b], whose version files name [bVersion]. */
class Home(val a: String, val b: String, val bVersion: String)

/** The Erlang and Elixir [Home]s. */
class Homes(val erlang: Home, val elixir: Home) {
    companion object {
        /**
         * Copies, never links: the guard empties the private home with `File.deleteRecursively()`, which follows a linked
         * folder into the real install. The Erlang copy's folder name doesn't start with the OTP major, or the Erlang type
         * shows the folder name as the version.
         */
        fun stage(): Homes {
            val root = NoScreen.privateHome.resolve("sdk-homes").createDirectories()
            val erlangB = copy(Path.of(IdeTestContext.erlangSdkPath), root.resolve("erlang-b"))
            val elixirB = copy(Path.of(IdeTestContext.elixirSdkPath), root.resolve("elixir-b"))
            val links = Files.walk(NoScreen.privateHome).use { paths -> paths.asSequence().filter { it.isSymbolicLink() }.toList() }
            check(links.isEmpty()) { "links in the private home: $links" }

            val otpVersion = otpVersionFile(erlangB)
            val erlangBVersion = otherVersion(otpVersion.readText().trim())
            otpVersion.writeText("$erlangBVersion\n")

            val app = elixirB.resolve("lib/elixir/ebin/elixir.app")
            val appText = app.readText()
            val vsn = Regex("""\{vsn,"([^"]+)"}""")
            val elixirBVersion = otherVersion(vsn.find(appText)!!.groupValues[1])
            app.writeText(appText.replace(vsn, """{vsn,"$elixirBVersion"}"""))

            // Normalized, or a `bin/..` in the Elixir home would leave no root "under" it and the A check would hold vacuously.
            val erlangA = Path.of(IdeTestContext.erlangSdkPath).normalize().toString()
            val elixirA = Path.of(IdeTestContext.elixirSdkPath).normalize().toString()
            return Homes(Home(erlangA, erlangB.toString(), erlangBVersion), Home(elixirA, elixirB.toString(), elixirBVersion))
        }

        /**
         * A copy of the Erlang install where mise keeps installs under the private home, so the Erlang step lists it as an
         * install no SDK has; its home.
         */
        fun stageErlangInstall(): String {
            val erlang = Path.of(IdeTestContext.erlangSdkPath).normalize()
            val otpVersion = otpVersionFile(erlang).readText().trim()
            val installs = NoScreen.privateHome.resolve(".local/share/mise/installs/erlang").createDirectories()
            return copy(erlang, installs.resolve(otpVersion)).toString()
        }

        private fun otpVersionFile(erlang: Path): Path =
            erlang.resolve("releases").listDirectoryEntries().single { Files.isDirectory(it) }.resolve("OTP_VERSION")

        /** Links followed, so the copy holds none; on Unix each file keeps its mode, so `bin` stays executable. */
        @OptIn(ExperimentalPathApi::class)
        private fun copy(from: Path, to: Path): Path = from.copyToRecursively(to, followLinks = true, overwrite = false)

        /** The same major and minor, so the copy still passes any version check; a patch no release has. */
        private fun otherVersion(version: String): String = version.split('.').take(2).joinToString(".") + ".999"
    }
}

/** Each row's failed checks, reported together when the class ends, so one row failing doesn't hide another's result. */
class Rows {
    private val failures = mutableMapOf<String, MutableList<String>>()

    fun check(row: String, what: String, holds: Boolean) {
        println("SDK settings row $row: $what: ${if (holds) "holds" else "FAILS"}")
        if (!holds) failures.getOrPut(row) { mutableListOf() } += what
    }

    fun fail(row: String, failure: Throwable) = check(row, "ran without throwing ($failure)", false)

    fun assertAllHeld() {
        if (failures.isNotEmpty()) {
            fail<Unit>(failures.entries.joinToString("\n") { (row, whats) -> "$row failed: ${whats.joinToString("; ")}" })
        }
    }
}

/** #4087 steps 2-4 for [added]: its Erlang step listed [listed], with nothing selected and Create disabled; [picked] is stored. */
fun Driver.checkErlangStepAdd(rows: Rows, row: String, added: AddedSdk, listed: List<String>, picked: String) {
    val shown = added.shown
    rows.check(row, "the step lists ${listed.joinToString(" and ") { "'$it'" }}: ${shown.entries}", listed.all { shown.lists(it) })
    rows.check(row, "nothing is selected: ${shown.selected}", shown.selected.isEmpty())
    rows.check(row, "Create is disabled before a pick", !shown.confirmEnabled)
    val paired = stored(added.name).pairedErlang
    rows.check(row, "the stored pairing '$paired' is the pick '$picked'", paired == picked)
}

/**
 * Applies a removal of [deleted], an SDK at [home], and checks that it reached the table while the dialog is still
 * open, and that the deletion ask titled [askTitle] is not shown while it is. Then clicks OK and checks the dialog
 * closed, which it can't while an ask or its progress is modal over it.
 */
fun UiComponent.applyRemovalAskingNothing(rows: Rows, row: String, deleted: String, home: String, askTitle: String) {
    clickApply()
    val removed = appears("the removal applied", 10.seconds) { deleted !in driver.sdksAt(home) }
    rows.check(row, "Apply removed '$deleted' while the dialog is open", removed)
    rows.check(row, "nothing asks while the dialog is open", !driver.dialogShown(askTitle))
    clickButtonText("OK")
    rows.check(row, "OK closes the dialog with nothing asked over it", appears("the dialog to close", 10.seconds) { !present() })
}

/** The deletion ask titled [askTitle], once the dialog has closed: it lists [pick], with nothing selected; [pick] is stored. */
fun Driver.checkDeletionAsk(rows: Rows, row: String, elixir: String, askTitle: String, pick: String) {
    val shown = pickInErlangStep(pick, title = askTitle, confirm = "OK")
    rows.check(row, "the ask lists '$pick': ${shown.entries}", shown.lists(pick))
    rows.check(row, "nothing is selected: ${shown.selected}", shown.selected.isEmpty())
    appears("the pick stored", 10.seconds) { stored(elixir).pairedErlang == pick }
    val paired = stored(elixir).pairedErlang
    rows.check(row, "the stored pairing '$paired' is the pick '$pick'", paired == pick)
}

/**
 * A failed Erlang step row recorded, and what it left open closed by [closeDialog] after the step, each on its own so
 * one throwing doesn't skip the rest.
 */
fun Driver.failedErlangStepRow(rows: Rows, row: String, failure: Throwable, closeDialog: Driver.() -> Unit) {
    rows.fail(row, failure)
    runCatching { closeInternalErrors("$row-failed") }
    runCatching { cancelErlangStep() }
    runCatching { closeDialog() }
}

/** Closes IDE Internal Errors when it is up, which takes every later click, and logs whether it was. */
fun Driver.closeInternalErrors(label: String) {
    val close = ui.x(INTERNAL_ERRORS_CLOSE)
    val shown = close.present()
    if (shown) close.click()
    println("SDK settings $label: IDE Internal Errors ${if (shown) "was open, closed" else "not open"}")
}

/**
 * The first line of each error the IDE has reported, read or not. IDE Internal Errors opens by itself over a modal
 * dialog, but not over a Settings tab, so a check on the dialog alone misses the tab's throw.
 */
fun Driver.ideErrors(): List<String> =
    utility(MessagePoolRef::class).getInstance().getFatalErrors(true, true).map { it.getThrowableText().lineSequence().first() }

/** The errors the IDE reported during [action], with IDE Internal Errors closed after it. */
fun Driver.errorsDuring(label: String, action: () -> Unit): List<String> {
    val before = ideErrors().size
    action()
    closeInternalErrors(label)
    return ideErrors().drop(before)
}

private fun Driver.modalSettings() = ui.x(xQuery { and(byType(JDialog::class.java), byAccessibleName("Settings")) })

/**
 * Clicks Apply once the dialog has enabled it. After a change, Project Structure enables it on a 500 ms alarm and
 * PyCharm's Settings on a 1000 ms queue, and a click before that applies nothing.
 */
fun UiComponent.clickApply() {
    val apply = x { or(byVisibleText("Apply"), byAccessibleName("Apply")) }
    waitFor("Apply to be enabled", 10.seconds) { apply.isEnabled() }
    apply.click()
}

/** Whether [condition] holds within [timeout], for a check that reads its value after. */
fun appears(what: String, timeout: Duration, condition: () -> Boolean): Boolean =
    runCatching { waitFor(what, timeout) { condition() } }.isSuccess

/** Opens Settings and waits for its dialog, asking again when a fresh PyCharm drops the first ShowSettings. */
fun Driver.openSettings(component: Component? = null) = step("Open Settings") {
    val dialog = modalSettings()
    repeat(3) {
        invokeAction("ShowSettings", now = false, component = component)
        if (appears("Settings to open", 20.seconds) { dialog.present() }) {
            Thread.sleep(2_000)
            return@step
        }
    }
    error("Settings did not open after three attempts")
}

/** Cancels a modal Settings a failed row left open, so the next row can open its own. */
fun Driver.cancelSettings() {
    val dialog = modalSettings()
    if (dialog.present()) dialog.clickButtonText("Cancel")
}

fun Driver.frameOf(project: String): IdeaFrameUI =
    ui.x("//div[@class='IdeFrameImpl' and contains(@accessiblename, '$project')]", IdeaFrameUI::class.java)

/** Modal Settings > Languages & Frameworks > Elixir > [page], [body] inside; the body closes the dialog. */
fun Driver.inSettings(page: SdkPage, body: SettingsDialogUiComponent.() -> Unit) = step("Settings > Elixir > ${page.label}") {
    openSettings()
    ideFrame {
        settingsDialog {
            openTreeSettingsSection("Languages & Frameworks", "Elixir", page.label)
            Thread.sleep(1_000)
            body()
        }
    }
}

/** Adds an Erlang Model at [home] with its page's Add and returns its name. */
fun Driver.addErlangOnPage(home: String): String = addOnPage(SdkPage.ERLANG) { addRow(SdkPage.ERLANG, home) {}.first }

/** Adds an Elixir Model at [home] with its page's Add, picking [erlang] in the Erlang step. */
fun Driver.addElixirOnPage(home: String, erlang: String): AddedSdk = addOnPage(SdkPage.ELIXIR) { addElixirRow(home, erlang) }

/** On the Elixir page, its Add at [home] with [erlang] picked in the Erlang step; Settings stays open. */
fun SettingsDialogUiComponent.addElixirRow(home: String, erlang: String): AddedSdk =
    addRow(SdkPage.ELIXIR, home) { pickInErlangStep(erlang) }.let { (name, shown) -> AddedSdk(name, shown) }

/** Settings opened on [page] with [add] run, then its OK; what [add] returned. */
private fun <T> Driver.addOnPage(page: SdkPage, add: SettingsDialogUiComponent.() -> T): T {
    val added = mutableListOf<T>()
    inSettings(page) {
        added += add()
        clickButtonText("OK")
    }
    return added.single()
}

/** [page]'s Add at [home], with [afterHome] run once the home is chosen: the name of the row it added, and what [afterHome] returned. */
private fun <T> SettingsDialogUiComponent.addRow(page: SdkPage, home: String, afterHome: Driver.() -> T): Pair<String, T> {
    val before = rowNames().toSet()
    x(LIST_ADD).click()
    driver.choosePath(page.chooserTitle, home)
    val result = driver.afterHome()
    waitFor("a row for the add at $home", 10.seconds) { (rowNames().toSet() - before).isNotEmpty() }
    return (rowNames().toSet() - before).single() to result
}

/** The home field's browse, which isn't editable, fired with Shift+Enter, the key `ComponentWithBrowseButton` binds; then the chooser. */
fun UiComponent.browseHome(model: String, title: String, home: String) {
    selectRow(model)
    val field = x("//div[@class='TextFieldWithBrowseButton']").x { byType(JTextField::class.java) }
    field.click()
    field.keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_ENTER) }
    driver.choosePath(title, home)
    Thread.sleep(1_000)
}

/** [model]'s Erlang combo set to the item named [erlang]. */
fun UiComponent.pairWith(model: String, erlang: String) {
    selectRow(model)
    val combo = comboBox(ERLANG_COMBO)
    combo.selectItem(combo.listValues().first { it == erlang || it.startsWith("$erlang ") })
}

private fun UiComponent.sdkList() = x("//div[@javaclass='org.elixir_lang.facet.sdks.List']", JListUiComponent::class.java)

fun UiComponent.rowNames(): List<String> = sdkList().items

fun UiComponent.selectRow(name: String) {
    sdkList().clickItem(name)
    Thread.sleep(500)
}

fun UiComponent.selectedRows(): List<String> = sdkList().selectedItems

/** [name] removed with its page's Remove. */
fun UiComponent.removeRow(name: String) {
    selectRow(name)
    x(LIST_REMOVE).click()
}

fun Driver.dialogShown(title: String): Boolean = ui.x(xQuery { byTitle(title) }).present()

/** Settings > Advanced Settings > [TAB_SETTING] ticked through the page's own search field: Settings opens as an editor tab from then on. */
fun Driver.settingsAsTab(frame: IdeaFrameUI) = step("Settings as an editor tab") {
    openSettings(frame.component)
    frame.apply {
        settingsDialog {
            openTreeSettingsSection("Advanced Settings")
            Thread.sleep(1_000)
            val search = content().x("//div[@class='SearchTextField']").x { byType(JTextField::class.java) }
            search.click()
            search.keyboard { typeText("editor tab") }
            Thread.sleep(1_500)
            // By accessible name: the checkbox's visible text didn't match.
            x("//div[@class='JBCheckBox' and @accessiblename='$TAB_SETTING']").click()
            clickButtonText("OK")
        }
    }
}

/**
 * Settings opened from [frame] as an editor tab on Elixir > [page], or null when the Settings tree doesn't show inside
 * the frame. The tab holds the dialog's root pane, so that is the Settings component.
 */
fun Driver.settingsTab(frame: IdeaFrameUI, page: SdkPage): SettingsDialogUiComponent? = step("Settings tab: ${page.label}") {
    frame.toFront()
    invokeAction("ShowSettings", now = false, component = frame.component)
    val inFrame = appears("the Settings tab", 30.seconds) { frame.x(SETTINGS_TREE).present() }
    if (!inFrame) return@step null
    frame.x(
        "$SETTINGS_TREE/ancestor::div[contains(@classhierarchy, 'javax.swing.JRootPane ') or @javaclass='javax.swing.JRootPane'][1]",
        SettingsDialogUiComponent::class.java,
    ).apply {
        openTreeSettingsSection("Languages & Frameworks", "Elixir", page.label)
        Thread.sleep(1_000)
    }
}

/** File > Open of [project] from the project frame PyCharm starts in, into this window, waited for until indexed. */
fun Driver.openInThisWindow(project: Path) = step("File > Open $project") {
    invokeAction("OpenFile", now = false)
    choosePath("Open File or Project", project.toString())
    val thisWindow = ui.x("//div[@visible_text='This Window' or @accessiblename='This Window']")
    if (appears("the where-to-open question", 15.seconds) { thisWindow.present() }) thisWindow.click()
    val opened = waitNotNull("$project to open", 2.minutes) { getOpenProjects().firstOrNull { Path.of(it.getBasePath()) == project } }
    waitForIndicators(opened, 5.minutes)
}

@Remote("com.intellij.diagnostic.MessagePool")
interface MessagePoolRef {
    fun getInstance(): MessagePoolRef
    fun getFatalErrors(includeReadMessages: Boolean, includeSubmittedMessages: Boolean): List<AbstractMessageRef>
}

@Remote("com.intellij.diagnostic.AbstractMessage")
interface AbstractMessageRef {
    fun getThrowableText(): String
}

/** The stored pairing only: `getErlangSdk()` heals and commits, so a read through it would change what it reads. */
@Remote("org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData", plugin = "org.elixir_lang")
interface PairingRef {
    fun getErlangSdkName(): String?
}

class Stored(val home: String?, val version: String?, val classesRoots: List<String>, val pairedErlang: String?)

fun Driver.stored(model: String): Stored = withReadAction {
    val sdk = utility(SdkTable::class).getInstance().findJdk(model) ?: error("no SDK named '$model' in the table")
    val classes = utility(OrderRootTypesRef::class).getAllTypes().single { it.name() == "CLASSES" }
    Stored(
        home = sdk.getHomePath(),
        version = sdk.getVersionString(),
        classesRoots = sdk.getRootProvider().getUrls(classes).toList(),
        pairedErlang = sdk.getSdkAdditionalData()?.let { runCatching { cast(it, PairingRef::class).getErlangSdkName() }.getOrNull() },
    )
}

/** Whether [url] names a path under [home]. */
fun under(url: String, home: String): Boolean = url.substringAfter("://").removeSuffix("!/").startsWith(home.trimEnd('/') + "/")

private fun keepLog(context: IDETestContext, name: String) {
    val out = Path.of("build/sdk-settings", name).createDirectories()
    NoScreen.files(context.paths.testHome).filter { it.name == "idea.log" }.forEachIndexed { index, log ->
        log.copyTo(out.resolve("idea-$index.log"), overwrite = true)
    }
}
