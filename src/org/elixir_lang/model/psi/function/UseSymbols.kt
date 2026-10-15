package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.preferred
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.structure_view.element.Delegation

/**
 * The Symbols a use names, from the preference step: each admitted candidate's, and for a delegation those of what it
 * delegates to, or its own when it delegates to nothing admitted.
 */
@Suppress("UnstableApiUsage")
internal object UseSymbols {
    /** A qualified `unquote` can reach any function of its module, and a name with no atom value none: neither names one. */
    @RequiresReadLock
    fun of(use: Use.OfCall?): List<Symbol> =
        when (use) {
            is Use.Named -> of(use) { use.arity }
            is Use.AnyName, null -> emptyList()
        }

    /** A `@spec` names what its definition's head declares, so a head with defaults gives it the head's one Symbol. */
    @RequiresReadLock
    fun of(use: Use.Specified?): List<Symbol> =
        use?.let { specified ->
            of(specified) { found -> headArity(found.candidate.declaration) ?: specified.arity }
        }.orEmpty()

    @RequiresReadLock
    private fun of(use: Use, arity: (Found) -> Int): List<Symbol> =
        preferred(use, sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, false), false).flatMap { preferred ->
            val used = arity(preferred.found)
            val targetArity = targetArity(preferred.found.candidate.declaration, used)

            preferred.delegatedTo
                .flatMap { symbols(it, targetArity) }
                .ifEmpty { symbols(preferred.found, used) }
        }

    /** The arity of the one Symbol a head declares: its lowest, as a head with defaults declares one Symbol for its name. */
    fun headArity(declaration: Declaration): Int? = declaration.arity.arityInterval()?.minimum

    /** A delegation head with defaults calls its target with every argument. */
    private fun targetArity(delegation: Declaration, used: Int): Int =
        when (val arity = delegation.arity) {
            is ArityKnowledge.Exact -> arity.arity
            is ArityKnowledge.Range -> arity.maximum
            is ArityKnowledge.Open, ArityKnowledge.Unknown -> used
        }

    @RequiresReadLock
    private fun symbols(found: Found, arity: Int): List<Symbol> {
        if (!found.candidate.applicability.admitted) return emptyList()

        val declaration = found.candidate.declaration

        return when (val declared = declaration.declared) {
            is Declared.Source -> {
                val call = found.element as? Call ?: return emptyList()

                when (declared.form) {
                    Form.CLAUSE -> symbols(call, CallDefinitionClause.nameIdentifier(call), declaration, arity)
                    Form.DELEGATION -> symbols(call, Delegation.nameIdentifier(call), declaration, arity)
                    Form.EXCEPTION -> symbols(call, call.functionNameElement(), declaration, arity)
                    Form.EEX_FUNCTION_FROM -> symbols(call, call.finalArguments()?.getOrNull(1), declaration, arity)
                    Form.GENERATOR_EMBED -> symbols(call, call.finalArguments()?.firstOrNull(), declaration, arity)
                    Form.CALLBACK -> emptyList()
                }
            }
            is Declared.Compiled ->
                ((found.element as? BeamCallDefinition)?.navigationElement as? Call)
                    ?.let { symbols(it, CallDefinitionClause.nameIdentifier(it), declaration, arity) }
                    .orEmpty()
        }
    }

    @RequiresReadLock
    private fun symbols(call: Call, nameIdentifier: PsiElement?, declaration: Declaration, arity: Int): List<Symbol> {
        val range = nameIdentifier?.textRange ?: return emptyList()
        val modular = CallDefinitionClause.enclosingModularMacroCall(call) ?: return emptyList()
        val moduleName = runCatching { org.elixir_lang.psi.Module.name(modular) }
            .getOrElse { if (it is ProcessCanceledException) throw it else null }
            ?: return emptyList()
        val macro = FunctionSymbol.isMacro(declaration.capabilities)

        return listOf(
            if (Protocol.`is`(modular)) {
                ProtocolFunction(call.containingFile, range, moduleName, declaration.name, arity, macro)
            } else {
                // A decompiled clause's `originalFile` is the compiled file the decompiled editor opens.
                FunctionSymbol(call.containingFile.originalFile, range, moduleName, declaration.name, arity, macro)
            }
        )
    }
}
