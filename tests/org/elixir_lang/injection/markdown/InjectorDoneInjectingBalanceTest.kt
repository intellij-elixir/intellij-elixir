package org.elixir_lang.injection.markdown

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.HeredocLiteral
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * `startInjecting`/`doneInjecting` must balance even when `addPlace` throws: a registrar left mid-`startInjecting`
 * throws `IllegalStateException` from the next `startInjecting()`, ending injection for the rest of the file.
 */
class InjectorDoneInjectingBalanceTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/injection/markdown"

    private class RecordingRegistrar(private val failOnAddPlaceCall: Int) : MultiHostRegistrar {
        val startInjectingLanguages = mutableListOf<Language>()
        var doneInjectingCount = 0
        private var addPlaceCallCount = 0

        override fun startInjecting(language: Language): MultiHostRegistrar {
            startInjectingLanguages.add(language)
            return this
        }

        override fun startInjecting(language: Language, extension: String?): MultiHostRegistrar =
            startInjecting(language)

        override fun addPlace(
            prefix: String?,
            suffix: String?,
            host: PsiLanguageInjectionHost,
            rangeInsideHost: com.intellij.openapi.util.TextRange
        ): MultiHostRegistrar {
            addPlaceCallCount++

            if (addPlaceCallCount == failOnAddPlaceCall) {
                throw RuntimeExceptionWithAttachments("simulated addPlace failure")
            }

            return this
        }

        override fun doneInjecting() {
            doneInjectingCount++
        }

        override fun <T> putInjectedFileUserData(key: Key<T>, data: T): MultiHostRegistrar = this
    }

    fun testDoneInjectingIsCalledEvenWhenAddPlaceThrows() {
        myFixture.configureByFile("long_documentation.ex")
        val documentation = PsiTreeUtil.findChildOfType(myFixture.file, HeredocLiteral::class.java)!!

        val registrar = RecordingRegistrar(failOnAddPlaceCall = 1)

        // The caught addPlace failure is logged through Logger.error, which TestLoggerFactory otherwise
        // turns into a hard test failure - suppress it, since this test is about the balance invariant,
        // not about whether the failure gets logged
        captureLoggedErrors(suppress = true) {
            Injector().getLanguagesToInject(registrar, documentation)
        }

        // The platform tracks a single "currently injecting" flag across the whole registrar, not one per
        // language, so it is the *totals* that must balance - filtering to Markdown's own start count would
        // let the unrelated, unaffected Elixir code-block injection's doneInjecting() mask the imbalance
        assertEquals(
            "startInjecting() and doneInjecting() must balance across the whole registrar even when one " +
                    "addPlace throws, or the next startInjecting() elsewhere throws IllegalStateException",
            registrar.startInjectingLanguages.size,
            registrar.doneInjectingCount
        )
        assertTrue(
            "Fixture has no Markdown injection to have exercised the failing addPlace call",
            registrar.startInjectingLanguages.contains(MarkdownLanguage.INSTANCE)
        )
    }
}
