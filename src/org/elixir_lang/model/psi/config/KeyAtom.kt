package org.elixir_lang.model.psi.config

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.expanderPathOn
import org.elixir_lang.expander.EnvAtom
import org.elixir_lang.expander.ImportMatch
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.ReceiverIndex
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.expander.findImportByNameArity
import org.elixir_lang.injection.sigilInjection
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/**
 * What an element of a setting or a read names in the env `at` compiles in, which [place] gives. Each answer is `null`
 * while the expander's path is closed, in dumb mode, where the file's SDK gives no inputs, and where the file's expansion
 * did not reach `at`.
 */
internal object KeyAtom {
    /** The atom [key], an alias or `__MODULE__` key, names; `null` also where [at] is. */
    @RequiresReadLock
    fun of(key: PsiElement, at: PsiElement? = place(key)): String? {
        if (at == null) return null
        val expanded = expanded(at) ?: return null
        val env = expanded.indexed.envs.atReached(at.textRange) ?: return null

        return expanded.lowering(key)?.let { lowering -> EnvAtom.of(key, env, lowering, expanded.inputs, expanded.holder, expanded.file) }
    }

    /**
     * The module [qualifier], a remote call's, names: [Named.Unknown] where the expansion reached it but names no module,
     * as for a variable whose value isn't known; `null` where it did not reach it.
     */
    @RequiresReadLock
    fun receiver(qualifier: PsiElement, at: PsiElement): Named? {
        val expanded = expanded(at) ?: return null
        val indexed = expanded.indexed
        val receivers = indexed.receivers.takeIf { qualifier.containingFile == expanded.file }
        val env = indexed.envs.atReached(at.textRange)
        val recorded = receivers?.at(qualifier.stripAccessExpressionAndParentheses().textRange) ?: ReceiverIndex.Receiver.Unreached
        if (env == null && recorded == ReceiverIndex.Receiver.Unreached) return null
        val lowering = expanded.lowering(qualifier) ?: return null

        return EnvAtom.receiver(qualifier, receivers, env, lowering, expanded.inputs, expanded.holder, expanded.file)
            ?.let(Named::Module)
            ?: Named.Unknown
    }

    /** What [receiver] names. */
    sealed interface Named {
        data class Module(val name: String) : Named

        data object Unknown : Named
    }

    /** What the imports in [call]'s env give for an unqualified call of [name] and [arity]. */
    @RequiresReadLock
    fun importOf(call: Call, name: String, arity: Int, at: PsiElement): ImportMatch? =
        expanded(at)?.let { expanded ->
            expanded.indexed.envs.atReached(at.textRange)?.let { env ->
                expanded.lowering(call)?.let { lowering ->
                    findImportByNameArity(lowering.lower(call).meta, name, arity, emptyList(), env, expanded.inputs.level)
                }
            }
        }

    /**
     * Where the env that names [element] is: [element] itself, or, in a template [sigil] holds, what
     * [TemplateReads.placeOf] gives, which is `null` where only the template's own Elixir could tell.
     */
    @RequiresReadLock
    fun place(element: PsiElement, sigil: Sigil? = injectedSigil(element)): PsiElement? =
        if (sigil == null) element else TemplateReads.placeOf(element, sigil)

    /**
     * What this object's answers for [file]'s elements depend on beyond [file]'s own text: its expansion's env index,
     * the same object while what the expansion read is unchanged, [Unexpanded] for a file nothing expands, or [Closed]
     * while the expander's path is closed; `null` in dumb mode and where its SDK gives no inputs, which may yet change.
     */
    @RequiresReadLock
    fun basis(file: PsiFile): Any? =
        when (val expansion = expansion(file)) {
            is Expanded -> expansion.indexed.envs
            else -> expansion
        }

    /** The template sigil [element]'s injected template is in. */
    @RequiresReadLock
    fun injectedSigil(element: PsiElement): Sigil? =
        (InjectedLanguageManager.getInstance(element.project).getInjectionHost(element) as? Sigil)?.takeIf { sigilInjection(it) != null }

    /**
     * How a file's elements are named: through its [Expanded] expansion, or, for a file nothing expands ([Unexpanded]) and
     * while the expander's path is closed ([Closed]), from its text alone.
     */
    private sealed interface Expansion

    private data object Unexpanded : Expansion

    private data object Closed : Expansion

    private class Expanded(
        val file: ElixirFile,
        val indexed: ProjectModules.Indexed,
        val inputs: ProjectModules.Inputs,
        val holder: ProjectModules,
    ) : Expansion {
        /** The lowering of [element]'s file: [file]'s own, or a template's. */
        @RequiresReadLock
        fun lowering(element: PsiElement): Lowering? =
            when (val elementFile = element.containingFile) {
                file -> indexed.lowering
                is ElixirFile -> Lowering.of(elementFile, indexed.lowering.languageLevel)
                else -> null
            }
    }

    @RequiresReadLock
    private fun expanded(at: PsiElement): Expanded? = at.containingFile?.let(::expansion) as? Expanded

    /** [file]'s expansion; `null` in dumb mode and where its SDK gives no inputs. */
    @RequiresReadLock
    private fun expansion(file: PsiFile): Expansion? =
        when (val asked = asked(file.project) { file }) {
            Asked.Closed -> Closed
            Asked.Dumb, Asked.NoFile -> null
            is Asked.Answered -> when (val answer = asked.answer) {
                is SdkInputs.Answer.Ready -> (file as? ElixirFile)?.let {
                    val holder = ProjectModules.getInstance(file.project)

                    Expanded(it, holder.indexed(it, answer.inputs), answer.inputs, holder)
                }
                SdkInputs.Answer.Template -> Unexpanded
                else -> null
            }
        }

    /** What asking for a file's inputs gave: [Closed], [Dumb] and [NoFile] before the SDK is asked. */
    private sealed interface Asked {
        data object Closed : Asked

        data object Dumb : Asked

        data object NoFile : Asked

        data class Answered(val answer: SdkInputs.Answer) : Asked
    }

    /**
     * The one place the expander's path, dumb mode and the SDK's inputs are asked for the file [file] gives. The file is
     * made only once the path is open and the index is ready, so a closed path creates no PSI.
     */
    @RequiresReadLock
    private fun asked(project: Project, file: () -> PsiFile?): Asked {
        if (!expanderPathOn()) return Asked.Closed
        if (DumbService.isDumb(project)) return Asked.Dumb

        val psi = file() ?: return Asked.NoFile

        return Asked.Answered(SdkInputs.getInstance(project).inputsFor(psi))
    }

    /**
     * The config files the `:dev`, `:test` and `:prod` reads of the content [mixExs] files import, each distinct set of
     * inputs asked once; none while the expander's path is closed, in dumb mode and where no file's SDK gives inputs, and
     * none, with nothing evaluated, unless [needed]: a build may read a file the config folder doesn't hold. [needed] is
     * asked only once the path is open, so a closed one reads nothing.
     */
    @RequiresReadLock
    fun reachedConfigFiles(
        project: Project,
        mixExs: Collection<VirtualFile>,
        needed: () -> Boolean,
    ): kotlin.collections.Set<VirtualFile> {
        val manager = PsiManager.getInstance(project)
        val inputs = mixExs.mapNotNullTo(LinkedHashSet()) {
            ((asked(project) { manager.findFile(it) } as? Asked.Answered)?.answer as? SdkInputs.Answer.Ready)?.inputs
        }
        if (inputs.isEmpty() || !needed()) return emptySet()
        val holder = ProjectModules.getInstance(project)

        return inputs.flatMapTo(HashSet(), holder::reachedConfigFiles)
    }
}
