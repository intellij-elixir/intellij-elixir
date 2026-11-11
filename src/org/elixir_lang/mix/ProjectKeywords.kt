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

    /**
     * The project's `app:`: [Reading.Absent] where the list `project/0` returns is `[]` or keywords without one, as an
     * umbrella root's is; [Reading.Unknown] where the app is not a literal atom or the list is not a literal one.
     */
    @RequiresReadLock
    fun app(mixExs: ElixirFile): Reading<String> =
        keyword(mixExs, "app", Reading.Absent) { (it as? ElixirAtom)?.let(AtomName::of)?.let { app -> Reading.Written(app) } }

    /**
     * The project's `config_path:`, Mix's default when the list `project/0` returns is `[]` or keywords without one;
     * [Reading.Unknown] for any other list. Never [Reading.Absent]: Mix's default is the answer.
     */
    @RequiresReadLock
    fun configPath(mixExs: ElixirFile): Reading<String> =
        keyword(mixExs, "config_path", Reading.Written(DEFAULT_CONFIG_PATH)) { value ->
            (value as? ElixirLine)
                ?.takeUnless { it.isCharList }
                ?.let { content(it, it.pieces(), ElixirLanguageLevelResolver.languageLevelFor(it)) }
                ?.let(AtomName::literalText)
                ?.let { Reading.Written(it) }
        }

    /**
     * What the list `project/0` returns says of [name]: [absent] where the list is `[]` or keywords without it, the
     * [read] of its value, with parentheses and access expressions stripped, where it has one, and [Reading.Unknown]
     * where [read] gives none or the list is not a literal one of keywords.
     */
    @RequiresReadLock
    internal fun <T> keyword(mixExs: ElixirFile, name: String, absent: Reading<T>, read: (PsiElement) -> Reading<T>?): Reading<T> {
        val list = list(mixExs) ?: return Reading.Unknown
        val keywords = list.keywords ?: return if (list.children.isEmpty()) absent else Reading.Unknown
        val value = keywords.keywordValue(name)?.stripAccessExpressionAndParentheses() ?: return absent

        return read(value) ?: Reading.Unknown
    }

    /** Whether [path], a written `config_path:`, is Mix's default, the only path that may be missing. */
    fun isDefault(path: String): Boolean = path == DEFAULT_CONFIG_PATH

    /** What a `mix.exs` file's `project/0` says of a keyword: a [Written] value, [Absent], or [Unknown]. */
    sealed interface Reading<out T> {
        /** The written value, `null` where [Absent] or [Unknown]. */
        val written: T? get() = (this as? Written)?.value

        data class Written<out T>(val value: T) : Reading<T>

        /** The list `project/0` returns is readable and has no such keyword. */
        data object Absent : Reading<Nothing>

        /** The keyword, or the list `project/0` returns, can't be read. */
        data object Unknown : Reading<Nothing>
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

    /** Whether [call] defines `project/0`. */
    @RequiresReadLock
    internal fun isProject(call: Call): Boolean =
        capabilities(call)?.remoteCallable == true &&
            nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
                name == "project" && arityInterval.contains(0)
            } == true

    /** The expression a function definition returns, the last of its `do` block or its `do:` value. */
    @RequiresReadLock
    internal fun returned(definition: Call): PsiElement? =
        definition.doBlock?.stab?.stabBody?.children?.lastOrNull() ?: definition.keywordArgument("do")
}
