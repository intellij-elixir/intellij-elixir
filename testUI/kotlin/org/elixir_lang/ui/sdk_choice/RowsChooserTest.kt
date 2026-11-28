package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import org.elixir_lang.ui.framework.core.chooser
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.isSdk
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_choice.RowsTable.BROKEN
import org.elixir_lang.ui.sdk_choice.RowsTable.JDK
import org.elixir_lang.ui.sdk_choice.RowsTable.JDK_TYPE
import org.elixir_lang.ui.sdk_choice.RowsTable.NEW
import org.elixir_lang.ui.sdk_choice.RowsTable.OLD
import org.elixir_lang.ui.sdk_settings.ERROR_OUTLINE
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * On IntelliJ IDEA the chooser is a Project SDK row and a row per Elixir module, each an SDK field over one model. The
 * Project SDK row offers the project's Elixir SDK, else the newest working one, and the module rows' "Project SDK"
 * follows it before OK. A JDK project SDK is kept by "Keep <JDK>", and a module that isn't an Elixir module has a row
 * offering only "Project SDK". OK writes only the changed rows, and refuses a changed row holding a broken SDK; Cancel
 * writes nothing. A folder mise pins has its row offer mise's entry, made into an SDK at OK only, with mise's boxes
 * showing the project's settings and written as shown. File > Open's chooser stays until it is answered.
 *
 * The mise tests stub mise with a shell script first on the IDE's PATH, so they run on Linux.
 */
class RowsChooserTest {
    private val rows = Rows()

    @Test
    fun `File Open's chooser stays open, and the module rows follow the Project SDK row`() {
        launchIdea("RowsChooserTest-1", configure = { RowsTable.seed(it) }) { _ ->
            row(rows, OPEN) {
                fileOpenStaysOpen(rows, OPEN, "open_ok", close = "OK")
                rows.check(OPEN, "OK writes the Project SDK row's offer '$NEW': '${projectSdk()}'", appears("the project SDK", 10.seconds) { projectSdk() == NEW })
            }

            val frame = openStaged(stageModules(HUB, hubModules()))
            row(rows, OFFERS) {
                rows.check(OFFERS, "Choose Elixir SDK... shows the chooser", openChooser(frame))
                chooser().apply {
                    waitUntilReady()
                    rows.check(OFFERS, "a Project SDK row", hasRow(PROJECT_ROW))
                    val offered = rowText(PROJECT_ROW)
                    rows.check(OFFERS, "the Project SDK row offers the newest working Elixir '$NEW': '$offered'", isSdk(NEW)(offered))
                    for (module in MODULES) {
                        rows.check(OFFERS, "a row for $module", hasRow(module))
                        val text = rowText(module)
                        rows.check(OFFERS, "$module's row offers '$NEW' as the Project SDK: '$text'", showsProjectSdk(text, NEW))
                    }
                    rows.check(OFFERS, "OK is enabled with the offers", okButton.isEnabled())
                }
            }
            row(rows, FOLLOW) {
                chooser().apply {
                    val items = rowItems("beta")
                    rows.check(FOLLOW, "beta's popup lists Project SDK first: $items", items.firstOrNull()?.let(::isProjectSdk) == true)
                    pickInRow("alpha", isSdk(OLD))
                    pickInRow(PROJECT_ROW, isSdk(OLD))
                    followed(OLD)
                    pickInRow(PROJECT_ROW, isSdk(NEW))
                    followed(NEW)
                    clickButtonText("OK")
                }
                rows.check(FOLLOW, "OK closes the chooser", chooserCloses())
                rows.check(FOLLOW, "the project SDK is '$NEW': '${projectSdk()}'", appears("the project SDK", 10.seconds) { projectSdk() == NEW })
                rows.check(FOLLOW, "alpha has '$OLD' as its own: '${moduleSdk("alpha")}'", hasOwnSdk("alpha", OLD))
                for (module in listOf(HUB, "beta")) rows.check(FOLLOW, "$module inherits", moduleInheritsSdk(module))
            }
        }
        rows.assertAllHeld()
    }

    /** hub's and beta's rows show [sdk] as the Project SDK, with no click in them; alpha's keeps its own [OLD]. */
    private fun UiComponent.followed(sdk: String) {
        for (module in listOf(HUB, "beta")) {
            val text = rowText(module)
            rows.check(FOLLOW, "$module's row shows '$sdk' as the Project SDK: '$text'", showsProjectSdk(text, sdk))
        }
        val alpha = rowText("alpha")
        rows.check(FOLLOW, "alpha's row keeps '$OLD': '$alpha'", isSdk(OLD)(alpha))
    }

    @Test
    fun `over a JDK Cancel writes nothing, a changed broken row refuses OK, and OK writes only the changed rows`() {
        val gamma = StagedModule("gamma", "apps/gamma", moduleIml("ELIXIR_MODULE", sdk = ModuleSdkEntry.Own(BROKEN)))
        val tools = StagedModule("tools", "tools", moduleIml("JAVA_MODULE"))
        // A module of a type this IDE doesn't know, as a plugin that is off leaves it.
        val py = StagedModule("py", "py", moduleIml("OTHER_MODULE", "<configuration />"))
        val web = StagedModule("web", "web", moduleIml("JAVA_MODULE", "<configuration />"))
        val modules = hubModules(
            StagedModule("alpha", "apps/alpha", moduleIml("ELIXIR_MODULE", sdk = ModuleSdkEntry.Own(NEW))),
            StagedModule("beta", "apps/beta", moduleIml("ELIXIR_MODULE", sdk = ModuleSdkEntry.None)),
            gamma, tools, py, web,
        )
        launchIdea("RowsChooserTest-2", configure = { RowsTable.seed(it, jdk = true) }) { _ ->
            val project = stageModules(HUB, modules, projectSdk = JDK to JDK_TYPE)
            val frame = openStaged(project)
            val before = savedImls(project, modules)
            // Each module's SDK as the model holds it, which a write changes at once, before any save.
            fun sdkOf(module: StagedModule) = moduleSdk(module.name) to moduleInheritsSdk(module.name)
            val sdksBefore = modules.associateWith(::sdkOf)

            row(rows, OVER_JDK) {
                val closed = cancelChooser(frame) {
                    val offered = rowText(PROJECT_ROW)
                    rows.check(OVER_JDK, "the Project SDK row offers '$NEW' over the JDK: '$offered'", isSdk(NEW)(offered))
                    val items = rowItems(PROJECT_ROW)
                    rows.check(OVER_JDK, "its popup lists 'Keep $JDK' first: $items", items.firstOrNull()?.startsWith("Keep $JDK") == true)
                    for (module in listOf(HUB, "beta")) {
                        val text = rowText(module)
                        rows.check(OVER_JDK, "$module offers '$NEW' as the Project SDK: '$text'", showsProjectSdk(text, NEW))
                    }
                    rows.check(NOT_ELIXIR, "tools, a Java module with no Elixir facet, has no row", !hasRow("tools"))
                    for (module in listOf("py", "web")) {
                        rows.check(NOT_ELIXIR, "$module has a row", hasRow(module))
                        val listed = rowItems(module)
                        rows.check(NOT_ELIXIR, "$module's row lists only Project SDK: $listed", listed.singleOrNull()?.let(::isProjectSdk) == true)
                    }
                }
                rows.check(CANCEL, "Cancel closes the chooser", closed)
                rows.check(CANCEL, "Cancel writes no project SDK: '${projectSdk()}'", projectSdk() == JDK)
                for (module in modules) {
                    val sdk = sdkOf(module)
                    rows.check(CANCEL, "Cancel leaves ${module.name}'s SDK and inheritance as they were: $sdk", sdk == sdksBefore.getValue(module))
                }
                val after = savedImls(project, modules)
                for (module in modules) rows.check(CANCEL, "Cancel leaves ${module.name}'s .iml unchanged", after.getValue(module) == before.getValue(module))
            }

            row(rows, REFUSED) {
                inChooser(frame) {
                    // The outline comes with the dialog's first validation, which can follow the chooser's showing.
                    rows.check(REFUSED, "gamma, broken and unchanged, is outlined", appears("gamma's outline", 10.seconds) { rowOutline("gamma") == ERROR_OUTLINE })
                    rows.check(REFUSED, "OK is allowed with gamma unchanged", okButton.isEnabled())
                    pickInRow("beta", isSdk(BROKEN))
                    rows.check(REFUSED, "beta, changed to '$BROKEN', is outlined", appears("beta's outline", 10.seconds) { rowOutline("beta") == ERROR_OUTLINE })
                    rows.check(REFUSED, "and OK is refused", appears("OK refused", 10.seconds) { !okButton.isEnabled() })
                    pickInRow("beta", ::isProjectSdk)
                    rows.check(REFUSED, "with beta on Project SDK, OK is allowed", appears("OK allowed", 10.seconds) { okButton.isEnabled() })
                }
            }

            row(rows, CHANGED_ONLY) {
                chooser().apply {
                    pickInRow(PROJECT_ROW, isSdk(OLD))
                    rows.check(CHANGED_ONLY, "beta offers '$OLD' as the Project SDK", showsProjectSdk(rowText("beta"), OLD))
                    // Back on "Project SDK", the value it opened on, beta is unchanged; an own SDK changes it.
                    pickInRow("beta", isSdk(NEW))
                    clickButtonText("OK")
                }
                rows.check(CHANGED_ONLY, "OK closes the chooser", chooserCloses())
                rows.check(CHANGED_ONLY, "the project SDK is '$OLD': '${projectSdk()}'", appears("the project SDK", 10.seconds) { projectSdk() == OLD })
                rows.check(CHANGED_ONLY, "beta has '$NEW' as its own: '${moduleSdk("beta")}'", hasOwnSdk("beta", NEW))
                rows.check(CHANGED_ONLY, "hub inherits '$OLD'", moduleInheritsSdk(HUB) && moduleSdk(HUB) == OLD)
                rows.check(CHANGED_ONLY, "tools keeps the JDK as its own: '${moduleSdk("tools")}'", hasOwnSdk("tools", JDK))
                val beta = modules.first { it.name == "beta" }
                checkOnlyChanged(rows, CHANGED_ONLY, project, modules, beta, before, unchanged = modules.filter { it.name in listOf("alpha", "gamma") })
            }
        }
        rows.assertAllHeld()
    }

    @Test
    fun `with no Elixir SDK the Project SDK row shows No Project SDK`() {
        launchIdea("RowsChooserTest-2b") { _ ->
            val frame = openStaged(stageModules(HUB, hubModules()))
            row(rows, NO_ELIXIR) {
                val closed = cancelChooser(frame) {
                    val offered = rowText(PROJECT_ROW)
                    rows.check(NO_ELIXIR, "the Project SDK row shows '$NO_PROJECT_SDK': '$offered'", isNoProjectSdk(offered))
                    val items = rowItems("beta")
                    rows.check(NO_ELIXIR, "beta's row lists no Project SDK: $items", items.none { isProjectSdk(it) || isNoProjectSdk(it) })
                }
                rows.check(NO_ELIXIR, "Cancel closes the chooser", closed)
            }
        }
        rows.assertAllHeld()
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a pinned folder's row offers mise's entry, made into an SDK at OK only`() {
        launchIdea("RowsChooserTest-7", { withEnv("PATH", stubbedPath()) }, configure = { RowsTable.seed(it) }) { _ ->
            val install = stageElixirInstall()
            val pin = pinsAt(install)
            val project = stageModules(HUB, hubModules())
            stubMiseByFolder(emptyMap())
            val frame = openStaged(project)

            row(rows, LATE) {
                stubMiseByFolder(mapOf(project.resolve("apps/alpha") to pin, project.resolve("apps/beta") to newPins()), delaySeconds = DELAY)
                cancelChooser(frame) {
                    rows.check(LATE, "alpha shows no entry while mise is asked: '${rowText("alpha")}'", !isMiseEntry(rowText("alpha")))
                    rows.check(LATE, "beta is changed before mise answers its folder", !miseAnswered(project.resolve("apps/beta")))
                    pickInRow("beta", isSdk(OLD))
                    rows.check(LATE, "once mise answers, alpha, unchanged, holds its entry", appears("alpha's entry", 60.seconds) { isMiseEntry(rowText("alpha")) })
                    // beta's folder is answered with alpha's, and a changed row still lists its entry.
                    rows.check(LATE, "beta's row lists its entry once mise answers", appears("beta's entry", 30.seconds) { rowItems("beta").any(::isMiseEntry) })
                    val beta = rowText("beta")
                    rows.check(LATE, "beta, changed first, keeps the user's '$OLD': '$beta'", isSdk(OLD)(beta))
                }
            }

            stubMiseByFolder(mapOf(project.resolve("apps/alpha") to pin))
            row(rows, PINNED) {
                cancelChooser(frame) {
                    val entry = miseEntry(pin.elixirVersion, pin.erlangVersion)
                    rows.check(PINNED, "alpha's row holds '$entry': '${rowText("alpha")}'", appears("alpha's entry", 30.seconds) { holds("alpha", entry) })
                    rows.check(PINNED, "beta has no entry", !isMiseEntry(rowText("beta")) && rowItems("beta").none(::isMiseEntry))
                    rows.check(PINNED, "the Project SDK row has no entry", rowItems(PROJECT_ROW).none(::isMiseEntry))
                }
            }

            row(rows, AT_OK) {
                cancelChooser(frame) {
                    appears("alpha's entry", 30.seconds) { isMiseEntry(rowText("alpha")) }
                    rows.check(AT_OK, "no SDK on mise's install while the chooser is open: ${driver.sdksAt(install)}", driver.sdksAt(install).isEmpty())
                }
                rows.check(AT_OK, "Cancel makes none: ${sdksAt(install)}", sdksAt(install).isEmpty())

                okChooser(frame, AT_OK) { appears("alpha's entry", 30.seconds) { isMiseEntry(rowText("alpha")) } }
                val made = sdksAt(install)
                rows.check(AT_OK, "one SDK on mise's install: $made", made.size == 1)
                rows.check(AT_OK, "alpha has it as its own: '${moduleSdk("alpha")}'", hasOwnSdk("alpha", made.singleOrNull()))
            }
        }
        rows.assertAllHeld()
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `each folder's pins reach its own row`() {
        launchIdea("RowsChooserTest-8", { withEnv("PATH", stubbedPath()) }, configure = { RowsTable.seed(it) }) { _ ->
            val install = stageElixirInstall()
            val pin = pinsAt(install)
            val project = stageModules(HUB, hubModules())
            stubMiseByFolder(emptyMap())
            val frame = openStaged(project)

            row(rows, DIFFERENT) {
                val betaPin = newPins().run { copy(elixirVersion = "$elixirVersion-otp-${erlangVersion.substringBefore('.')}") }
                stubMiseByFolder(mapOf(project.resolve("apps/alpha") to pin, project.resolve("apps/beta") to betaPin))
                val alphaEntry = miseEntry(pin.elixirVersion, pin.erlangVersion)
                val betaEntry = miseEntry(betaPin.elixirVersion, betaPin.erlangVersion)
                cancelChooser(frame) {
                    val both = appears("both entries", 60.seconds) { isMiseEntry(rowText("alpha")) && isMiseEntry(rowText("beta")) }
                    rows.check(DIFFERENT, "alpha holds '$alphaEntry': '${rowText("alpha")}'", both && holds("alpha", alphaEntry))
                    rows.check(DIFFERENT, "beta holds '$betaEntry': '${rowText("beta")}'", both && holds("beta", betaEntry))
                    rows.check(DIFFERENT, "the Project SDK row has no entry", rowItems(PROJECT_ROW).none(::isMiseEntry))
                }
            }

            row(rows, SAME) {
                stubMiseByFolder(listOf("", "apps/alpha", "apps/beta").associate { project.resolve(it).normalize() to pin })
                val entry = miseEntry(pin.elixirVersion, pin.erlangVersion)
                okChooser(frame, SAME) {
                    rows.check(SAME, "the Project SDK row holds '$entry': '${rowText(PROJECT_ROW)}'", appears("the Project SDK row's entry", 60.seconds) { holds(PROJECT_ROW, entry) })
                    for (module in MODULES) rows.check(SAME, "$module's row holds its own folder's entry: '${rowText(module)}'", holds(module, entry))
                }
                val made = sdksAt(install)
                rows.check(SAME, "one SDK on mise's install: $made", made.size == 1)
                rows.check(SAME, "the project SDK is it: '${projectSdk()}'", projectSdk() == made.singleOrNull())
                for (module in MODULES) rows.check(SAME, "$module inherits", moduleInheritsSdk(module))
            }
        }
        rows.assertAllHeld()
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `an Erlang pin that isn't installed is said, an install with SDKs on it offers no entry, and the boxes are written as shown`() {
        launchIdea("RowsChooserTest-11", { withEnv("PATH", stubbedPath()) }, configure = { RowsTable.seed(it) }) { _ ->
            val install = stageElixirInstall()
            val project = stageModules(HUB, hubModules())
            val alpha = project.resolve("apps/alpha")
            stubMiseByFolder(emptyMap())
            val frame = openStaged(project)

            row(rows, UNINSTALLED) {
                stubMiseByFolder(mapOf(alpha to pinsAt(install).withMissingErlang()))
                cancelChooser(frame) {
                    val said = "alpha: mise pins Erlang $MISSING_ERLANG, which isn't installed"
                    rows.check(UNINSTALLED, "the chooser says '$said'", appears("the line", 30.seconds) { line(said).present() })
                    rows.check(UNINSTALLED, "alpha's row has no entry", rowItems("alpha").none(::isMiseEntry))
                }
            }

            row(rows, SEVERAL) {
                // NEW's install has NEW and BROKEN on it, and no Erlang pin that registers.
                stubMiseByFolder(mapOf(alpha to newPins().withMissingErlang()))
                cancelChooser(frame) {
                    val before = rowText("alpha")
                    // The answer comes with no sign in the row: wait for the stub to give it, then for the chooser to take it.
                    rows.check(SEVERAL, "mise answers alpha's folder", appears("mise's answer for alpha", 30.seconds) { miseAnswered(alpha) })
                    Thread.sleep(1_000)
                    val items = rowItems("alpha")
                    rows.check(SEVERAL, "alpha's row lists no entry: $items", items.none(::isMiseEntry))
                    rows.check(SEVERAL, "alpha's row is as it opened: '${rowText("alpha")}'", rowText("alpha") == before)
                    // The row's own label reads "alpha:", so the line is looked for by what it says.
                    rows.check(SEVERAL, "nothing is said under the rows", !line("mise pins").present())
                }
            }

            row(rows, BOXES) {
                stubMiseByFolder(mapOf(alpha to pinsAt(install)))
                cancelChooser(frame) {
                    appears("alpha's entry", 30.seconds) { isMiseEntry(rowText("alpha")) }
                    rows.check(BOXES, "the boxes show with mise's entry held, each as Tool Managers has it, off", boxes() == Triple(true, false, false))
                    pickInRow("alpha", isSdk(OLD))
                    rows.check(BOXES, "with no entry held the boxes hide", appears("the boxes hidden", 10.seconds) { !inStepBox().shows() })
                    pickInRow("alpha", ::isMiseEntry)
                    inStepBox().check()
                    environmentBox().check()
                }
                rows.check(BOXES, "Cancel adopts nothing", toolManagersShowMise() == (false to false))

                okWithTheBoxes(frame) {
                    inStepBox().check()
                    environmentBox().check()
                }
                rows.check(BOXES, "OK with both ticked adopts both", toolManagersShowMise() == (true to true))

                okWithTheBoxes(frame) {
                    rows.check(BOXES, "with mise on, both boxes show ticked", boxes() == Triple(true, true, true))
                    inStepBox().uncheck()
                }
                rows.check(BOXES, "OK with the first unticked turns mise off", !toolManagersShowMise().first)
            }
        }
        rows.assertAllHeld()
    }

    /** The chooser over [frame], once ready, with [body] done in it. */
    private fun Driver.inChooser(frame: IdeaFrameUI, body: DialogUiComponent.() -> Unit) {
        openChooser(frame)
        chooser().apply {
            waitUntilReady()
            body()
        }
    }

    /** The chooser over [frame], once ready, [body] done in it, and Cancel; whether it closes. */
    private fun Driver.cancelChooser(frame: IdeaFrameUI, body: DialogUiComponent.() -> Unit): Boolean {
        inChooser(frame) {
            body()
            clickButtonText("Cancel")
        }
        return chooserCloses()
    }

    /** The chooser over [frame], once ready, [body] done in it, and OK, which [row] checks closes it. */
    private fun Driver.okChooser(frame: IdeaFrameUI, row: String, body: DialogUiComponent.() -> Unit) {
        inChooser(frame) {
            body()
            clickButtonText("OK")
        }
        rows.check(row, "OK closes the chooser", chooserCloses(60.seconds))
    }

    /** The chooser over [frame] with mise's entry picked in alpha's row, [boxes] done with the boxes, and OK. */
    private fun Driver.okWithTheBoxes(frame: IdeaFrameUI, boxes: UiComponent.() -> Unit) = okChooser(frame, BOXES) {
        // OK gave alpha the SDK mise's pins made, so the row opens on it and lists the entry beside it.
        pickInRow("alpha", ::isMiseEntry)
        boxes()
    }

    /** Whether the boxes show, and whether each is ticked. */
    private fun UiComponent.boxes(): Triple<Boolean, Boolean, Boolean> {
        appears("the boxes", 10.seconds) { inStepBox().shows() }
        return Triple(inStepBox().shows(), inStepBox().isSelected(), environmentBox().isSelected())
    }

    /** A folder's pins: the Elixir at [home], as [version], and the Erlang the table's [RowsTable.ERLANG] is at, installed. */
    private fun pinsAt(home: String, version: String = Path.of(home).fileName.toString()) =
        Pins(version, home, RowsTable.erlangVersion, RowsTable.erlangHome)

    /** The pins of [NEW]'s install. */
    private fun newPins() = pinsAt(RowsTable.newHome, RowsTable.newVersion)

    /** Where mise would put the Erlang it pins but hasn't installed. */
    private fun missingErlang(): String = Path.of(RowsTable.oldHome).resolveSibling("erlang-$MISSING_ERLANG").toString()

    /** These pins, with the Erlang pinned [MISSING_ERLANG], which isn't installed. */
    private fun Pins.withMissingErlang() = copy(erlangVersion = MISSING_ERLANG, erlangHome = missingErlang(), erlangInstalled = false)

    private companion object {
        val MODULES = listOf(HUB, "alpha", "beta")
        const val DELAY = 8
        const val OPEN = "File > Open's chooser"
        const val OFFERS = "the rows' offers"
        const val FOLLOW = "the module rows follow the Project SDK row"
        const val OVER_JDK = "the offers over a JDK"
        const val NOT_ELIXIR = "modules that aren't Elixir's"
        const val CANCEL = "Cancel writes nothing"
        const val REFUSED = "a changed broken row refuses OK"
        const val CHANGED_ONLY = "OK writes only the changed rows"
        const val NO_ELIXIR = "no Elixir SDK"
        const val LATE = "mise's late answer"
        const val PINNED = "a pinned folder's row"
        const val AT_OK = "mise's SDK made at OK"
        const val DIFFERENT = "different pins per folder"
        const val SAME = "one pin in every folder"
        const val UNINSTALLED = "an Erlang pin that isn't installed"
        const val SEVERAL = "an install with several SDKs"
        const val BOXES = "mise's boxes"
        const val MISSING_ERLANG = "28.0"
    }
}
