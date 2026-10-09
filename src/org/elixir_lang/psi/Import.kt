package org.elixir_lang.psi

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.isAncestor
import com.intellij.psi.util.parents
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Arity
import org.elixir_lang.Name
import org.elixir_lang.NameArity
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.isCompilerAdded
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.declaration.Capabilities
import org.elixir_lang.declaration.Reach
import org.elixir_lang.language_level.ElixirLanguageFeature.DIGITS_IN_SIGIL_NAMES
import org.elixir_lang.language_level.ElixirLanguageFeature.IMPORT_ONLY_SIGILS
import org.elixir_lang.language_level.ElixirLanguageFeature.IMPORT_ONLY_SIGILS_READS_SIGIL_NAMES
import org.elixir_lang.language_level.ElixirLanguageFeature.IMPORT_VALIDATES_EXCEPT_FIRST
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.IMPORT
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordArguments
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.scope.Recording
import org.elixir_lang.psi.scope.reachedThrough
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Delegation
import java.math.BigInteger

/**
 * An `import` call
 */
object Import {
    /**
     * The options of the `import` a definition was reached through. The walk admits a definition when any arity it
     * covers is admitted; a use of one arity checks that arity against this.
     */
    val FILTER = Key<Filter>("Import.FILTER")

    /** What a module exports, or what an `import` of it brings in: its functions and its macros. */
    data class Imports(val functions: Set<NameArity>, val macros: Set<NameArity>) {
        fun of(macro: Boolean): Set<NameArity> = if (macro) macros else functions
    }

    /** An option term as the compiler reads it once the options are expanded. */
    sealed class Term {
        data class Atom(val name: String) : Term()

        data class Integer(val value: BigInteger) : Term()

        /** @property bytes `null` where the content isn't known, as `__DIR__`'s isn't */
        class Binary(val bytes: ByteArray?) : Term()

        /** @property tail what follows a `|` in the last element, as in `[h | t]` */
        data class List(val elements: kotlin.collections.List<Term>, val tail: Term? = null) : Term()

        /** A two-element tuple, as each keyword pair is. */
        data class Pair(val first: Term, val second: Term) : Term()

        /** A tuple of any other size, whose elements are known: `{}`, `{a}`, `{a, b, c}`. */
        data class Tuple(val elements: kotlin.collections.List<Term>) : Term()

        /** An expression as AST: a variable, a pinned variable, one `returns_boolean/1` holds of, or any other node. */
        data class Node(val kind: Kind) : Term() {
            enum class Kind { VARIABLE, PIN, BOOLEAN, OTHER }
        }

        /** A float. Readers type it as a float, so no value of another kind is one. */
        data object NonTuple : Term()

        /** A call that isn't expanded where the term is read, such as `unquote(x)`, `@attr` or a macro. */
        data object Unexpanded : Term()

        /** The value of this list's first `{key, _}` pair, as `lists:keyfind/3` finds it. */
        fun keyfind(key: String): Term? =
            (this as? List)?.elements?.firstNotNullOfOrNull { element ->
                (element as? Pair)?.takeIf { (it.first as? Atom)?.name == key }?.second
            }
    }

    /**
     * The `elixir_expand:validate_opts/5` error for [options] of a directive taking [allowed]: `unsupported_option`
     * for a pair whose key isn't allowed, or `options_are_not_keyword` when [options] isn't a list.
     */
    fun optionsError(options: Term, allowed: Collection<String>): String? =
        when (options) {
            is Term.List ->
                "unsupported_option".takeIf {
                    options.elements.any { element ->
                        element is Term.Pair && (element.first as? Term.Atom)?.name !in allowed
                    }
                }
            Term.Unexpanded -> null
            else -> "options_are_not_keyword"
        }

    /**
     * What an `import`'s options bring in, as the compiler of a language level reads them: the first `only:` and the
     * first `except:`, an `only:` list or its `:functions`, `:macros` or `:sigils`, and no name starting with `_`
     * unless an `only:` list names it.
     */
    sealed class Filter {
        abstract fun admits(name: Name, arity: Arity, macro: Boolean): Boolean

        protected abstract fun namedArities(name: Name): Collection<Arity>

        fun admits(name: Name, arityInterval: ArityInterval, macro: Boolean): Boolean =
            arities(name, arityInterval).any { admits(name, it, macro) }

        /** The arities of [arityInterval] this filter can tell apart. */
        fun arities(name: Name, arityInterval: ArityInterval): IntRange {
            // Every arity above those the options name and the sigil arity is admitted alike, so the first stands for all.
            val maximum = arityInterval.maximum
                ?: ((namedArities(name) + arityInterval.minimum + SIGIL_ARITY).max() + 1)

            return arityInterval.minimum..maximum
        }

        fun imports(exports: Imports): Imports =
            Imports(
                exports.functions.filterTo(mutableSetOf()) { admits(it.name, it.arity, false) },
                exports.macros.filterTo(mutableSetOf()) { admits(it.name, it.arity, true) },
            )

        /** An unsupported option, an `only:` list with `except:`, or a value `only:` or `except:` does not take. */
        class Invalid(val error: Error) : Filter() {
            /** @property atom the error's reason, as `elixir_import` raises it */
            enum class Error(val kind: String, val atom: String) {
                UNSUPPORTED_OPTION("unsupported_option", "unsupported_option"),
                ONLY_AND_EXCEPT_GIVEN("only_and_except_given", "only_and_except_given"),
                INVALID_ONLY("invalid_option only", "invalid_option"),
                INVALID_EXCEPT("invalid_option except", "invalid_option"),
            }

            override fun admits(name: Name, arity: Arity, macro: Boolean): Boolean = false

            override fun namedArities(name: Name): Collection<Arity> = emptyList()
        }

        /** An `only:` list: exactly what it names, `_` names included. */
        private class Only(private val nameArities: Set<NameArity>) : Filter() {
            override fun admits(name: Name, arity: Arity, macro: Boolean): Boolean =
                NameArity(name, arity) in nameArities

            override fun namedArities(name: Name): Collection<Arity> =
                nameArities.filter { it.name == name }.map { it.arity }
        }

        /**
         * No options, `except:` alone, or a selector with or without `except:`. With `except:`, a kind [prior] brings
         * in keeps what it brought in less `except:`, and the selector's own rule is not applied to it.
         */
        private class Selecting(
            private val selector: Selector?,
            private val except: Set<NameArity>?,
            private val prior: Imports?,
            private val languageLevel: ElixirLanguageLevel,
        ) : Filter() {
            override fun admits(name: Name, arity: Arity, macro: Boolean): Boolean {
                if (selector?.selects(macro) == false) return false

                val nameArity = NameArity(name, arity)
                val priorOfKind = prior?.of(macro)?.takeIf { except != null && it.isNotEmpty() }
                val selected = priorOfKind?.let { nameArity in it }
                    ?: (!name.startsWith("_") && (selector != Selector.SIGILS || isSigil(name, arity)))

                return selected && nameArity !in except.orEmpty()
            }

            override fun namedArities(name: Name): Collection<Arity> =
                (except.orEmpty() + prior?.functions.orEmpty() + prior?.macros.orEmpty())
                    .filter { it.name == name }
                    .map { it.arity }

            private fun isSigil(name: Name, arity: Arity): Boolean {
                if (!name.startsWith(SIGIL_PREFIX)) return false

                val letters = name.removePrefix(SIGIL_PREFIX)

                return if (IMPORT_ONLY_SIGILS_READS_SIGIL_NAMES.isSufficient(languageLevel)) {
                    val digits = DIGITS_IN_SIGIL_NAMES.isSufficient(languageLevel)

                    arity == SIGIL_ARITY &&
                        ((letters.length == 1 && letters[0] in 'a'..'z') ||
                            (letters.isNotEmpty() && letters[0] in 'A'..'Z' &&
                                letters.drop(1).all { it in 'A'..'Z' || (digits && it in '0'..'9') }))
                } else {
                    letters.length == 1 && (letters[0] in 'a'..'z' || letters[0] in 'A'..'Z')
                }
            }
        }

        /** A selector `only:` names in place of a list. */
        enum class Selector {
            FUNCTIONS,
            MACROS,
            SIGILS;

            fun selects(macro: Boolean): Boolean =
                when (this) {
                    FUNCTIONS -> !macro
                    MACROS -> macro
                    SIGILS -> true
                }
        }

        /**
         * What [of] reads from an `import`'s options.
         *
         * @property selector the `only:` selector, or `null` when `only:` is a list or not given
         * @property only the `only:` list as written, duplicates included, or `null` when it isn't a valid one
         * @property except the `except:` list as written, duplicates included, or `null` when it isn't a valid one
         */
        class Options(
            val filter: Filter,
            val selector: Selector?,
            val only: List<NameArity>?,
            val except: List<NameArity>?,
        )

        companion object {
            private const val SIGIL_ARITY = 2

            /**
             * The filter an `import`'s [options], expanded, make at [languageLevel], checked as that level's
             * `elixir_import` checks them. [prior] is what an earlier `import` of the same module in scope brings in,
             * which `except:` subtracts from, or `null` when there is none.
             */
            fun of(options: Term.List, languageLevel: ElixirLanguageLevel, prior: Imports?): Options {
                if (optionsError(options, OPTIONS) != null) return invalid(Invalid.Error.UNSUPPORTED_OPTION)

                val only = options.keyfind("only")
                val except = options.keyfind("except")
                val exceptList = (except as? Term.List)?.let(::nameArities)
                val exceptInvalid = except != null && except != Term.Unexpanded && exceptList == null

                if (exceptInvalid && IMPORT_VALIDATES_EXCEPT_FIRST.isSufficient(languageLevel)) {
                    return invalid(Invalid.Error.INVALID_EXCEPT)
                }

                val selector = when (only) {
                    null, Term.Unexpanded, is Term.List -> null
                    else -> when ((only as? Term.Atom)?.name) {
                        "functions" -> Selector.FUNCTIONS
                        "macros" -> Selector.MACROS
                        "sigils" -> Selector.SIGILS.takeIf { IMPORT_ONLY_SIGILS.isSufficient(languageLevel) }
                        else -> null
                    } ?: return invalid(Invalid.Error.INVALID_ONLY)
                }

                if (only is Term.List) {
                    val onlyList = nameArities(only) ?: return invalid(Invalid.Error.INVALID_ONLY)
                    val filter = if (except == null) {
                        Only(onlyList.toSet())
                    } else {
                        Invalid(Invalid.Error.ONLY_AND_EXCEPT_GIVEN)
                    }

                    return Options(filter, null, onlyList, null)
                }

                return when {
                    exceptInvalid -> invalid(Invalid.Error.INVALID_EXCEPT)
                    only == Term.Unexpanded -> Options(Only(emptySet()), null, null, exceptList)
                    else -> {
                        val filter = Selecting(selector, exceptList?.toSet(), prior, languageLevel)

                        Options(filter, selector, null, exceptList)
                    }
                }
            }

            private fun invalid(error: Invalid.Error) = Options(Invalid(error), null, null, null)

            /**
             * `ensure_keyword_list/1`: the `name: arity` pairs of [list], or `null` when it holds anything else. An
             * element that is or holds [Term.Unexpanded] is skipped, as is an arity no [Arity] can hold.
             */
            private fun nameArities(list: Term.List): List<NameArity>? =
                list.elements.mapNotNull { element ->
                    val name = ((element as? Term.Pair)?.first as? Term.Atom)?.name
                    val arity = ((element as? Term.Pair)?.second as? Term.Integer)?.value

                    when {
                        element == Term.Unexpanded -> null
                        element is Term.Pair && Term.Unexpanded in listOf(element.first, element.second) -> null
                        name == null || arity == null -> return null
                        arity.bitLength() < Int.SIZE_BITS -> NameArity(name, arity.toInt())
                        else -> null
                    }
                }

            private const val SIGIL_PREFIX = "sigil_"

            /**
             * The filter [importCall]'s options make at [languageLevel]. [prior] is what an earlier `import` of the
             * same module in scope brings in, which `except:` subtracts from, or `null` when there is none. A call
             * as a value, such as `unquote(x)`, `@attr` or a macro, is not followed.
             */
            @RequiresReadLock
            fun of(importCall: Call, languageLevel: ElixirLanguageLevel, prior: Imports?): Filter {
                ThreadingAssertions.assertReadAccess()

                val options = importCall
                    .takeIf { it.finalArguments()?.size == 2 }
                    ?.keywordArguments()
                    ?.quotableKeywordPairList()
                    .orEmpty()
                    .map { term(ElementLowering.quote(it)) }

                return of(Term.List(options), languageLevel, prior).filter
            }

            /**
             * [quoted] as an option term, where a call, which quotes to `{name, meta, arguments}`, isn't expanded, nor
             * is broken code, which quotes to `{:__cursor__, meta, []}`.
             */
            private fun term(quoted: OtpErlangObject): Term =
                when (quoted) {
                    is OtpErlangAtom -> Term.Atom(quoted.atomValue())
                    is OtpErlangLong -> Term.Integer(quoted.bigIntegerValue())
                    is OtpErlangBinary -> Term.Binary(quoted.binaryValue())
                    // A charlist.
                    is OtpErlangString ->
                        Term.List(
                            quoted.stringValue().codePoints().toArray().map { Term.Integer(BigInteger.valueOf(it.toLong())) }
                        )
                    is OtpErlangList ->
                        if (quoted.lastTail == null) Term.List(quoted.elements().map(::term)) else NODE
                    is OtpErlangTuple ->
                        when (quoted.arity()) {
                            2 -> Term.Pair(term(quoted.elementAt(0)), term(quoted.elementAt(1)))
                            3 ->
                                when {
                                    quoted.elementAt(2) is OtpErlangAtom -> Term.Node(Term.Node.Kind.VARIABLE)
                                    quoted.elementAt(0) in LITERALS -> NODE
                                    else -> Term.Unexpanded
                                }
                            else -> NODE
                        }
                    is OtpErlangDouble -> Term.NonTuple
                    else -> NODE
                }

            private val NODE = Term.Node(Term.Node.Kind.OTHER)

            private val OPTIONS = listOf("only", "except", "warn")

            /** A tuple or map written out, which quotes like a call but is a value no option takes. */
            private val LITERALS = listOf(OtpErlangAtom("{}"), OtpErlangAtom("%{}"))
        }
    }

    /**
     * Whether `call` is an `import Module` or `import Module, opts` call
     */
    @JvmStatic
    fun `is`(call: Call): Boolean = call.isCalling(KERNEL, IMPORT) && call.resolvedFinalArity() in 1..2

    @JvmStatic
    @RequiresReadLock
    fun treeWalkUp(
        importCall: Call,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean {
        ThreadingAssertions.assertReadAccess()

        var accumulatedKeepProcessing = true

        if (walks(importCall, resolveState)) {
            val blockImports = blockImports(importCall)
            val modulars = blockImports.first { it.call == importCall }.modulars

            if (modulars.isNotEmpty()) {
                val importCallResolveState = Recording
                    .enter(
                        resolveState, "IMPORT", importCall, stops = false, absorbs = true,
                        gate = { walks(importCall, it) }, reach = ::reached
                    )
                    .putVisitedElement(importCall)
                    .let(::reached)
                val languageLevel = ElixirLanguageLevelResolver.languageLevelFor(importCall)

                for (modular in modulars) {
                    ProgressManager.checkCanceled()
                    val prior = prior(blockImports, importCall, modular, languageLevel)
                    val filter = Filter.of(importCall, languageLevel, prior)
                    val filtered = { state: ResolveState -> state.put(FILTER, filter) }
                    // One imported module stops at a `false` and answers `true` (`takeWhile { it }.lastOrNull() ?: true`).
                    val childResolveState = Recording
                        .enter(
                            importCallResolveState.putVisitedElement(modular), "IMPORTED", modular, stops = true,
                            absorbs = true, childGate = if (modular is Call) ::walksChild else null, reach = filtered
                        )
                        .let(filtered)

                    accumulatedKeepProcessing =
                        treeWalkUpImportedModular(modular, filter, childResolveState, keepProcessing)

                    if (!accumulatedKeepProcessing) {
                        break
                    }
                }
            }
        }

        return accumulatedKeepProcessing
    }

    /**
     * Don't descend back into `import` when the entrance is the alias to the `import` like `MyAlias` in `import MyAlias`,
     * nor when a later `import` of the same module has replaced or narrowed it by the entrance.
     */
    private fun walks(importCall: Call, resolveState: ResolveState): Boolean {
        val entrance = resolveState.get(ENTRANCE)

        return !importCall.isAncestor(entrance) && !isReimportedBefore(importCall, entrance)
    }

    /**
     * Decided by where the entrance sits in [importCall]'s block, never by what the walk has reached, as every `import`
     * in a module body is walked whatever the entrance. An entrance outside the block, such as in a block a macro's
     * `quote` unquotes, comes after none. So does a callable table's seed, so the table records the `import` and its
     * replay decides.
     */
    private fun isReimportedBefore(importCall: Call, entrance: PsiElement?): Boolean {
        val block = importCall.parent
        val anchor = entrance?.parents(withSelf = true)?.firstOrNull { it.parent == block } ?: return false
        val anchorStart = anchor.textRange.startOffset

        if (anchorStart <= importCall.textRange.startOffset) return false

        val blockImports = blockImports(importCall)
        val index = blockImports.indexOfFirst { it.call == importCall }
        val modulars = blockImports[index].modulars

        return blockImports.drop(index + 1).any { later ->
            later.call.textRange.endOffset <= anchorStart && later.modulars.any { it in modulars }
        }
    }

    /** An `import` in a block, with the modules it imports. */
    private class BlockImport(val call: Call, val modulars: Set<PsiNamedElement>)

    /**
     * The `import`s in [importCall]'s block, in source order. They are resolved together because a compiled module's
     * PSI can differ between two resolutions.
     */
    private fun blockImports(importCall: Call): List<BlockImport> {
        val block = importCall.parent

        return CachedValuesManager.getCachedValue(block) {
            val blockImports = block.firstChild
                ?.siblingExpressions()
                .orEmpty()
                .filterIsInstance<Call>()
                .filter { `is`(it) }
                .map { BlockImport(it, modulars(it)) }
                .toList()

            CachedValueProvider.Result.create(blockImports, PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    /**
     * What the `import`s of [modular] before [importCall] in [blockImports] bring in, each built from what the one
     * before left, or `null` when there is none. An `import` in an enclosing block is not looked for, though Elixir
     * chains it.
     */
    private fun prior(
        blockImports: List<BlockImport>,
        importCall: Call,
        modular: PsiNamedElement,
        languageLevel: ElixirLanguageLevel
    ): Imports? {
        val earlier = blockImports.takeWhile { it.call != importCall }.filter { modular in it.modulars }

        if (earlier.isEmpty()) return null

        val exports = exports(modular)

        return earlier.fold(null as Imports?) { prior, blockImport ->
            Filter.of(blockImport.call, languageLevel, prior).imports(exports)
        }
    }

    private fun reached(resolveState: ResolveState): ResolveState = resolveState.reachedThrough(Reach.IMPORT)

    private fun walksChild(resolveState: ResolveState, child: PsiElement): Boolean = !resolveState.hasBeenVisited(child)

    private fun treeWalkUpImportedModular(
        importedModular: PsiElement,
        filter: Filter,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        when (importedModular) {
            is Call -> treeWalkUpImportedModular(importedModular, filter, resolveState, keepProcessing)
            is BeamModule -> treeWalkUpImportedModular(importedModular, filter, resolveState, keepProcessing)
            else -> true
        }

    private fun treeWalkUpImportedModular(
        importedModular: Call,
        filter: Filter,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        CallDefinitionClause.modularChildCalls(importedModular)
            .asSequence()
            .filter { walksChild(resolveState, it) }
            .map {
                ProgressManager.checkCanceled()
                treeWalkUpImportedModularChildExpression(filter, it, resolveState, keepProcessing)
            }
            .takeWhile { it }
            .lastOrNull()
            ?: true

    private fun treeWalkUpImportedModular(
        importedModular: BeamModule,
        filter: Filter,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        importedModular
            .callDefinitions()
            .map {
                ProgressManager.checkCanceled()
                treeWalkUpImportedModularChildExpression(filter, it, resolveState, keepProcessing)
            }
            .takeWhile { it }
            .lastOrNull()
            ?: true

    private fun treeWalkUpImportedModularChildExpression(
        filter: Filter,
        importedCall: Call,
        resolveState: ResolveState,
        keepProcessing: (Call, ResolveState) -> Boolean
    ): Boolean =
        export(importedCall, resolveState)
            ?.takeIf { it.isAdmittedBy(filter) }
            ?.let { keepProcessing(importedCall, resolveState) }
            ?: true

    private fun treeWalkUpImportedModularChildExpression(
        filter: Filter,
        importedCall: BeamCallDefinition,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        export(importedCall)
            ?.takeIf { it.isAdmittedBy(filter) }
            ?.let { keepProcessing(importedCall, resolveState) }
            ?: true

    /**
     * The highest arity of [definition] that the `import` the walk reached it through brings in, or `null` when no
     * `import` reached it or its arities are open.
     */
    @RequiresReadLock
    fun highestImportedArity(definition: Call, state: ResolveState): Arity? {
        ThreadingAssertions.assertReadAccess()

        val filter = state.get(FILTER) ?: return null

        return export(definition, state)?.highestAdmittedArity(filter)
    }

    /** A definition an `import` of its module can bring in: a public one, with a delegation as a function. */
    private class Export(val nameArityInterval: NameArityInterval, val macro: Boolean) {
        fun isAdmittedBy(filter: Filter): Boolean =
            filter.admits(nameArityInterval.name, nameArityInterval.arityInterval, macro)

        fun highestAdmittedArity(filter: Filter): Arity? {
            val (name, arityInterval) = nameArityInterval
            val maximum = arityInterval.maximum ?: return null

            return (maximum downTo arityInterval.minimum).firstOrNull { filter.admits(name, it, macro) }
        }
    }

    private fun export(child: Call, resolveState: ResolveState): Export? =
        when {
            CallDefinitionClause.`is`(child) ->
                importedCapabilities(child)?.let { capabilities ->
                    CallDefinitionClause.nameArityInterval(child, resolveState)?.let { Export(it, capabilities.compileTime) }
                }
            Delegation.`is`(child) ->
                child.finalArguments()?.takeIf { it.size == 2 }?.let { arguments ->
                    CallDefinitionHead.nameArityInterval(arguments[0], resolveState)?.let { Export(it, macro = false) }
                }
            else -> null
        }

    private fun export(definition: BeamCallDefinition): Export? =
        importedCapabilities(definition)?.let { Export(definition.nameArityInterval, it.compileTime) }

    /** [clause]'s capabilities when an `import` of its module brings it in, else `null`. */
    @RequiresReadLock
    internal fun importedCapabilities(clause: Call): Capabilities? =
        CallDefinitionClause.capabilities(clause)?.takeIf { it.public }

    /** [definition]'s capabilities when an `import` of its module brings it in, else `null`. */
    @RequiresReadLock
    internal fun importedCapabilities(definition: BeamCallDefinition): Capabilities? =
        definition.capabilities.takeIf { it.public && !definition.isCompilerAdded }

    /** Whether an `import` of [child]'s module brings [child] in: a public definition clause, or a delegation. */
    @RequiresReadLock
    internal fun imports(child: Call): Boolean = export(child, ResolveState.initial()) != null

    /** Whether an `import` of [definition]'s module brings it in. */
    @RequiresReadLock
    internal fun imports(definition: BeamCallDefinition): Boolean = export(definition) != null

    /** The definitions of [modular] that an `import` of it brings in, unmapped. */
    @RequiresReadLock
    internal fun importedDefinitions(modular: PsiElement): List<PsiElement> =
        when (modular) {
            is Call -> CallDefinitionClause.modularChildCalls(modular).filter { imports(it) }
            is BeamModule -> modular.callDefinitions().filter { imports(it) }
            else -> emptyList()
        }

    private fun exports(modular: PsiNamedElement): List<Export> =
        when (modular) {
            is Call -> CallDefinitionClause.modularChildCalls(modular).mapNotNull { export(it, ResolveState.initial()) }
            is BeamModule -> modular.callDefinitions().mapNotNull(::export)
            else -> emptyList()
        }

    /** What this filter brings in of [exports], at each arity of each that it can tell apart. */
    private fun Filter.imports(exports: List<Export>): Imports {
        fun nameArities(macro: Boolean): Set<NameArity> =
            exports.filter { it.macro == macro }.flatMapTo(mutableSetOf()) { export ->
                val (name, arityInterval) = export.nameArityInterval

                arities(name, arityInterval).map { NameArity(name, it) }
            }

        return imports(Imports(nameArities(macro = false), nameArities(macro = true)))
    }

    fun elementDescription(call: Call, location: ElementDescriptionLocation): String? =
        when {
            location === UsageViewTypeLocation.INSTANCE -> "import"
            location === UsageViewNodeTextLocation.INSTANCE -> call.text
            else -> null
        }

    /**
     * The modular that is imported by `importCall`.
     * @param importCall a [Call] where [is] is `true`.
     * @return `defmodule`, `defimpl`, or `defprotocol` imported by `importCall`.  It can be
     * `null` if Alias passed to `importCall` cannot be resolved.
     */
    @RequiresReadLock
    internal fun modulars(importCall: Call): Set<PsiNamedElement> =
        importCall
            .finalArguments()
            ?.firstOrNull()
            ?.maybeModularNameToModulars(maxScope = importCall.parent, useCall = null, incompleteCode = false)
            ?: emptySet()
}
