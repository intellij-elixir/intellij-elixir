package org.elixir_lang.status_bar_widget

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import org.elixir_lang.sdk.choice.RecordedChooser
import org.elixir_lang.sdk.choice.moduleEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.SimpleJavaSdkType
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.junit.onPooledThread
import java.util.concurrent.Callable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.Type
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.sdk
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import org.elixir_lang.tool_manager.NotInstalled
import org.elixir_lang.tool_manager.ToolEntry
import org.elixir_lang.tool_manager.ToolManagerResult
import org.elixir_lang.tool_manager.ToolManagerVersions
import org.elixir_lang.sdk.elixir.SettingsPage
import org.elixir_lang.tool_manager.ModuleSdkIssue
import org.elixir_lang.tool_manager.SdkVersionRow
import org.elixir_lang.tool_manager.SdkVersionTable

/**
 * Tests for [ElixirEditorBasedSdkWidget] detection logic (notification scan methods).
 *
 * These tests exercise the project-wide background notification helpers directly:
 * [ElixirEditorBasedSdkWidget.detectModuleSdkIssues] and
 * [ElixirEditorBasedSdkWidget.findModuleLevelElixirSdk].
 *
 * Tests scenarios from the project-sdk-fixes plan (adapted for the new widget class):
 * 1a. Project SDK = Java, module SDK = Elixir -> no NotConfigured, no mismatch
 * 1b. Project SDK = Java, module SDK = Elixir -> no mismatch issues
 * 2.  Project SDK = null, module SDK = Elixir -> no NotConfigured
 * 3.  Project SDK = Elixir A, module SDK = Elixir B -> mismatch reported
 * 5.  No Elixir modules -> widget factory isAvailable() = false
 * 6.  Small IDE: stale Facet SDK reference, Elixir SDKs exist -> dangling issue
 * 7.  Small IDE: Facet SDK resolves correctly -> no issue
 * 8.  Small IDE: stale Facet SDK reference, no Elixir SDKs in table -> no dangling (NotConfigured)
 */
class ElixirSdkStatusWidgetTest : PlatformTestCase() {

    private lateinit var testScope: CoroutineScope

    override fun setUp() {
        super.setUp()
        testScope = CoroutineScope(SupervisorJob())
        ensureElixirFacet()
    }

    override fun tearDown() {
        try {
            ProcessOutput.isSmallIdeOverride = null
            testScope.cancel("test tearDown")
            WriteAction.run<Throwable> {
                ProjectRootManager.getInstance(project).projectSdk = null
            }
        } finally {
            super.tearDown()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun ensureElixirFacet() {
        val facetManager = FacetManager.getInstance(module)
        if (facetManager.getFacetByType(Facet.ID) == null) {
            FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
        }
    }

    private fun removeElixirFacet() {
        val facetManager = FacetManager.getInstance(module)
        val facet = facetManager.getFacetByType(Facet.ID) ?: return
        ApplicationManager.getApplication().runWriteAction {
            val model = facetManager.createModifiableModel()
            model.removeFacet(facet)
            model.commit()
        }
    }

    private fun createAndRegisterElixirSdk(name: String): Sdk {
        val sdk = ProjectJdkImpl(name, ElixirSdkType.instance)
        // Removed with testRootDisposable, after the module stops naming it.
        WriteAction.run<Throwable> {
            ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable)
        }
        return sdk
    }

    private fun createJavaSdk(): Sdk {
        val javaHome = System.getProperty("java.home") ?: "/usr/lib/jvm/java"
        return SimpleJavaSdkType().createJdk("Java Mock", javaHome)
    }

    private fun setModuleSdk(sdk: Sdk) {
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
    }

    private fun setProjectSdk(sdk: Sdk?) {
        WriteAction.run<Throwable> {
            ProjectRootManager.getInstance(project).projectSdk = sdk
        }
    }

    private fun setFacetSdk(sdk: Sdk) {
        val facetManager = FacetManager.getInstance(module)
        val facet = facetManager.getFacetByType(Facet.ID) ?: error("No Elixir Facet on module")
        ApplicationManager.getApplication().runWriteAction {
            facet.sdk = sdk
        }
    }

    private fun createWidget(): ElixirEditorBasedSdkWidget = ElixirEditorBasedSdkWidget(project, testScope)

    /** Calls [ElixirEditorBasedSdkWidget.detectModuleSdkIssues], which asserts it runs off the EDT. */
    private fun ElixirEditorBasedSdkWidget.detectModuleSdkIssuesInTest(): List<ModuleSdkIssue> =
        onPooledThread {
            ApplicationManager.getApplication().runReadAction<List<ModuleSdkIssue>> {
                detectModuleSdkIssues()
            }
        }

    // -------------------------------------------------------------------------
    // Scenario 1: Project SDK = Java, module SDK = Elixir
    // -------------------------------------------------------------------------

    @RequiresReadLock
    fun testModuleElixirSdkFoundWhenProjectSdkIsJava() {
        val elixirSdk = createAndRegisterElixirSdk("Elixir Mock")
        val javaSdk = createJavaSdk()

        setProjectSdk(javaSdk)
        setModuleSdk(elixirSdk)

        val widget = createWidget()
        val foundSdk = ReadAction.nonBlocking(Callable { widget.findModuleLevelElixirSdk() }).executeSynchronously()

        assertNotNull("Should find Elixir SDK from module even when project SDK is Java", foundSdk)
        assertEquals("Should return the module's Elixir SDK", elixirSdk, foundSdk)
    }

    fun testNoMismatchWhenProjectSdkIsJavaAndModuleSdkIsElixir() {
        val elixirSdk = createAndRegisterElixirSdk("Elixir Mock")
        val javaSdk = createJavaSdk()

        setProjectSdk(javaSdk)
        setModuleSdk(elixirSdk)

        val widget = createWidget()
        val issues = widget.detectModuleSdkIssuesInTest()

        assertTrue(
            "No module SDK issues should be reported when project SDK is non-Elixir; got: $issues",
            issues.isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Project SDK = null, module SDK = Elixir
    // -------------------------------------------------------------------------

    @RequiresReadLock
    fun testModuleElixirSdkFoundWhenProjectSdkIsNull() {
        val elixirSdk = createAndRegisterElixirSdk("Elixir Mock")

        setProjectSdk(null)
        setModuleSdk(elixirSdk)

        val widget = createWidget()
        val foundSdk = ReadAction.nonBlocking(Callable { widget.findModuleLevelElixirSdk() }).executeSynchronously()

        assertNotNull("Should find Elixir SDK from module when project SDK is null", foundSdk)
        assertEquals(elixirSdk, foundSdk)
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Project SDK = Elixir A, module SDK = Elixir B -> mismatch reported
    // -------------------------------------------------------------------------

    fun testMismatchReportedWhenModuleSdkDiffersFromElixirProjectSdk() {
        val elixirSdkA = createAndRegisterElixirSdk("Elixir 1.17")
        val elixirSdkB = createAndRegisterElixirSdk("Elixir 1.16")

        setProjectSdk(elixirSdkA)
        setModuleSdk(elixirSdkB)

        val widget = createWidget()
        val issues = widget.detectModuleSdkIssuesInTest()

        assertTrue(
            "Expected a mismatch issue (project=Elixir 1.17, module=Elixir 1.16); got: $issues",
            issues.any { !it.isDangling && it.moduleName == module.name }
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 5: No Elixir modules -> widget factory isAvailable = false
    // -------------------------------------------------------------------------

    fun testWidgetFactoryNotAvailableWhenNoElixirModules() {
        removeElixirFacet()

        val factory = ElixirSdkStatusWidgetFactory()
        assertFalse(
            "Widget should not be available when the project has no Elixir modules",
            factory.isAvailable(project)
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Small IDE - stale Facet SDK, SDKs exist -> dangling issue
    // -------------------------------------------------------------------------

    @RequiresEdt
    fun testDanglingFacetSdkReportedWhenElixirSdkExistsButFacetReferenceIsStale() {
        ProcessOutput.isSmallIdeOverride = true
        val staleSdk = createAndRegisterElixirSdk("Elixir 1.17")
        setFacetSdk(staleSdk)

        WriteAction.run<Throwable> {
            ProjectJdkTable.getInstance().removeJdk(staleSdk)
        }

        // Register a different SDK so Facet.sdks().isNotEmpty() -> stale (not NotConfigured)
        createAndRegisterElixirSdk("Elixir 1.18")

        // Do NOT call setModuleSdk() - no JdkOrderEntry, simulating Small IDE path

        val widget = createWidget()
        val issues = widget.detectModuleSdkIssuesInTest()

        assertTrue(
            "Expected a dangling issue for stale Facet SDK reference; got: $issues",
            issues.any { it.isDangling && it.moduleName == module.name }
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Small IDE - Facet SDK resolves correctly -> no issue
    // -------------------------------------------------------------------------

    fun testNoDanglingWhenFacetSdkResolvesCorrectly() {
        ProcessOutput.isSmallIdeOverride = true
        val registeredSdk = createAndRegisterElixirSdk("Elixir 1.17")
        setFacetSdk(registeredSdk)

        val widget = createWidget()
        val issues = widget.detectModuleSdkIssuesInTest()

        assertTrue(
            "Expected no issues when Facet SDK resolves correctly; got: $issues",
            issues.isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Small IDE - stale Facet SDK, NO Elixir SDKs in table -> no dangling
    // -------------------------------------------------------------------------

    @RequiresEdt
    fun testNoDanglingWhenNoElixirSdksExistInTable() {
        ProcessOutput.isSmallIdeOverride = true
        val sdk = createAndRegisterElixirSdk("Elixir 1.17")
        setFacetSdk(sdk)

        WriteAction.run<Throwable> {
            ProjectJdkTable.getInstance().removeJdk(sdk)
        }

        assertTrue("Precondition: no Elixir SDKs in table", Facet.sdks().isEmpty())

        val widget = createWidget()
        val issues = widget.detectModuleSdkIssuesInTest()

        assertTrue(
            "Expected no dangling issue when no Elixir SDKs exist (this is NotConfigured); got: $issues",
            issues.isEmpty()
        )
    }

    /** A project opened in two IDEs: a small IDE set the facet, and IntelliJ IDEA's module SDK is gone. */
    fun testIntelliJIdeaReportsABrokenModuleSdkBesideAFacetSdk() {
        ProcessOutput.isSmallIdeOverride = false
        setFacetSdk(createAndRegisterElixirSdk("Elixir 1.19.5"))
        setMissingModuleSdk("Elixir set in IntelliJ IDEA")

        val issues = createWidget().detectModuleSdkIssuesInTest()

        assertTrue(
            "Project Structure's module SDK is the one IntelliJ IDEA uses; got: $issues",
            issues.any { it.isDangling && it.missingSdkName == "Elixir set in IntelliJ IDEA" },
        )
    }

    fun testAModuleSdkMissingFromTheTableIsReportedWithoutAFacetSdk() {
        createAndRegisterElixirSdk("Elixir 1.19.5")
        setMissingModuleSdk("Elixir set in another IDE")

        val issues = createWidget().detectModuleSdkIssuesInTest()

        assertTrue(
            "nothing else supplies an SDK, so code insight has none; got: $issues",
            issues.any { it.isDangling && it.missingSdkName == "Elixir set in another IDE" },
        )
    }

    fun testTheMissingSdkAdviceNamesTheSettingsPageWhereThereIsNoProjectStructure() {
        val message = StringUtil.unescapeXmlEntities(ElixirEditorBasedSdkWidget.danglingMessage(listOf("my_app"), listOf("Elixir 1.20.4"), "Settings"))

        assertTrue(message, message.contains("Settings -> Languages & Frameworks -> Elixir"))
        assertFalse(message, message.contains("Project Structure"))
    }

    fun testTheAdviceForAFacetWithNoSdkNamesNoSdk() {
        val message = StringUtil.unescapeXmlEntities(ElixirEditorBasedSdkWidget.danglingMessage(listOf("my_app"), emptyList(), "Settings"))

        assertTrue(message, message.startsWith("Module 'my_app' has no Elixir SDK."))
    }

    fun testTheNoteForAnUninstalledPinNamesItAndTheCommand() {
        val note = ElixirEditorBasedSdkWidget.notInstalledNote("my_app", listOf(NotInstalled("mise", "Erlang", "28.1.2")))

        assertTrue(note, note.contains("mise's Erlang 28.1.2 is not installed"))
        assertTrue(note, note.contains("mise install"))
        assertTrue(note, note.contains("my_app"))
    }

    fun testAnIdenticalNoticeIsTheSameNotice() {
        assertTrue(
            ElixirEditorBasedSdkWidget.isSameNotice(
                notice("text", "Open Settings"), emptyMap(),
                notice("text", "Open Settings"), emptyMap(),
            )
        )
    }

    fun testANoticeWithOtherTextOrActionsIsNotTheSameNotice() {
        assertFalse(
            ElixirEditorBasedSdkWidget.isSameNotice(
                notice("text", "Open Settings"), emptyMap(),
                notice("other", "Open Settings"), emptyMap(),
            )
        )
        assertFalse(
            ElixirEditorBasedSdkWidget.isSameNotice(
                notice("text", "Open Settings"), emptyMap(),
                notice("text", "Configure from mise", "Open Settings"), emptyMap(),
            )
        )
    }

    /** Its text can stay the same while the tool manager's choice changes, which Configure would then apply. */
    fun testANoticeThatWouldConfigureAnotherSdkIsNotTheSameNotice() {
        assertFalse(
            ElixirEditorBasedSdkWidget.isSameNotice(
                notice("text", "Configure from mise"), mapOf("my_app" to listOf("/elixir/1.17.3", "/erlang/27.3")),
                notice("text", "Configure from mise"), mapOf("my_app" to listOf("/elixir/1.18.4", "/erlang/27.3")),
            )
        )
    }

    /** Another module's assignment changing must not replace the notice, nor one of this module's be missed. */
    fun testANoticeConfiguresOnlyTheModulesItIsAbout() {
        val a = object : ToolManagerVersions {
            override val toolManagerName = "mise"
            override val elixir = ToolEntry("1.19.5-otp-28", "/elixir/a", true)
            override val erlang = null
        }
        val b = object : ToolManagerVersions {
            override val toolManagerName = "mise"
            override val elixir = ToolEntry("1.18.4-otp-27", "/elixir/b", true)
            override val erlang = null
        }
        val aOnly = SdkStatus.ModuleSdkError(null, null, listOf(ModuleSdkIssue("a", "references non-existent SDK 'x'", true)))

        assertEquals(setOf("a"), ElixirEditorBasedSdkWidget.configureAssignments(aOnly, mapOf("a" to a, "b" to b)).keys)
        assertEquals(
            "a module with no SDK at all is configured project-wide",
            setOf("a", "b"),
            ElixirEditorBasedSdkWidget.configureAssignments(SdkStatus.NotConfiguredToolManagerAvailable(a), mapOf("a" to a, "b" to b)).keys,
        )
    }

    fun testAModuleSdkNoticeOpensTheModulePageAndAnSdkNoticeTheSdksPage() {
        val elixirSdk = createAndRegisterElixirSdk("Elixir")
        val otpMismatch = SdkStatus.OtpMismatch(elixirSdk, elixirSdk, "1.19.5", "27", "28")

        assertEquals(SettingsPage.MODULE_SDKS, ElixirEditorBasedSdkWidget.settingsPageFor(SdkStatus.NotConfigured))
        assertEquals(
            SettingsPage.MODULE_SDKS,
            ElixirEditorBasedSdkWidget.settingsPageFor(SdkStatus.ModuleSdkError(null, null, emptyList())),
        )
        assertEquals("the Erlang pairing is set on the SDK", SettingsPage.SDKS, ElixirEditorBasedSdkWidget.settingsPageFor(otpMismatch))
    }

    private fun notice(content: String, vararg actions: String) =
        Notification("Elixir SDK", "Elixir SDK Error", content, NotificationType.ERROR).apply {
            for (action in actions) addAction(NotificationAction.createSimple(action) {})
        }

    /** The notice is HTML, so a raw `<module>` placeholder or a module name with `<` in it would vanish. */
    fun testTheMissingSdkAdviceIsEscapedForHtml() {
        val message = ElixirEditorBasedSdkWidget.danglingMessage(listOf("a<b", "c"), listOf("Elixir <1.20>"), "Project Structure")

        assertFalse(message, message.contains("<"))
        val text = StringUtil.unescapeXmlEntities(message)
        assertTrue(text, text.contains("Modules -> <module> -> Dependencies"))
        assertTrue(text, text.contains("'a<b'"))
        assertTrue(text, text.contains("'Elixir <1.20>'"))
    }

    fun testTheNotInstalledNoteEscapesWhatItNames() {
        val note = ElixirEditorBasedSdkWidget.notInstalledNote("a<b", listOf(NotInstalled("mise", "Erlang", "28.1 <rc>")))

        assertTrue(note, note.contains("a&lt;b"))
        assertTrue(note, note.contains("28.1 &lt;rc&gt;"))
    }

    /** A tool manager's description is its own HTML, such as mise's `<code>mise trust</code>`; only its name is text. */
    fun testAToolManagerErrorKeepsItsMarkupAndEscapesItsName() {
        val lines = ElixirEditorBasedSdkWidget.toolManagerErrorLines(
            listOf(ToolManagerResult.Error("a<b", "Run <code>mise trust</code> in the project directory."))
        )

        assertTrue(lines, lines.contains("Run <code>mise trust</code>"))
        assertTrue(lines, lines.contains("[a&lt;b]"))
    }

    fun testTheOtpMismatchNoticeEscapesTheSdkName() {
        val message = ElixirEditorBasedSdkWidget.otpMismatchMessage("Elixir <local>", "27", "28")

        assertFalse(message, message.contains("<"))
        assertTrue(message, StringUtil.unescapeXmlEntities(message).contains("'Elixir <local>'"))
    }

    fun testTheVersionTableEscapesWhatItNames() {
        val html = createWidget().buildSdkVersionTableHtml(
            SdkVersionTable(
                moduleName = "a<b",
                toolManagerName = "mise",
                rows = listOf(SdkVersionRow("Elixir", "Elixir <local>", "1.21 <rc>", isMismatch = true, isInstalled = false)),
            )
        )

        assertTrue(html, html.contains("a&lt;b"))
        assertTrue(html, html.contains("Elixir &lt;local&gt;"))
        assertTrue(html, html.contains("1.21 &lt;rc&gt;"))
    }

    fun testTheMissingSdkAdviceNamesTheModuleSdkInProjectStructure() {
        val message = StringUtil.unescapeXmlEntities(ElixirEditorBasedSdkWidget.danglingMessage(listOf("my_app"), listOf("Elixir 1.20.4"), "Project Structure"))

        assertTrue(message, message.contains("Project Structure -> Modules -> my_app -> Dependencies -> Module SDK"))
    }

    fun testTheOtpMismatchNoticeNamesTheSdkOnce() {
        val message = StringUtil.unescapeXmlEntities(
            ElixirEditorBasedSdkWidget.otpMismatchMessage("mise Elixir 1.19.5-otp-27 (Erlang 28.1)", "27", "28")
        )

        assertTrue(message, message.startsWith("Elixir SDK 'mise Elixir 1.19.5-otp-27 (Erlang 28.1)' was compiled for OTP 27 but is paired with OTP 28."))
    }

    /** A small IDE has no module SDK settings, so a module SDK left by IntelliJ IDEA cannot be fixed there. */
    fun testASmallIdeReadsOnlyTheFacet() {
        ProcessOutput.isSmallIdeOverride = true
        setModuleSdk(createAndRegisterElixirSdk("Elixir set in IntelliJ IDEA"))

        assertNull(resolvedSdk())
    }

    fun testIntelliJIdeaFallsThroughToTheModuleSdk() {
        ProcessOutput.isSmallIdeOverride = false
        val moduleSdk = createAndRegisterElixirSdk("Elixir set in IntelliJ IDEA")
        setModuleSdk(moduleSdk)

        assertEquals(moduleSdk, resolvedSdk())
    }

    /** A small IDE's facet must not override what the user set in Project Structure. */
    fun testIntelliJIdeaReadsTheModuleSdkBeforeTheFacet() {
        ProcessOutput.isSmallIdeOverride = false
        setFacetSdk(createAndRegisterElixirSdk("Elixir set in RubyMine"))
        val moduleSdk = createAndRegisterElixirSdk("Elixir set in IntelliJ IDEA")
        setModuleSdk(moduleSdk)

        assertEquals(moduleSdk, resolvedSdk())
    }

    fun testIntelliJIdeaReadsTheProjectSdkBeforeTheFacet() {
        ProcessOutput.isSmallIdeOverride = false
        setFacetSdk(createAndRegisterElixirSdk("Elixir set in RubyMine"))
        val projectSdk = createAndRegisterElixirSdk("Elixir set in IntelliJ IDEA")
        setProjectSdk(projectSdk)
        ModuleRootModificationUtil.updateModel(module) { it.inheritSdk() }

        assertEquals("the module SDK is <Project SDK>", projectSdk, resolvedSdk())
    }

    fun testIntelliJIdeaDoesNotFallBackToTheFacetForABrokenModuleSdk() {
        ProcessOutput.isSmallIdeOverride = false
        setFacetSdk(createAndRegisterElixirSdk("Elixir set in RubyMine"))
        setMissingModuleSdk("Elixir set in IntelliJ IDEA")

        assertNull("the module SDK the user set is broken, which is reported", resolvedSdk())
    }

    fun testIntelliJIdeaDoesNotFallBackToTheFacetForAModuleWithNoSdk() {
        ProcessOutput.isSmallIdeOverride = false
        setFacetSdk(createAndRegisterElixirSdk("Elixir set in RubyMine"))
        ModuleRootModificationUtil.updateModel(module) { it.inheritSdk() }

        assertNull("<Project SDK> with no project SDK is no SDK", resolvedSdk())
    }

    /** IntelliJ IDEA has no settings for the facet, so one a small IDE left can never be changed there. */
    @RequiresEdt
    fun testIntelliJIdeaNeverReadsTheFacet() {
        ProcessOutput.isSmallIdeOverride = false
        setFacetSdk(createAndRegisterElixirSdk("Elixir set in RubyMine"))
        val javaSdk = createJavaSdk()
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().addJdk(javaSdk, testRootDisposable) }
        setModuleSdk(javaSdk)

        assertNull(resolvedSdk())
    }

    fun testASmallIdeDoesNotReportAModuleSdkMissingFromTheTable() {
        ProcessOutput.isSmallIdeOverride = true
        createAndRegisterElixirSdk("Elixir 1.19.5")
        setMissingModuleSdk("Elixir set in IntelliJ IDEA")

        val issues = createWidget().detectModuleSdkIssuesInTest()

        assertFalse(
            "only Project Structure can change a module SDK, and a small IDE has none; got: $issues",
            issues.any { it.issue.contains("Elixir set in IntelliJ IDEA") },
        )
    }

    private fun resolvedSdk(): Sdk? =
        ReadAction.nonBlocking(Callable { ElixirSdkLookup.resolve(module).sdk }).executeSynchronously()

    private fun setMissingModuleSdk(name: String) {
        ModuleRootModificationUtil.updateModel(module) { it.setInvalidSdk(name, ElixirSdkType.instance.name) }
    }

    // -------------------------------------------------------------------------
    // Smoke tests (regression)
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // The version table, which is all a single-module notification shows
    // -------------------------------------------------------------------------

    private fun table(isInstalled: Boolean) = SdkVersionTable(
        moduleName = module.name,
        toolManagerName = "mise",
        rows = listOf(SdkVersionRow("Elixir", "1.20.5", "1.21.0", isMismatch = true, isInstalled = isInstalled)),
    )

    fun testAnUninstalledRowSaysSoAndNamesTheCommandThatInstallsIt() {
        val html = createWidget().buildSdkVersionTableHtml(table(isInstalled = false))

        assertTrue("one module renders this table and never the issue text; got: $html", html.contains("not installed"))
        assertTrue("got: $html", html.contains("mise install"))
    }

    fun testTheTitleSaysAVersionIsNotInstalled() {
        assertEquals(
            "Elixir SDK: '${module.name}' - mise version not installed",
            ElixirEditorBasedSdkWidget.versionTableTitle(table(isInstalled = false)),
        )
    }

    fun testTheTitleOfAMismatchAtAnotherPathSaysMismatch() {
        assertEquals(
            "Elixir SDK: '${module.name}' Version Mismatch",
            ElixirEditorBasedSdkWidget.versionTableTitle(table(isInstalled = true)),
        )
    }

    fun testReconfigureIsOfferedOnlyForFolderMarks() {
        assertFalse(
            "it resets a module to the project SDK, which undoes the SDK the tool manager chose",
            ElixirEditorBasedSdkWidget.offersReconfigure(SdkStatus.ModuleSdkError(null, null, emptyList())),
        )
        assertTrue(
            ElixirEditorBasedSdkWidget.offersReconfigure(
                SdkStatus.FolderMarkWarning(ProjectJdkImpl("folder", ElixirSdkType.instance, "/fake", ""), "1.20.5", emptyList())
            )
        )
    }

    fun testAMismatchAtAnotherPathOffersNoInstall() {
        val html = createWidget().buildSdkVersionTableHtml(table(isInstalled = true))

        assertFalse("the version is installed, just not the one configured; got: $html", html.contains("not installed"))
        assertFalse("got: $html", html.contains("mise install"))
    }

    fun testWidgetIdConstant() {
        assertEquals("ElixirSdkStatus", ElixirEditorBasedSdkWidget.ID)
    }

    fun testThePopupsFirstItemAsksTheChooser() {
        val recorded = RecordedChooser(testRootDisposable)
        val first = createWidget().popupActions().getChildren(null).first()
        ActionUtil.performAction(first, moduleEvent(first, project, module))

        recorded.assertChooserAskedFor(module)
    }
}
