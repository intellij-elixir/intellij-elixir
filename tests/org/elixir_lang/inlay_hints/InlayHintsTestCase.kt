package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase
import com.intellij.util.ThrowableRunnable
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.declaration.eachExpander
import org.elixir_lang.junit.LightFixtureGuard
import java.util.concurrent.Callable

/**
 * A [DeclarativeInlayHintsProviderTestCase] of the provider [providerId] names, under a [LightFixtureGuard].
 *
 * The platform's dump shows a hint's text and a PSI payload's element, but no handler id, so [textNodes] reads what a
 * provider builds directly.
 */
abstract class InlayHintsTestCase : DeclarativeInlayHintsProviderTestCase() {
    private val guard = LightFixtureGuard()

    /** The id the provider under test is registered under. */
    protected abstract val providerId: String

    protected abstract fun provider(): InlayHintsProvider

    @Throws(Throwable::class)
    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        guard.runBare { super.runBare(testRunnable) }
    }

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        guard.setUp(project, module)
    }

    @Throws(Exception::class)
    override fun tearDown() {
        guard.tearDown(project, module, name) { super.tearDown() }
    }

    /** Runs [test] with the expander off, then on with the leg's SDK. */
    protected fun eachExpander(test: () -> Unit) = eachExpander(module, testRootDisposable, test)

    /** Settings shows the preview's own hints, so they must be the ones the provider gives. */
    fun testThePreviewShowsTheProvidersHints() =
        assertHints(javaClass.classLoader.getResource("inlayProviders/$providerId/preview.ex")!!.readText())

    /** Asserts the provider is the one registered for Elixir under [providerId], on by default when [enabledByDefault]. */
    protected fun assertRegistered(enabledByDefault: Boolean) {
        val info = InlayHintsProviderFactory.getProviderInfo(ElixirLanguage, providerId)!!

        assertInstanceOf(info.provider, provider()::class.java)
        assertEquals(enabledByDefault, info.isEnabledByDefault)
    }

    /** Asserts the provider gives the hints [expected] marks, with the expander off, then on. */
    protected fun assertHints(expected: String) = eachExpander {
        doTestProvider("test.ex", expected.trimIndent(), provider())
    }

    /** One text node of a hint. */
    data class TextNode(val text: String, val actionData: InlayActionData?)

    /** Each hint the provider builds over the file the fixture holds, as its text nodes, in the order it adds them. */
    protected fun textNodes(): List<List<TextNode>> =
        ReadAction.nonBlocking(Callable {
            val collector = provider().createCollector(myFixture.file, myFixture.editor) as SharedBypassCollector
            val sink = RecordingSink()

            SyntaxTraverser.psiTraverser(myFixture.file).forEach { collector.collectFromElement(it, sink) }

            sink.hints
        }).executeSynchronously()

    private class RecordingSink : InlayTreeSink {
        val hints = mutableListOf<List<TextNode>>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            hints.add(RecordingBuilder().apply(builder).nodes)
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = Unit
    }

    /** Records the text nodes the providers build, which is all they build. */
    private class RecordingBuilder : PresentationTreeBuilder {
        val nodes = mutableListOf<TextNode>()

        override fun text(text: String, actionData: InlayActionData?) {
            nodes.add(TextNode(text, actionData))
        }

        override fun list(builder: PresentationTreeBuilder.() -> Unit) = error("unused")

        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit,
        ) = error("unused")

        override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) = error("unused")
    }
}
