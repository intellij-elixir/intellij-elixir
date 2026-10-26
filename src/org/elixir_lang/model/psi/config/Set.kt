package org.elixir_lang.model.psi.config

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
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
            val arity = call.resolvedFinalArity()
            if (arity != 2 && arity != 3 || !isConfig(call, arity)) return emptyList()

            val arguments = Read.arguments(call).takeIf { it.size == arity } ?: return emptyList()
            val app = (arguments[0] as? ElixirAtom)?.let { AtomName.of(it) } ?: return emptyList()
            if (call !is Qualified && !importsConfig(call.containingFile)) return emptyList()

            return if (arity == 2) {
                keywords(arguments[1])?.quotableKeywordPairList().orEmpty().mapNotNull { pair ->
                    val keywordKey = pair.keywordKey

                    AtomName.of(keywordKey)?.let { Set(app, WrittenKey.Atom(it), keywordKey) }
                }
            } else {
                val element = arguments[1]

                when (val key = WrittenKey.of(element)) {
                    is WrittenKey.Atom, WrittenKey.Alias -> listOf(Set(app, key, element))
                    WrittenKey.Module, WrittenKey.Unknown, WrittenKey.All -> emptyList()
                }
            }
        }

        /** `config/2`'s options: trailing keywords, or a list of them. */
        private fun keywords(options: PsiElement): QuotableKeywordList? =
            when (options) {
                is QuotableKeywordList -> options
                is ElixirList -> options.keywords
                else -> null
            }

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
