package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ElixirFile

/** What the mirror pass owns, read from its output and from an expansion, and what it lowers a guard to. */
internal object MirrorPassRows {
    /** [mirror]'s forms, lowered as `ProjectModules` lowers them before the pass. */
    fun lowered(mirror: ElixirFile): ElixirAst =
        ReadAction.computeBlocking<ElixirAst, Throwable> { Lowering.of(mirror, legLevel()).lower(mirror) }

    /** [mirror]'s module's definitions as its source wrote them, read from its `.beam` as the pass reads them. */
    fun written(mirror: ElixirFile): Set<NameArity>? =
        ReadAction.computeBlocking<Set<NameArity>?, Throwable> { writtenDefinitions(mirror) }

    /**
     * Each definition [forms] hold that the pass drops or lowers, as `definer name/arity`, where [written] are the
     * module's definitions as its source wrote them.
     */
    fun ownedStubs(forms: ElixirAst, written: Set<NameArity>?): List<String> =
        definitions(forms).filter { isOwned(it, written.orEmpty()) }.map(Definition::toString).toList()

    /** Each definition [forms] hold, as `definer name/arity`. */
    fun defined(forms: ElixirAst): List<String> = definitions(forms).map(Definition::toString).toList()

    /** Each definition of [output], the pass's output for [forms], that [forms] write as a guard, as `definer name/arity`. */
    fun loweredGuards(forms: ElixirAst, output: ElixirAst): List<String> {
        val guards = definitions(forms).filter { it.definer in GUARD_DEFINERS }.map { it.nameArity }.toSet()

        return definitions(output).filter { it.nameArity in guards }.map(Definition::toString).sorted().toList()
    }

    /** Each error [result] reported, raised or crashed on that comes of a stub the pass owns, as its kind. */
    fun ownedErrors(result: ExpansionResult): List<String> =
        (result.errors.map { it.kind to it.at } + listOfNotNull(endedError(result)?.second?.let { it.kind to it.at }))
            .filter { (kind, at) -> kind in DROPPED_KINDS || kind == "undefined_function" && isGuardDefinition(at) }
            .map { (kind, _) -> kind.toString() }

    /** How [result] ended, `raised` or `crashed`, and its error; `null` where it did neither. */
    fun endedError(result: ExpansionResult): Pair<String, Expansion.Error>? =
        when (val ended = result.ended) {
            is ExpansionResult.Ended.Raised -> "raised" to ended.error
            is ExpansionResult.Ended.Crashed -> "crashed" to ended.error
            else -> null
        }

    private class Definition(val definer: String, val nameArity: NameArity) {
        override fun toString(): String = "$definer ${nameArity.name}/${nameArity.arity}"
    }

    /** The definitions among the statements of each module [forms] define, a nested module's included. */
    private fun definitions(forms: ElixirAst): Sequence<Definition> =
        statements(forms).flatMap { statement ->
            val call = statement as? ElixirAst.Call ?: return@flatMap emptySequence()
            val definer = (call.callee as? ElixirAst.Literal.Atom)?.name ?: return@flatMap emptySequence()
            if (definer == "defmodule") return@flatMap call.arguments?.getOrNull(1)?.let(::doBody)?.let(::definitions).orEmpty()
            if (definer !in DEFINERS) return@flatMap emptySequence()
            val head = call.arguments?.firstOrNull()?.let { extractGuards(it).first } as? ElixirAst.Call
            val name = (head?.callee as? ElixirAst.Literal.Atom)?.name ?: return@flatMap emptySequence()

            sequenceOf(Definition(definer, NameArity(name, head.arguments?.size ?: 0)))
        }

    private fun statements(forms: ElixirAst): Sequence<ElixirAst> =
        (forms as? ElixirAst.Block)?.expressions?.asSequence() ?: sequenceOf(forms)

    /** The `do` body of the keyword list [options], `[do: body]`. */
    private fun doBody(options: ElixirAst): ElixirAst? =
        ((options as? ElixirAst.ListNode)?.elements?.singleOrNull()?.takeIf { keyOf(it) == "do" } as? ElixirAst.Tuple)
            ?.elements?.get(1)

    private fun isOwned(definition: Definition, written: Set<NameArity>): Boolean =
        definition.definer in GUARD_DEFINERS ||
            definition.nameArity in COMPILER_ADDED && definition.nameArity !in written ||
            definition.nameArity == IS_RECORD && definition.definer in FUNCTION_DEFINERS

    private fun isGuardDefinition(node: ElixirAst): Boolean =
        ((node as? ElixirAst.Call)?.callee as? ElixirAst.Literal.Atom)?.name in GUARD_DEFINERS

    private val FUNCTION_DEFINERS = setOf("def", "defp")
    private val GUARD_DEFINERS = setOf("defguard", "defguardp")
    private val DEFINERS = FUNCTION_DEFINERS + GUARD_DEFINERS + setOf("defmacro", "defmacrop")

    /** What the compiler adds to a `.beam`. Only `behaviour_info/1` may also be a module's own, in a module with no callbacks. */
    private val COMPILER_ADDED = setOf(
        NameArity("__info__", 1),
        NameArity("module_info", 0),
        NameArity("module_info", 1),
        NameArity("behaviour_info", 1),
    )

    /** `:erlang`'s export, which a function in source can't be named. */
    private val IS_RECORD = NameArity("is_record", 2)

    /** The kinds a definition of a stub the pass drops raises. */
    private val DROPPED_KINDS = setOf("__info__", "module_info", "is_record")
}
