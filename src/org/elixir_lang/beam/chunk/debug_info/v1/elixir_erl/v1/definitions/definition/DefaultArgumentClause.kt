package org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.definition

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.Macro
import org.elixir_lang.beam.MacroNameArity
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.Definition
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.toOtpErlangList

/**
 * Elixir compiles `def f(a, b \\ 1)` into `f/2` and a clause `def f(x0), do: super(x0, 1)`. That clause's
 * arguments are variables `elixir_def` generates, so they are renamed after the arguments of the clause `super`
 * calls, when those are all plain variables.
 */
internal object DefaultArgumentClause {
    /** [definition]'s clauses with its default clause's arguments renamed, or `null` when there is none to rename. */
    fun named(definition: Definition): OtpErlangList? {
        val (arguments, superArguments) = defaultClause(definition)?.takeIf { (arguments, _) -> arguments.arity() > 0 }
            ?: return null
        val targetArguments = target(definition)?.let(::firstClauseArguments) ?: return null

        val nameByGenerated = arguments.associateWith { argument ->
            superArguments.indexOf(argument)
                .takeIf { it >= 0 }
                ?.let { targetArguments.elementAt(it) }
                ?.takeIf { isNameable(it) }
                ?: return null
        }
        if (nameByGenerated.values.map(::variableName).distinct().size != nameByGenerated.size) return null

        val clause = (definition.clausesTerm as OtpErlangList).elementAt(0) as OtpErlangTuple
        val renamed = OtpErlangTuple(
            arrayOf(
                clause.elementAt(0),
                OtpErlangList(arguments.map { nameByGenerated.getValue(it) }.toTypedArray()),
                clause.elementAt(2),
                rename(clause.elementAt(3), nameByGenerated)
            )
        )

        return OtpErlangList(arrayOf<OtpErlangObject>(renamed))
    }

    /**
     * The head [definition] is written with, `q, x \\ nil` for `def f(q, x \\ nil)`: the first clause of the full-arity
     * definition, with `\\ default` at each position the lowest-arity default clause fills. `null` when no default
     * clause targets it. [definition] may be the full-arity one or any default clause.
     */
    fun head(definition: Definition): List<String>? {
        val target = target(definition) ?: return null
        val fullArity = target.arity ?: return null
        val parameters = target.renderedClauses()
            ?.firstOrNull()
            ?.parameters
            ?.takeIf { it.size == fullArity }
            ?: return null

        val (ownArguments, superArguments) = (0 until fullArity).firstNotNullOfOrNull { lowerArity ->
            namesake(definition, lowerArity)
                ?.let(::defaultClause)
                ?.takeIf { (_, superArguments) -> superArguments.arity() == fullArity }
        } ?: return null

        return parameters.mapIndexed { index, parameter ->
            val argument = superArguments.elementAt(index)

            if (argument in ownArguments) {
                parameter
            } else {
                "$parameter \\\\ ${ParameterText.normalised(Macro.toString(argument))}"
            }
        }
    }

    /**
     * The arguments of the debug info [clause] and of the `super` call it is, when it is the clause `elixir_def`
     * generates for another definition's defaults: every argument is one it generated, and the call passes more. A
     * user's `super` has named arguments.
     */
    fun generated(clause: OtpErlangObject): Pair<OtpErlangList, OtpErlangList>? {
        if (clause !is OtpErlangTuple || clause.arity() != Clause.EXPECTED_ARITY) return null

        val arguments = clause.elementAt(1).toOtpErlangList()
        if (!arguments.all { Clause.isGeneratedVariable(it) }) return null

        return superArguments(clause.elementAt(3))
            ?.takeIf { it.arity() > arguments.arity() }
            ?.let { arguments to it }
    }

    /** The full-arity definition [definition] calls `super` on when it is a default clause, else [definition]. */
    fun target(definition: Definition): Definition? {
        val (_, superArguments) = defaultClause(definition) ?: return definition

        return namesake(definition, superArguments.arity())
    }

    /** [generated] of [definition]'s one clause. */
    private fun defaultClause(definition: Definition): Pair<OtpErlangList, OtpErlangList>? =
        (definition.clausesTerm as? OtpErlangList)?.singleOrNull()?.let(::generated)

    /** The arguments of the `super` call [block], which a list of small integers decodes to an [OtpErlangString] as. */
    private fun superArguments(block: OtpErlangObject): OtpErlangList? =
        (block as? OtpErlangTuple)
            ?.takeIf { it.arity() == 3 && (it.elementAt(0) as? OtpErlangAtom)?.atomValue() == "super" }
            ?.elementAt(2)
            ?.takeIf { it is OtpErlangList || it is OtpErlangString }
            ?.toOtpErlangList()

    /** The definition with [definition]'s macro and name at [arity]. */
    private fun namesake(definition: Definition, arity: Int): Definition? {
        val macro = definition.macro ?: return null
        val name = definition.name ?: return null

        return definition.debugInfo.definitions?.get(MacroNameArity(macro, name, arity))
    }

    private fun firstClauseArguments(definition: Definition): OtpErlangList? =
        (definition.clausesTerm as? OtpErlangList)
            ?.firstOrNull()
            ?.let { it as? OtpErlangTuple }
            ?.takeIf { it.arity() == Clause.EXPECTED_ARITY }
            ?.elementAt(1)
            ?.toOtpErlangList()

    private fun isNameable(term: OtpErlangObject): Boolean =
        Macro.isVariable(term) && !Clause.isGeneratedVariable(term) && variableName(term) != "_"

    private fun variableName(term: OtpErlangObject): String =
        ((term as OtpErlangTuple).elementAt(0) as OtpErlangAtom).atomValue()

    private fun rename(term: OtpErlangObject, nameByGenerated: Map<OtpErlangObject, OtpErlangObject>): OtpErlangObject =
        nameByGenerated[term]
            ?: when (term) {
                is OtpErlangTuple -> OtpErlangTuple(term.elements().map { rename(it, nameByGenerated) }.toTypedArray())
                is OtpErlangList -> {
                    val elements = term.elements().map { rename(it, nameByGenerated) }.toTypedArray()

                    term.lastTail
                        ?.let { OtpErlangList(elements, rename(it, nameByGenerated)) }
                        ?: OtpErlangList(elements)
                }
                else -> term
            }
}
