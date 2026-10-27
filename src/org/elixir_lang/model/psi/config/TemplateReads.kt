package org.elixir_lang.model.psi.config

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.indexing.FileBasedIndex
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.injection.sigilInjection
import org.elixir_lang.model.psi.words.Spellings
import org.elixir_lang.model.psi.words.SpellingsIndex
import org.elixir_lang.model.psi.words.templateSigils
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.ALIAS
import org.elixir_lang.psi.call.name.Function.IMPORT
import org.elixir_lang.psi.call.name.Function.REQUIRE
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/**
 * The reads inside template sigils (`~H`, `~E`, `~L`), whether or not sigils are injection hosts: each sigil's injected
 * text is parsed as a file of the language the injector gives it, and [Read.of] runs on that file's Elixir.
 */
internal object TemplateReads {
    /**
     * [read], in a template, whose key is at [range] in [host]. [keyAt] is where the env that names the key is, as
     * [placeOf] gives.
     */
    class TemplateRead(val read: Read, val host: Sigil, val range: TextRange, val keyAt: PsiElement?)

    /** The files in [scope] with a template sigil. */
    @RequiresReadLock
    fun files(scope: GlobalSearchScope): Collection<VirtualFile> =
        FileBasedIndex.getInstance().getContainingFiles(Spellings.NAME, SpellingsIndex.SIGIL_MARKER, scope)

    /** The reads in [file]'s template sigils. */
    @RequiresReadLock
    fun of(file: PsiFile): List<TemplateRead> =
        file.viewProvider.getPsi(ElixirLanguage)?.let(::templateSigils).orEmpty().flatMap(::of)

    private fun of(sigil: Sigil): List<TemplateRead> {
        val injection = sigilInjection(sigil) ?: return emptyList()
        val hostText = sigil.text
        val template = PsiFileFactory.getInstance(sigil.project).createFileFromText(
            injection.extension?.let { "template.$it" } ?: "template",
            injection.language,
            injection.ranges.joinToString("") { it.substring(hostText) },
            false,
            false,
        )
        val elixir = template.viewProvider.getPsi(ElixirLanguage) ?: return emptyList()
        val reads = mutableListOf<TemplateRead>()

        PsiTreeUtil.processElements(elixir, Call::class.java) { call ->
            Read.of(call)?.let { read ->
                val range = Keys.range(read.element).shiftRight(read.element.textRange.startOffset)

                reads += TemplateRead(
                    read,
                    sigil,
                    TextRange(inHost(injection.ranges, range.startOffset), inHost(injection.ranges, range.endOffset - 1) + 1),
                    placeOf(read.element, sigil),
                )
            }

            true
        }

        return reads
    }

    /**
     * Where the env that names [element], in the Elixir of a template [sigil] holds, is: [sigil], or `null` where the
     * template's own Elixir before it could rename it, which [sigil]'s env does not see. That is an element written as
     * an alias, inside any parentheses, after an `alias` or `require ... as:`, and a read called unqualified after an
     * `import`.
     */
    @RequiresReadLock
    fun placeOf(element: PsiElement, sigil: Sigil): PsiElement? {
        val renames: (Call) -> Boolean =
            when (val written = element.stripAccessExpressionAndParentheses()) {
                is QualifiableAlias ->
                    { call -> call.isCalling(KERNEL, ALIAS) || call.isCalling(KERNEL, REQUIRE) && call.keywordArgument("as") != null }
                is Call if written !is Qualified && Read.of(written) != null -> { call -> call.isCalling(KERNEL, IMPORT) }
                else -> return sigil
            }

        return sigil.takeUnless {
            PsiTreeUtil.findChildrenOfType(element.containingFile, Call::class.java).any { call ->
                call.textRange.startOffset < element.textRange.startOffset && renames(call)
            }
        }
    }

    /** The offset in the host of [offset] in the text [ranges] join to. */
    private fun inHost(ranges: List<TextRange>, offset: Int): Int {
        var start = 0

        for (range in ranges) {
            if (offset < start + range.length) return range.startOffset + offset - start

            start += range.length
        }

        return ranges.last().endOffset
    }
}
