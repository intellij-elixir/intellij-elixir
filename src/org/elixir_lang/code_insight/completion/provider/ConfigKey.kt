package org.elixir_lang.code_insight.completion.provider

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.code_insight.completion.insert_handler.QualifiedName
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.model.psi.config.ConfigNames
import org.elixir_lang.model.psi.config.Keys
import org.elixir_lang.model.psi.config.Slot
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirIdentifier
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall

/**
 * Completion of an application env app or first-level key at a read's or a `config` call's argument: the apps and keys
 * written anywhere, spelled for where the caret is.
 */
class ConfigKey : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, resultSet: CompletionResultSet) {
        val position = parameters.position
        val written = written(position) ?: position
        val slot = Keys.slotAt(written) ?: return
        val project = position.project
        val names = when (slot) {
            Slot.App -> ConfigNames.apps(project)
            is Slot.Key -> ConfigNames.keys(project, slot.app) - slot.written.toSet()
        }
        val insertHandler = insertHandler(written, options = slot is Slot.Key && slot.options)
        val result = resultSet.withPrefixMatcher(
            written.text.substring(Keys.range(written).startOffset, parameters.offset - written.textRange.startOffset)
        )
        // The original file is indexed, so the name as written there, caret anywhere in it, is among the names.
        val asWritten = parameters.originalPosition
            ?.takeIf { it.textRange.startOffset == position.textRange.startOffset }
            ?.let { original ->
                if (written is ElixirAtom) {
                    PsiTreeUtil.getParentOfType(original, ElixirAtom::class.java)?.let(AtomName::of)
                } else {
                    original.text
                }
            }

        for (name in names) {
            ProgressManager.checkCanceled()
            if (name != asWritten) result.addElement(LookupElementBuilder.create(name).withInsertHandler(insertHandler))
        }
    }

    /**
     * The argument being written at [position]: an atom with a literal name, an alias, an identifier, or a keyword key,
     * if any.
     */
    private fun written(position: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(position, ElixirAtom::class.java)?.takeIf { AtomName.of(it) != null }
            ?: when (val parent = position.parent) {
                is QualifiableAlias, is ElixirKeywordKey -> parent
                is ElixirIdentifier -> parent.parent as? UnqualifiedNoArgumentsCall<*>
                else -> null
            }

    /**
     * How a name is written over [written]: after its `:` or between its quotes; as a keyword key, before its colon or
     * with one where it is written in [options]; else as an atom.
     */
    private fun insertHandler(written: PsiElement, options: Boolean): InsertHandler<LookupElement> =
        if (written is ElixirAtom) {
            QualifiedName.forAtom(written)
        } else {
            val spell: (String) -> String = when {
                written is ElixirKeywordKey -> { name -> InspectAtom.key(name).removeSuffix(":") }
                options -> { name -> "${InspectAtom.key(name)} " }
                else -> InspectAtom::literal
            }

            InsertHandler { insertion, item ->
                val spelling = spell(item.lookupString)
                insertion.document.replaceString(insertion.startOffset, insertion.tailOffset, spelling)
                insertion.tailOffset = insertion.startOffset + spelling.length
            }
        }
}
