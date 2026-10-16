package org.elixir_lang.navigation

import com.intellij.psi.PsiNamedElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/** The head a declaring call writes a declaration with, as a user reads it. */
object DeclarationHead {
    /**
     * The definer and head [call] writes [declaration] with: `def to_string(%MyStruct{} = value)`, so sibling clauses
     * that share a name and arity are told apart by their patterns. A `Compiled` declaration is rendered over its
     * decompiled clause, [call]. `null` for the forms with no head text yet: EEx, embed, callback and exception.
     */
    @RequiresReadLock
    fun text(declaration: Declaration, call: Call): String? =
        when (val declared = declaration.declared) {
            is Declared.Compiled -> clause(call)
            is Declared.Source -> when (declared.form) {
                Form.CLAUSE -> clause(call)
                Form.DELEGATION -> delegation(call)
                Form.CALLBACK, Form.EXCEPTION, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> null
            }
        }

    private fun clause(call: Call): String {
        val head = CallDefinitionClause.head(call)?.text?.let(::normalizeSignature)

        return if (head != null) {
            call.functionName()?.let { definer -> "$definer $head" } ?: head
        } else {
            (call as? PsiNamedElement)?.name ?: normalizeSignature(call.text)
        }
    }

    /** The head as written, defaults included. */
    private fun delegation(call: Call): String? =
        Delegation.head(call)?.let { head -> "${call.functionName()} ${normalizeSignature(head.text)}" }

    /** Collapse whitespace and tighten bracket spacing, mirroring the structure-view head presentation. */
    private fun normalizeSignature(text: String): String =
        text
            .replace(Regex("\\s+"), " ")
            .replace(Regex("([\\[(]) ?"), "$1")
            .replace(Regex(" ?([])])"), "$1")
            .trim()
}
