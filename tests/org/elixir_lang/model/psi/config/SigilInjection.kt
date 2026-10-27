package org.elixir_lang.model.psi.config

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.injection.PsiLanguageInjectionHost
import org.elixir_lang.settings.ElixirExperimentalSettings
import org.junit.Assert.assertTrue

/** [block]'s result with sigils as injection hosts, as HTML injection makes them. */
internal fun <T> CodeInsightTestFixture.withSigilInjection(block: () -> T): T {
    val state = ElixirExperimentalSettings.instance.state
    val original = state.enableHtmlInjection
    state.enableHtmlInjection = true
    InjectedLanguageManager.getInstance(project).registerMultiHostInjector(ElixirSigilInjector(), testRootDisposable)

    try {
        assertTrue(PsiLanguageInjectionHost.sigilsAreHosts())

        return block()
    } finally {
        state.enableHtmlInjection = original
    }
}
