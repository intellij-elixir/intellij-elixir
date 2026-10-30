package org.elixir_lang.expander

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/** The atom an element names in an env. */
internal object EnvAtom {
    /**
     * The atom [element] expands to in [env]; `null` where it expands to none.
     *
     * @param asker the file whose loaded modules the expansion sees
     */
    @RequiresReadLock
    fun of(
        element: PsiElement,
        env: Env,
        lowering: Lowering,
        inputs: ProjectModules.Inputs,
        holder: ProjectModules,
        asker: PsiFile = element.containingFile,
    ): String? {
        val file = asker as? ElixirFile ?: return null
        val expansion = Expander.expand(
            lowering.lower(element),
            ExState.empty(inputs.level),
            env,
            inputs.level,
            { holder.exports(file, it, inputs) },
            { holder.struct(file, it, inputs) },
            holder.appEnv(inputs),
        )

        return ((expansion as? Expansion.Expanded)?.value as? Term.Atom)?.name
    }

    /**
     * The module [written], a remote call's qualifier, expanded to in its file's expansion, or where the expansion didn't
     * reach it, expands to in [env]; `null` where it expands to no atom, or where it was not reached and [env] is `null`.
     * The expansion records the qualifier inside any parentheses.
     *
     * @param receivers the receivers [written]'s file's expansion recorded; `null` for a template's file, which no
     * expansion enters
     * @param asker the file whose loaded modules the expansion sees
     */
    @RequiresReadLock
    fun receiver(
        written: PsiElement,
        receivers: ReceiverIndex?,
        env: Env?,
        lowering: Lowering,
        inputs: ProjectModules.Inputs,
        holder: ProjectModules,
        asker: PsiFile = written.containingFile,
    ): String? {
        val qualifier = written.stripAccessExpressionAndParentheses()

        return when (val receiver = receivers?.at(qualifier.textRange) ?: ReceiverIndex.Receiver.Unreached) {
            is ReceiverIndex.Receiver.Module -> receiver.name
            ReceiverIndex.Receiver.Unknown -> null
            ReceiverIndex.Receiver.Unreached -> env?.let { of(qualifier, it, lowering, inputs, holder, asker) }
        }
    }
}
