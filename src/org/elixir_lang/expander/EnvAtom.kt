package org.elixir_lang.expander

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Import.Term

/** The atom an element names in an env. */
internal object EnvAtom {
    /** The atom [element] expands to in [env]; `null` where it expands to none. */
    @RequiresReadLock
    fun of(element: PsiElement, env: Env, lowering: Lowering, inputs: ProjectModules.Inputs, holder: ProjectModules): String? {
        val file = element.containingFile as? ElixirFile ?: return null
        val expansion = Expander.expand(
            lowering.lower(element),
            ExState.empty(inputs.level),
            env,
            inputs.level,
            { holder.exports(file, it, inputs) },
            { holder.struct(file, it, inputs) },
        )

        return ((expansion as? Expansion.Expanded)?.value as? Term.Atom)?.name
    }

    /**
     * The module [qualifier], a remote call's, expanded to in its file's expansion, or where the expansion didn't reach
     * it, expands to in [env]; `null` where it expands to no atom, or where it was not reached and [env] is `null`.
     */
    @RequiresReadLock
    fun receiver(
        qualifier: PsiElement,
        receivers: ReceiverIndex,
        env: Env?,
        lowering: Lowering,
        inputs: ProjectModules.Inputs,
        holder: ProjectModules,
    ): String? =
        when (val receiver = receivers.at(qualifier.textRange)) {
            is ReceiverIndex.Receiver.Module -> receiver.name
            ReceiverIndex.Receiver.Unknown -> null
            ReceiverIndex.Receiver.Unreached -> env?.let { of(qualifier, it, lowering, inputs, holder) }
        }
}
