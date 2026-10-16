package org.elixir_lang.navigation

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.Declarations

/**
 * Concise presentation of an Elixir `def`/`defmacro` clause, or another declaring call, for navigation popups.
 *
 * Shared by [ElixirGotoTargetPresentationProvider] (Go To Implementation / Go To Target, via
 * `TargetPresentation`) and [ElixirImplementationCellRenderer] (the protocol gutter icon popup, via
 * `PsiElementListCellRenderer`) so both render each clause the same way:
 * ```
 * | icon  def to_string(%MyStruct{} = value)   MyApp.MyStruct   <module> |
 * ```
 * - [headText] is the head (definer + name + argument patterns), so sibling clauses that
 *   share a name/arity are told apart by their patterns.
 * - [containerText] is the implementing type: the module the `defimpl` is for, which without `for:` is the
 *   module it is written in (as in `defimpl String.Chars do` nested in a module).
 */
object ElixirClausePresentation {
    /** The head text of the first declaration [call] makes, or `null` when it declares nothing or its form has no text. */
    @RequiresReadLock
    fun headText(call: Call): String? =
        DeclaringForm.shapedForm(call)?.let { form ->
            Declarations.of(form, call, ResolveState.initial())
                .firstNotNullOfOrNull { it.declaration }
                ?.let { DeclarationHead.text(it, call) }
        }

    @RequiresReadLock
    fun containerText(call: Call): String? {
        val enclosingModular = enclosingModularMacroCall(call) ?: return null

        return if (Implementation.`is`(enclosingModular)) {
            Implementation.forText(enclosingModular) ?: Implementation.protocolName(enclosingModular)
        } else if (Module.`is`(enclosingModular)) {
            SyntacticCall.of(enclosingModular).canonicalName()?.let { org.elixir_lang.Module.inspect(it) }
        } else {
            null
        }
    }
}
