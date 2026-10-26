package org.elixir_lang.model.psi.config

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.expander.EnvAtom
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.psi.ElixirFile

/** The atom an alias or `__MODULE__` key names. */
internal object KeyAtom {
    /** `null` in dumb mode, where the file's SDK gives no inputs, and where the file's expansion did not reach [key]. */
    @RequiresReadLock
    fun of(key: PsiElement): String? {
        val file = key.containingFile as? ElixirFile ?: return null
        val project = file.project
        if (DumbService.isDumb(project)) return null
        val inputs = (SdkInputs.getInstance(project).inputsFor(file) as? SdkInputs.Answer.Ready)?.inputs ?: return null
        val holder = ProjectModules.getInstance(project)
        val indexed = holder.indexed(file, inputs)
        val env = indexed.envs.atReached(key.textRange) ?: return null

        return EnvAtom.of(key, env, indexed.lowering, inputs, holder)
    }
}
