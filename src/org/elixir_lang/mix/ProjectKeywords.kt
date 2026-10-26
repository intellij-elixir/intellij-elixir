package org.elixir_lang.mix

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.lowering.content
import org.elixir_lang.lowering.pieces
import org.elixir_lang.psi.CallDefinitionClause.capabilities
import org.elixir_lang.psi.CallDefinitionClause.modularChildCalls
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/** What a `mix.exs` file's `project/0` returns, read from its source. */
object ProjectKeywords {
    /** The keywords of the list `project/0` returns. */
    @RequiresReadLock
    fun keywords(mixExs: ElixirFile): QuotableKeywordList? = list(mixExs)?.keywords

    /** The project's `app:`. */
    @RequiresReadLock
    fun app(mixExs: ElixirFile): String? =
        (keywords(mixExs)?.keywordValue("app")?.stripAccessExpressionAndParentheses() as? ElixirAtom)?.let(AtomName::of)

    /**
     * The project's `config_path:`, Mix's default when the list `project/0` returns is `[]` or keywords without one;
     * [ConfigPath.Unknown] for any other list.
     */
    @RequiresReadLock
    fun configPath(mixExs: ElixirFile): ConfigPath {
        val list = list(mixExs) ?: return ConfigPath.Unknown
        val keywords = list.keywords
            ?: return if (list.children.isEmpty()) ConfigPath.Written(DEFAULT_CONFIG_PATH) else ConfigPath.Unknown
        val value = keywords.keywordValue("config_path")?.stripAccessExpressionAndParentheses()
            ?: return ConfigPath.Written(DEFAULT_CONFIG_PATH)

        return (value as? ElixirLine)
            ?.takeUnless { it.isCharList }
            ?.let { content(it, it.pieces(), ElixirLanguageLevelResolver.languageLevelFor(it)) }
            ?.let(AtomName::literalText)
            ?.let(ConfigPath::Written)
            ?: ConfigPath.Unknown
    }

    /** Where a `mix.exs` file's `config_path:` points: a [Written] path relative to its directory, or [Unknown]. */
    sealed interface ConfigPath {
        data class Written(val path: String) : ConfigPath

        data object Unknown : ConfigPath
    }

    private const val DEFAULT_CONFIG_PATH = "config/config.exs"

    /** The list `project/0` returns, the last expression of its body, if it is written as one. */
    private fun list(mixExs: ElixirFile): ElixirList? =
        mixExs
            .modulars()
            .asSequence()
            .flatMap { modularChildCalls(it).asSequence() }
            .filter(::isProject)
            .firstNotNullOfOrNull { returned(it)?.stripAccessExpressionAndParentheses() as? ElixirList }

    private fun isProject(call: Call): Boolean =
        capabilities(call)?.remoteCallable == true &&
            nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
                name == "project" && arityInterval.contains(0)
            } == true

    private fun returned(definition: Call): PsiElement? =
        definition.doBlock?.stab?.stabBody?.children?.lastOrNull() ?: definition.keywordArgument("do")
}
