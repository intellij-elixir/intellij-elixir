package org.elixir_lang.mix

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.CallDefinitionClause.capabilities
import org.elixir_lang.psi.CallDefinitionClause.modularChildCalls
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/** What a `mix.exs` file's `project/0` returns, read from its source. */
object ProjectKeywords {
    /** The keyword list `project/0` returns, the last expression of its body, if it is written as one. */
    @RequiresReadLock
    fun keywords(mixExs: ElixirFile): QuotableKeywordList? =
        mixExs
            .modulars()
            .asSequence()
            .flatMap { modularChildCalls(it).asSequence() }
            .filter(::isProject)
            .firstNotNullOfOrNull { (returned(it)?.stripAccessExpressionAndParentheses() as? ElixirList)?.keywords }

    /** The project's `app:`. */
    @RequiresReadLock
    fun app(mixExs: ElixirFile): String? =
        (keywords(mixExs)?.keywordValue("app")?.stripAccessExpressionAndParentheses() as? ElixirAtom)?.let(AtomName::of)

    private fun isProject(call: Call): Boolean =
        capabilities(call)?.remoteCallable == true &&
            nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
                name == "project" && arityInterval.contains(0)
            } == true

    private fun returned(definition: Call): PsiElement? =
        definition.doBlock?.stab?.stabBody?.children?.lastOrNull() ?: definition.keywordArgument("do")
}
