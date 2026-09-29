package org.elixir_lang.psi.scope

import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.navigation.isDecompiled
import org.elixir_lang.psi.impl.QuotableImpl.normalizeIdentifier

/**
 * How a declared name matches a name being looked up, treating two spellings the compiler normalizes to the
 * same identifier (decomposed `c` + COMBINING ACUTE ACCENT and precomposed `ć`) as the same name.
 */
enum class NameMatch {
    NONE,

    /** The declared name only starts with the query: a completion candidate, never a declaration of it. */
    PREFIX,
    EXACT;

    companion object {
        /** [name], written at [site], as a lookup compares it; normalize once, on entry, never per candidate. */
        @RequiresReadLock
        fun query(name: String, site: PsiElement): String = identifier(name, site)

        /** How [candidate], declared at [element], matches [query], which must have come from [NameMatch.query]. */
        @RequiresReadLock
        fun of(query: String, candidate: String, element: PsiElement): NameMatch {
            val candidateIdentifier = identifier(candidate, element)

            return when {
                candidateIdentifier == query -> EXACT
                candidateIdentifier.startsWith(query) -> PREFIX
                else -> NONE
            }
        }

        /** [name], declared at [element], as an index keys it, so a [query] finds it by its identifier. */
        @RequiresReadLock
        fun key(name: String, element: PsiElement): String = identifier(name, element)

        /** Whether [name] declared at [element] and [otherName] declared at [otherElement] are one identifier. */
        @RequiresReadLock
        fun same(name: String, element: PsiElement, otherName: String, otherElement: PsiElement): Boolean =
            identifier(name, element) == identifier(otherName, otherElement)

        /**
         * A compiled name is the atom as the compiler emitted it: already normalized if Elixir compiled it, and
         * never normalized if Erlang did, so it is compared as it is.
         */
        private fun identifier(name: String, element: PsiElement): String =
            if (element is PsiCompiledElement || element.isDecompiled()) name else normalizeIdentifier(name, element)
    }
}
