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
}
