package org.elixir_lang.model.psi.words

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.sdk.PARSED_AS_ELIXIR

/**
 * The atoms a file spells where a literal word search for them finds nothing ([Spellings.isFoundLiterally]): the file of
 * `M."fo\x6f"(1)` is under `foo`, and the file of `1 <~> 2` under `<~>`. Every other name is left to the platform's
 * words index.
 *
 * Two more keys mark a file's template sigils (`~H`, `~E`, `~L`), which the index cannot read the Elixir of without
 * injecting: [SIGIL_MARKER] a file with one, and [ESCAPED_SIGIL_MARKER] one whose Elixir parts hold a `\` or a
 * character beyond ASCII.
 */
internal class SpellingsIndex : ScalarIndexExtension<String>() {
    override fun dependsOnFileContent() = true
    override fun getIndexer(): DataIndexer<String, Void, FileContent> = Spellings.INDEXER
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(*PARSED_AS_ELIXIR)
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getName() = Spellings.NAME
    override fun getVersion() = VERSION * 1000 + AtomName.VERSION

    companion object {
        /** Bumped when [Spellings.isFoundLiterally]'s global case or the markers change. */
        const val VERSION = 2

        const val SIGIL_MARKER = "~H"
        const val ESCAPED_SIGIL_MARKER = "~H\\"
    }
}
