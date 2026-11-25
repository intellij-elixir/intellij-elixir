package org.elixir_lang.code_insight.completion.insert_handler

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.code.InspectAtom.Form
import org.elixir_lang.code_insight.completion.contentAt
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.model.psi.atom.contentTextRange
import org.elixir_lang.psi.ElixirAtom

/**
 * Inserts a name as the file's Elixir parses it, then [after] with the caret before it, then hands a [CALL] to
 * [CallDefinitionClause]. The lookup string stays the plain name, which is what matching uses.
 */
class QualifiedName private constructor(
    private val spell: (String, ElixirLanguageLevel) -> String,
    private val call: Boolean,
    private val after: String = ""
) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val name = item.lookupString
        val start = context.startOffset
        val spelling = spell(name, ElixirLanguageLevelResolver.languageLevelFor(context.file))
        val text = spelling + after

        if (text != name) {
            context.document.replaceString(start, context.tailOffset, text)
            context.tailOffset = start + text.length
            context.editor.caretModel.moveToOffset(start + spelling.length)
        }

        if (call) CallDefinitionClause.WHOLE_HEAD.handleInsert(context, item)
    }

    companion object {
        /** `Mod.<caret>`: the name after `Mod.`, then the call. */
        val CALL = QualifiedName(spelt(Form.REMOTE_CALL), call = true)

        /** `&Mod.<caret>/arity`: the name after `Mod.`. */
        val CAPTURE = QualifiedName(spelt(Form.REMOTE_CALL), call = false)

        /**
         * `apply(Mod, :<caret>, args)` or a keyword key: the name after the user's `:` or before the key's, spelt as a
         * key's name: `ok` for `:ok`, `"Elixir.Foo"` for `Foo`.
         */
        val ATOM = QualifiedName(spelt(Form.KEY), call = false)

        /** A name written as an atom's literal: `:ok` for `ok`, `Foo` for `Elixir.Foo`. */
        val LITERAL = QualifiedName(spelt(Form.LITERAL), call = false)

        /** A keyword key being written in options: the key's name, then `: `. */
        val OPTION_KEY = QualifiedName(spelt(Form.KEY), call = false, after = ": ")

        /** `apply(Mod, :"<caret>", args)`: the function atom's body, between the user's [delimiter]s. */
        fun quotedAtom(delimiter: Char): QualifiedName =
            QualifiedName({ name, _ -> InspectAtom.escape(name, delimiter) }, call = false)

        /**
         * [atom] being written at [offset]: [quotedAtom] where [contentAt] puts [offset] between its quotes, else
         * [ATOM]. By default where its reference's range starts.
         */
        fun forAtom(atom: ElixirAtom, offset: Int = atom.textRange.startOffset + contentTextRange(atom).startOffset): QualifiedName =
            atom.line?.takeIf { contentAt(it, offset) != null }?.let { quotedAtom(if (it.isCharList) '\'' else '"') } ?: ATOM

        private fun spelt(form: Form): (String, ElixirLanguageLevel) -> String =
            { name, level -> InspectAtom.spelling(name, form, level) }
    }
}
