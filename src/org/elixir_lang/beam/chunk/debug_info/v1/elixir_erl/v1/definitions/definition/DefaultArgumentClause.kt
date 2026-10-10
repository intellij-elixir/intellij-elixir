package org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.definition

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
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
    fun named(clauses: OtpErlangList, definition: Definition): OtpErlangList {
        val clause = clauses.singleOrNull() as? OtpErlangTuple ?: return clauses
        if (clause.arity() != Clause.EXPECTED_ARITY) return clauses

        val arguments = clause.elementAt(1).toOtpErlangList()
        if (arguments.arity() == 0 || !arguments.all { Clause.isGeneratedVariable(it) }) return clauses

        val superArguments = superArguments(clause.elementAt(3)) ?: return clauses
        val targetArguments = targetArguments(definition, superArguments.arity()) ?: return clauses

        val nameByGenerated = arguments.associateWith { argument ->
            superArguments.indexOf(argument)
                .takeIf { it >= 0 }
                ?.let { targetArguments.elementAt(it) }
                ?.takeIf { isNameable(it) }
                ?: return clauses
        }
        if (nameByGenerated.values.map(::variableName).distinct().size != nameByGenerated.size) return clauses

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
        val macro = definition.macro ?: return null
        val name = definition.name ?: return null
        val arity = definition.arity ?: return null
        val definitions = definition.debugInfo.definitions ?: return null

        val fullArity = defaultClause(definition)?.second?.arity() ?: arity
        val parameters = definitions[MacroNameArity(macro, name, fullArity)]
            ?.renderedClauses()
            ?.firstOrNull()
            ?.parameters
            ?.takeIf { it.size == fullArity }
            ?: return null

        val (ownArguments, superArguments) = (0 until fullArity).firstNotNullOfOrNull { lowerArity ->
            definitions[MacroNameArity(macro, name, lowerArity)]
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
     * The arguments of [definition]'s one clause and of the `super` call it is, when it is a default clause: every
     * argument is one `elixir_def` generated, and the call passes more. A user's `super` has named arguments.
     */
    private fun defaultClause(definition: Definition): Pair<OtpErlangList, OtpErlangList>? {
        val clause = definition.clausesTerm.let { it as? OtpErlangList }?.singleOrNull() as? OtpErlangTuple ?: return null
        if (clause.arity() != Clause.EXPECTED_ARITY) return null

        val arguments = clause.elementAt(1).toOtpErlangList()
        if (!arguments.all { Clause.isGeneratedVariable(it) }) return null

        return superArguments(clause.elementAt(3))
            ?.takeIf { it.arity() > arguments.arity() }
            ?.let { arguments to it }
    }

    private fun superArguments(block: OtpErlangObject): OtpErlangList? =
        (block as? OtpErlangTuple)
            ?.takeIf { it.arity() == 3 && (it.elementAt(0) as? OtpErlangAtom)?.atomValue() == "super" }
            ?.elementAt(2) as? OtpErlangList

    private fun targetArguments(definition: Definition, arity: Int): OtpErlangList? {
        val macro = definition.macro ?: return null
        val name = definition.name ?: return null
        val target = definition.debugInfo.definitions?.get(MacroNameArity(macro, name, arity)) ?: return null

        return (target.clausesTerm as? OtpErlangList)
            ?.firstOrNull()
            ?.let { it as? OtpErlangTuple }
            ?.takeIf { it.arity() == Clause.EXPECTED_ARITY }
            ?.elementAt(1)
            ?.toOtpErlangList()
    }

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
