package org.elixir_lang.model.psi.config

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/** A first-level [key] of [app] that a `config/2,3` call sets, written at [element]. */
class Set(val app: String, val key: WrittenKey, val element: PsiElement) {
    companion object {
        /**
         * The keys [call] sets: `config/2`'s keyword keys, or `config/3`'s atom or alias key, of a literal app. An
         * unqualified `config` sets keys only in a file that imports `Config` or `Mix.Config` at its top level.
         */
        @RequiresReadLock
        fun of(call: Call): List<Set> {
            if (call.resolvedFinalArity() != 3) return options(call)?.second.orEmpty()

            val arguments = arguments(call) ?: return emptyList()
            val app = app(arguments[0]) ?: return emptyList()
            val element = arguments[1]

            return when (val key = WrittenKey.of(element)) {
                is WrittenKey.Atom, WrittenKey.Alias -> listOf(Set(app, key, element))
                WrittenKey.Module, WrittenKey.Unknown, WrittenKey.All -> emptyList()
            }
        }

        /**
         * What [element] names as an argument of a `config` call that may still be being written: its first argument
         * the app, its second a key of a literal app, or a keyword key of `config/2`'s options or a bare name in their
         * list. A key written after those options is no argument: the comma before it parses as an error after the
         * call, beside [element].
         */
        @RequiresReadLock
        fun slotAt(element: PsiElement): Slot? {
            val call = Keys.call(element)?.takeIf { it.resolvedFinalArity() in 1..3 } ?: return keyAfterOptions(element)
            val arguments = arguments(call) ?: return keyAfterOptions(element)

            return when (element) {
                arguments[0] -> Slot.App
                arguments.getOrNull(1) -> app(arguments[0])?.let { Slot.Key(it, options = call.resolvedFinalArity() == 2) }
                else -> options(call)?.let { (app, sets) ->
                    when {
                        sets.any { it.element == element } -> optionsKey(app, sets.filter { it.element != element })
                        element.parent == arguments[1] -> optionsKey(app, sets)
                        else -> null
                    }
                }
            }
        }

        private fun keyAfterOptions(element: PsiElement): Slot? {
            val comma = previous(element)?.takeIf { it is PsiErrorElement && it.firstChild?.node?.elementType == ElixirTypes.COMMA }
                ?: return null
            val (app, sets) = (previous(comma) as? Call)?.let(::options) ?: return null

            return optionsKey(app, sets)
        }

        /** A keyword key of [app]'s options, which set [others] besides. */
        private fun optionsKey(app: String, others: List<Set>): Slot =
            Slot.Key(app, options = true, written = others.mapNotNull { (it.key as? WrittenKey.Atom)?.name })

        /**
         * The literal app of a `config/2` call with an options list, and the keys those options set: none for a list
         * that holds no keywords yet.
         */
        private fun options(call: Call): Pair<String, List<Set>>? {
            if (call.resolvedFinalArity() != 2) return null
            val arguments = arguments(call) ?: return null
            val app = app(arguments[0]) ?: return null
            val pairs = when (val options = arguments[1]) {
                is QuotableKeywordList -> options.quotableKeywordPairList()
                is ElixirList -> options.keywords?.quotableKeywordPairList().orEmpty()
                else -> return null
            }

            return app to pairs.mapNotNull { pair ->
                val keywordKey = pair.keywordKey

                AtomName.of(keywordKey)?.let { Set(app, WrittenKey.Atom(it), keywordKey) }
            }
        }

        private fun previous(element: PsiElement): PsiElement? =
            generateSequence(element.prevSibling) { it.prevSibling }
                .firstOrNull { it !is PsiWhiteSpace && it.node.elementType != ElixirTypes.EOL }

        /**
         * [call]'s arguments, as [Read.arguments] gives them, when it is a `config` call: an unqualified one only in a
         * file that imports `Config` or `Mix.Config` at its top level.
         */
        private fun arguments(call: Call): List<PsiElement>? {
            val arity = call.resolvedFinalArity()
            if (!isConfig(call, arity) || call !is Qualified && !importsConfig(call.containingFile)) return null

            return Read.arguments(call).takeIf { it.size == arity }
        }

        private fun app(argument: PsiElement): String? = (argument as? ElixirAtom)?.let { AtomName.of(it) }

        private fun isConfig(call: Call, arity: Int): Boolean =
            if (call is Qualified) {
                call.isCalling(CONFIG, CONFIG_FUNCTION, arity) || call.isCalling(MIX_CONFIG, CONFIG_FUNCTION, arity)
            } else {
                call.functionName() == CONFIG_FUNCTION
            }

        private fun importsConfig(file: PsiFile): Boolean =
            file.children.any { child ->
                child is Call && child !is Qualified && child.functionName().let { it == "import" || it == "use" } &&
                    child.finalArguments()
                        ?.firstOrNull()
                        ?.let { moduleName(it.stripAccessExpressionAndParentheses()) }
                        ?.name
                        .let { name -> name == MIX_CONFIG || name == CONFIG && child.functionName() == "import" }
            }

        private const val CONFIG = "Config"
        private const val MIX_CONFIG = "Mix.Config"
        private const val CONFIG_FUNCTION = "config"
    }
}
