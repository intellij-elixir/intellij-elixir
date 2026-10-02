package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageFeature.DIALYZER_ATTRIBUTE_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.DIALYZER_REFUSES_MACROS
import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATED_HEADS_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.NIFS_ATTRIBUTE_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.ON_LOAD_ALLOWS_PRIVATE
import org.elixir_lang.language_level.ElixirLanguageFeature.POST_MODULE_LOCAL_CHECKS_TYPED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/**
 * One definition as the checks after the module body see it.
 *
 * @property at its first head
 * @property clauses whether it has a clause, so isn't only bodiless heads
 * @property checksClauses whether its last head is checked for clauses
 */
internal data class Defined<out At>(val kind: Kind, val at: At, val clauses: Boolean, val checksClauses: Boolean)

/** What one of the checks after the module body gives. */
internal sealed interface PostModuleCheck<out At> {
    /**
     * An error at [site], at [at].
     *
     * @property caller the definition whose env it is reported in, if not the module's
     * @property named the attribute's function it names, if an attribute's check gave it
     */
    data class Error<out At>(
        val site: ErrorSite,
        val at: At,
        val caller: NameArity? = null,
        val named: Term? = null,
    ) : PostModuleCheck<At>

    /** A value the expander doesn't know decides what follows, so the checks stop. */
    data object Stop : PostModuleCheck<Nothing>
}

/**
 * The checks Elixir makes once [module]'s body has run, in its order at [level]: before [ON_LOAD_ALLOWS_PRIVATE] the
 * bodiless heads, the local calls, the import conflicts, `@compile :inline` and `@on_load`; then the bodiless heads,
 * `@on_load`, `@dialyzer`, `@nifs`, the local calls, the import conflicts and `@compile :inline`; and from
 * [POST_MODULE_LOCAL_CHECKS_TYPED] the import conflicts before the local calls, which with `@compile :inline` run only
 * when nothing before them was reported. A [PostModuleCheck.Stop] ends the list.
 *
 * @param at the module
 * @param definitions each definition, in the order it was first stored
 * @param calls each definition's local calls, in expansion order
 * @param usedPrivate the private macros dispatched as local macros, in the order they were first dispatched
 * @param imports each name and arity a function body called through an import, and the import's module
 * @param attribute each value of an attribute, oldest first
 * @param tainted whether an error was reported in the module before these checks
 */
internal fun <At> postModuleChecks(
    level: ElixirLanguageLevel,
    module: String,
    at: At,
    definitions: Map<NameArity, Defined<At>>,
    calls: Map<NameArity, List<LocalCall<At>>>,
    usedPrivate: List<NameArity>,
    imports: Map<NameArity, String>,
    attribute: (String) -> List<AttributeValue>,
    tainted: Boolean,
): List<PostModuleCheck<At>> =
    with(Steps(level, module, at, definitions, calls, usedPrivate, imports, attribute)) {
        functionHeads()

        when {
            !ON_LOAD_ALLOWS_PRIVATE.isSufficient(level) -> {
                locals()
                importConflicts()
                inline() && onLoad()
            }
            !POST_MODULE_LOCAL_CHECKS_TYPED.isSufficient(level) ->
                onLoad() && dialyzer() && nifs() && run {
                    locals()
                    importConflicts()
                    inline()
                }
            else ->
                onLoad() && dialyzer() && nifs() && run {
                    importConflicts()
                    // `compile_error_if_tainted/2`
                    !tainted && checks.isEmpty() && run {
                        locals()
                        inline()
                    }
                }
        }

        checks
    }

/** The steps of [postModuleChecks]. Each that can meet a value the expander doesn't know answers whether to go on. */
private class Steps<At>(
    private val level: ElixirLanguageLevel,
    private val module: String,
    private val at: At,
    private val definitions: Map<NameArity, Defined<At>>,
    private val calls: Map<NameArity, List<LocalCall<At>>>,
    usedPrivate: List<NameArity>,
    private val imports: Map<NameArity, String>,
    private val attribute: (String) -> List<AttributeValue>,
) {
    val checks = mutableListOf<PostModuleCheck<At>>()

    private val usedPrivate = usedPrivate.toMutableList()

    /** `elixir_def:fetch_definitions/2`, which drops a definition of bodiless heads. */
    private val all = definitions.filterValues { it.clauses }

    /** `elixir_def:check_bodiless_function/2`: each definition of bodiless heads, at its first head, ascending. */
    fun functionHeads() {
        val checksGenerated = GENERATED_HEADS_CHECKED.isSufficient(level)

        definitions
            .filterValues { !it.clauses && (checksGenerated || it.checksClauses && module != ELIXIR_MODULE) }
            .toSortedMap(NAME_ARITY_ORDER)
            .values
            .forEach { checks += PostModuleCheck.Error(ErrorSite.FUNCTION_HEAD, it.at) }
    }

    /**
     * `validate_on_load_attribute/5`: the function `@on_load` names must be defined, and not as a macro; before
     * [ON_LOAD_ALLOWS_PRIVATE], as a `def`. A private one is used.
     */
    fun onLoad(): Boolean {
        val named = when (val value = attribute("on_load").firstOrNull()) {
            null -> return true
            AttributeValue.Unknown -> return stop()
            is AttributeValue.Known -> value.term
        }
        val defined = defined(named)

        return when {
            defined == null -> error(ErrorSite.UNDEFINED_ATTRIBUTE_FUNCTION, named)
            defined.kind.macro || !ON_LOAD_ALLOWS_PRIVATE.isSufficient(level) && defined.kind != Kind.DEF ->
                error(ErrorSite.WRONG_KIND_ATTRIBUTE_FUNCTION, named)
            else -> {
                val nameArity = nameArity(named)!!

                if (defined.kind == Kind.DEFP && nameArity !in usedPrivate) usedPrivate += nameArity

                true
            }
        }
    }

    /** `validate_dialyzer_attribute/4`: each function the first `@dialyzer` value names under an option. */
    fun dialyzer(): Boolean {
        if (!DIALYZER_ATTRIBUTE_CHECKED.isSufficient(level)) return true

        val options = when (val value = attribute("dialyzer").firstOrNull()) {
            null -> return true
            AttributeValue.Unknown -> return stop()
            is AttributeValue.Known -> flatten(value.term) ?: return stop()
        }
        val refusesMacros = DIALYZER_REFUSES_MACROS.isSufficient(level)

        for (option in options) {
            if (option !is Term.Pair) continue

            for (named in flatten(option.second) ?: return stop()) if (!checkNamed(named, refusesMacros)) return false
        }

        return true
    }

    /** `validate_nifs_attribute/4`: each function the first `@nifs` value names. */
    fun nifs(): Boolean {
        if (!NIFS_ATTRIBUTE_CHECKED.isSufficient(level)) return true

        val nifs = when (val value = attribute("nifs").firstOrNull()) {
            null -> return true
            AttributeValue.Unknown -> return stop()
            is AttributeValue.Known -> flatten(value.term) ?: return stop()
        }

        return nifs.all { checkNamed(it, refusesMacros = true) }
    }

    /** The checks of the local calls. */
    fun locals() {
        localErrors(level, definitions.mapValues { it.value.kind }, calls, usedPrivate).forEach {
            checks += PostModuleCheck.Error(it.site, it.call.at, it.caller)
        }
    }

    /** `elixir_import:ensure_no_local_conflict/3`: each definition an import was called for, descending. */
    fun importConflicts() {
        if (module == "Elixir.Kernel") return

        all.toSortedMap(NAME_ARITY_ORDER.reversed()).forEach { (nameArity, defined) ->
            if (nameArity in imports) checks += PostModuleCheck.Error(ErrorSite.IMPORT_CONFLICT, defined.at)
        }
    }

    /** `validate_compile_opts/5` of each `@compile` value, oldest first. */
    fun inline(): Boolean =
        attribute("compile").all { value ->
            when (value) {
                AttributeValue.Unknown -> stop()
                is AttributeValue.Known -> compileOption(value.term)
            }
        }

    /** `validate_compile_opt/5`: a list is each of its options; an improper one fails at its tail. */
    private fun compileOption(option: Term): Boolean =
        when (option) {
            is Term.Pair -> option.first != INLINE || inlines(option.second)
            is Term.List -> option.elements.all(::compileOption) && (option.tail == null || stop())
            else -> true
        }

    /** `validate_inlines/4`: the first function that isn't defined, or, from [NIFS_ATTRIBUTE_CHECKED], is a macro. */
    private fun inlines(inlines: Term): Boolean {
        if (inlines !is Term.List) return stop()

        val refusesMacros = NIFS_ATTRIBUTE_CHECKED.isSufficient(level)

        for (named in inlines.elements) {
            val defined = defined(named)

            when {
                defined == null -> return error(ErrorSite.UNDEFINED_ATTRIBUTE_FUNCTION, named)
                refusesMacros && defined.kind.macro -> return error(ErrorSite.WRONG_KIND_ATTRIBUTE_FUNCTION, named)
            }
        }

        return inlines.tail == null || stop()
    }

    /** `validate_definition/5`. */
    private fun checkNamed(named: Term, refusesMacros: Boolean): Boolean {
        val defined = defined(named)

        return when {
            defined == null -> error(ErrorSite.UNDEFINED_ATTRIBUTE_FUNCTION, named)
            refusesMacros && defined.kind.macro -> error(ErrorSite.WRONG_KIND_ATTRIBUTE_FUNCTION, named)
            else -> true
        }
    }

    /** `lists:keyfind/3` of [named] in the definitions. */
    private fun defined(named: Term): Defined<At>? = nameArity(named)?.let(all::get)

    /**
     * `format_error/1` formats the function as `~ts/~B`, and raises on a name `~ts` can't format or an arity that isn't
     * an integer, which isn't modelled.
     */
    private fun error(site: ErrorSite, named: Term): Boolean {
        val pair = named as? Term.Pair

        if (pair == null || !isChardata(pair.first) || pair.second !is Term.Integer) return stop()

        checks += PostModuleCheck.Error(site, at, named = named)

        return true
    }

    private fun stop(): Boolean = false.also { checks += PostModuleCheck.Stop }

    private companion object {
        val INLINE = Term.Atom("inline")

        /** An atom, a binary or a flat charlist. Nested chardata, which `~ts` also formats, stops. */
        fun isChardata(term: Term): Boolean =
            when (term) {
                is Term.Atom -> true
                is Term.Binary -> term.bytes != null
                is Term.List ->
                    term.tail == null &&
                        term.elements.all { it is Term.Integer && it.value.signum() >= 0 && it.value <= MAX_CODE_POINT }
                else -> false
            }

        val MAX_CODE_POINT: BigInteger = BigInteger.valueOf(Character.MAX_CODE_POINT.toLong())

        fun nameArity(term: Term): NameArity? {
            val name = ((term as? Term.Pair)?.first as? Term.Atom)?.name ?: return null
            val arity = (term.second as? Term.Integer)?.value?.takeIf { it.bitLength() < Int.SIZE_BITS } ?: return null

            return NameArity(name, arity.toInt())
        }

        /** `lists:flatten([term])`, or `null` where a list in it is improper. */
        fun flatten(term: Term): List<Term>? =
            when (term) {
                is Term.List -> if (term.tail != null) null else term.elements.flatMap { flatten(it) ?: return null }
                else -> listOf(term)
            }
    }
}
