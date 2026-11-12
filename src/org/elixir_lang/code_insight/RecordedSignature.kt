package org.elixir_lang.code_insight

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.IOLib
import org.elixir_lang.Macro
import org.elixir_lang.Module
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.code.Identifier
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.debugger.stack_frame.value.Presentation
import org.elixir_lang.declaration.ClauseOrder
import org.elixir_lang.declaration.Form
import org.elixir_lang.documentation.moduleAttributeValue
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.model.psi.module_attribute.ModuleAttributeReference
import org.elixir_lang.model.psi.module_attribute.accumulates
import org.elixir_lang.module.RegisterAttribute
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirStructOperation
import org.elixir_lang.psi.isDefaultArgument
import org.elixir_lang.psi.Exception as ElixirException
import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.moduleAttributeAtom
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.Infix
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.toOtpErlangList

/**
 * The signature Elixir records for a function in its `Docs` chunk: [text] as `Code.fetch_docs` gives it, and each of
 * its [parameters] as text.
 */
class RecordedSignature(val parameters: List<String>, val text: String) {
    /**
     * One clause of a function, as [form] declares it at [head], the head `DeclaringForm.head` gives, defined at [order]
     * in its module.
     */
    data class Clause(val form: Form, val head: PsiElement, val order: ClauseOrder = ClauseOrder.at(head))

    companion object {
        /**
         * The signature Elixir records for [name] at the full arity of [arities] in [module], a module's index name
         * (`Module.indexName`), from [clauses] taken in the order the module defines them, or `null` where it records
         * none or the arity is open: `Module.build_signature/2` over each clause's head, merged as `compile_doc` merges
         * them, and printed as `elixir_erl:signature_to_binary/3` prints it.
         */
        @RequiresReadLock
        fun of(module: String?, name: String, arities: ArityInterval, clauses: List<Clause>): RecordedSignature? {
            ThreadingAssertions.assertReadAccess()

            val (arity, open) = arities.definition()

            if (open) return null

            if (name == "__struct__" && arity == 0) {
                return module?.let { RecordedSignature(emptyList(), "%${Module.inspect(it)}{}") }
            }

            val built = clauses.sortedBy(Clause::order).map { built(module, name, it) }

            if (Built.Unknown in built) return null

            val parameters = built
                .filterIsInstance<Built.Recorded>()
                .map(Built.Recorded::parameters)
                .reduceOrNull { older, newer -> older.zip(newer).mapIndexed { i, (o, n) -> merged(o, n, i + 1) } }
                ?: return null

            val text = when (name) {
                "__aliases__", "__block__" -> "$name(args)"
                "fn" ->
                    if (ElixirLanguageLevelResolver.isAvailable(ElixirLanguageFeature.DOCS_SIGNATURE_OF_FN_NAMES_CLAUSES, clauses.first().head)) {
                        "fn(clauses)"
                    } else {
                        "fn"
                    }
                "__CALLER__", "__DIR__", "__ENV__", "__MODULE__", "__STACKTRACE__", "%{}" -> name
                "%" -> "%struct{}"
                else -> printed(name, parameters)
            }

            return RecordedSignature(parameters.map(Parameter::text), text)
        }

        /** `Module.build_signature/2` over [clause]'s head. */
        private fun built(module: String?, name: String, clause: Clause): Built {
            val simplified = when (clause.form) {
                Form.CLAUSE, Form.DELEGATION ->
                    (CallDefinitionHead.strip(clause.head) as? Call)
                        ?.let(CallDefinitionHead::parameters)
                        ?.map(Simplifier(module, clause.head)::argument)
                        .orEmpty()
                Form.EXCEPTION -> return exception(name, clause.head as? Call)?.let(Built::Recorded) ?: Built.None
                Form.EEX_FUNCTION_FROM ->
                    (clause.head as? Call)?.let { eexFunctionFrom(Simplifier(module, it), it) }.orEmpty()
                Form.GENERATOR_EMBED -> embed(clause.head as? Call)
                Form.CALLBACK -> return Built.None
            }

            return if (null in simplified) Built.Unknown else Built.Recorded(expandKeys(simplified.filterNotNull()))
        }

        /**
         * `defexception`'s `exception/1` takes `msg` where the struct has a `:message` field, else `args`, and its
         * `message/1`, defined only with that field, takes `exception`. Fields the plugin cannot read leave `message/1`
         * as the walk declares it, and `exception/1` as every struct defines it.
         */
        private fun exception(name: String, call: Call?): List<Parameter>? {
            val hasMessage = call?.finalArguments()?.singleOrNull()?.let(::fields)?.any { field ->
                field == MESSAGE || (field as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elementAt(0) == MESSAGE
            }

            return when (name) {
                ElixirException.EXCEPTION.name -> listOf(Variable(if (hasMessage == true) "msg" else "args", false))
                ElixirException.MESSAGE.name ->
                    listOf(Variable(ElixirException.EXCEPTION.name, false)).takeUnless { hasMessage == false }
                else -> null
            }
        }

        /** The fields `defexception` is given as [fields], written or held by an `@name`, where they are a literal list. */
        private fun fields(fields: PsiElement): Array<OtpErlangObject>? {
            val written = (attributeValue(fields) ?: fields).stripAccessExpression()

            return ((written as? Quotable)?.quote() as? OtpErlangList)?.elements()
        }

        /** The macro's own `[:a, :b]` argument-name list, which no PSI head spells, each name read as a variable. */
        private fun eexFunctionFrom(simplifier: Simplifier, call: Call): List<Simplified> =
            call.finalArguments()
                ?.let(Declarations::eexBindings)
                ?.first
                ?.map(simplifier::variableNamed)
                .orEmpty()

        private fun embed(call: Call?): List<Simplified> =
            if (call != null && embedsAssigns(call)) listOf(Variable("assigns", false)) else emptyList()

        private val MESSAGE = OtpErlangAtom("message")
    }
}

/**
 * The macro's own `[:a, :b]` argument-name list, which no PSI head spells, `null` for a name that isn't an atom
 * literal.
 */
internal fun eexFunctionFromNames(call: Call): List<String?> =
    call.finalArguments()
        ?.getOrNull(3)
        ?.let { (it.stripAccessExpression() as? ElixirList)?.children }
        ?.map { child -> (child.stripAccessExpression() as? ElixirAtom)?.let(::quotedAtomValue) }
        .orEmpty()

/** `embed_template` defines a function of `assigns`, `embed_text` one of nothing. */
internal fun embedsAssigns(call: Call): Boolean = call.functionName() == "embed_template"

/** What Elixir records for one clause's head. */
private sealed interface Built {
    data class Recorded(val parameters: List<Parameter>) : Built

    /** Its form records nothing. */
    data object None : Built

    /** A parameter is named by a module the plugin cannot name, so what Elixir records is not known. */
    data object Unknown : Built
}

/** A parameter of a recorded signature: a variable, or one with a default. Named already, so `expand_keys` keeps it. */
private sealed interface Parameter : Simplified {
    val text: String
}

/** A variable named [name]; [guessed] where the name is only Elixir's guess, which a later clause can replace. */
private data class Variable(val name: String, val guessed: Boolean) : Parameter {
    override val text: String get() = name
}

/** [left] with the default [default], as written. */
private data class Defaulted(val left: Parameter, val default: String) : Parameter {
    override val text: String get() = "${left.text} \\\\ $default"
}

/** A parameter as `simplify_arg` leaves it, before `expand_keys` names its keys: a [Parameter] where named already. */
private sealed interface Simplified {
    /** A name Elixir makes from what the argument is, numbered where it makes it more than once. */
    data class Key(val key: String) : Simplified

    /** [left] with [default]. */
    data class Default(val left: Simplified, val default: String) : Simplified
}

/** `simplify_var(var, Elixir)`: a leading `_` is dropped, and only a name with one is a guess. */
private fun variable(name: String): Simplified =
    if (name.startsWith("_")) Variable(namedBy(name), true) else Variable(name, false)

/** `simplify_var(var, nil)`: a leading `_` is dropped, but not from `_` itself. */
private fun namedBy(name: String): String = if (name == "_") name else name.removePrefix("_")

/** `expand_keys/3`: a key made once is its own name, and one made more often is numbered from 1 in order. */
private fun expandKeys(simplified: List<Simplified>): List<Parameter> {
    fun key(parameter: Simplified): String? =
        when (parameter) {
            is Simplified.Key -> parameter.key
            is Simplified.Default -> key(parameter.left)
            is Parameter -> null
        }

    val totals = simplified.mapNotNull(::key).groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()

    fun expanded(parameter: Simplified): Parameter =
        when (parameter) {
            is Parameter -> parameter
            is Simplified.Key -> {
                val key = parameter.key
                val nth = seen.merge(key, 1, Int::plus)!!

                Variable(if (totals.getValue(key) > 1) "$key$nth" else key, true)
            }
            is Simplified.Default -> Defaulted(expanded(parameter.left), parameter.default)
        }

    return simplified.map(::expanded)
}

/** `merge_signature/3` of an [older] clause's parameter at 1-based [position] with a [newer] one's. */
private fun merged(older: Parameter, newer: Parameter, position: Int): Parameter =
    when {
        older is Defaulted -> Defaulted(merged(older.left, newer, position), older.default)
        newer is Defaulted -> merged(older, newer.left, position)
        older is Variable && !older.guessed -> older
        newer is Variable && !newer.guessed -> newer
        older is Variable && newer is Variable && older.name == newer.name -> older
        else -> Variable("arg$position", true)
    }

/** `Code.quoted_to_algebra/2` of a call to [name] with [parameters], as `signature_to_binary` prints it. */
private fun printed(name: String, parameters: List<Parameter>): String {
    val operands = parameters.map { if (it is Defaulted) "(${it.text})" else it.text }
    val call = "$name(${parameters.joinToString(", ", transform = Parameter::text)})"

    return when (operands.size) {
        1 ->
            when {
                name == "not" -> "not ${operands[0]}"
                Identifier.unaryOperator(name) != null -> "$name${operands[0]}"
                else -> call
            }
        2 ->
            when {
                name == ".." -> "${operands[0]}..${operands[1]}"
                isBinaryOperator(name) -> "${operands[0]} $name ${operands[1]}"
                else -> call
            }
        3 -> if (name == "..//") "${operands[0]}..${operands[1]}//${operands[2]}" else call
        else -> call
    }
}

/** An operator Elixir writes between its two operands; `+` and `-` are also unary, the rest of the unary ones only so. */
private fun isBinaryOperator(name: String): Boolean =
    InspectAtom.classify(name).let { it == InspectAtom.Class.UNQUOTED_OPERATOR || it == InspectAtom.Class.QUOTED_OPERATOR } &&
        (Identifier.unaryOperator(name) == null || name == "+" || name == "-")

/**
 * The value written where the attribute [use], an `@name` use, is set, or `null` where [use] is no `@name`, names none,
 * or may accumulate, as its value is then every write, which the plugin does not gather.
 */
private fun attributeValue(use: PsiElement): PsiElement? {
    val at = use.stripAccessExpression() as? AtOperation ?: return null
    val name = moduleAttributeAtom(at) ?: return null

    return if (accumulates(name, ElixirLanguageLevelResolver.languageLevelFor(at)) || registeredToAccumulate(name, at)) {
        null
    } else {
        ModuleAttributeReference.resolveDeclaration(at)?.moduleAttributeValue()
    }
}

/** Whether the module's nearest `Module.register_attribute/3` call for the attribute [name] before [use] may accumulate it. */
private fun registeredToAccumulate(name: String, use: PsiElement): Boolean =
    generateSequence(PsiTreeUtil.getParentOfType(use, Call::class.java)) { PsiTreeUtil.getParentOfType(it, Call::class.java) }
        .firstNotNullOfOrNull { CallDefinitionClause.enclosingModularMacroCall(it) }
        ?.let { CallDefinitionClause.modularChildCalls(it) }
        ?.lastOrNull { call ->
            call.textOffset < use.textOffset && RegisterAttribute.`is`(call) && RegisterAttribute.name(call) == "@$name"
        }
        ?.let(::mayAccumulate) == true

/**
 * Whether [registration], a `Module.register_attribute/3` call, may accumulate its attribute: its options are not a
 * literal keyword list whose `accumulate:`, where it has one, is `false`.
 */
private fun mayAccumulate(registration: Call): Boolean {
    val options = (registration.finalArguments()?.lastOrNull()?.stripAccessExpression() as? Quotable)
        ?.quote() as? OtpErlangList
        ?: return true
    val pairs = options.elements().map { element ->
        (element as? OtpErlangTuple)?.takeIf { it.arity() == 2 && it.elementAt(0) is OtpErlangAtom } ?: return true
    }
    val accumulate = pairs.firstOrNull { it.elementAt(0) == ACCUMULATE }?.elementAt(1)

    return accumulate != null && accumulate != OtpErlangAtom(false)
}

private val ACCUMULATE = OtpErlangAtom("accumulate")

/** `simplify_args/4` over one head's arguments, for a function in [module], at [head]'s language level. */
private class Simplifier(private val module: String?, head: PsiElement) {
    private val varBang = ElixirLanguageLevelResolver.isAvailable(ElixirLanguageFeature.DOCS_SIGNATURE_NAMES_VAR_BANG, head)

    /** The variable the atom [element] evaluates to names, as `EEx.function_from_string/5` makes one, else `arg`. */
    @RequiresReadLock
    fun variableNamed(element: PsiElement): Simplified =
        when (val stripped = element.stripAccessExpression()) {
            is ElixirAtom -> quotedAtomValue(stripped)
            is AtOperation -> attribute(stripped)?.atom
            else -> null
        }
            ?.let(::variable)
            ?: Simplified.Key("arg")

    /** `simplify_arg/3`, or `null` where it names the argument by a module the plugin cannot name. */
    fun argument(element: PsiElement): Simplified? {
        val stripped = element.stripAccessExpression()

        // Only a default's text is shown, so it is read from the PSI rather than quoted.
        if (stripped is Infix && stripped.isDefaultArgument()) {
            val left = stripped.leftOperand()?.let { argument(it) ?: return null } ?: Simplified.Key("arg")

            return Simplified.Default(left, stripped.rightOperand()?.let(::defaultText).orEmpty())
        }

        val quoted = (stripped as? Quotable)?.quote() ?: return Simplified.Key("arg")
        val variableName = quoted.variableName()

        return when {
            quoted.isCall("=", 2) ->
                (quoted.argument(0).variableName() ?: quoted.argument(1).variableName())
                    ?.let { Variable(namedBy(it), false) }
                    ?: Simplified.Key("arg")
            variableName != null -> variable(variableName)
            quoted.isCall("%", 2) -> structKey(stripped as? ElixirStructOperation, quoted.argument(0))?.let(Simplified::Key)
            quoted.isCall("%{}") -> Simplified.Key("map")
            quoted.isCall("@") -> Simplified.Key(attribute(stripped)?.key ?: "arg")
            varBang && quoted.isCall("var!") ->
                quoted.argument(0).variableName()?.let(::variable) ?: Simplified.Key("arg")
            else -> Simplified.Key(literal(quoted))
        }
    }

    /**
     * What `Macro.expand_once/2` makes of [use], an `@name` use: the value the attribute holds, where it is data the
     * plugin reads without evaluating code.
     */
    private fun attribute(use: PsiElement): Value? = attributeValue(use)?.let(::value)

    /** The value [element], written data, evaluates to: an alias names the module its `alias` gives it. */
    private fun value(element: PsiElement): Value? {
        val stripped = element.stripAccessExpression()
        val quoted = (stripped as? Quotable)?.quote() ?: return null
        val aliased = quoted.takeIf { it.isCall("__aliases__") }
            ?.let { (stripped.reference?.resolve() as? CanonicallyNamed)?.canonicalName() }

        return aliased?.let(Module::atom)?.let(::atom) ?: value(quoted)
    }

    /**
     * The value [quoted] evaluates to, where it is data, with the term `Macro.escape/1` makes of it where the plugin
     * prints that as Elixir does. A container holding anything else has only its key. Elixir prints a float with its
     * digits grouped (`100_000.0`), a binary that is not UTF-8 as a string with escapes (`"\xFF"`), a non-empty map
     * with its keys sorted, and a struct with every field its module's compile gives it, so none of them has a term.
     * Nor has a list of integers that either Elixir or the plugin prints as a charlist, since they accept different
     * characters (`List.ascii_printable?/1`, `:io_lib.printable_list/1`). An alias that an `alias` can rename has no
     * term here either, as only [value] of its element reads the `alias`.
     */
    private fun value(quoted: OtpErlangObject): Value? =
        when {
            quoted is OtpErlangAtom -> atom(quoted.atomValue())
            quoted is OtpErlangLong -> Value(literal(quoted), quoted)
            quoted is OtpErlangBinary -> Value(literal(quoted), quoted.takeIf { Presentation.toUtf8String(it) != null })
            quoted is OtpErlangList -> {
                val elements = quoted.elements()
                val charlist = elements.isNotEmpty() && (IOLib.printableList(quoted) || asciiPrintable(elements))

                Value(
                    literal(quoted),
                    elements.takeIf { quoted.lastTail == null && !charlist }?.map(::value)?.escaped()?.let(::OtpErlangList)
                )
            }
            quoted.isCall("-", 1) || quoted.isCall("+", 1) ->
                when (val number = quoted.argument(0)) {
                    is OtpErlangLong ->
                        value(OtpErlangLong(number.bigIntegerValue().let { if (quoted.isCall("-")) it.negate() else it }))
                    is OtpErlangDouble -> Value(literal(number), null)
                    else -> null
                }
            quoted.isCall("__MODULE__") -> module?.let(Module::atom)?.let(::atom) ?: UNNAMED_MODULE
            quoted.isCall("__aliases__") ->
                aliased(quoted as OtpErlangTuple)?.let { name ->
                    if (renamesNone(quoted)) atom(name) else Value("atom", null, name)
                } ?: UNNAMED_MODULE.takeIf { module == null && quoted.argument(0).isCall("__MODULE__") }
            quoted.isCall("%{}") -> Value("map", quoted.takeIf { it.arguments()?.isEmpty() == true })
            quoted.isCall("%", 2) -> Value("map", null)
            quoted.isCall("{}") ->
                Value(literal(quoted), quoted.arguments()?.map(::value)?.escaped()?.let { Macro.expr("{}", OtpErlangList(it)) })
            quoted is OtpErlangTuple && quoted.arity() == 2 ->
                Value(literal(quoted), quoted.elements().map(::value).escaped()?.let(::OtpErlangTuple))
            // A list of bytes is quoted as a string.
            quoted is OtpErlangString -> value(quoted.toOtpErlangList())
            quoted is OtpErlangDouble -> Value(literal(quoted), null)
            else -> null
        }

    /** `List.ascii_printable?/1`: `\a` to `\r`, `\e`, and space to `~`. */
    private fun asciiPrintable(elements: Array<OtpErlangObject>): Boolean =
        elements.all { element ->
            (element as? OtpErlangLong)?.longValue()?.let { it in 7..13 || it == 27L || it in 32..126 } == true
        }

    /**
     * The atom naming the module [aliases], `__aliases__`, names as written: nested in the module after `__MODULE__`
     * where it starts with it, as `elixir_aliases:concat/1` composes it.
     */
    private fun aliased(aliases: OtpErlangTuple): String? {
        val segments = aliases.arguments() ?: return null
        val first = segments.first()
        val rest = segments.drop(1).map { (it as? OtpErlangAtom)?.atomValue() ?: return null }

        return when {
            first.isCall("__MODULE__") ->
                module?.let { if (rest.isEmpty()) it else Module.nest(it, Module.concat(rest)) }?.let(Module::atom)
            first !is OtpErlangAtom -> null
            // `Elixir.Foo` is written with the prefix it expands to.
            first.atomValue() == "Elixir" -> if (rest.isEmpty()) "Elixir" else Module.atom(Module.concat(rest))
            else -> Module.atom(Module.concat(listOf(first.atomValue()) + rest))
        }
    }

    /** Whether [aliases] starts where no `alias` renames it: `__MODULE__` or `Elixir`. */
    private fun renamesNone(aliases: OtpErlangTuple): Boolean =
        aliases.argument(0).let { it.isCall("__MODULE__") || (it as? OtpErlangAtom)?.atomValue() == "Elixir" }

    /** The value that is the atom [name]. */
    private fun atom(name: String): Value = OtpErlangAtom(name).let { Value(literal(it), it, name) }

    /**
     * `simplify_module_name/1` of the module `%left{}` names, `Macro.expand_once/2`'d, or `struct` where it is no module;
     * `null` where it is a module the plugin cannot name.
     */
    private fun structKey(struct: ElixirStructOperation?, left: OtpErlangObject?): String? {
        val value = when {
            left.isCall("@") -> struct?.matchedExpression?.let(::attribute)
            left.isCall("__aliases__") -> struct?.alias?.let(::value) ?: left?.let(::value)
            else -> left?.let(::value)
        }

        return if (value === UNNAMED_MODULE) null else value?.atom?.let(::moduleKey) ?: "struct"
    }

    /** A default as written, on one line, with each attribute replaced by its value where the plugin prints it. */
    private fun defaultText(default: PsiElement): String {
        val start = default.textRange.startOffset
        val replacements = PsiTreeUtil.findChildrenOfAnyType(default, false, AtOperation::class.java)
            .mapNotNull { at -> attribute(at)?.escaped?.let { at.textRange to Macro.toString(it) } }
            .sortedByDescending { (range, _) -> range.startOffset }
        val text = StringBuilder(default.text)

        for ((range, value) in replacements) {
            text.replace(range.startOffset - start, range.endOffset - start, value)
        }

        return ParameterText.normalised(text.toString())
    }
}

/**
 * A value Elixir evaluates written data to: `simplify_arg/3`'s [key] for it, the term `Macro.escape/1` makes of it
 * where the plugin prints that as Elixir does, and the [atom] it is, where it is one.
 */
private class Value(val key: String, val escaped: OtpErlangObject?, val atom: String? = null)

/** `__MODULE__`, or an alias nested in it, where the module a head is in is not known, as in a `quote`: an atom with no name. */
private val UNNAMED_MODULE = Value("atom", null)

/** The values' escaped terms, where every one has its term. */
private fun List<Value?>.escaped(): Array<OtpErlangObject>? = map { it?.escaped ?: return null }.toTypedArray()

/** `simplify_module_name/1` of the atom [name]: an Elixir module's last segment underscored, else the atom itself. */
private fun moduleKey(name: String): String =
    if (name.startsWith("Elixir.")) underscore(name.substringAfterLast('.')) else name

/** `simplify_arg/3`'s last clauses: what a literal is, by its type, else `arg`. */
private fun literal(quoted: OtpErlangObject): String =
    when (quoted) {
        is OtpErlangLong -> "int"
        is OtpErlangAtom -> if (quoted.atomValue() == "true" || quoted.atomValue() == "false") "bool" else "atom"
        is OtpErlangList, is OtpErlangString -> "list"
        is OtpErlangDouble -> "float"
        is OtpErlangBinary -> "binary"
        else -> "arg"
    }

/** Whether this is `{name, meta, args}`, with [arity] arguments where given; `__MODULE__` has a context, not a list. */
private fun OtpErlangObject?.isCall(name: String, arity: Int? = null): Boolean =
    this is OtpErlangTuple && Macro.isExpression(this) && (elementAt(0) as? OtpErlangAtom)?.atomValue() == name &&
        if (Macro.isLocalCall(this)) {
            arity == null || Macro.callArguments(this).arity() == arity
        } else {
            arity == null && name == "__MODULE__" && elementAt(2) is OtpErlangAtom
        }

private fun OtpErlangObject?.arguments(): Array<OtpErlangObject>? =
    (this as? OtpErlangTuple)?.takeIf(Macro::isLocalCall)?.let(Macro::callArguments)?.elements()

private fun OtpErlangObject?.argument(index: Int): OtpErlangObject? = arguments()?.getOrNull(index)

/** The name of `{name, meta, context}`, a variable. */
private fun OtpErlangObject?.variableName(): String? =
    ((this as? OtpErlangTuple)?.takeIf(Macro::isVariable)?.elementAt(0) as? OtpErlangAtom)?.atomValue()

/**
 * `Macro.underscore/1` of one segment. Elixir walks bytes, but it tests each only against ASCII, which no byte of a
 * multi-byte character is, so walking characters gives the same text.
 */
private fun underscore(segment: String): String {
    fun Char.upper() = this in 'A'..'Z'
    fun Char.lower() = if (upper()) this + 32 else this

    if (segment.isEmpty()) return ""

    val out = StringBuilder().append(segment[0].lower())
    var prev = segment[0]
    var i = 1

    while (i < segment.length) {
        val h = segment[i]
        val t = segment.getOrNull(i + 1)

        when {
            h.upper() && t != null && !t.upper() && t !in '0'..'9' && t != '.' && t != '_' -> {
                out.append('_').append(h.lower()).append(t)
                prev = t
                i += 2
            }
            h.upper() && !prev.upper() && prev != '_' -> {
                out.append('_').append(h.lower())
                prev = h
                i++
            }
            else -> {
                out.append(h.lower())
                prev = h
                i++
            }
        }
    }

    return out.toString()
}
