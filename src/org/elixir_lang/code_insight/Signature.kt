package org.elixir_lang.code_insight

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.beam.decompiler.generatedArguments
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.Exception as ElixirException
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * The parameters one definition of a function or macro takes, as text. The guard is not a parameter.
 */
data class Signature(val nameArityInterval: NameArityInterval, val parameters: List<String>) {
    /** What names a definition: its name and the arities it covers, by the largest, or by the least where open. */
    internal val definition: Pair<String, Pair<Int, Boolean>>
        get() = nameArityInterval.name to nameArityInterval.arityInterval.definition()

    /** How many default arguments it declares. */
    internal val defaults: Int
        get() = nameArityInterval.arityInterval.defaults()

    companion object {
        /** `null` for a declaration that is no definition to show, as a `@callback`. */
        @RequiresReadLock
        fun of(found: Found): Signature? = of(found.candidate.declaration, found.element)

        /**
         * [declaration] made by [element]: its name and arities are the declaration's, and the parameter text is read
         * from [element] by the form that made it.
         */
        @RequiresReadLock
        fun of(declaration: Declaration, element: PsiElement): Signature? {
            ThreadingAssertions.assertReadAccess()

            return when (val declared = declaration.declared) {
                is Declared.Source -> sourceSignature(declaration, declared.form, element as? Call)
                is Declared.Compiled -> (element as? BeamCallDefinition)?.let { of(it) }
            }
        }

        private fun sourceSignature(declaration: Declaration, form: Form, call: Call?): Signature? {
            if (call == null) return null

            val nameArityInterval = declaration.arity.arityInterval()?.let { NameArityInterval(declaration.name, it) }
                ?: return null
            val parameters = when (form) {
                Form.CLAUSE -> clause(call)
                Form.DELEGATION -> delegation(call)
                Form.EXCEPTION -> exception(declaration.name)
                Form.EEX_FUNCTION_FROM -> eexFunctionFrom(call)
                Form.GENERATOR_EMBED -> embed(call)
                Form.CALLBACK -> null
            }

            return parameters?.let { Signature(nameArityInterval, it.map(ParameterText::normalised)) }
        }

        private fun clause(clause: Call): List<String>? = CallDefinitionClause.head(clause)?.let(::headParameters)

        private fun delegation(defdelegate: Call): List<String>? = defdelegate.finalArguments()?.firstOrNull()?.let(::headParameters)

        private fun headParameters(head: PsiElement): List<String> =
            (CallDefinitionHead.strip(head) as? Call)?.let(CallDefinitionHead::parameters)?.map { it.text }.orEmpty()

        /** The two functions `defexception` defines take the other's name: `exception(message)`, `message(exception)`. */
        private fun exception(name: String): List<String> =
            when (name) {
                ElixirException.EXCEPTION.name -> listOf(ElixirException.MESSAGE.name)
                ElixirException.MESSAGE.name -> listOf(ElixirException.EXCEPTION.name)
                else -> emptyList()
            }

        /**
         * The macro's own `[:a, :b]` argument-name list, which no PSI head spells; a name that isn't an atom literal
         * leaves a slot called `arg`.
         */
        private fun eexFunctionFrom(call: Call): List<String> =
            call.finalArguments()
                ?.getOrNull(3)
                ?.let { (it.stripAccessExpression() as? ElixirList)?.children }
                ?.map { child -> child.stripAccessExpression().let { it as? ElixirAtom }?.let(::quotedAtomValue) ?: "arg" }
                .orEmpty()

        /** `embed_template` defines a function of `assigns`, `embed_text` one of nothing. */
        private fun embed(call: Call): List<String> =
            if (call.functionName() == "embed_template") listOf("assigns") else emptyList()

        /** A stub stores no parameters for a definition the decompiler did not render, so those get generated names. */
        fun of(definition: BeamCallDefinition): Signature {
            val arity = definition.nameArityInterval.arityInterval.minimum
            val parameters = definition.parameters.takeIf { it.size == arity } ?: generatedArguments(arity)

            return Signature(definition.nameArityInterval, parameters)
        }
    }
}

/** What names a definition among those of one name: the largest arity it covers, or the least where open, and whether it is open. */
internal fun ArityInterval.definition(): Pair<Int, Boolean> = (maximum ?: minimum) to (maximum == null)

/** How many arities below its largest the interval covers: the default arguments a definition declares. */
internal fun ArityInterval.defaults(): Int = definition().first - minimum
