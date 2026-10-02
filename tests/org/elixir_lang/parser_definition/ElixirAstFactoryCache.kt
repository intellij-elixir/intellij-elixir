@file:JvmName("ElixirAstFactoryCache")

package org.elixir_lang.parser_definition

import com.intellij.lang.LanguageASTFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.elixir_lang.ElixirLanguage

/**
 * [LanguageASTFactory] caches the factory it finds for Elixir on [ElixirLanguage], and a mock application finds the
 * platform default rather than the plugin's `lang.ast.factory`.
 *
 * Call it after `MockApplication.setUp`, so the teardown clear runs while the mock application is still current:
 * `clearCache` reaches `LanguageUtil.matchingMetaLanguages`, which needs an application.
 */
fun clearElixirAstFactory(testRootDisposable: Disposable) {
    LanguageASTFactory.INSTANCE.clearCache(ElixirLanguage)
    Disposer.register(testRootDisposable) { LanguageASTFactory.INSTANCE.clearCache(ElixirLanguage) }
}
