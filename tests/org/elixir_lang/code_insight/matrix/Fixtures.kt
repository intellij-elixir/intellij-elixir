package org.elixir_lang.code_insight.matrix

import com.google.gson.Gson
import java.io.File

/** The fixtures `generate.exs` writes beside [DIRECTORY]: one [Scenario] per backing, form and world. */
object Fixtures {
    const val DIRECTORY = "testData/org/elixir_lang/code_insight/code_intelligence_matrix"

    val oracle: Oracle by lazy { Gson().fromJson(File(DIRECTORY, "oracle.json").readText(), Oracle::class.java) }

    val manifest: Manifest by lazy { Gson().fromJson(File(DIRECTORY, "manifest.json").readText(), Manifest::class.java) }

    fun file(path: String): File = File(DIRECTORY, path)
}

/** [elixir] and [otp] are the pair whose compiler answered which call binds to which definition. */
class Oracle(
    val elixir: String,
    val otp: String,
    val scenarios: List<Scenario>,
    val notApplicable: List<Skipped>
)

/** A (backing, form, world) the generator did not build, and why. */
class Skipped(val backing: String, val form: String, val world: String, val reason: String)

class Manifest(val sha256: Map<String, String>)

/**
 * [caller] calls into [modules] from outside; the first module is the one the world is about.
 *
 * [brokenCallers] hold the calls at an arity nothing defines, which cannot share a file with the rest: made
 * locally such a call is a compile error and would take the whole module with it. There are up to two, because a
 * remote call at an unknown arity only warns, and its warning never arrives if a hard error aborts the same
 * compilation.
 */
class Scenario(
    val backing: String,
    val form: String,
    val world: String,
    val caller: String,
    val brokenCallers: List<String> = emptyList(),
    /**
     * The callers that reach the module through a directive other than a bare `import`: `only:`, `except:`, an import
     * of a module that imports it, a `require`. What each makes visible is on its sites, as [Site.visible].
     */
    val importCallers: List<String> = emptyList(),
    val modules: List<DeclaringModule>,
    val sites: List<Site>,
    /** The only marked places asked, where every other place would repeat another world's question; null asks all. */
    val asks: List<String>? = null,
) {
    val main: DeclaringModule get() = modules.first()

    /** Whether the subject is a module attribute rather than a function; its module is then its own [caller]. */
    val attribute: Boolean get() = form == ATTRIBUTE_FORM

    /** Whether the subject is a variable rather than a function; its module is then its own [caller]. */
    val variable: Boolean get() = form == VARIABLE_FORM

    /** Whether the main module's definitions are written in another module's source, as a `use` injects them. */
    val injected: Boolean get() = main.declarations.any { it.file != null }

    /** Every file the scenario's sites live in, so a fixture can hold them all. */
    val callers: List<String> get() = listOf(caller) + brokenCallers + importCallers

    fun module(reference: String): DeclaringModule = modules.single { it.module == reference }
}

/**
 * [source] declares [definitions] in [module]. [beam] is the compiled module where it is compiled, whose
 * definitions decompile through [clauseSource]; a delegate's target can be compiled when its delegator is not.
 * [spec] is what is quoted for the expected heads: the source itself, or an Elixir rendering of an Erlang source.
 * [declarations] are the heads' positions in an Elixir [source]. [delegateTo] is the module a `defdelegate` form
 * delegates to, which is absent from the scenario when it is unresolvable.
 */
class DeclaringModule(
    val module: String,
    val source: String,
    val spec: String,
    val beam: String?,
    val clauseSource: String?,
    val delegateTo: String?,
    /** What a `defdelegate ..., as:` prefixes the head's name with to name the target's function; null without `as:`. */
    val delegateAs: String? = null,
    val definitions: List<Definition>,
    val declarations: List<Declaration>,
    /**
     * Every clause head a reader of this module can honestly describe, as `generate.exs` measured it from the
     * artefact: the source's own heads where there is a source or debug info, one signature per definition where
     * only a `Docs` chunk survives, and name-and-arity alone - parameters written `_` - where only the export and
     * local tables do.
     */
    val heads: List<CommittedHead>,
    /**
     * Whether the reader has everything the author wrote. False for a beam stripped of its debug info, where the
     * guard, the other clauses and sometimes the parameter names are gone for good.
     */
    val complete: Boolean,
    /** What the compiler kept of the module's `@moduledoc`s, where a moduledoc world asks about it. */
    val moduledoc: Moduledoc? = null,
    /**
     * A delegator's functions that `Code.fetch_docs/1` has a doc for. A `defdelegate` without an `@doc` of its own has
     * none there, so null or absent means the delegate has no doc of its own.
     */
    val docs: List<FunctionDoc>? = null,
) {
    val compiled: Boolean get() = beam != null

    fun doc(name: String, arity: Int): String? = docs?.firstOrNull { nfc(it.name) == nfc(name) && it.arity == arity }?.doc
}

class FunctionDoc(val name: String, val arity: Int, val doc: String)

/**
 * The module's documentation as `Code.fetch_docs/1` has it: [doc], or none where [hidden] by `@moduledoc false`.
 * [written] is every text a `@moduledoc` in the source spells, whichever the compiler kept.
 */
class Moduledoc(val doc: String?, val hidden: Boolean, val written: List<String>)

/** A clause head as `generate.exs` read it from the source; [Expected] turns these into the expected [Head]s. */
class CommittedHead(
    val definer: String,
    val name: String,
    val parameters: List<String>,
    val defaults: Int,
    val guard: String?,
)

/** A definition spans [minArity] to [maxArity] when it has default arguments. */
class Definition(val name: String, val minArity: Int, val maxArity: Int, val clauses: Int) {
    fun covers(binding: Binding): Boolean = nfc(binding.name) == nfc(name) && binding.arity in minArity..maxArity
}

/**
 * [spelled] is what is written at the position where that is not [name]: an embed's atom, which lacks the suffix the embed adds.
 * [file] is the source the declaration is written in where that is not its module's own: a definition a `use` injects is
 * written in the `__using__` quote of another module, and defined in every module that uses it.
 */
class Declaration(
    val name: String,
    val arity: Int,
    val clause: Int,
    val definer: String,
    val line: Int,
    val column: Int,
    val spelled: String? = null,
    val file: String? = null,
)

/**
 * A marked place in [file], 1-based, and the definition the compiler bound it to, or none.
 *
 * [diagnostic] is what the compiler said about a call at an arity nothing defines - the answer the IDE should be
 * giving in its own words. It is absent wherever the call is correct.
 */
class Site(
    val id: String,
    val file: String,
    val line: Int,
    val column: Int,
    /** What the call names and how many arguments it passes, whether or not anything defines that. */
    val name: String,
    val arity: Int,
    val binding: Binding?,
    val diagnostic: Diagnostic? = null,
    /**
     * For a call written bare under a directive that names what it brings in, the declaring module's `name/arity`s
     * that directive makes callable, as Elixir's own `__ENV__` has them; null where the caller imports the module
     * whole. Empty where nothing is brought in at all.
     */
    val visible: List<String>? = null,
    /**
     * Where Go To Declaration lands, as lines of [file]: for an interpolated atom, those declaring the module it evaluates
     * to; empty where it must land nowhere, even on a definition outside the scenario's modules.
     */
    val targets: List<Int>? = null,
    /** For a module attribute's read or write, what the compiler says of it; null at a call. */
    val attribute: AttributeSite? = null,
    /** For a variable's binding or read, what the compiler says of it; null at a call. */
    val variable: VariableSite? = null,
)

/**
 * A `@name value` ([write]) or `@name` read. [declarations] are the ids of the writes a read's value came from, under
 * any branch choice, and a write's own id. [visible] is the user attributes set where a read is, under any branch
 * choice; null at a write.
 */
class AttributeSite(val write: Boolean, val declarations: List<String>, val visible: List<String>? = null)

/** A variable's [binding] or read. [declarations] are the ids of the bindings a read is of, and a binding's own id. */
class VariableSite(val binding: Boolean, val declarations: List<String>)

/** Whether a bare call at [this] site can reach [definition] at any of its arities: always, unless [Site.visible] says. */
fun Site.sees(definition: Definition): Boolean =
    visible?.let { names ->
        val visible = names.map(::nfc)
        (definition.minArity..definition.maxArity).any { "${nfc(definition.name)}/$it" in visible }
    } ?: true

/** [severity] is the compiler's: a remote call at an unknown arity warns, a local one is an error. */
class Diagnostic(val severity: String, val message: String)

class Binding(val module: String, val name: String, val arity: Int, val kind: String) {
    /** Whether this names a module rather than a function: an alias the compiler expanded, or a `defmodule`'s name. */
    val namesModule: Boolean get() = kind in MODULE_KINDS
}

private val MODULE_KINDS = setOf("alias_reference", "defmodule")
