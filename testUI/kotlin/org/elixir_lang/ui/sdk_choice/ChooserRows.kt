package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.client.impl.RefWrapper
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.AccessibleNameCellRendererReader
import com.intellij.driver.sdk.ui.UiText.Companion.asString
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.elements.JCheckBoxUi
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.checkBoxWithName
import com.intellij.driver.sdk.waitFor
import org.elixir_lang.ui.framework.core.SDK_FIELD
import org.elixir_lang.ui.framework.core.chooser
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.isSdk
import org.elixir_lang.ui.framework.core.popupList
import org.elixir_lang.ui.framework.core.popupRows
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.checkPaired
import org.elixir_lang.ui.sdk_settings.outline
import java.awt.event.KeyEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Project SDK row's name; a module's row is named for its module. */
internal const val PROJECT_ROW = "Project SDK"

/** Whether the chooser shows within [timeout]. */
internal fun Driver.chooserShows(timeout: Duration = 60.seconds): Boolean = appears("the chooser", timeout) { chooser().present() }

/** Whether the chooser closes within [timeout]. */
internal fun Driver.chooserCloses(timeout: Duration = 30.seconds): Boolean = closes(chooser(), timeout)

/** Choose Elixir SDK..., over [frame] with no module selected; whether the chooser shows. */
internal fun Driver.openChooser(frame: IdeaFrameUI): Boolean {
    invokeAction("Elixir.ChooseElixirSdk", now = false, component = frame.component)
    return chooserShows()
}

/** The field of the chooser's row [row]: [PROJECT_ROW], or a module's name. */
internal fun UiComponent.rowField(row: String): UiComponent = x("$SDK_FIELD[@accessiblename='$row']")

internal fun UiComponent.hasRow(row: String): Boolean = rowField(row).present()

/** What [row]'s field shows closed. */
internal fun UiComponent.rowText(row: String): String = rowField(row).getAllTexts().asString(" ")

/** Whether [row]'s field shows [text], drawn with whatever the platform draws after it. */
internal fun UiComponent.holds(row: String, text: String): Boolean = text in rowText(row)

/** Which of [rows] has the focus, once one has it. */
internal fun UiComponent.focusedRow(rows: List<String>): String? {
    var focused: String? = null
    appears("a focused row", 10.seconds) { rows.firstOrNull { rowField(it).isFocusOwner() }.also { focused = it } != null }
    return focused
}

/**
 * The popup [list]'s items, each its painted text, or its accessible name where the painted text reads empty: the
 * driver's reader reads a row's SDK items as "" where the same items read by name.
 */
private fun popupItems(list: JListUiComponent): List<String> {
    val painted = list.items
    if (painted.none(String::isEmpty)) return painted
    // A list keeps the reader it was first read with, so the names are read through a list of their own.
    val named = list.driver.popupList().apply {
        replaceCellRendererReader { driver.new(AccessibleNameCellRendererReader::class, rdTarget = (it as RefWrapper).getRef().rdTarget) }
    }.items
    return painted.mapIndexed { index, text -> text.ifEmpty { named.getOrElse(index) { "" } } }
}

/** [row]'s popup opened, once an item [matches] accepts is listed: its items, in order. */
private fun UiComponent.openRow(row: String, matches: (String) -> Boolean): List<String> =
    popupRows("an item of row '$row'", matches, ::popupItems) { rowField(row).click() }

/** [row]'s popup items, in order; the popup is closed again. */
internal fun UiComponent.rowItems(row: String): List<String> {
    val items = openRow(row) { true }
    driver.popupList().keyboard { key(KeyEvent.VK_ESCAPE) }
    waitFor("row '$row''s popup to close", 10.seconds) { !driver.popupList().present() }
    return items
}

/** [row]'s first item [matches] accepts, clicked, once its field shows it. */
internal fun UiComponent.pickInRow(row: String, matches: (String) -> Boolean) {
    val items = openRow(row, matches)
    val item = items.first(matches)
    driver.popupList().clickItemAtIndex(items.indexOf(item))
    waitFor("row '$row' to show '$item'", 10.seconds) { matches(rowText(row)) }
}

/** The outline [row]'s field shows, or null. */
internal fun UiComponent.rowOutline(row: String): String? = rowField(row).outline()

/** Whether the component is in the hierarchy and showing, not hidden. */
internal fun UiComponent.shows(): Boolean = present() && component.isShowing()

/** The line under the rows holding [text], as a row's mise answer says it. */
internal fun UiComponent.line(text: String): UiComponent = x("//div[contains(@visible_text, \"$text\") or contains(@accessiblename, \"$text\")]")

/** The tool manager's boxes under the rows. */
internal fun UiComponent.inStepBox(): JCheckBoxUi = checkBoxWithName("Keep the SDKs in step with mise")

internal fun UiComponent.environmentBox(): JCheckBoxUi = checkBoxWithName("Use mise's environment for Mix and the build")

/** The chooser's mise entry for an Elixir [elixir] and Erlang [erlang], as a row names it. */
internal fun miseEntry(elixir: String, erlang: String) = "mise: Elixir $elixir, Erlang $erlang"

/** Whether a row's item or text is a mise entry. */
internal fun isMiseEntry(text: String): Boolean = text.startsWith("mise:")

/** Whether a row's item or text is "<No SDK>". */
internal fun isNoSdk(text: String): Boolean = "No SDK" in text

/** What the Project SDK row shows with no project SDK, as the platform draws it. */
internal const val NO_PROJECT_SDK = "<No Project SDK>"

internal fun isNoProjectSdk(text: String): Boolean = NO_PROJECT_SDK in text

/** Whether a row's item or text is "Project SDK", the project SDK inherited. */
internal fun isProjectSdk(text: String): Boolean = text.startsWith(PROJECT_ROW)

/** Whether a row's text is "Project SDK" naming [sdk]. */
internal fun showsProjectSdk(text: String, sdk: String): Boolean = isProjectSdk(text) && sdk in text

/**
 * In the chooser, [elixir] picked in the focused row, already paired with [erlang] before OK, and OK: the chooser closes
 * and [elixir] is saved paired with [erlang]. [rows] here are the results, [row] the result row. A row draws an SDK's
 * name and version only, so the pairing is read from the table.
 */
internal fun Driver.pickInChooser(rows: Rows, row: String, elixir: String, erlang: String) {
    chooser().apply {
        waitUntilReady()
        val names = xx(SDK_FIELD).list().mapNotNull { it.component.getAccessibleContext()?.getAccessibleName() }
        val focused = focusedRow(names)
        rows.check(row, "a row of $names has the focus", focused != null)
        pickInRow(focused ?: names.first(), isSdk(elixir))
        driver.checkPaired(rows, row, elixir, erlang)
        clickButtonText("OK")
    }
    rows.check(row, "OK closes the chooser", chooserCloses())
    checkPaired(rows, row, elixir, erlang)
}
