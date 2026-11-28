package org.elixir_lang.mix

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.expander.LexicalAliases
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.lowering.content
import org.elixir_lang.lowering.pieces
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.CallDefinitionClause.capabilities
import org.elixir_lang.psi.CallDefinitionClause.modularChildCalls
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirParentheticalStab
import org.elixir_lang.psi.ElixirStab
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.blockKeyword
import org.elixir_lang.psi.impl.call.blockKeywordPairs
import org.elixir_lang.psi.impl.keywordPairs
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/** What a `mix.exs` file's `project/0` returns, read from its source. */
object ProjectKeywords {
    /**
     * The project's `app:`: [Reading.Absent] where the list `project/0` returns is `[]` or pairs without one, as an
     * umbrella root's is; [Reading.Unknown] where the app is not a literal atom or the list is not a literal one.
     */
    @RequiresReadLock
    fun app(mixExs: ElixirFile): Reading<String> =
        keyword(mixExs, "app", Reading.Absent) { (it as? ElixirAtom)?.let(AtomName::of)?.let { app -> Reading.Written(app) } }

    /**
     * The project's `config_path:`, Mix's default when the list `project/0` returns is `[]` or pairs without one;
     * [Reading.Unknown] for any other list. Never [Reading.Absent]: Mix's default is the answer. `Config.Reader` raises
     * on a charlist, so one names no file.
     */
    @RequiresReadLock
    fun configPath(mixExs: ElixirFile): Reading<String> =
        keyword(mixExs, "config_path", Reading.Written(DEFAULT_CONFIG_PATH)) { value ->
            stringText(value)?.let { Reading.Written(it) }
        }

    /**
     * The text [value], a string or charlist, spells, its escapes decoded as Elixir's tokenizer decodes them; `null` where
     * it is interpolated or no string.
     */
    @RequiresReadLock
    fun literalText(value: PsiElement): String? =
        (value as? ElixirLine)
            ?.let { line -> content(line, line.pieces(), ElixirLanguageLevelResolver.languageLevelFor(line)) }
            ?.let(AtomName::literalText)

    /** The [literalText] of [value] where it is a string, `null` for a charlist, for a reader that raises on one. */
    @RequiresReadLock
    fun stringText(value: PsiElement): String? = (value as? ElixirLine)?.takeUnless { it.isCharList }?.let(::literalText)

    /**
     * What the project's `elixirc_options:` give [option], the [read] of the last that names it, as
     * `Code.compiler_options/1` puts each in turn: [Reading.Absent] where the list `project/0` returns has no
     * `elixirc_options:`, or a literal list of pairs without [option]; [Reading.Unknown] where the options are any other
     * value.
     */
    @RequiresReadLock
    fun <T> elixircOption(mixExs: ElixirFile, option: String, read: (PsiElement) -> Reading.Single<T>): Reading<T> =
        keyword(mixExs, "elixirc_options", Reading.Absent) { value ->
            val pairs = value.keywordPairs()
            val written = pairs?.lastOrNull { (key, _) -> key == option }?.second

            when {
                written != null -> read(written)
                pairs != null -> Reading.Absent
                else -> null
            }
        }

    /**
     * What `project/0` says of [name]: the reading of each list it may return ([returnedLists]), one of them where they
     * differ ([Reading.of]).
     */
    @RequiresReadLock
    internal fun <T> keyword(mixExs: ElixirFile, name: String, absent: Reading.Single<T>, read: (PsiElement) -> Reading<T>?): Reading<T> {
        val definition = project(mixExs) ?: return Reading.Unknown

        return Reading.of(returnedLists(definition).map { list -> keyword(list, name, absent, read) })
    }

    /**
     * What [list], one `project/0` may return, says of [name]: [absent] where the list is `[]` or pairs without it, the
     * [read] of its first value where it has one, as Mix keeps a key given twice and reads the first, and
     * [Reading.Unknown] where [read] gives none or the list's pairs can't be read ([keywordPairs]).
     */
    @RequiresReadLock
    private fun <T> keyword(list: ElixirList?, name: String, absent: Reading.Single<T>, read: (PsiElement) -> Reading<T>?): Reading<T> {
        val pairs = list?.keywordPairs() ?: return Reading.Unknown
        val value = pairs.firstOrNull { (key, _) -> key == name }?.second ?: return absent

        return read(value) ?: Reading.Unknown
    }

    /** Whether [path], a written `config_path:`, is Mix's default, the only path that may be missing. */
    fun isDefault(path: String): Boolean = path == DEFAULT_CONFIG_PATH

    /**
     * What a `mix.exs` file's `project/0` says of a keyword: a [Single] reading, or [Ambiguous] where the lists it may
     * return read differently.
     */
    sealed interface Reading<out T> {
        /** Each [Single] reading this may be, in the order `project/0` gives them. */
        val candidates: List<Single<T>>

        sealed interface Single<out T> : Reading<T> {
            override val candidates: List<Single<T>> get() = listOf(this)
        }

        data class Written<out T>(val value: T) : Single<T>

        /** The list `project/0` returns is readable and has no such keyword. */
        data object Absent : Single<Nothing>

        /** The keyword, or the list `project/0` returns, can't be read. */
        data object Unknown : Single<Nothing>

        /** Two or more distinct [candidates], one of which `project/0` gives when it runs. */
        @ConsistentCopyVisibility
        data class Ambiguous<out T> internal constructor(override val candidates: List<Single<T>>) : Reading<T> {
            override fun toString(): String = "Ambiguous(${candidates.joinToString()})"
        }

        companion object {
            /**
             * One of [candidates]: each [Ambiguous] flattened into its own, and duplicates merged in first-seen order. The
             * one [Single] left where only one is, and [Unknown] where there are none.
             */
            fun <T> of(candidates: List<Reading<T>>): Reading<T> {
                val distinct = candidates.flatMapTo(LinkedHashSet()) { it.candidates }.toList()

                return when (distinct.size) {
                    0 -> Unknown
                    1 -> distinct.single()
                    else -> Ambiguous(distinct)
                }
            }
        }
    }

    /** Whether a module of `mix.exs` uses `Mix.Project`, which makes it a project Mix reads. */
    internal enum class UsesMixProject {
        YES,
        NO,

        /** A `use` in it names a module that can't be read, or a form before that `use` may alias any name. */
        UNKNOWN,
    }

    /** A `defmodule` [definition] of `mix.exs` and whether it [usesMixProject]. */
    internal data class ProjectModule(val definition: Call, val usesMixProject: UsesMixProject)

    /**
     * Each module written at [mixExs]'s top level, and each nested in one, in file order, with whether it uses
     * `Mix.Project`: each `use` names a module through the aliases in scope at it ([LexicalAliases]). Each module is read
     * only when it is reached.
     */
    @RequiresReadLock
    internal fun projectModules(mixExs: ElixirFile): Sequence<ProjectModule> =
        withNested(mixExs.modulars().asSequence()).map { ProjectModule(it, usesMixProject(it)) }

    private const val DEFAULT_CONFIG_PATH = "config/config.exs"
    private const val MIX_PROJECT = "Elixir.Mix.Project"

    /**
     * The first `project/0` definition of the module Mix reads the project of, the clause Mix calls as it takes no
     * argument: the first of [projectModules] that uses `Mix.Project`. `null` where there is none, where it defines no
     * `project/0`, as Mix raises, or where a module before it may use `Mix.Project`.
     */
    @RequiresReadLock
    private fun project(mixExs: ElixirFile): Call? =
        projectModules(mixExs)
            .firstOrNull { it.usesMixProject != UsesMixProject.NO }
            ?.takeIf { it.usesMixProject == UsesMixProject.YES }
            ?.let { modularChildCalls(it.definition).firstOrNull(::isProject) }

    /** The `defmodule`s of [calls] and the modules defined in each, nested ones included, in file order. */
    @RequiresReadLock
    private fun withNested(calls: Sequence<Call>): Sequence<Call> =
        calls.filter(Module::isDefmodule).flatMap { module ->
            ProgressManager.checkCanceled()

            sequenceOf(module) + withNested(modularChildCalls(module).asSequence())
        }

    /** Whether [module] uses `Mix.Project`: [UsesMixProject.YES] at the first `use` that names it. */
    @RequiresReadLock
    private fun usesMixProject(module: Call): UsesMixProject {
        for (use in modularChildCalls(module).filter(Use::`is`)) {
            ProgressManager.checkCanceled()

            when (Alias.firstArgument(use)?.let(LexicalAliases::moduleNamedBy)) {
                null -> return UsesMixProject.UNKNOWN
                MIX_PROJECT -> return UsesMixProject.YES
            }
        }

        return UsesMixProject.NO
    }

    /** Whether [call] defines `project/0`. */
    @RequiresReadLock
    internal fun isProject(call: Call): Boolean =
        capabilities(call)?.remoteCallable == true &&
            nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
                name == "project" && arityInterval.contains(0)
            } == true

    /**
     * The lists [definition] may return, one for each value it can give: its body's last expression, unless an `else`
     * replaces it, then the last expression of each `else`, `rescue` and `catch` clause, written as blocks or as keywords.
     * `after` changes no value. `null` stands for a value that isn't a list as written.
     */
    @RequiresReadLock
    internal fun returnedLists(definition: Call): List<ElixirList?> {
        val doBlock = definition.doBlock
        val blocks = if (doBlock != null) {
            listOf(DO to listOf(doBlock.stab?.stabBody?.children?.lastOrNull())) +
                doBlock.blockList?.blockItemList.orEmpty().map { it.blockIdentifier.text to lastExpressions(it.stab) }
        } else {
            definition.blockKeywordPairs()
                .mapNotNull { pair ->
                    pair.blockKeyword()?.let { keyword ->
                        val value = pair.keywordValue

                        keyword to if (keyword != DO && value is ElixirParentheticalStab) lastExpressions(value.stab) else listOf(value)
                    }
                }
                .ifEmpty { listOf(DO to listOf(null)) }
        }
        val replacesBody = blocks.any { (keyword, _) -> keyword == ELSE }

        return blocks
            .filter { (keyword, _) -> if (keyword == DO) !replacesBody else keyword in HANDLERS }
            .flatMap { (_, values) -> values }
            .map { it?.stripAccessExpressionAndParentheses() as? ElixirList }
    }

    /** The last expression of [stab]'s body, or of each of its clauses' bodies. */
    @RequiresReadLock
    private fun lastExpressions(stab: ElixirStab?): List<PsiElement?> =
        stab?.stabBody?.let { listOf(it.children.lastOrNull()) }
            ?: stab?.stabOperationList?.map { it.stabBody?.children?.lastOrNull() }
            ?: listOf(null)

    private const val DO = "do"
    private const val ELSE = "else"
    private val HANDLERS = setOf(ELSE, "rescue", "catch")
}
