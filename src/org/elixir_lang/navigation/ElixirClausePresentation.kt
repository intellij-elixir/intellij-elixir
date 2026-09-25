package org.elixir_lang.navigation

import com.intellij.psi.PsiNamedElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.CallDefinitionClause.head
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.call.Call

/**
 * Concise presentation of an Elixir `def`/`defmacro` clause for navigation popups.
 *
 * Shared by [ElixirGotoTargetPresentationProvider] (Go To Implementation / Go To Target, via
 * `TargetPresentation`) and [ElixirImplementationCellRenderer] (the protocol gutter icon popup, via
 * `PsiElementListCellRenderer`) so both render each clause the same way:
 * ```
 * | icon  def to_string(%MyStruct{} = value)   MyApp.MyStruct   <module> |
 * ```
 * - [elementText] is the clause head (definer + name + argument patterns), so sibling clauses that
 *   share a name/arity are told apart by their patterns.
 * - [containerText] is the implementing type: the `defimpl ... for:` target, defaulting to the enclosing
 *   `defmodule` when `for:` is implicit (as in `defimpl String.Chars do` nested in a module).
 */
object ElixirClausePresentation {
    @RequiresReadLock
    fun elementText(call: Call): String {
        val head = head(call)?.text?.let(::normalizeSignature)

        return if (head != null) {
            call.functionName()?.let { definer -> "$definer $head" } ?: head
        } else {
            (call as? PsiNamedElement)?.name ?: normalizeSignature(call.text)
        }
    }

    /** The type that implements the protocol: the `defimpl ... for:` target, else the enclosing `defmodule`. */
    @RequiresReadLock
    fun containerText(call: Call): String? {
        val enclosingModular = enclosingModularMacroCall(call) ?: return null

        return if (Implementation.`is`(enclosingModular)) {
            val forNames = Implementation.forNameElement(enclosingModular)
                ?.let { Implementation.forNameCollection(it) }
                ?.takeIf { it.isNotEmpty() }

            forNames?.joinToString(", ")
                ?: enclosingModuleName(enclosingModular)
                ?: Implementation.protocolName(enclosingModular)
        } else if (Module.`is`(enclosingModular)) {
            moduleName(enclosingModular)
        } else {
            null
        }
    }

    /** The name of the `defmodule` enclosing [defimpl], which is the implicit `for:` target. */
    @RequiresReadLock
    private fun enclosingModuleName(defimpl: Call): String? =
        enclosingModularMacroCall(defimpl)
            ?.takeIf { Module.`is`(it) }
            ?.let { moduleName(it) }

    @RequiresReadLock
    private fun moduleName(module: Call): String? = Module.nameOrNull(module)

    /** Collapse whitespace and tighten bracket spacing, mirroring the structure-view head presentation. */
    private fun normalizeSignature(text: String): String =
        text
            .replace(Regex("\\s+"), " ")
            .replace(Regex("([\\[(]) ?"), "$1")
            .replace(Regex(" ?([])])"), "$1")
            .trim()
}
