package org.elixir_lang.expander

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.util.ThrowableRunnable
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.gtduNavigationAtCaret
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.expander.ExpanderSourceRows.lineOf
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.junit.logs.UnexpectedLogs
import org.elixir_lang.model.psi.config.caret
import org.elixir_lang.model.psi.config.disableMixDepsCheck

/**
 * A test on disk, in a `CodeInsightTestFixture` whose files are real ones, with one module on the leg's SDK and the
 * features answered as no switch says, whatever a test before it set.
 */
abstract class LegSdkFixtureTestCase : UsefulTestCase() {
    protected lateinit var myFixture: CodeInsightTestFixture
    protected lateinit var module: Module
    protected val project get() = myFixture.project
    private var savedProperty: String? = null

    @Throws(Throwable::class)
    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        UnexpectedLogs.failOnUnexpectedLogs {
            TestApplicationManager.getInstance()
            super.runBare(testRunnable)
        }
    }

    override fun setUp() {
        savedProperty = System.getProperty(SOURCE_PROPERTY)
        System.clearProperty(SOURCE_PROPERTY)
        super.setUp()

        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${javaClass.name}.$name")
        myFixture = factory.createCodeInsightFixture(builder.fixture)
        val moduleBuilder = builder.addModule(EmptyModuleFixtureBuilder::class.java)
        moduleBuilder.addSourceContentRoot(myFixture.tempDirPath)
        myFixture.setUp()
        module = moduleBuilder.fixture.module
        // The fixture's own disposable, which goes before the fixture's check for leaked SDKs.
        disableMixDepsCheck(myFixture.testRootDisposable)
        setUpSdk(LegSdk.setUp(module, myFixture.testRootDisposable))
    }

    /** What a test adds once the module has the leg's [sdk]. */
    protected open fun setUpSdk(sdk: Sdk) {}

    /** [element]'s file, relative to the project, or from `elixir/` for the SDK's, and its 1-based line. */
    protected fun place(element: PsiElement): String {
        val file = element.containingFile.originalFile
        val path = FileUtil.toSystemIndependentName(file.virtualFile.path)
        val relative = FileUtil.getRelativePath(FileUtil.toSystemIndependentName(myFixture.tempDirPath), path, '/')
            ?.takeUnless { it.startsWith("..") }
            ?: path.substringAfterLast("/lib/elixir/", "").takeIf { it.isNotEmpty() }?.let { "elixir/$it" }
            ?: path

        return "$relative:${lineOf(element)}"
    }

    /** [text], its indent trimmed, as the project's [CALLER], indexed. */
    protected fun caller(text: String): PsiFile {
        val file = myFixture.addFileToProject(CALLER, text.trimIndent() + "\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return file
    }

    /** Where Go to Declaration at [marked] in the [CALLER] lands: each target as [render] writes it, sorted. */
    protected fun destinations(marked: String, render: (PsiElement) -> String = this::place): String {
        myFixture.caret(CALLER, marked)

        return when (val navigation = myFixture.gtduNavigationAtCaret()) {
            is GtduNavigation.GotoDeclaration ->
                navigation.targets.map { target -> read { render(target.destination!!) } }.sorted().joinToString("\n")
            GtduNavigation.None -> "none"
            is GtduNavigation.ShowUsages -> "show usages"
        }
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
            myFixture.tearDown()
        } finally {
            savedProperty?.let { System.setProperty(SOURCE_PROPERTY, it) } ?: System.clearProperty(SOURCE_PROPERTY)
            super.tearDown()
        }
    }

    protected companion object {
        const val CALLER = "lib/caller.ex"
    }
}
