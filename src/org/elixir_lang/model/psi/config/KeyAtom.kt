package org.elixir_lang.model.psi.config

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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

    /**
     * What this object's answers for [file]'s elements depend on beyond [file]'s own text: its expansion's env index,
     * the same object while what the expansion read is unchanged, or [Unexpanded] for a file nothing expands; `null` in
     * dumb mode and where its SDK gives no inputs, which may yet change.
     */
    @RequiresReadLock
    fun basis(file: PsiFile): Any? =
        when (val expansion = expansion(file)) {
            is Expanded -> expansion.indexed.envs
            else -> expansion
        }

    /** How a file's elements are named: through its [Expanded] expansion, or, for a file nothing expands, from its text alone. */
    private sealed interface Expansion

    private data object Unexpanded : Expansion

    private class Expanded(val indexed: ProjectModules.Indexed, val inputs: ProjectModules.Inputs, val holder: ProjectModules) : Expansion

    @RequiresReadLock
    private fun expanded(element: PsiElement): Expanded? = element.containingFile?.let(::expansion) as? Expanded

    /** [file]'s expansion; `null` in dumb mode and where its SDK gives no inputs. */
    @RequiresReadLock
    private fun expansion(file: PsiFile): Expansion? {
        val project = file.project
        if (DumbService.isDumb(project)) return null

        return when (val answer = SdkInputs.getInstance(project).inputsFor(file)) {
            is SdkInputs.Answer.Ready -> (file as? ElixirFile)?.let {
                val holder = ProjectModules.getInstance(project)

                Expanded(holder.indexed(it, answer.inputs), answer.inputs, holder)
            }
            SdkInputs.Answer.Template -> Unexpanded
            else -> null
        }
    }
}
