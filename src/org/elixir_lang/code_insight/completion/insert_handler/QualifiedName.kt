package org.elixir_lang.code_insight.completion.insert_handler

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import org.elixir_lang.code.InspectAtom

/**
 * Inserts a function's name as Elixir writes it in a qualified position, then hands a [CALL] to
 * [CallDefinitionClause]. The lookup string stays the plain name, which is what matching uses.
 */
class QualifiedName private constructor(
    private val spell: (String) -> String,
    private val call: Boolean
) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val name = item.lookupString
        val start = context.startOffset
        val spelling = spell(name)

        if (spelling != name) {
            context.document.replaceString(start, context.tailOffset, spelling)
            context.tailOffset = start + spelling.length
        }

        if (call) CallDefinitionClause.WHOLE_HEAD.handleInsert(context, item)
    }

    companion object {
        /** `Mod.<caret>`: the name after `Mod.`, then the call. */
        val CALL = QualifiedName(InspectAtom::remoteCall, call = true)

        /** `&Mod.<caret>/arity`: the name after `Mod.`. */
        val CAPTURE = QualifiedName(InspectAtom::remoteCall, call = false)

        /** `apply(Mod, :<caret>, args)`: the function atom after the user's `:`. */
        val ATOM = QualifiedName(::atomAfterColon, call = false)

        /** `apply(Mod, :"<caret>", args)`: the function atom's body, between the user's [delimiter]s. */
        fun quotedAtom(delimiter: Char): QualifiedName =
            QualifiedName({ InspectAtom.escape(it, delimiter) }, call = false)

        /**
         * An alias, `nil`, `true` or `false` has a literal with no colon (`Elixir.Foo`'s is `Foo`), so after the user's
         * `:` it takes its key's spelling.
         */
        private fun atomAfterColon(name: String): String =
            InspectAtom.literal(name).let {
                if (it.startsWith(':')) it.substring(1) else InspectAtom.key(name).removeSuffix(":")
            }
    }
}
