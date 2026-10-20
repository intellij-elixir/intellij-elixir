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
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.Exception as ElixirException
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
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
        fun of(found: Found): Signature? = found.head.written?.let { of(found.candidate.declaration, it) }

        /**
         * [declaration] written as [head], a [org.elixir_lang.declaration.Head.written]: its name and arities are the
         * declaration's, and the parameter text is read from [head] by the form that made it.
         */
        @RequiresReadLock
        private fun of(declaration: Declaration, head: PsiElement): Signature? {
            ThreadingAssertions.assertReadAccess()

            return when (val declared = declaration.declared) {
                is Declared.Source -> sourceSignature(declaration, declared.form, head)
                is Declared.Compiled -> (head as? BeamCallDefinition)?.let { of(it) }
            }
        }

        /** [declaration] as [call] writes it, at the head [call] declares it under. */
        @RequiresReadLock
        fun declaredBy(call: Call, declaration: Declaration): Signature? =
            DeclaringForm.writtenHead(call, declaration, module = null).written?.let { of(declaration, it) }

        private fun sourceSignature(declaration: Declaration, form: Form, head: PsiElement): Signature? {
            val nameArityInterval = declaration.arity.arityInterval()?.let { NameArityInterval(declaration.name, it) }
                ?: return null
            val parameters = when (form) {
                Form.CLAUSE, Form.DELEGATION -> headParameters(head)
                Form.EXCEPTION -> exception(declaration.name)
                Form.EEX_FUNCTION_FROM -> (head as? Call)?.let(::eexFunctionFrom)
                Form.GENERATOR_EMBED -> (head as? Call)?.let(::embed)
                Form.CALLBACK -> null
            }

            return parameters?.let { Signature(nameArityInterval, it.map(ParameterText::normalised)) }
        }

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

        /**
         * The head [definition] is written with, at each arity its defaults cover. A stub stores no parameters for a
         * definition the decompiler did not render, so those get generated names.
         */
        fun of(definition: BeamCallDefinition): Signature {
            val head = definition.head

            if (head.isNotEmpty()) {
                val arities = ParameterText.arities(head)

                return Signature(
                    NameArityInterval(definition.nameArityInterval.name, ArityInterval(arities.first, arities.last)),
                    head
                )
            }

            val arity = definition.nameArityInterval.arityInterval.minimum
            val parameters = definition.parameters.takeIf { it.size == arity } ?: generatedArguments(arity)

            return Signature(definition.nameArityInterval, parameters)
        }

        /** What a call at [definition]'s own arity passes: its head without the defaults that arity leaves out. */
        fun parametersAt(definition: BeamCallDefinition): List<String> {
            val parameters = of(definition).parameters

            return ParameterText.covered(parameters, definition.nameArityInterval.arityInterval.minimum) ?: parameters
        }
    }
}

/** What names a definition among those of one name: the largest arity it covers, or the least where open, and whether it is open. */
internal fun ArityInterval.definition(): Pair<Int, Boolean> = (maximum ?: minimum) to (maximum == null)

/** How many arities below its largest the interval covers: the default arguments a definition declares. */
internal fun ArityInterval.defaults(): Int = definition().first - minimum
