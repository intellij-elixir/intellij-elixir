package org.elixir_lang.model.psi.words

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.injection.sigilElixirParts
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine

/** What [SpellingsIndex] keys a file by, and the rule that says which atoms it keys. */
internal object Spellings {
    val NAME = ID.create<String, Void>("elixir.spellings")

    val INDEXER = DataIndexer<String, Void, FileContent> { inputData ->
        val keys = mutableMapOf<String, Void?>()

        if (inputData.project != null) {
            inputData.psiFile.viewProvider.getPsi(ElixirLanguage)?.let { root ->
                atomOccurrences(root) { occurrence ->
                    if (!isFoundLiterally(occurrence, global = true)) keys[occurrence.name] = null
                }

                markSigils(root, keys)
            }
        }

        keys
    }

    /**
     * Whether the platform's literal word search delivers [occurrence], [global] for a search over files or not for
     * one over a scope's elements: its file's text holds the atom wholly inside the element's range, and an
     * identifier-like atom is not run into a neighbouring identifier character other than `$`. A search over files
     * also needs the words index to hold each of the atom's words ([isIndexedAsWords]).
     */
    fun isFoundLiterally(occurrence: AtomName.Occurrence, global: Boolean): Boolean {
        val atom = occurrence.name

        return atom.isEmpty() ||
            (!global || isIndexedAsWords(atom)) &&
            spelledInside(atom, occurrence.element.containingFile.viewProvider.contents, occurrence.element.textRange)
    }

    /**
     * Whether the words scanner the Elixir file types use makes a word of each of [atom]'s words: none over 100
     * characters or holding `$`, each starting on a letter, a digit or a Java identifier start. An atom with no
     * words, as an operator's, is looked up whole.
     */
    fun isIndexedAsWords(atom: String): Boolean =
        StringUtil.getWordsIn(atom).ifEmpty { listOf(atom.trim()) }.all(::isScannerWord)

    private fun isScannerWord(word: String): Boolean =
        word.isNotEmpty() && word.length <= MAX_WORD_LENGTH && '$' !in word && word.first().let {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || Character.isJavaIdentifierStart(it)
        }

    private fun spelledInside(atom: String, text: CharSequence, range: TextRange): Boolean {
        val runsIntoNeighbours = Character.isJavaIdentifierPart(atom.first()) && Character.isJavaIdentifierPart(atom.last())

        // Only the element's own range is searched; the text beyond it is read for the neighbours alone.
        return (range.startOffset..range.endOffset - atom.length).any { start ->
            text.startsWith(atom, start) &&
                (!runsIntoNeighbours || !(isIdentifierNeighbour(text, start - 1) || isIdentifierNeighbour(text, start + atom.length)))
        }
    }

    private fun isIdentifierNeighbour(text: CharSequence, index: Int): Boolean =
        index in text.indices && text[index] != '$' && Character.isJavaIdentifierPart(text[index])

    private fun markSigils(root: PsiElement, keys: MutableMap<String, Void?>) {
        for (sigil in templateSigils(root)) {
            keys[SpellingsIndex.SIGIL_MARKER] = null

            if (SpellingsIndex.ESCAPED_SIGIL_MARKER !in keys && elixirParts(sigil).any { part -> part.any { it == '\\' || it.code > MAX_ASCII } }) {
                keys[SpellingsIndex.ESCAPED_SIGIL_MARKER] = null
            }
        }
    }

    /** The Elixir parts of [sigil]'s body, as the lexer of the language it is injected with cuts them. */
    private fun elixirParts(sigil: Sigil): Sequence<CharSequence> {
        val body = when (sigil) {
            is SigilLine -> sigil.body?.text
            is SigilHeredocLiteral -> sigil.heredocLineList.joinToString("") { it.text }
            else -> null
        }

        return if (body == null) emptySequence() else sigilElixirParts(sigil.sigilName(), body).orEmpty()
    }

    private const val MAX_WORD_LENGTH = 100
    private const val MAX_ASCII = 127
}
