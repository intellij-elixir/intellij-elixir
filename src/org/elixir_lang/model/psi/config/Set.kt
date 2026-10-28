package org.elixir_lang.model.psi.config

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.MacroRole
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirBitString
import org.elixir_lang.psi.ElixirCharToken
import org.elixir_lang.psi.ElixirDecimalFloat
import org.elixir_lang.psi.ElixirKeywords
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirMapOperation
import org.elixir_lang.psi.ElixirParentheticalStab
import org.elixir_lang.psi.ElixirStab
import org.elixir_lang.psi.ElixirStructOperation
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.Quote
import org.elixir_lang.psi.WholeNumber
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.blockKeyword
import org.elixir_lang.psi.impl.call.blockKeywordPairs
import org.elixir_lang.psi.impl.childExpressions
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/** A first-level [key] of [app] that a `config/2,3` call sets, written at [element]. */
class Set(val app: String, val key: WrittenKey, val element: PsiElement) {
    companion object {
        /** The keys [call] sets where it is `Config`'s or `Mix.Config`'s `config/2,3` ([isConfigCall]). */
        @RequiresReadLock
        fun of(call: Call): List<Set> = candidates(call).takeIf { it.isNotEmpty() && isConfigCall(call) }.orEmpty()

        /**
         * The keys [call] sets where it is a `config/2,3` call, whatever module it calls: `config/2`'s keyword keys, or
         * `config/3`'s atom or alias key, of a literal app.
         */
        @RequiresReadLock
        fun candidates(call: Call): List<Set> {
            if (call.resolvedFinalArity() != 3) return options(call)?.sets.orEmpty()

            val arguments = arguments(call) ?: return emptyList()
            val app = app(arguments[0]) ?: return emptyList()
            val element = arguments[1]

            return when (val key = WrittenKey.of(element)) {
                is WrittenKey.Atom, WrittenKey.Alias -> listOf(Set(app, key, element))
                WrittenKey.Module, WrittenKey.Unknown, WrittenKey.All -> emptyList()
            }
        }

        /**
         * Where [call] may set keys of an app that [of] can't name, whatever module it calls: a `config/2,3` call whose
         * key, options or app [candidates] can't read, at the argument it can't read; or a call of a name and arity that
         * writes the env as the file runs, at what names the app. Each is filed under its app, or
         * [ConfigIndexKey.AnyApp] where the app can't be read.
         */
        @RequiresReadLock
        fun unread(call: Call): List<Pair<ConfigIndexKey, PsiElement>> = listOfNotNull(unreadSetting(call)) + writes(call)

        /** The modules whose [name] and [arity] are the call [candidates], [unread] or [imported] names. */
        fun modules(name: String, arity: Int): List<String> =
            if (isConfigName(name, arity)) CONFIG_MODULES else WRITES[Signature(name, arity)]?.let { listOf(it.module) }.orEmpty()

        /**
         * Where [file]'s value may be its config, as `Config.__eval__!` takes a list the file returns when no `config`
         * call ran: its last expression, filed under [ConfigIndexKey.AnyApp], when no `config` call at the file's top
         * level runs before it and it may be a list that no `config` call ran before ([mayBeConfig]).
         */
        @RequiresReadLock
        fun returned(file: PsiFile): PsiElement? =
            returnedCandidate(file)?.takeIf { candidate ->
                file.childExpressions().none(::runsConfig) && mayBeConfig(candidate)
            }

        /**
         * Whether [element] may be a list that no `config` call ran before: a list, or a value that may be one. A pipe
         * gives the value of the call it pipes into. A form whose value is a block's last expression
         * ([MacroRole.Value.BLOCK_TAIL]) may be one where one of those may be, and no `config` call in that block runs
         * before it. Any other call may give any value.
         */
        @RequiresReadLock
        private fun mayBeConfig(element: PsiElement): Boolean {
            val value = element.stripAccessExpressionAndParentheses()
            if (value is ElixirList || value is QuotableKeywordList) return true
            Keys.pipedInto(value)?.let { return mayBeConfig(it) }
            if (notAList(value)) return false
            val blocks = (value as? Call)
                ?.takeIf { valueOf(it) == MacroRole.Value.BLOCK_TAIL }
                ?.let(::blocks) ?: return true

            return blocks.isEmpty() ||
                blocks.any { statements ->
                    statements.dropLast(1).none(::runsConfig) && statements.lastOrNull()?.let(::mayBeConfig) == true
                }
        }

        /** Whether the statement [element] is a `config` call, or pipes into one. */
        @RequiresReadLock
        private fun runsConfig(element: PsiElement): Boolean =
            when (val value = element.stripAccessExpressionAndParentheses()) {
                is Call -> Keys.pipedInto(value)?.let(::runsConfig) ?: isConfigCall(value)
                else -> false
            }

        /** The statements of each of [call]'s blocks, or of each clause of one: its `do` and the blocks after it. */
        @RequiresReadLock
        private fun blocks(call: Call): List<List<PsiElement>> {
            val doBlock = call.doBlock ?: return call.blockKeywordPairs().filter { it.blockKeyword() != null }.flatMap { pair ->
                when (val value = pair.keywordValue) {
                    is ElixirParentheticalStab -> value.stab?.let(::blocks).orEmpty()
                    else -> listOf(listOf(value))
                }
            }

            return (listOfNotNull(doBlock.stab) + doBlock.blockList?.blockItemList.orEmpty().mapNotNull { it.stab }).flatMap(::blocks)
        }

        /** The statements of [stab]'s body, or of each of its clauses' bodies. */
        @RequiresReadLock
        private fun blocks(stab: ElixirStab): List<List<PsiElement>> =
            stab.stabBody?.let { listOf(it.childExpressions().toList()) }
                ?: stab.stabOperationList.map { it.stabBody?.childExpressions()?.toList().orEmpty() }

        /** [file]'s last expression where, as written, it may be a list, which [returned] decides. */
        @RequiresReadLock
        fun returnedCandidate(file: PsiFile): PsiElement? =
            file.childExpressions(forward = false).firstOrNull()?.stripAccessExpressionAndParentheses()?.takeUnless(::notAListAsWritten)

        /**
         * The argument of [call] when it is an `import_config/1` call, whatever module it calls, which reads the file it
         * names.
         */
        @RequiresReadLock
        fun imported(call: Call): PsiElement? =
            call.takeIf { it.functionName() == IMPORT_CONFIG_FUNCTION && it.resolvedFinalArity() == 1 }
                ?.let { Read.arguments(it).singleOrNull() }

        private fun unreadSetting(call: Call): Pair<ConfigIndexKey, PsiElement>? {
            val arity = call.resolvedFinalArity()
            if (call.functionName() != CONFIG_FUNCTION || arity != 2 && arity != 3) return null
            val arguments = Read.arguments(call).takeIf { it.size == arity } ?: return null
            val app = app(arguments[0])?.let(ConfigIndexKey::App) ?: return ConfigIndexKey.AnyApp to arguments[0]
            val read = when (arity) {
                3 -> candidates(call).isNotEmpty()
                else -> options(call)?.complete == true
            }

            return if (read) null else app to arguments[1]
        }

        /**
         * Where [call], of a name and arity that writes the env, writes an app's env: the whole call, under the app its
         * first argument names, for a write of one app; each keyword key, under its app, of a write of a keyword list of
         * apps, and the list where one of its entries isn't a keyword.
         */
        private fun writes(call: Call): List<Pair<ConfigIndexKey, PsiElement>> {
            val arity = call.resolvedFinalArity()
            val writer = call.functionName()?.let { WRITES[Signature(it, arity)] }?.writer ?: return emptyList()
            val arguments = Read.arguments(call).takeIf { it.size == arity && arity > 0 } ?: return emptyList()
            val first = arguments[0]

            return when (writer) {
                Writer.ONE -> listOf((app(first)?.let(ConfigIndexKey::App) ?: ConfigIndexKey.AnyApp) to call)
                Writer.KEYWORDS -> {
                    val pairs = keywordPairs(first)
                    val apps = pairs?.mapNotNull { pair ->
                        pair?.keywordKey?.let { key -> AtomName.of(key)?.let { ConfigIndexKey.App(it) to key } }
                    }

                    if (apps != null && apps.size == pairs.size) apps else listOf(ConfigIndexKey.AnyApp to first)
                }
                Writer.TUPLES -> listOf(ConfigIndexKey.AnyApp to first)
            }
        }

        /** Whether [call] writes the env: it calls a module whose function of its name and arity does. */
        @RequiresReadLock
        private fun isWrite(call: Call): Boolean {
            val module = CallModule.of(call) ?: return false
            val writes = WRITES[Signature(module.name, module.arity)] ?: return false

            return module.reaches(call, listOf(writes.module)) == CallModule.Answer.REACHES
        }

        /** What a call that writes the env writes. */
        private enum class Writer {
            /** One app's key. */
            ONE,

            /** The apps a keyword list's keys name. */
            KEYWORDS,

            /** The apps a list of `{app, env}` tuples names, which isn't read. */
            TUPLES,
        }

        private data class Signature(val name: String, val arity: Int)

        /** What a function of [module] that writes the env writes. */
        private class Writes(val writer: Writer, val module: String)

        private val WRITES: Map<Signature, Writes> =
            listOf(
                Triple(ELIXIR_APPLICATION, Signature("put_env", 3), Writer.ONE),
                Triple(ELIXIR_APPLICATION, Signature("put_env", 4), Writer.ONE),
                Triple(ELIXIR_APPLICATION, Signature("delete_env", 2), Writer.ONE),
                Triple(ELIXIR_APPLICATION, Signature("delete_env", 3), Writer.ONE),
                Triple(ELIXIR_APPLICATION, Signature("put_all_env", 1), Writer.KEYWORDS),
                Triple(ELIXIR_APPLICATION, Signature("put_all_env", 2), Writer.KEYWORDS),
                Triple(ERLANG_APPLICATION, Signature("set_env", 1), Writer.TUPLES),
                Triple(ERLANG_APPLICATION, Signature("set_env", 2), Writer.TUPLES),
                Triple(ERLANG_APPLICATION, Signature("set_env", 3), Writer.ONE),
                Triple(ERLANG_APPLICATION, Signature("set_env", 4), Writer.ONE),
                Triple(ERLANG_APPLICATION, Signature("unset_env", 2), Writer.ONE),
                Triple(ERLANG_APPLICATION, Signature("unset_env", 3), Writer.ONE),
                Triple(MIX_CONFIG, Signature("persist", 1), Writer.KEYWORDS),
            ).associate { (module, signature, writer) -> signature to Writes(writer, module) }

        /**
         * What [element] names as an argument of a `config` call that may still be being written: its first argument
         * the app, its second a key of a literal app, or a keyword key of `config/2`'s options or a bare name in their
         * list. A key written after those options is no argument: the comma before it parses as an error after the
         * call, beside [element].
         */
        @RequiresReadLock
        fun slotAt(element: PsiElement): Slot? {
            val call = Keys.call(element)?.takeIf { it.resolvedFinalArity() in 1..3 && isConfigCallBeingWritten(it) }
                ?: return keyAfterOptions(element)
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
            val (app, sets) = (previous(comma) as? Call)?.takeIf(::isConfigCall)?.let(::options) ?: return null

            return optionsKey(app, sets)
        }

        /** A keyword key of [app]'s options, which set [others] besides. */
        private fun optionsKey(app: String, others: List<Set>): Slot =
            Slot.Key(app, options = true, written = others.mapNotNull { (it.key as? WrittenKey.Atom)?.name })

        /**
         * The literal app of a `config/2` call with an options list, and the keys those options set: none for a list
         * that holds no keywords yet.
         */
        private fun options(call: Call): Options? {
            if (call.resolvedFinalArity() != 2) return null
            val arguments = arguments(call) ?: return null
            val app = app(arguments[0]) ?: return null
            val entries = keywordPairs(arguments[1]) ?: return null
            val sets = entries.mapNotNull { pair ->
                val keywordKey = pair?.keywordKey ?: return@mapNotNull null

                AtomName.of(keywordKey)?.let { Set(app, WrittenKey.Atom(it), keywordKey) }
            }

            return Options(app, sets, complete = sets.size == entries.size)
        }

        /** A `config/2` call's literal [app], the keys its options set, and whether each of its options sets one. */
        private data class Options(val app: String, val sets: List<Set>, val complete: Boolean)

        /** The entries of [list], a list as written: each keyword pair, and `null` for any other entry. */
        private fun keywordPairs(list: PsiElement): List<QuotableKeywordPair?>? =
            when (list) {
                is QuotableKeywordList -> list.quotableKeywordPairList()
                is ElixirList -> list.children.flatMap { (it as? ElixirKeywords)?.quotableKeywordPairList() ?: listOf(null) }
                else -> null
            }

        private fun previous(element: PsiElement): PsiElement? =
            generateSequence(element.prevSibling) { it.prevSibling }
                .firstOrNull { it !is PsiWhiteSpace && it.node.elementType != ElixirTypes.EOL }

        /** [call]'s arguments, as [Read.arguments] gives them, when it is named `config`, whatever module it calls. */
        private fun arguments(call: Call): List<PsiElement>? {
            if (call.functionName() != CONFIG_FUNCTION) return null

            return Read.arguments(call).takeIf { it.size == call.resolvedFinalArity() }
        }

        private fun app(argument: PsiElement): String? = (argument as? ElixirAtom)?.let { AtomName.of(it) }

        /** Whether [call] is `Config`'s or `Mix.Config`'s `config/2,3`. */
        private fun isConfigCall(call: Call): Boolean {
            val arity = call.resolvedFinalArity()

            return (arity == 2 || arity == 3) && isConfigFunction(call, CONFIG_FUNCTION, arity)
        }

        /** Whether [call], a `config` call that may still be being written, is `Config`'s or `Mix.Config`'s at an arity. */
        private fun isConfigCallBeingWritten(call: Call): Boolean =
            (maxOf(call.resolvedFinalArity(), 2)..3).any { arity ->
                CallModule(CallModule.qualifier(call), CONFIG_FUNCTION, arity).reaches(call, CONFIG_MODULES) == CallModule.Answer.REACHES
            }

        /** Whether [call] is `Config`'s or `Mix.Config`'s [name]/[arity]. */
        @RequiresReadLock
        internal fun isConfigFunction(call: Call, name: String, arity: Int): Boolean =
            call.functionName() == name && call.resolvedFinalArity() == arity &&
                CallModule.of(call)?.reaches(call, CONFIG_MODULES) == CallModule.Answer.REACHES

        private fun isConfigName(name: String, arity: Int): Boolean =
            name == CONFIG_FUNCTION && (arity == 2 || arity == 3) || name == IMPORT_CONFIG_FUNCTION && arity == 1

        /**
         * Whether [element] is no list: as written ([notAListAsWritten]), or a call of `Config` or one that writes the
         * env, each of which returns `:ok`.
         */
        private fun notAList(element: PsiElement): Boolean =
            notAListAsWritten(element) ||
                element is Call && (isConfigCall(element) || isConfigFunction(element, IMPORT_CONFIG_FUNCTION, 1) || isWrite(element))

        /**
         * Whether [element], as written, is no list: a literal of another type, a module, or a call whose value is never
         * config ([MacroRole.Value.NEVER_CONFIG]).
         */
        private fun notAListAsWritten(element: PsiElement): Boolean =
            when (element) {
                is ElixirAtom, is WholeNumber, is ElixirDecimalFloat, is ElixirCharToken, is ElixirTuple, is ElixirBitString,
                is ElixirMapOperation, is ElixirStructOperation -> true
                is Quote -> !element.isCharList
                is Call -> valueOf(element) == MacroRole.Value.NEVER_CONFIG
                else -> false
            }

        private const val ELIXIR_APPLICATION = "Elixir.Application"
        private const val ERLANG_APPLICATION = "application"
        private const val MIX_CONFIG = "Elixir.Mix.Config"
        private val CONFIG_MODULES = listOf("Elixir.Config", MIX_CONFIG)
        private const val CONFIG_FUNCTION = "config"
        private const val IMPORT_CONFIG_FUNCTION = "import_config"

        /**
         * What [call]'s value can be, where it is unqualified and so may be the `Kernel` or `Kernel.SpecialForms` macro of
         * its name.
         */
        private fun valueOf(call: Call): MacroRole.Value =
            call.takeIf { it !is Qualified }?.functionName()?.let(MacroRole::listed)?.value ?: MacroRole.Value.ANY
    }
}
