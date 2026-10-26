package org.elixir_lang.model.psi.config

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.expander.EnvAtom
import org.elixir_lang.expander.ImportMatch
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.expander.findImportByNameArity
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call

/**
 * What an element of a setting or a read names in its file's expansion. Each answer is `null` in dumb mode, where the
 * file's SDK gives no inputs, and where the file's expansion did not reach the element.
 */
internal object KeyAtom {
    /** The atom [key], an alias or `__MODULE__` key, names. */
    @RequiresReadLock
    fun of(key: PsiElement): String? =
        expanded(key)?.let { expanded ->
            expanded.indexed.envs.atReached(key.textRange)?.let { env ->
                EnvAtom.of(key, env, expanded.indexed.lowering, expanded.inputs, expanded.holder)
            }
        }

    /** The module [qualifier], a remote call's, names. */
    @RequiresReadLock
    fun ofQualifier(qualifier: PsiElement): String? =
        expanded(qualifier)?.let { expanded ->
            val indexed = expanded.indexed

            EnvAtom.receiver(
                qualifier,
                indexed.receivers,
                indexed.envs.atReached(qualifier.textRange),
                indexed.lowering,
                expanded.inputs,
                expanded.holder,
            )
        }

    /** What the imports in [call]'s env give for an unqualified call of [name] and [arity]. */
    @RequiresReadLock
    fun importOf(call: Call, name: String, arity: Int): ImportMatch? =
        expanded(call)?.let { expanded ->
            val indexed = expanded.indexed

            indexed.envs.atReached(call.textRange)?.let { env ->
                findImportByNameArity(indexed.lowering.lower(call).meta, name, arity, emptyList(), env, expanded.inputs.level)
            }
        }

    private class Expanded(val indexed: ProjectModules.Indexed, val inputs: ProjectModules.Inputs, val holder: ProjectModules)

    @RequiresReadLock
    private fun expanded(element: PsiElement): Expanded? {
        val file = element.containingFile as? ElixirFile ?: return null
        val project = file.project
        if (DumbService.isDumb(project)) return null
        val inputs = (SdkInputs.getInstance(project).inputsFor(file) as? SdkInputs.Answer.Ready)?.inputs ?: return null
        val holder = ProjectModules.getInstance(project)

        return Expanded(holder.indexed(file, inputs), inputs, holder)
    }
}
