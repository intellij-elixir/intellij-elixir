package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.jBlist
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitNotNull
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The chooser's list, in whichever dialog or wizard page shows it. */
internal fun UiComponent.chooserList(): JListUiComponent = jBlist("//div[@class='SdkChoicePanel']//div[@class='JBList']")

/**
 * File > New > Project or Module from Existing Sources, run by [action] or, when there is none, by a gesture already
 * made, of [path]: the wizard titled [title], on the first page's radio button whose name contains [choice], with the
 * list item [model] clicked when given.
 */
internal fun Driver.fromExistingSources(action: String?, path: Path, title: String, choice: String, model: String? = null) {
    action?.let { invokeAction(it, now = false) }
    val chooser = ui.dialog(xQuery { byTitle(CHOOSE_IMPORT) })
    choosePath(CHOOSE_IMPORT, path.toString())
    // Once in a run the chooser stayed open after its OK with the path typed; it is typed again.
    if (!closes(chooser, 10.seconds)) {
        val shown = chooser.texts()
        runCatching { choosePath(CHOOSE_IMPORT, path.toString()) }
        check(closes(chooser, 10.seconds)) { "The file chooser stays open after OK, showing '$shown'" }
    }
    val wizard = wizard(title)
    waitFor("the $title wizard", 30.seconds) { wizard.present() }
    wizard.x("//div[@class='JBRadioButton' and contains(@accessiblename, '$choice')]").click()
    model?.let { wizard.x("//div[@class='JBList']").waitOneText(it).click() }
}

/**
 * Walks the wizard titled [title] page by page, pressing Next, then Create or Finish, and on the page showing the chooser
 * runs [atChooser] once. No page may show an SDK combo: one is a second ask, preselecting what it shows. True when the
 * wizard asked with the chooser.
 */
internal fun Driver.walkWizard(rows: Rows, row: String, title: String, atChooser: UiComponent.() -> Unit): Boolean {
    val wizard = wizard(title)
    var asked = false
    for (page in 1..10) {
        Thread.sleep(1_000)
        if (!wizard.present()) break
        val combo = wizard.comboBox(SDK_COMBO)
        if (combo.present()) {
            rows.check(row, "page $page asks with no SDK combo, here preselecting '${combo.getSelectedItem()}'", false)
        }
        if (!asked && wizard.chooserList().present()) {
            wizard.atChooser()
            asked = true
        }
        val next = wizard.x(NEXT)
        val finish = wizard.x(FINISH)
        val shown = wizard.texts()
        when {
            next.present() && next.isEnabled() -> next.click()
            finish.present() && finish.isEnabled() -> finish.click()
            else -> break
        }
        // A step's Next can run modal work first, such as the Mix import's deps tasks; a click before the next page shows
        // would land on it.
        val message = message(title)
        runCatching { waitFor("the next page", 5.minutes) { !wizard.present() || wizard.texts() != shown || message.present() } }
        if (message.present()) {
            rows.check(row, "page $page moves on with no message, here '${message.texts()}'", false)
            message.x(OK).click()
            break
        }
        if (wizard.present() && wizard.texts() == shown) {
            rows.check(row, "page $page moves on", false)
            break
        }
    }
    rows.check(row, "the wizard closed", closes(wizard))
    return asked
}

/**
 * The wizard titled [title] asks with the chooser, listing [elixir] paired with [erlang], picking nothing and offering no
 * Set Default; [elixir] is picked.
 */
internal fun Driver.asksWithTheChooser(rows: Rows, row: String, title: String, elixir: String, erlang: String) {
    val asked = walkWizard(rows, row, title) {
        rows.listsPairedWithNothingPicked(row, chooserList(), elixir, erlang)
        rows.check(row, "there is no Set Default", !x("//div[@class='JButton' and @visible_text='Set Default']").present())
        chooserList().pickEntry(elixir)
    }
    rows.check(row, "the wizard asks with the chooser", asked)
}

/** The text [wizard] shows, or none once it has closed. */
internal fun UiComponent.texts(): String = runCatching { getAllTexts().joinToString("") { it.text } }.getOrDefault("")

/** Whether [dialog] closes within [timeout]. */
private fun closes(dialog: UiComponent, timeout: Duration = 1.minutes): Boolean =
    appears("the dialog to close", timeout) { !dialog.present() }

/** The project open at [path], once it is open and indexed. */
internal fun Driver.waitForProject(path: Path) {
    val opened = waitNotNull("$path to open", 2.minutes) { getOpenProjects().firstOrNull { Path.of(it.getBasePath()) == path } }
    waitForIndicators(opened, 5.minutes)
}

private fun Driver.wizard(title: String) = ui.dialog("//div[contains(@title, '$title')]")

/** A message over the wizard titled [title], such as the error a step's refusal shows. */
private fun Driver.message(title: String) =
    ui.dialog("//div[@class='MyDialog' and not(contains(@title, '$title')) and .//div[@class='JButton' and @visible_text='OK'] and not(.//div[@class='JButton' and @visible_text='Cancel'])]")

private const val CHOOSE_IMPORT = "Select File or Directory to Import"
private const val SDK_COMBO = "//div[@class='JdkComboBox']"
private const val NEXT = "//div[@class='JButton' and @visible_text='Next']"
private const val OK = "//div[@class='JButton' and @visible_text='OK']"
private const val FINISH = "//div[@class='JButton' and (@visible_text='Create' or @visible_text='Finish')]"
