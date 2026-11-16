package org.elixir_lang.mix

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.lowering.content
import org.elixir_lang.lowering.pieces
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.Alias.aliasNameIntroducedBy
import org.elixir_lang.psi.Alias.aliasTargetName
import org.elixir_lang.psi.CallDefinitionClause.capabilities
import org.elixir_lang.psi.CallDefinitionClause.modularChildCalls
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.hasKeywordKey
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/** What a `mix.exs` file's `project/0` returns, read from its source. */
object ProjectKeywords {
    /** The keywords of the list `project/0` returns. */
    @RequiresReadLock
    fun keywords(mixExs: ElixirFile): QuotableKeywordList? = project(mixExs)?.second?.keywords

    /**
     * The project's `app:`: [Reading.Absent] where the list `project/0` returns is `[]` or keywords without one, as an
     * umbrella root's is; [Reading.Unknown] where the app is not a literal atom or the list is not a literal one.
     */
    @RequiresReadLock
    fun app(mixExs: ElixirFile): Reading<String> =
        keyword(mixExs, "app", Reading.Absent, NeedsWhole.ABSENT) { (it as? ElixirAtom)?.let(AtomName::of)?.let { app -> Reading.Written(app) } }

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
     * What the project's `elixirc_options:` give [option], the last that names it, as `Code.compiler_options/1` puts each
     * in turn: [Reading.Absent] where the list `project/0` returns has no `elixirc_options:`, or a literal list of keywords
     * without [option]; [Reading.Unknown] where the options are any other value, or the list isn't all `project/0` can
     * return.
     */
    @RequiresReadLock
    fun elixircOption(mixExs: ElixirFile, option: String): Reading<PsiElement> =
        keyword(mixExs, "elixirc_options", Reading.Absent, NeedsWhole.EVERY_ANSWER) { value ->
            val list = value as? ElixirList
            val written = list?.keywords?.keywordValue(option)?.stripAccessExpressionAndParentheses()

            when {
                written != null -> Reading.Written(written)
                list?.children?.all { it is QuotableKeywordList } == true -> Reading.Absent
                else -> null
            }
        }

    /**
     * What the list `project/0` returns says of [name]: [absent] where the list is `[]` or keywords without it, the
     * [read] of its first value, parentheses and access expressions stripped, where it has one, and [Reading.Unknown]
     * where [read] gives none or the list is not a literal one of keywords. [needsWhole] says which answers need the list
     * to be all `project/0` can return ([returnsOnlyItsBody]) and to hold nothing but keywords, as a tuple before them
     * may name the keyword first; the others read the body's keywords.
     */
    @RequiresReadLock
    internal fun <T> keyword(
        mixExs: ElixirFile,
        name: String,
        absent: Reading<T>,
        needsWhole: NeedsWhole = NeedsWhole.NOTHING,
        read: (PsiElement) -> Reading<T>?,
    ): Reading<T> {
        val (definition, list) = project(mixExs) ?: return Reading.Unknown
        val whole = returnsOnlyItsBody(definition) && list.children.all { it is QuotableKeywordList }

        if (needsWhole == NeedsWhole.EVERY_ANSWER && !whole) return Reading.Unknown

        val missing = if (whole || needsWhole == NeedsWhole.NOTHING) absent else Reading.Unknown
        val keywords = list.keywords ?: return if (list.children.isEmpty()) missing else Reading.Unknown
        val value = value(keywords, name) ?: return missing

        return read(value) ?: Reading.Unknown
    }

    /**
     * The value of the first pair in [keywords] naming [name], read through its parentheses as Elixir reads it: Mix keeps
     * a key `project/0` gives twice, and reads the first.
     */
    @RequiresReadLock
    internal fun value(keywords: QuotableKeywordList, name: String): PsiElement? =
        keywords.quotableKeywordPairList().firstOrNull { it.hasKeywordKey(name) }?.keywordValue?.stripAccessExpressionAndParentheses()

    /** Which of [keyword]'s answers need the list `project/0` returns to be all it can return. */
    internal enum class NeedsWhole {
        /** None: the body's list answers, as it always has. */
        NOTHING,

        /** Only a keyword the list lacks. */
        ABSENT,

        /** Every answer. */
        EVERY_ANSWER,
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

    /**
     * The first `project/0` definition of the module Mix reads the project of, the clause Mix calls as it takes no
     * argument, and the list it returns ([returnedList]); `null` where there is none or it returns something else. That
     * module is the first that uses `Mix.Project` ([usesMixProject]).
     */
    @RequiresReadLock
    private fun project(mixExs: ElixirFile): Pair<Call, ElixirList>? {
        val module = mixExs
            .modulars()
            .asSequence()
            .map { modularChildCalls(it) }
            .firstOrNull { calls -> calls.any(::isProject) && usesMixProject(calls) } ?: return null
        val definition = module.first(::isProject)

        return returnedList(definition)?.let { definition to it }
    }

    /**
     * Whether a module's [calls] `use Mix.Project`, which makes the module the one Mix reads the project of: named so, or
     * through an `alias` before the `use`.
     */
    @RequiresReadLock
    private fun usesMixProject(calls: List<Call>): Boolean {
        val aliases = mutableMapOf<String, String>()

        for (call in calls) {
            ProgressManager.checkCanceled()
            if (Alias.`is`(call)) {
                val name = aliasNameIntroducedBy(call) ?: continue
                aliases[name] = aliasTargetName(call) ?: continue
            } else if (Use.`is`(call) && used(call, aliases) == "Mix.Project") {
                return true
            }
        }

        return false
    }

    /** The module [use] names, its head expanded through [aliases] as Elixir expands it; `null` where it names none. */
    @RequiresReadLock
    private fun used(use: Call, aliases: Map<String, String>): String? {
        val (name, absolute) = use.finalArguments()?.firstOrNull()?.stripAccessExpressionAndParentheses()?.let(::moduleName) ?: return null
        if (absolute) return name
        val head = name.substringBefore('.')

        return aliases[head]?.let { it + name.removePrefix(head) } ?: name
    }

    /** Whether [call] defines `project/0`. */
    @RequiresReadLock
    internal fun isProject(call: Call): Boolean =
        capabilities(call)?.remoteCallable == true &&
            nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
                name == "project" && arityInterval.contains(0)
            } == true

    /** The expression a function definition returns, the last of its `do` block or its `do:` value. */
    @RequiresReadLock
    private fun returned(definition: Call): PsiElement? =
        definition.doBlock?.stab?.stabBody?.children?.lastOrNull() ?: definition.keywordArgument("do")

    /** The list a function definition returns ([returned]), `null` where it returns something else. */
    @RequiresReadLock
    internal fun returnedList(definition: Call): ElixirList? =
        returned(definition)?.stripAccessExpressionAndParentheses() as? ElixirList

    /**
     * Whether the list [returnedList] reads is all [definition] can return. A `rescue`, `catch` or `else` block, written as
     * a block or as a keyword, may return another value; an `after` block leaves the value alone.
     */
    @RequiresReadLock
    internal fun returnsOnlyItsBody(definition: Call): Boolean =
        definition.doBlock?.blockList?.blockItemList?.none { it.blockIdentifier.text in VALUE_CHANGING_BLOCKS } != false &&
            VALUE_CHANGING_BLOCKS.none { definition.keywordArgument(it) != null }

    private val VALUE_CHANGING_BLOCKS = setOf("rescue", "catch", "else")
}
