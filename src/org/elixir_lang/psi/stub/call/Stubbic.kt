package org.elixir_lang.psi.stub.call

import com.intellij.psi.PsiReference
import org.elixir_lang.psi.Definition
import org.elixir_lang.psi.call.Call

interface Stubbic {
    val definition: Definition?
    val name: String?

    /**
     * If [definition] is [Definition.IMPLEMENTATION], this is the name of the implemented protocol.
     */
    val implementedProtocolName: String?

    /**
     * These names do not depend on aliases or nested modules.
     *
     * @return the canonical texts of the reference
     * @see PsiReference.getCanonicalText
     */
    fun canonicalNameSet(): Set<String>

    /**
     * Whether this call has a `do` block or a `:do` keyword, so it is a macro
     *
     * @return `true` if [Call.getDoBlock] is NOT `null` or there is a `"do"` keyword argument
     * @see org.elixir_lang.psi.impl.call.keywordArgument
     */
    fun hasDoBlockOrKeyword(): Boolean

    /**
     * The final arity that is non-`null`.
     *
     * @return [Call.resolvedSecondaryArity] if it is not `null`; [Call.resolvedPrimaryArity] if
     * it is not `null`; otherwise, `0`.
     */
    fun resolvedFinalArity(): Int

    /**
     * @return name of the function/macro after taking into account any imports
     */
    fun resolvedFunctionName(): String?

    /**
     * @return name of the qualifying module after taking into account any aliases
     */
    fun resolvedModuleName(): String?
}
