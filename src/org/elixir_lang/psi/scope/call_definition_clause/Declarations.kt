package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Capabilities
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.Exception
import org.elixir_lang.psi.arityKnowledge
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.headAtomValue
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.impl.writtenAtomText
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Callback

/** The declarations a declaring call makes, with the arities the walk gives them. */
object Declarations {
    /**
     * A declaration beside [text], the walk's spelling of its name, which it matches the use and keys completion by.
     * [declaration] is `null` when the name has no atom value; otherwise [text] is that atom.
     */
    data class Spelled(val text: String, val declaration: Declaration?)

    private val PUBLIC_RUNTIME = runtime(Visibility.PUBLIC)
    private val PRIVATE_RUNTIME = runtime(Visibility.PRIVATE)
    private val UNDECIDED_RUNTIME = runtime(Visibility.UNDECIDED)

    @RequiresReadLock
    fun of(form: Form, call: Call, state: ResolveState): List<Spelled> {
        ThreadingAssertions.assertReadAccess()

        return when (form) {
            Form.CLAUSE -> clause(call, state)
            Form.CALLBACK -> callback(call, state)
            Form.DELEGATION -> delegation(call, state)
            Form.EXCEPTION -> exception(call)
            Form.EEX_FUNCTION_FROM -> eexFunctionFrom(call)
            Form.GENERATOR_EMBED -> generatorEmbed(call)
        }
    }

    /**
     * The declaration [call] makes that the walk spells [name], when that name has an atom value. No form gives two
     * declarations one name.
     */
    @RequiresReadLock
    fun named(form: Form, call: Call, name: String, state: ResolveState = ResolveState.initial()): Declaration? =
        of(form, call, state).firstOrNull { it.text == name }?.declaration

    private fun clause(call: Call, state: ResolveState): List<Spelled> {
        val declaration = CallDefinitionClause.declaration(call, state) ?: return emptyList()
        val atom = CallDefinitionClause.head(call)?.let(::headAtomValue)

        return listOf(Spelled(declaration.name, declaration.takeIf { atom != null }))
    }

    private fun callback(call: Call, state: ResolveState): List<Spelled> {
        val head = Callback.headCall(call as AtUnqualifiedNoParenthesesCall<*>) ?: return emptyList()
        val (name, arityInterval) = CallDefinitionHead.nameArityInterval(head, state) ?: return emptyList()
        val macro = Callback.Kind.of(call) == Callback.Kind.MACROCALLBACK

        return listOf(
            spelled(
                call,
                name,
                headAtomValue(head) != null,
                arityInterval.arityKnowledge(),
                Capabilities(quotesArguments = macro, compileTime = macro, usableInGuards = false, Visibility.PUBLIC),
                Form.CALLBACK
            )
        )
    }

    private fun delegation(call: Call, state: ResolveState): List<Spelled> {
        val head = call.finalArguments()?.takeIf { it.size == 2 }?.get(0) ?: return emptyList()
        val (name, arityInterval) = CallDefinitionHead.nameArityInterval(head, state) ?: return emptyList()

        return listOf(
            spelled(call, name, headAtomValue(head) != null, arityInterval.arityKnowledge(), PUBLIC_RUNTIME, Form.DELEGATION)
        )
    }

    private fun exception(call: Call): List<Spelled> =
        Exception.NAME_ARITY_LIST.map { (name, arity) ->
            spelled(call, name, true, ArityKnowledge.Exact(arity), PUBLIC_RUNTIME, Form.EXCEPTION)
        }

    private fun eexFunctionFrom(call: Call): List<Spelled> {
        val arguments = call.finalArguments() ?: return emptyList()
        val atom = arguments.getOrNull(1)?.stripAccessExpression() as? ElixirAtom ?: return emptyList()
        val arity = if (arguments.size >= 4) {
            (arguments[3].stripAccessExpression() as? ElixirList)?.children?.size
                ?.let { ArityKnowledge.Exact(it) }
                ?: ArityKnowledge.Unknown
        } else {
            ArityKnowledge.Exact(0)
        }
        // The kind is `bind_quoted`, so any other expression is known only once evaluated.
        val capabilities = when ((arguments[0].stripAccessExpression() as? ElixirAtom)?.let(::quotedAtomValue)) {
            "def" -> PUBLIC_RUNTIME
            "defp" -> PRIVATE_RUNTIME
            else -> UNDECIDED_RUNTIME
        }

        val name = quotedAtomValue(atom)

        return listOf(
            spelled(call, name ?: writtenAtomText(atom), name != null, arity, capabilities, Form.EEX_FUNCTION_FROM)
        )
    }

    private fun generatorEmbed(call: Call): List<Spelled> {
        val atom = call.finalArguments()?.firstOrNull()?.stripAccessExpression() as? ElixirAtom ?: return emptyList()
        val suffix = call.functionName()?.removePrefix("embed_") ?: return emptyList()
        // `embed_template` defines `name_template/1` only, but the walk has always resolved `name_template()` too.
        val arity = when (suffix) {
            "template" -> ArityKnowledge.Range(0, 1)
            "text" -> ArityKnowledge.Exact(0)
            else -> return emptyList()
        }

        val name = quotedAtomValue(atom)

        return listOf(
            spelled(
                call,
                "${name ?: writtenAtomText(atom)}_$suffix",
                name != null,
                arity,
                PRIVATE_RUNTIME,
                Form.GENERATOR_EMBED
            )
        )
    }

    private fun runtime(visibility: Visibility): Capabilities =
        Capabilities(quotesArguments = false, compileTime = false, usableInGuards = false, visibility)

    private fun spelled(
        call: Call,
        text: String,
        atom: Boolean,
        arity: ArityKnowledge,
        capabilities: Capabilities,
        form: Form
    ): Spelled =
        Spelled(
            text,
            Declaration(text, arity, capabilities, Declared.Source(form, CallDefinitionClause.sourceOrigin(call)))
                .takeIf { atom }
        )
}
