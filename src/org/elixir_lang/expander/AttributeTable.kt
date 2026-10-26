package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.AFTER_VERIFY_ACCUMULATES
import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageFeature.BEHAVIOUR_VALUE_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.DIALYZER_ATTRIBUTE_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.NIFS_ACCUMULATES
import org.elixir_lang.language_level.ElixirLanguageFeature.NIFS_ATTRIBUTE_CHECKED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/** What an attribute holds, as far as the expander can tell. */
internal sealed interface AttributeValue {
    data class Known(val term: Term) : AttributeValue

    data object Unknown : AttributeValue

    companion object {
        /** [term] when it is exact: no node, no non-tuple and no binary of unknown content anywhere in it. */
        fun of(term: Term): AttributeValue = if (term.isExact()) Known(term) else Unknown
    }
}

/** Whether Elixir's `if` takes [this] as true, or `null` where it is a node or unexpanded. */
internal fun Term.isTruthy(): Boolean? =
    when (this) {
        is Term.Atom -> name != "nil" && name != "false"
        is Term.Integer, is Term.Binary, is Term.List, is Term.Pair, is Term.Tuple, Term.NonTuple -> true
        is Term.Node, Term.Unexpanded -> null
    }

private fun Term.isExact(): Boolean = isShaped(contentKnown = true)

/** Whether Elixir's checks of [this] can be made: no node or non-tuple anywhere in it, though a binary may be unread. */
private fun Term.isShaped(contentKnown: Boolean = false): Boolean {
    ProgressManager.checkCanceled()

    return when (this) {
        is Term.Atom, is Term.Integer -> true
        is Term.Binary -> !contentKnown || bytes != null
        is Term.List -> elements.all { it.isShaped(contentKnown) } && tail?.isShaped(contentKnown) ?: true
        is Term.Pair -> first.isShaped(contentKnown) && second.isShaped(contentKnown)
        is Term.Tuple -> elements.all { it.isShaped(contentKnown) }
        is Term.Node, Term.NonTuple, Term.Unexpanded -> false
    }
}

/** What a call in a module body does to that module's attributes when the body runs. */
internal sealed interface Effect {
    /** `Module.__put_attribute__/4,5`, which `@name value` builds, or `Module.put_attribute/3`. */
    data class Write(val name: String, val value: Term) : Effect

    /** `Module.register_attribute/3`, with its `accumulate:` option, or `null` where that isn't known. */
    data class Register(val name: String, val accumulate: Boolean?) : Effect

    /** `Module.delete_attribute/2`. */
    data class Delete(val name: String) : Effect

    /**
     * `Kernel.Typespec.deftypespec/6`, which a typespec attribute builds.
     *
     * @property head the name and arity `translate_spec` reads from the spec, or `null` where it raises or they aren't
     *   known
     */
    data class TypespecWrite(val name: String, val head: NameArity?) : Effect

    /** One of the others whose attribute isn't known. */
    data object UnknownEffect : Effect

    /**
     * [effect] on [module], a module other than the one whose body runs the call, or `null` where the module isn't
     * known, which may be any module being compiled, the body's own included: [effect] is then [UnknownEffect].
     */
    data class OtherModule(val module: String?, val effect: Effect) : Effect
}

internal sealed interface EffectOutcome {
    data object Stored : EffectOutcome

    /** The value isn't known, or the write may not run, so whether Elixir refuses it isn't known either. */
    data object Unchecked : EffectOutcome

    /** Elixir raises `ArgumentError` where the write runs. */
    data class Raises(val kind: String) : EffectOutcome
}

/** One module's attributes as `Module`'s data tables hold them while its body runs (`Mo:426–463`). */
internal class AttributeTable(private val level: ElixirLanguageLevel) {
    private sealed interface Entry {
        data class Set(val value: AttributeValue) : Entry

        /** Registered without `accumulate:` and never written. */
        data object Unset : Entry

        /** @property values oldest first */
        data class Accumulate(val values: List<AttributeValue>) : Entry
    }

    private val entries: MutableMap<String, Entry> = mutableMapOf()
    private val unknown = mutableSetOf<String>()
    private var everyUnknown = false

    /**
     * Whether a module nested in the body stopped ([stopped]): what Elixir ran after it may have written every
     * attribute.
     */
    private var stopped = false

    // What an effect that may not run may have written or deleted: a subset of what is unknown, which also holds what a
    // statement wrote that the expander can't hold.
    private val changed = mutableSetOf<String>()
    private var everyChanged = false

    /** Each typespec write's name and arity still in its attribute's bag, oldest first, `null` where it isn't known. */
    private val heads = mutableMapOf<String, List<NameArity?>>()

    /** The kind, name and arity of each callback whose `@doc` its first write took, `null` where they aren't known. */
    private var documented: MutableSet<Pair<String, NameArity>>? = mutableSetOf()

    init {
        entries["moduledoc"] = Entry.Set(AttributeValue.Known(NIL))

        val accumulating = ALWAYS_ACCUMULATING +
            listOfNotNull(
                "after_verify".takeIf { AFTER_VERIFY_ACCUMULATES.isSufficient(level) },
                "nifs".takeIf { NIFS_ACCUMULATES.isSufficient(level) },
            )

        for (name in accumulating) entries[name] = Entry.Accumulate(emptyList())

        entries["on_definition"] = Entry.Accumulate(listOf(AttributeValue.Known(COMPILE_DEFINITION_ATTRIBUTES)))
    }

    /**
     * Applies [effect] at its place in the module body. An effect that isn't a [statement] may run any number of
     * times or not at all, so every later read of what it touches is unknown.
     */
    fun apply(effect: Effect, statement: Boolean = true): EffectOutcome {
        if (!statement) return applyElsewhere(effect)

        return when (effect) {
            is Effect.Write -> write(effect.name, effect.value)
            is Effect.Register -> EffectOutcome.Stored.also { register(effect.name, effect.accumulate) }
            is Effect.Delete -> EffectOutcome.Stored.also { delete(effect.name) }
            is Effect.TypespecWrite -> EffectOutcome.Stored.also {
                store(effect.name, AttributeValue.Unknown)
                heads[effect.name] = heads[effect.name].orEmpty() + effect.head
                takeDoc(effect)
            }
            // Whether Elixir refuses a write whose attribute isn't known isn't known either.
            Effect.UnknownEffect -> EffectOutcome.Unchecked.also { everyUnknown = true }
            is Effect.OtherModule -> applyElsewhere(effect)
        }
    }

    /**
     * Whether Elixir calls back through [name], a callback attribute at this level, into code the expander doesn't
     * follow: the body wrote an entry to it, or may have. `on_definition`'s own `compile_definition_attributes` entry
     * is followed.
     */
    fun callsBack(name: String): Boolean = callbackFunction(name) != null && values(name).any { callsBackThrough(it) }

    /**
     * Whether the body's own writes have Elixir call back through [name] ([callsBack]): an entry it wrote, or a write
     * the expander didn't follow may have written ([unknownByWrite]). A stop of a module nested in the body alone also
     * leaves an entry possible, which this leaves aside.
     */
    fun callsBackByItsWrites(name: String): Boolean =
        callbackFunction(name) != null && (unknownByWrite(name) || written(name).any { callsBackThrough(it) })

    /**
     * Whether [entry], of a callback attribute, has Elixir call back into code the expander doesn't follow: any but
     * `on_definition`'s own `compile_definition_attributes`.
     */
    private fun callsBackThrough(entry: AttributeValue): Boolean =
        (entry as? AttributeValue.Known)?.term != COMPILE_DEFINITION_ATTRIBUTES

    /** The function Elixir calls on a module written to `@name`, `null` where `@name` is no callback at this level. */
    private fun callbackFunction(name: String): String? =
        CALLBACKS[name]?.takeIf { name != "after_verify" || AFTER_VERIFY_ACCUMULATES.isSufficient(level) }

    /** What `@name` in a definition injects at this point (`K:3815–3828`). */
    fun read(name: String): Term =
        when (val value = value(name)) {
            is AttributeValue.Known ->
                value.term.let { term -> if (name in DOCS && term is Term.Pair) term.second else term }
            AttributeValue.Unknown -> Term.Unexpanded
        }

    /** An attribute's row, as `Module`'s `get_attribute` finds it. */
    sealed interface Lookup {
        data object Absent : Lookup

        /** Registered without `accumulate:` and never written. */
        data object Unset : Lookup

        /** @property value an accumulating attribute's values, newest first */
        data class Holds(val value: AttributeValue) : Lookup
    }

    /** [name]'s row at this point, as `Module.get_attribute/3` and `Module.__get_attribute__` read it. */
    fun lookup(name: String): Lookup {
        if (isUnknown(name)) return Lookup.Holds(AttributeValue.Unknown)

        return when (entries[name]) {
            null -> Lookup.Absent
            Entry.Unset -> Lookup.Unset
            else -> Lookup.Holds(value(name))
        }
    }

    /**
     * Whether [name] has a row at this point, as `Module.has_attribute?/2` finds it (`:ets.member/2`), or `null` where an
     * effect that may not run may have written or deleted it.
     */
    fun has(name: String): Boolean? = if (isUnknown(name)) null else name in entries

    /** Whether an effect that may not run may have written or deleted [name]. */
    fun mayHaveChanged(name: String): Boolean = everyChanged || name in changed

    /**
     * Each attribute's value as `Module.get_attribute/2` gives it at this point. After an [Effect.UnknownEffect] an
     * attribute missing here may have been written, so it is unknown too.
     */
    val final: Map<String, AttributeValue>
        get() = (entries.keys + unknown).associateWith(::value)

    /** The attributes that accumulate at this point. */
    val accumulating: Set<String>
        get() = entries.filterValues { it is Entry.Accumulate }.keys.toSet()

    /**
     * `:ets.take/2` of [name], as a definition takes `@impl`, `@doc` and `@deprecated` (`Mod:1852–1977`): its value,
     * removed, or `null` where it isn't set. An unknown attribute stays unknown, as an effect that isn't a statement
     * may still set it.
     */
    fun take(name: String): AttributeValue? {
        if (isUnknown(name)) return AttributeValue.Unknown
        if (name !in entries) return null

        return value(name).also { entries.remove(name) }
    }

    /**
     * `lookup_attribute`, as the checks after the module body read [name]: each value written, oldest first, none where
     * it isn't set, and one unknown value where it may have been written.
     */
    fun values(name: String): List<AttributeValue> =
        if (isUnknown(name)) listOf(AttributeValue.Unknown) else written(name)

    /** Each value the body wrote to [name], oldest first, none where it isn't set. */
    private fun written(name: String): List<AttributeValue> =
        when (val entry = entries[name]) {
            null, Entry.Unset -> emptyList()
            is Entry.Set -> listOf(entry.value)
            is Entry.Accumulate -> entry.values
        }

    /**
     * Whether [name]'s value may differ from what the table holds: a module nested in the body stopped, or code the
     * expander didn't follow may have written it ([unknownByWrite]).
     */
    private fun isUnknown(name: String): Boolean = stopped || unknownByWrite(name)

    /** Whether a write the expander didn't follow, to [name] or to every attribute, may have changed it. */
    private fun unknownByWrite(name: String): Boolean = everyUnknown || name in unknown

    /**
     * A module nested in the body stopped: what Elixir ran after it, which may write every attribute, isn't followed.
     */
    fun stopped() {
        stopped = true
    }

    /**
     * [name]'s row in the set table as `Kernel.Utils.defstruct` reads it from 1.14 (`:ets.lookup(set, name)`): `null`
     * where there is none, `nil` for an attribute registered and never written, and `[]` for an accumulating one,
     * whose values are in the bag.
     */
    fun row(name: String): AttributeValue? {
        if (isUnknown(name)) return AttributeValue.Unknown

        return when (val entry = entries[name]) {
            null -> null
            Entry.Unset -> AttributeValue.Known(NIL)
            is Entry.Set -> entry.value
            is Entry.Accumulate -> AttributeValue.Known(Term.List(emptyList()))
        }
    }

    /**
     * `:ets.update_element(set, name, {3, :used})`, which `Kernel.Utils.defstruct` does to the row it reads: an
     * accumulating attribute stops accumulating, and holds the row's `[]`.
     */
    fun used(name: String) {
        if (isUnknown(name)) return

        if (entries[name] is Entry.Accumulate) entries[name] = Entry.Set(AttributeValue.Known(Term.List(emptyList())))
    }

    /**
     * The name and arity of each typespec write of [name] in its bag at this point, oldest first, or `null` where one
     * of them, or whether a write ran, isn't known.
     */
    fun heads(name: String): List<NameArity>? {
        if (isUnknown(name)) return null

        return heads[name].orEmpty().map { it ?: return null }
    }

    /** Makes every later read of [names] unknown, as after a definition that isn't a statement takes them. */
    fun markUnknown(vararg names: String) {
        unknown += names
    }

    private fun value(name: String): AttributeValue {
        if (isUnknown(name)) return AttributeValue.Unknown

        return when (val entry = entries[name]) {
            null, Entry.Unset -> AttributeValue.Known(NIL)
            is Entry.Set -> entry.value
            is Entry.Accumulate ->
                entry.values
                    .asReversed()
                    .map { (it as? AttributeValue.Known)?.term ?: return AttributeValue.Unknown }
                    .let { AttributeValue.Known(Term.List(it)) }
        }
    }

    private fun applyElsewhere(effect: Effect): EffectOutcome =
        when (effect) {
            is Effect.Write -> {
                val prepared = prepare(effect.name, effect.value, statement = false)

                mayChange(effect.name)

                if (prepared.checked) EffectOutcome.Stored else EffectOutcome.Unchecked
            }
            is Effect.Register -> EffectOutcome.Stored.also { mayChange(effect.name) }
            is Effect.Delete -> EffectOutcome.Stored.also { mayChange(effect.name) }
            is Effect.TypespecWrite -> EffectOutcome.Stored.also {
                mayChange(effect.name)
                docOf(effect.name)?.let(::mayChange)
                if (effect.name in CALLBACK_SPECS) documented = null
            }
            Effect.UnknownEffect -> EffectOutcome.Stored.also { mayChangeEvery() }
            is Effect.OtherModule -> EffectOutcome.Stored.also { if (effect.module == null) mayChangeEvery() }
        }

    /**
     * `deftypespec`'s `get_doc_info` (`Typespec:117–162` at v1.20.4, the same at v1.11.4): a type takes `@typedoc`,
     * and a callback `@doc` where its kind, name and arity has no doc yet, once its name and arity are read.
     */
    private fun takeDoc(effect: Effect.TypespecWrite) {
        val doc = docOf(effect.name) ?: return
        val head = effect.head
        val documented = documented

        when {
            head == null -> {
                markUnknown(doc)
                if (effect.name in CALLBACK_SPECS) this.documented = null
            }
            effect.name !in CALLBACK_SPECS -> take(doc)
            documented == null -> markUnknown(doc)
            documented.add(effect.name to head) -> take(doc)
        }
    }

    /** The doc attribute a typespec write of [name] takes, `null` for a spec. */
    private fun docOf(name: String): String? =
        when (name) {
            in CALLBACK_SPECS -> "doc"
            "spec" -> null
            else -> "typedoc"
        }

    private fun mayChange(name: String) {
        unknown += name
        changed += name
    }

    private fun mayChangeEvery() {
        everyUnknown = true
        everyChanged = true
    }

    private fun write(name: String, term: Term): EffectOutcome =
        when (val prepared = prepare(name, term, statement = true)) {
            Prepared.Raise -> EffectOutcome.Raises(INVALID_ATTRIBUTE_VALUE)
            is Prepared.Metadata -> if (prepared.checked) EffectOutcome.Stored else EffectOutcome.Unchecked
            is Prepared.Store.Checked -> EffectOutcome.Stored.also { store(name, prepared.value) }
            is Prepared.Store.Unchecked -> EffectOutcome.Unchecked.also { store(name, prepared.value) }
        }

    private sealed interface Prepared {
        /** Whether Elixir is known to take the value where the write runs. */
        val checked: Boolean

        sealed interface Store : Prepared {
            val value: AttributeValue

            data class Checked(override val value: AttributeValue) : Store {
                override val checked get() = true
            }

            data class Unchecked(override val value: AttributeValue) : Store {
                override val checked get() = false
            }
        }

        /** A doc attribute's keyword list, which goes to the doc's metadata and leaves the doc as it was. */
        data class Metadata(override val checked: Boolean) : Prepared

        data object Raise : Prepared {
            override val checked get() = false
        }
    }

    /** What Elixir's walk of a list value does: take it, raise `ArgumentError`, or have no clause for its tail. */
    private enum class Walk { VALID, INVALID, CRASHES }

    /** A walked value as Elixir takes it. Its `FunctionClauseError` isn't modelled. */
    private fun prepareWalked(term: Term, walk: Walk): Prepared =
        when (walk) {
            Walk.VALID -> Prepared.Store.Checked(AttributeValue.of(term))
            Walk.INVALID -> Prepared.Raise
            Walk.CRASHES -> Prepared.Store.Unchecked(AttributeValue.of(term))
        }

    /** `Module.put_attribute/7`'s clauses and `preprocess_attribute/2` (`Mod:2138–2345`). */
    private fun prepare(name: String, term: Term, statement: Boolean): Prepared {
        val value = AttributeValue.of(term)

        return when {
            name == "on_load" -> prepareOnLoad(value, statement)
            name in TYPESPECS -> Prepared.Raise
            term.isShaped() -> prepareKnown(name, term)
            isChecked(name) -> Prepared.Store.Unchecked(value)
            else -> Prepared.Store.Checked(value)
        }
    }

    /** The value is checked before an earlier write is looked for (`Mod:2139–2163`). */
    private fun prepareOnLoad(value: AttributeValue, statement: Boolean): Prepared {
        val term = (value as? AttributeValue.Known)?.term
        val prepared = when (term) {
            null -> value
            is Term.Atom -> AttributeValue.Known(Term.Pair(term, ZERO))
            is Term.Pair if term.first is Term.Atom && term.second == ZERO -> value
            else -> return Prepared.Raise
        }

        return when {
            // A write that may run more than once, or follow one that may not have run, may or may not be the second.
            !statement || isUnknown("on_load") -> Prepared.Store.Unchecked(prepared)
            "on_load" in entries -> Prepared.Raise
            term == null -> Prepared.Store.Unchecked(prepared)
            else -> Prepared.Store.Checked(prepared)
        }
    }

    private fun prepareKnown(name: String, term: Term): Prepared {
        val valid = when (name) {
            in DOCS -> return prepareDoc(term)
            "impl" -> term is Term.Atom && term != NIL
            "behaviour" -> term is Term.Atom || !BEHAVIOUR_VALUE_CHECKED.isSufficient(level)
            "deprecated", "external_resource" -> term.isText()
            "file" -> term.isText() || term is Term.Pair && term.first.isText() && term.second is Term.Integer
            "dialyzer" ->
                if (DIALYZER_ATTRIBUTE_CHECKED.isSufficient(level)) {
                    return prepareWalked(term, walk(term.wrapped(), ::dialyzerOption))
                } else {
                    true
                }
            "nifs" ->
                if (NIFS_ATTRIBUTE_CHECKED.isSufficient(level)) {
                    return prepareWalked(term, functionArities(term))
                } else {
                    true
                }
            else -> true
        }

        if (!valid) return Prepared.Raise

        val callback = callbackFunction(name)?.takeIf { term is Term.Atom }

        return Prepared.Store.Checked(AttributeValue.of(callback?.let { Term.Pair(term, Term.Atom(it)) } ?: term))
    }

    /**
     * From 1.14 a list is metadata before the doc itself is checked; before it, a list is checked as a doc first, and
     * only a non-empty one whose first element is a pair with an atom key gets through (`v1.13.4 Mod:2167–2184`).
     */
    private fun prepareDoc(term: Term): Prepared {
        val list = (term as? Term.Pair)?.second as? Term.List

        return when {
            list != null && (ATTRIBUTES_EXPANDED_LAZILY.isSufficient(level) || isLegacyMetadata(term)) -> metadata(list)
            term is Term.Pair && term.first is Term.Integer && term.second.isDoc() ->
                Prepared.Store.Checked(AttributeValue.of(term))
            else -> Prepared.Raise
        }
    }

    private fun isLegacyMetadata(term: Term.Pair): Boolean =
        term.first is Term.Integer &&
            ((term.second as Term.List).elements.firstOrNull() as? Term.Pair)?.first is Term.Atom

    /**
     * `preprocess_doc_meta/4` and `validate_doc_meta/2` (`Mod:2375–2412`), in element order. An element that isn't a
     * pair with an atom key, or an improper tail, has no clause, and its `FunctionClauseError` isn't modelled.
     */
    private fun metadata(list: Term.List): Prepared {
        for (element in list.elements) {
            val pair = element as? Term.Pair
            val key = pair?.first as? Term.Atom

            if (pair == null || key == null) return Prepared.Metadata(checked = false)

            val valid = when (key.name) {
                "since", "deprecated" -> pair.second.isText()
                "delegate_to" -> pair.second.isDelegation()
                else -> true
            }

            if (!valid) return Prepared.Raise
        }

        return Prepared.Metadata(checked = list.tail == null)
    }

    /** `validate_doc_meta(:delegate_to, value)`: `{module, function, arity}`, with atoms and a non-negative integer. */
    private fun Term.isDelegation(): Boolean =
        this is Term.Tuple && elements.size == 3 && elements[0] is Term.Atom && elements[1] is Term.Atom &&
            (elements[2] as? Term.Integer)?.let { it.value.signum() >= 0 } == true

    private fun isChecked(name: String): Boolean =
        when (name) {
            in DOCS, "impl", "deprecated", "external_resource", "file" -> true
            "behaviour" -> BEHAVIOUR_VALUE_CHECKED.isSufficient(level)
            "dialyzer" -> DIALYZER_ATTRIBUTE_CHECKED.isSufficient(level)
            "nifs" -> NIFS_ATTRIBUTE_CHECKED.isSufficient(level)
            else -> false
        }

    private fun store(name: String, value: AttributeValue) {
        val entry = entries[name]

        entries[name] =
            if (entry is Entry.Accumulate && name !in SET_DIRECTLY) Entry.Accumulate(entry.values + value)
            else Entry.Set(value)
    }

    /** `Mod:1782–1799`: accumulating keeps no earlier value, and registering an attribute already there changes nothing else. */
    private fun register(name: String, accumulate: Boolean?) {
        when (accumulate) {
            null -> unknown += name
            true -> if (entries[name] !is Entry.Accumulate) entries[name] = Entry.Accumulate(emptyList())
            false -> entries.putIfAbsent(name, Entry.Unset)
        }
    }

    /** `Mod:1729–1745`: an accumulating attribute keeps accumulating. */
    private fun delete(name: String) {
        heads.remove(name)

        when (entries[name]) {
            is Entry.Accumulate -> entries[name] = Entry.Accumulate(emptyList())
            else -> entries.remove(name)
        }
    }

    private companion object {
        val ZERO = Term.Integer(BigInteger.ZERO)
        val COMPILE_DEFINITION_ATTRIBUTES = Term.Pair(Term.Atom(ELIXIR_MODULE), Term.Atom("compile_definition_attributes"))

        /** Written by a `put_attribute/7` clause of their own, which never accumulates. */
        val SET_DIRECTLY = DOCS + setOf("impl", "deprecated", "on_load")

        val ALWAYS_ACCUMULATING =
            listOf(
                "after_compile", "before_compile", "behaviour", "compile", "derive", "dialyzer", "external_resource",
                "on_definition", "optional_callbacks",
            ) + TYPESPECS

        val CALLBACKS = mapOf(
            "before_compile" to "__before_compile__",
            "after_compile" to "__after_compile__",
            "after_verify" to "__after_verify__",
            "on_definition" to "__on_definition__",
        )

        /** `valid_dialyzer_attribute?/1` at 1.16, whose options 1.12–1.15 partly refuse. */
        val DIALYZER_OPTIONS = setOf(
            "no_return", "no_unused", "no_improper_lists", "no_fun_app", "no_match", "no_opaque", "no_fail_call",
            "no_contracts", "no_behaviours", "no_undefined_callbacks", "unmatched_returns", "error_handling",
            "race_conditions", "no_missing_calls", "specdiffs", "overspecs", "underspecs", "unknown", "no_underspecs",
            "extra_return", "no_extra_return", "no_missing_return", "missing_return", "no_unknown",
        )

        fun Term.isText(): Boolean = this is Term.Binary

        fun Term.isDoc(): Boolean = isText() || this == NIL || this == FALSE

        /** `List.wrap/1`. */
        fun Term.wrapped(): Term.List =
            when (this) {
                NIL -> Term.List(emptyList())
                is Term.List -> this
                else -> Term.List(listOf(this))
            }

        /**
         * `:lists.all/2` and `:lists.foreach/2` over [list]: the first element [check] doesn't find valid decides, and
         * an improper tail after valid elements has no clause.
         */
        fun walk(list: Term.List, check: (Term) -> Walk): Walk =
            list.elements.firstNotNullOfOrNull { element -> check(element).takeUnless { it == Walk.VALID } }
                ?: if (list.tail == null) Walk.VALID else Walk.CRASHES

        /** `valid_dialyzer_attribute?/1`. */
        fun dialyzerOption(term: Term): Walk {
            val key = ((term as? Term.Pair)?.first as? Term.Atom)?.name

            return when (key) {
                null -> if (term is Term.Atom && term.name in DIALYZER_OPTIONS) Walk.VALID else Walk.INVALID
                "nowarn_function", in DIALYZER_OPTIONS -> functionArities(term.second.wrapped())
                else -> Walk.INVALID
            }
        }

        /** `function_arity_list?/1`. */
        fun functionArities(term: Term): Walk =
            if (term is Term.List) {
                walk(term) { element ->
                    val valid = element is Term.Pair && element.first is Term.Atom && element.second is Term.Integer

                    if (valid) Walk.VALID else Walk.INVALID
                }
            } else {
                Walk.INVALID
            }
    }
}

internal const val INVALID_ATTRIBUTE_VALUE = "invalid_attribute_value"

internal val DOCS = setOf("doc", "moduledoc", "typedoc")

/** `typespec?/1`. */
internal val TYPESPECS = setOf("type", "typep", "opaque", "spec", "callback", "macrocallback")

private val CALLBACK_SPECS = setOf("callback", "macrocallback")
