package org.elixir_lang.model.psi.words

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.indexing.DumbModeAccessType
import com.intellij.util.indexing.FileBasedIndex
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.injection.PsiLanguageInjectionHost

/**
 * The occurrences of the atom [atom] that the platform's literal word search for it misses (`M."fo\x6f"(1)` for `foo`,
 * `1 <~> 2` for `<~>`), each handed to [mapper] as that search hands its own.
 *
 * Over files, the candidates come from [SpellingsIndex]; over a scope's elements, each is walked. With [injections],
 * the Elixir of the template sigils (`~H`, `~E`, `~L`) is walked too, when sigils are injection hosts.
 */
internal class DecodedWordQuery<T : Any>(
    private val project: Project,
    private val atom: String,
    private val scope: SearchScope,
    private val mapper: WordOccurrenceMapper<T>,
    private val injections: Boolean
) : AbstractQuery<T>() {
    override fun processResults(consumer: Processor<in T>): Boolean =
        when (scope) {
            is LocalSearchScope -> scope.scope.all { processScopeElement(it, consumer) }
            is GlobalSearchScope -> processFiles(scope, consumer)
            else -> true
        }

    private fun processFiles(scope: GlobalSearchScope, consumer: Processor<in T>): Boolean {
        val candidates = candidateFiles(project, atom, scope, injections)
        val files = LinkedHashSet(candidates.spellings).apply { addAll(candidates.sigilHosts) }

        return files.all { file ->
            ProgressManager.checkCanceled()

            processInReadAction(consumer) { results ->
                PsiManager.getInstance(project).findFile(file)?.viewProvider?.getPsi(ElixirLanguage)?.let { root ->
                    if (file in candidates.spellings) collect(root, true, results)
                    if (file in candidates.sigilHosts) collectInjected(root, true, results)
                }
            }
        }
    }

    private fun processScopeElement(scopeElement: PsiElement, consumer: Processor<in T>): Boolean =
        processInReadAction(consumer) { results ->
            if (scopeElement.isValid) {
                val root = if (!scopeElement.isPhysical || scopeElement is PsiCompiledElement) {
                    scopeElement.navigationElement
                } else {
                    scopeElement
                }

                if (root !is PsiCompiledElement) {
                    collect(root, false, results)
                    if (injections && PsiLanguageInjectionHost.sigilsAreHosts()) collectInjected(root, false, results)
                }
            }
        }

    /** [fill]s a list of results and hands them on in one read action, as the consumer reads PSI too. */
    private fun processInReadAction(consumer: Processor<in T>, fill: (MutableList<T>) -> Unit): Boolean =
        runReadActionBlocking {
            val results = mutableListOf<T>()

            fill(results)

            results.all { consumer.process(it) }
        }

    private fun collect(root: PsiElement, global: Boolean, results: MutableList<T>) {
        atomOccurrences(root) { occurrence ->
            ProgressManager.checkCanceled()

            if (occurrence.name == atom && !Spellings.isFoundLiterally(occurrence, global)) {
                results += mapper.map(PsiTreeUtil.getDeepestFirst(occurrence.element), 0)
            }
        }
    }

    private fun collectInjected(root: PsiElement, global: Boolean, results: MutableList<T>) {
        val injected = InjectedLanguageManager.getInstance(project)

        for (sigil in templateSigils(root)) {
            injected.getInjectedPsiFiles(sigil).orEmpty()
                .mapNotNullTo(LinkedHashSet()) { it.first.containingFile.viewProvider.getPsi(ElixirLanguage) }
                .forEach { collect(it, global, results) }
        }
    }

    internal class CandidateFiles(val spellings: Collection<VirtualFile>, val sigilHosts: Collection<VirtualFile>)

    companion object {
        /**
         * The files a search over [scope] for [atom] walks: those [SpellingsIndex] holds under it and, when [injections]
         * and sigils are hosts, those with a template sigil the literal search cannot cover: one holding an escape or a
         * character beyond ASCII when the words index would find [atom] written literally, any template sigil otherwise.
         */
        internal fun candidateFiles(
            project: Project,
            atom: String,
            scope: GlobalSearchScope,
            injections: Boolean
        ): CandidateFiles =
            readIndex(project) {
                val index = FileBasedIndex.getInstance()
                val marker = if (Spellings.isIndexedAsWords(atom)) SpellingsIndex.ESCAPED_SIGIL_MARKER else SpellingsIndex.SIGIL_MARKER

                CandidateFiles(
                    index.getContainingFiles(Spellings.NAME, atom, scope),
                    if (injections && PsiLanguageInjectionHost.sigilsAreHosts()) {
                        index.getContainingFiles(Spellings.NAME, marker, scope)
                    } else {
                        emptyList()
                    }
                )
            }

        /** As the literal search reads the words index: in a read action, and in dumb mode for what is there. */
        private fun <R> readIndex(project: Project, compute: () -> R): R =
            runReadActionBlocking {
                if (DumbService.isDumb(project)) {
                    DumbModeAccessType.RAW_INDEX_DATA_ACCEPTABLE.ignoreDumbMode(ThrowableComputable<R, RuntimeException> { compute() })
                } else {
                    compute()
                }
            }
    }
}
