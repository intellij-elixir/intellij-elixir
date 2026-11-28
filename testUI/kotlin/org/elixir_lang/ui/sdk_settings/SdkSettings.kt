package org.elixir_lang.ui.sdk_settings

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.invokeActionByShortcut
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.UiText.Companion.asString
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.textField
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
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.VMOptions
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import org.elixir_lang.jps.shared.ElixirSdkTypeId.ELIXIR_SDK_TYPE_ID
import org.elixir_lang.jps.shared.ErlangSdkTypeId.ERLANG_SDK_TYPE_ID
import org.elixir_lang.ui.code_intelligence.TextPane
import org.elixir_lang.ui.framework.core.ERLANG_STEP_TITLE
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.OrderRootTypesRef
import org.elixir_lang.ui.framework.core.SDK_FIELD
import org.elixir_lang.ui.framework.core.SdkTable
import org.elixir_lang.ui.framework.core.blamesPlugin
import org.elixir_lang.ui.framework.core.cancelErlangStep
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.fieldText
import org.elixir_lang.ui.framework.core.pickInErlangStep
import org.elixir_lang.ui.framework.core.pickInField
import org.elixir_lang.ui.framework.core.projectStructure
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.framework.core.waitForIndicatorsOrChooser
import org.elixir_lang.ui.sdk_choice.CHOOSE_LABEL
import org.elixir_lang.ui.sdk_choice.askTheChooser
import org.elixir_lang.ui.sdk_choice.bannerLabel
import org.elixir_lang.ui.sdk_choice.pickInChooser
import org.junit.jupiter.api.Assertions.fail
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JDialog
import javax.swing.JEditorPane
import javax.swing.JTextField
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
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

/** An SDK page's own action named [name], not the roots tab's in the editor beside it. */
private fun listAction(name: String): String =
    "//div[@class='ActionButton' and (@accessiblename='$name' or @tooltiptext='$name' or @myicon='${name.lowercase()}.svg') and not(ancestor::div[@class='JBEditorTabs'])]"

private val LIST_ADD = listAction("Add")
internal val LIST_REMOVE = listAction("Remove")
private const val SETTINGS_TREE = "//div[@accessiblename='Settings categories']"
private const val TAB_SETTING = "Show settings window as an editor tab"
private const val INTERNAL_ERRORS_CLOSE =
    "//div[@class='MyDialog' and @accessiblename='IDE Internal Errors']//div[@class='JButton' and @accessiblename='Close']"

/**
 * One PyCharm launch under the guard, with [vmOptions] applied and [configure] given the IDE's configuration folder
 * before it starts. The rows' homes: A, the installs the build resolves, and B, copies of them in the private home whose
 * version files name another version.
 */
fun launchPyCharm(
    name: String,
    vmOptions: VMOptions.() -> Unit = {},
    configure: (Path) -> Unit = {},
    body: Driver.(Homes) -> Unit,
) = launch(name, IdeProductProvider.PY, vmOptions, configure, emptyList(), body)

/**
 * One IntelliJ IDEA launch under the guard, with the homes, [vmOptions] and [configure] of [launchPyCharm], and the
 * Marketplace's latest build of each of [plugins] for the IDE installed.
 */
fun launchIdea(
    name: String,
    vmOptions: VMOptions.() -> Unit = {},
    configure: (Path) -> Unit = {},
    plugins: List<String> = emptyList(),
    body: Driver.(Homes) -> Unit,
) = launch(name, IdeProductProvider.IU, vmOptions, configure, plugins, body)

private fun launch(
    name: String,
    product: IdeInfo,
    vmOptions: VMOptions.() -> Unit,
    configure: (Path) -> Unit,
    plugins: List<String>,
    body: Driver.(Homes) -> Unit,
) {
    val homes = Homes.stage()
    val context = IdeTestContext.setupTestContext(name, product, plugins = plugins).apply { configure(paths.configDir) }.applyVMOptionsPatch {
        // The private home sits under the real one, so mise's config walk would reach the real home's configs.
        withEnv("MISE_CEILING_PATHS", NoScreen.privateHome.toString())
        // The logger mutes an exception's repeats, so a second row's identical throw would reach no error check.
        addSystemProperty("idea.logger.exception.expiration.minutes", 0)
        vmOptions()
    }
    try {
        context.runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde { body(homes) }
    } finally {
        keepLog(context, name)
    }
}

/** The homes a row moves a Model between: [a], whose version files name [aVersion], and [b], whose name [bVersion]. */
class Home(val a: String, val aVersion: String, val b: String, val bVersion: String)

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
            val erlangAVersion = otpVersion.readText().trim()
            val erlangBVersion = otherVersion(erlangAVersion)
            otpVersion.writeText("$erlangBVersion\n")

            val elixirAVersion = elixirVersion(elixirB)
            val elixirBVersion = otherVersion(elixirAVersion)
            setElixirVersion(elixirB, elixirBVersion)

            // Normalized, or a `bin/..` in the Elixir home would leave no root "under" it and the A check would hold vacuously.
            val erlangA = Path.of(IdeTestContext.erlangSdkPath).normalize().toString()
            val elixirA = Path.of(IdeTestContext.elixirSdkPath).normalize().toString()
            return Homes(
                Home(erlangA, erlangAVersion, erlangB.toString(), erlangBVersion),
                Home(elixirA, elixirAVersion, elixirB.toString(), elixirBVersion),
            )
        }

        /** A copy of the Erlang install whose release folder and version file name the OTP major before its own; its home. */
        fun stageErlangOfAnotherMajor(): String {
            val erlang = copy(Path.of(IdeTestContext.erlangSdkPath), NoScreen.privateHome.resolve("sdk-homes/erlang-other-major"))
            toPreviousMajor(erlang, previousMajorVersion(erlang, "999"))
            return erlang.toString()
        }

        /**
         * A copy of the Erlang install where mise keeps installs under the private home, so the SDK field lists it as an
         * install no SDK has; its home.
         */
        fun stageErlangInstall(): String {
            val erlang = Path.of(IdeTestContext.erlangSdkPath).normalize()
            val otpVersion = otpVersionFile(erlang).readText().trim()
            return copy(erlang, miseErlangInstalls().resolve(otpVersion)).toString()
        }

        /**
         * A copy of the Erlang install where mise keeps installs, its release folder and `OTP_VERSION` naming the OTP major
         * before its own, at patch [patch], so the field lists it as Detected and no Elixir built on the install's OTP
         * matches it; its home.
         */
        fun stageDetectedErlang(patch: String): String {
            val erlang = Path.of(IdeTestContext.erlangSdkPath).normalize()
            val version = previousMajorVersion(erlang, patch)
            val home = copy(erlang, miseErlangInstalls().resolve(version))
            toPreviousMajor(home, version)
            return home.toString()
        }

        /** `<major - 1>.0.[patch]`, from the major of [erlang]'s release folder. */
        private fun previousMajorVersion(erlang: Path, patch: String): String = "${otpVersionFile(erlang).parent.name.toInt() - 1}.0.$patch"

        /** [erlang]'s release folder and `OTP_VERSION` moved to [version], from [previousMajorVersion]. */
        private fun toPreviousMajor(erlang: Path, version: String) {
            val release = otpVersionFile(erlang).parent
            release.moveTo(release.resolveSibling(version.substringBefore('.'))).resolve("OTP_VERSION").writeText("$version\n")
        }

        private fun miseErlangInstalls(): Path = NoScreen.privateHome.resolve(".local/share/mise/installs/erlang").createDirectories()

        /** Links followed, so the copy holds none; on Unix each file keeps its mode, so `bin` stays executable. */
        @OptIn(ExperimentalPathApi::class)
        internal fun copy(from: Path, to: Path): Path = from.copyToRecursively(to, followLinks = true, overwrite = false)

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
    checkPaired(rows, row, elixir, pick)
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
 * The first line of each error the IDE has reported, read or not, that [blamesPlugin] counts as the plugin's, in order.
 * IDE Internal Errors opens by itself over a modal dialog, but not over a Settings tab, so a check on the dialog alone
 * misses the tab's throw.
 */
fun Driver.ideErrors(): List<String> =
    utility(MessagePoolRef::class).getInstance()
        .getFatalErrors(includeReadMessages = true, includeSubmittedMessages = true)
        .map { it.getThrowableText() }
        .filter(::blamesPlugin)
        .map { it.lineSequence().first() }

/** The [ideErrors] reported during [action], with IDE Internal Errors closed after it. */
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

/** A Project Structure a failed row left open, closed; an Apply relabels its Cancel as Close. */
fun Driver.closeProjectStructure() {
    val dialog = ui.projectStructure()
    if (!dialog.present()) return
    val close = listOf("Cancel", "Close").firstOrNull { dialog.x { byVisibleText(it) }.present() }
    if (close != null) dialog.clickButtonText(close)
}

/** The Name field of the SDK page shown: Project Structure's or Settings'. */
fun UiComponent.nameField() = textField { and(byType(JTextField::class.java), byAccessibleName("Name:")) }

fun Driver.frameOf(project: String): IdeaFrameUI =
    ui.x("//div[@class='IdeFrameImpl' and contains(@accessiblename, '$project')]", IdeaFrameUI::class.java)

/** Settings' Languages & Frameworks > Elixir > [page], once it has had a moment to build. */
fun SettingsDialogUiComponent.openSdkPage(page: SdkPage) {
    openTreeSettingsSection("Languages & Frameworks", "Elixir", page.label)
    Thread.sleep(1_000)
}

/** Modal Settings > Languages & Frameworks > Elixir > [page], [body] inside; the body closes the dialog. */
fun <T> Driver.inSettings(page: SdkPage, body: SettingsDialogUiComponent.() -> T): T = step("Settings > Elixir > ${page.label}") {
    openSettings()
    val result = mutableListOf<T>()
    ideFrame {
        settingsDialog {
            openSdkPage(page)
            result += body()
        }
    }
    result.single()
}

/** Adds an Erlang Model at [home] with its page's Add and returns its name. */
fun Driver.addErlangOnPage(home: String): String = addOnPage(SdkPage.ERLANG) { addRow(SdkPage.ERLANG, home) }

/** Adds an Elixir Model at [home] with its page's Add, picking [erlang] in its page's field, and returns its name. */
fun Driver.addElixirOnPage(home: String, erlang: String): String = addOnPage(SdkPage.ELIXIR) { addElixirRow(home, erlang) }

/**
 * On the Elixir page, its Add at [home] with [erlang] picked in the new SDK's field; Settings stays open. Returns the
 * name the Name field shows after the pick.
 */
private fun SettingsDialogUiComponent.addElixirRow(home: String, erlang: String): String {
    addRow(SdkPage.ELIXIR, home)
    pickInField(erlang)
    return nameField().text
}

/** Settings opened on [page] with [add] run, then its OK; what [add] returned. */
private fun <T> Driver.addOnPage(page: SdkPage, add: SettingsDialogUiComponent.() -> T): T =
    inSettings(page) { add().also { clickButtonText("OK") } }

/** [page]'s Add at [home], with [afterHome] run once the home is chosen: the name of the row it added within [timeout]. */
fun SettingsDialogUiComponent.addRow(
    page: SdkPage,
    home: String,
    timeout: Duration = 10.seconds,
    afterHome: Driver.() -> Unit = {},
): String {
    val before = rowNames().toSet()
    x(LIST_ADD).click()
    driver.choosePath(page.chooserTitle, home)
    driver.afterHome()
    waitFor("a row for the add at $home", timeout) { (rowNames().toSet() - before).isNotEmpty() }
    return (rowNames().toSet() - before).single()
}

/** An Erlang step that opens fails [row], whose failure cancels it. */
fun Driver.checkNoErlangStep(rows: Rows, row: String) {
    val asked = appears("the Erlang step", 5.seconds) { dialogShown(ERLANG_STEP_TITLE) }
    rows.check(row, "no Erlang step opens", !asked)
    check(!asked) { "the Erlang step opened" }
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

/** [model]'s Erlang SDK field set to the row naming [erlang]. */
fun UiComponent.pairWith(model: String, erlang: String) {
    selectRow(model)
    pickInField(erlang)
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
    ).apply { openSdkPage(page) }
}

/** File > Open of [project] from the project frame PyCharm starts in, into this window. */
fun Driver.openInThisWindow(project: Path): Unit = step("File > Open $project") {
    // PyCharm's start project joins the open projects seconds after its frame shows, and an open before then asks no
    // This Window question and takes a second frame. IntelliJ IDEA opens from the welcome screen, with no project.
    waitFor("a project or the welcome screen", 2.minutes) { getOpenProjects().isNotEmpty() || welcomeScreen().present() }
    // The chooser's OK takes its tree's selection, which follows the typed path only once the IDE has seen the folder,
    // and one written from outside moments ago isn't seen unprompted.
    utility(LocalFileSystemRef::class).getInstance().refreshAndFindFileByPath(project.toString())
    invokeAction("OpenFile", now = false)
    choosePath("Open File or Project", project.toString())
    val thisWindow = ui.x("//div[@visible_text='This Window' or @accessiblename='This Window']")
    if (appears("the where-to-open question", 15.seconds) { thisWindow.present() }) thisWindow.click()
    val opened = waitNotNull("$project to open", 2.minutes) { getOpenProjects().firstOrNull { Path.of(it.getBasePath()) == project } }
    waitForIndicatorsOrChooser(opened, 5.minutes)
}

@Remote("com.intellij.openapi.vfs.LocalFileSystem")
private interface LocalFileSystemRef {
    fun getInstance(): LocalFileSystemRef
    fun refreshAndFindFileByPath(path: String): VirtualFile?
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

/** [elixir] is saved paired with [erlang]; [row] fails when it isn't, or isn't saved. */
fun Driver.checkPaired(rows: Rows, row: String, elixir: String, erlang: String) {
    val paired = runCatching { stored(elixir).pairedErlang }.getOrNull()
    rows.check(row, "'$elixir' is saved paired with '$erlang': '$paired'", paired == erlang)
}

/** Whether [url] names a path under [home]. */
fun under(url: String, home: String): Boolean = url.substringAfter("://").removeSuffix("!/").startsWith(home.trimEnd('/') + "/")

/** The names of the table's Elixir and Erlang SDKs, in the table's order; PyCharm adds its own interpreter for the project it starts with. */
fun Driver.tableNames(): List<String> = withReadAction {
    utility(SdkTable::class).getInstance().getAllJdks()
        .filter { it.getSdkType().getName() in setOf(ELIXIR_SDK_TYPE_ID, ERLANG_SDK_TYPE_ID) }
        .map { it.getName() }
}

/** The `OTP_VERSION` file of the Erlang install at [erlang]. */
internal fun otpVersionFile(erlang: Path): Path =
    erlang.resolve("releases").listDirectoryEntries().single { Files.isDirectory(it) }.resolve("OTP_VERSION")

/** The version term in an Elixir install's `elixir.app`, the version its first group. */
private val ELIXIR_VSN = Regex("""\{vsn,"([^"]+)"}""")

private fun elixirApp(home: Path): Path = home.resolve("lib/elixir/ebin/elixir.app")

/** The version the Elixir install at [home] names in its `elixir.app`. */
internal fun elixirVersion(home: Path): String = ELIXIR_VSN.find(elixirApp(home).readText())!!.groupValues[1]

/** The Elixir install at [home], its `elixir.app` made to name [version]. */
internal fun setElixirVersion(home: Path, version: String) {
    elixirApp(home).apply { writeText(readText().replace(ELIXIR_VSN, """{vsn,"$version"}""")) }
}

/**
 * A `jdk.table.xml` SDK of [type] named [name], at [home], with [classes] as its class roots and [additional] as its
 * `additional` element, empty for none.
 */
internal fun jdkXml(name: String, type: String, version: String, home: String, classes: List<String> = emptyList(), additional: String = ""): String {
    val roots = classes.joinToString("") { """<root url="${escapeXmlEntities(it)}" type="simple" />""" }
    return """
        <jdk version="2">
          <name value="${escapeXmlEntities(name)}" />
          <type value="${escapeXmlEntities(type)}" />
          <version value="${escapeXmlEntities(version)}" />
          <homePath value="${escapeXmlEntities(home)}" />
          <roots>
            <annotationsPath><root type="composite" /></annotationsPath>
            <classPath><root type="composite">$roots</root></classPath>
            <javadocPath><root type="composite" /></javadocPath>
            <sourcePath><root type="composite" /></sourcePath>
          </roots>
          $additional
        </jdk>
    """.trimIndent()
}

/** The SDK table in [configDir], holding [jdks], each from [jdkXml]. */
internal fun writeJdkTable(configDir: Path, jdks: List<String>) {
    configDir.resolve("options").createDirectories().resolve("jdk.table.xml").writeText(
        "<application>\n<component name=\"ProjectJdkTable\">\n${jdks.joinToString("\n")}\n</component>\n</application>\n",
    )
}

/** A seeded Elixir SDK saved with no additional data. */
const val NO_DATA = "Elixir NoData"

/**
 * Registered Elixir Models at the A install, each a name and its `additional` element, empty for none, so that one
 * loads with null data.
 */
fun seedElixirs(configDir: Path, vararg seeds: Pair<String, String>) {
    val home = Path.of(IdeTestContext.elixirSdkPath).normalize().toString()
    writeJdkTable(configDir, seeds.map { (name, additional) -> jdkXml(name, ELIXIR_SDK_TYPE_ID, "1.20.4", home, additional = additional) })
}

/** The sentence an Elixir SDK page shows under its Erlang SDK field while it asks for one, and Apply's refusal ends with. */
const val ERLANG_NOT_CHOSEN = "An Erlang SDK must be chosen."

/** A field's outline while it shows an error. */
const val ERROR_OUTLINE = "error"

/** Whether the page shows [ERLANG_NOT_CHOSEN] under its field. */
fun UiComponent.messageShown(): Boolean = x("//div[@class='JLabel' and @visible_text='$ERLANG_NOT_CHOSEN']").present()

/** The component's outline, or null. */
fun UiComponent.outline(): String? =
    driver.cast(component, ClientProperties::class).getClientProperty("JComponent.outline")?.toString()

/** A field row listing a mise Erlang install. */
fun isErlangRow(row: String): Boolean = "installs/erlang" in row

/** The pause for a page to settle where nothing it shows can be waited on. */
const val SETTLE_MS = 2_000L

/** What a page shows while it asks for an Erlang: nothing picked, the text under the field, its outline and its popup. */
fun UiComponent.checkAsking(rows: Rows, row: String) {
    val shown = fieldText()
    rows.check(row, "the field has nothing selected: '$shown'", "rlang" !in shown)
    rows.check(row, "the text under the field is shown", messageShown())
    val outline = x(SDK_FIELD).outline()
    rows.check(row, "the field has the error outline: $outline", outline == ERROR_OUTLINE)
    x(SDK_FIELD).moveMouse()
    rows.check(row, "the popup on the field says it", appears("the field's popup", 5.seconds) { driver.validatorPopup() })
}

/** None of the asking shows. */
fun UiComponent.checkNeverAsked(rows: Rows, row: String) {
    rows.check(row, "the text under the field is not shown", !messageShown())
    val outline = x(SDK_FIELD).outline()
    rows.check(row, "the field has no outline: $outline", outline == null)
}

/** [name]'s page, viewed, shows the text and the error outline. */
fun UiComponent.checkRed(rows: Rows, row: String, name: String) {
    rows.check(row, "'$name' shows the text", appears("the text", 10.seconds) { messageShown() })
    val outline = x(SDK_FIELD).outline()
    rows.check(row, "'$name' has the error outline: $outline", outline == ERROR_OUTLINE)
}

/** No validator popup on the field, with the pointer over it. */
fun UiComponent.checkNoPopup(rows: Rows, row: String) {
    x(SDK_FIELD).moveMouse()
    Thread.sleep(SETTLE_MS)
    rows.check(row, "no popup on the field", !driver.validatorPopup())
}

/** Whether the validator's popup is up: the only editor pane showing the sentence. */
private fun Driver.validatorPopup(): Boolean = ui.xx(xQuery { byType(JEditorPane::class.java) }).list().any { pane ->
    val document = cast(pane.component, TextPane::class).getDocument()
    document.getText(0, document.getLength()).trim() == ERLANG_NOT_CHOSEN
}

@Remote("javax.swing.JComponent")
private interface ClientProperties {
    fun getClientProperty(key: Any): Any?
}

/** The project's [source] given [elixir] through the banner's chooser, then [checkResolvesInAnEditor]. */
fun Driver.checkWorksInAnEditor(rows: Rows, row: String, source: String, elixirHome: String, elixir: String, erlangHome: String, erlang: String) {
    askTheChooser(rows, row, source)
    pickInChooser(rows, row, elixir, erlang)
    checkResolvesInAnEditor(rows, row, source, elixirHome, erlangHome)
}

/** Module [module], with the calls [checkResolvesInAnEditor] goes to the declarations of. */
fun resolvableSource(module: String): String = """
    defmodule $module do
      def elixir_call, do: Enum.count([])
      def erlang_call, do: :lists.reverse([])
    end
""".trimIndent()

/** A standard-library call in [source] resolved into [elixirHome] and `:lists` into [erlangHome], in its showing editor. */
fun Driver.checkResolvesInAnEditor(rows: Rows, row: String, source: String, elixirHome: String, erlangHome: String) {
    waitForIndicators(singleProject(), 5.minutes)
    openFile(source, waitForCodeAnalysis = false)
    rows.check(row, "the file's editor is showing", appears("the editor", 30.seconds) { editorPath().endsWith(source) })
    rows.check(row, "the banner no longer asks", !bannerLabel(CHOOSE_LABEL).present())
    checkResolves(rows, row, source, "Enum.count", "Enum.".length, elixirHome)
    checkResolves(rows, row, source, ":lists.reverse", ":lists.".length, erlangHome)
}

/** Go to Declaration on [marker] in [source], at [inset], opens a file under [home]. */
private fun Driver.checkResolves(rows: Rows, row: String, source: String, marker: String, inset: Int, home: String) {
    openFile(source, waitForCodeAnalysis = false)
    ideFrame {
        codeEditor().apply {
            click()
            moveCaretToOffset(text.indexOf(marker) + inset)
            invokeActionByShortcut("GotoDeclaration")
        }
    }
    val popup = ui.popup()
    val moved = appears("the declaration", 30.seconds) {
        // A function with a source and a decompiled target asks which; both are in the SDK, so the first is as good.
        if (popup.present()) {
            println("SDK settings row $row: '$marker' asks: ${popup.getAllTexts().asString(" ")}")
            popup.keyboard { key(KeyEvent.VK_ENTER) }
        }
        !editorPath().endsWith(source)
    }
    val target = editorPath()
    rows.check(row, "'$marker' resolves under $home: $target", moved && target.startsWith(home))
}

private fun Driver.editorPath(): String {
    var path = ""
    ideFrame { path = codeEditor().editor.getVirtualFile().getPath() }
    return path
}

private fun keepLog(context: IDETestContext, name: String) {
    val out = Path.of("build/sdk-settings", name).createDirectories()
    NoScreen.files(context.paths.testHome).filter { it.name == "idea.log" }.forEachIndexed { index, log ->
        log.copyTo(out.resolve("idea-$index.log"), overwrite = true)
    }
}
