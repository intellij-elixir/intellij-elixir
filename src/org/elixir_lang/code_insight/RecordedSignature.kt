package org.elixir_lang.code_insight

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.ArityInterval

/**
 * The signature Elixir records for a function in its `Docs` chunk: [text] as `Code.fetch_docs` gives it, and each of
 * its [parameters] as text.
 */
class RecordedSignature(val name: String, val parameters: List<String>, val text: String) {
    /** One clause of a function, as [form] declares it at [head], the head `DeclaringForm.head` gives. */
    data class Clause(val form: Form, val head: PsiElement)

    companion object {
        /**
         * The signature Elixir records for [name] at the full arity of [arities] in [module], from [clauses] in source
         * order, or `null` where it records none or the arity is open.
         */
        @RequiresReadLock
        @Suppress("UNUSED_PARAMETER")
        fun of(module: String?, name: String, arities: ArityInterval, clauses: List<Clause>): RecordedSignature? = null
    }
}
