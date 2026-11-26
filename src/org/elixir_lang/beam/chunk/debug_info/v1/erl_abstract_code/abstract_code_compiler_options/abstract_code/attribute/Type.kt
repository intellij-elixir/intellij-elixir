package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Attribute
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Attributes
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.anonymousVariableToAny
import org.elixir_lang.beam.decompiler.Options
import org.elixir_lang.beam.decompiler.ReservedTypeName
import org.elixir_lang.code.Identifier

private const val TYPE_ATTRIBUTE_NAME = "type"
private const val OPAQUE_ATTRIBUTE_NAME = "opaque"
private const val NOMINAL_ATTRIBUTE_NAME = "nominal"

/** The attributes that declare a type. `-nominal` is OTP 28+. */
private val TYPE_ATTRIBUTE_NAMES = setOf(TYPE_ATTRIBUTE_NAME, OPAQUE_ATTRIBUTE_NAME, NOMINAL_ATTRIBUTE_NAME)

class Type(val attributes: Attributes, attribute: Attribute): MacroString(attribute) {
    val elixirAttributeName by lazy { elixirAttributeName() }
    val name by lazy { ((attribute.value as? OtpErlangTuple)?.elementAt(0) as? OtpErlangAtom)?.atomValue() }
    val value by lazy { ((attribute.value as? OtpErlangTuple)?.elementAt(1) as? OtpErlangTuple) }

    /**
     * The type's arity - the number of type parameters. The Erlang abstract form of `-type name(V_1, ..., V_k)`
     * is `{attribute, ANNO, type, {Name, Rep(T), [Rep(V_1), ..., Rep(V_k)]}}`, so the arity is the length of the
     * argument list at element 2 of the value tuple.
     */
    val arity by lazy {
        ((attribute.value as? OtpErlangTuple)?.elementAt(2) as? OtpErlangList)?.arity() ?: 0
    }

    override fun toMacroString(options: Options): String {
        val elixirAttributeName = elixirAttributeName()
        val subtypeMacroString = subtypeMacroString()
        val rendered = "@$elixirAttributeName $subtypeMacroString"

        // A type whose name is a reserved Elixir word (e.g. an Erlang `-type nil()`) has no valid
        // Elixir declaration, so preserve it as a comment instead of emitting unparseable source.
        return name
            ?.takeIf { ReservedTypeName.isReserved(it) }
            ?.let { ReservedTypeName.commentOut(it, rendered) }
            ?: rendered
    }

    private fun elixirAttributeName() =
        when (attribute.name) {
            OPAQUE_ATTRIBUTE_NAME -> OPAQUE_ATTRIBUTE_NAME
            else ->
                name?.let { nameArityToElixirAttributeName(NameArity(it, arity)) }
                    ?: "unknown_type_attribute"
        }

    private fun nameArityToElixirAttributeName(nameArity: NameArity) =
            if (attributes.exportTypeNameAritySet.contains(nameArity)) {
                "type"
            } else {
                "typep"
            }

    private fun subtypeMacroString() = subtypeMacroString(attribute.value)

    companion object {
        fun from(attributes: Attributes, attribute: Attribute): Type? =
                if (attribute.name in TYPE_ATTRIBUTE_NAMES) {
                    Type(attributes, attribute)
                } else {
                    null
                }

        private fun nameString(term: OtpErlangTuple): String =
            toName(term)
                    ?.let { nameToMacroString(it) }
                    ?: AbstractCode.missing("name", "Attribute type name", term)

        private fun nameToMacroString(name: OtpErlangObject): String =
                when (name) {
                    is OtpErlangAtom -> Identifier.inspectAsFunction(name, true)
                    else -> AbstractCode.unknown("name", "Attribute type name", name)
                }

        /**
         * The type parameters, rendered as `(param1, param2)` (or empty for arity 0), from the argument list at
         * element 2 of the value tuple.  Each parameter is a `Rep(V_i)` variable, rendered the same way the type
         * body renders it, so head parameters match the variables referenced in the body.
         */
        private fun argumentsMacroString(term: OtpErlangTuple): String =
                (toArguments(term))
                        ?.takeIf { it.arity() > 0 }
                        ?.joinToString(", ", prefix = "(", postfix = ")") { AbstractCode.toString(it) }
                        ?: ""

        private fun subtypeMacroString(term: OtpErlangObject?) =
                when (term) {
                    is OtpErlangTuple -> subtypeMacroString(term)
                    else -> if (term != null) {
                        AbstractCode.unknown("subtype", "Attribute type subtype", term)
                    } else {
                        "missing_subtype"
                    }
                }

        private fun subtypeMacroString(term: OtpErlangTuple): String {
            val nameString = nameString(term)
            val argumentsMacroString = argumentsMacroString(term)
            val valueString = valueString(term)

            return "$nameString$argumentsMacroString :: $valueString"
        }

        private fun toName(term: OtpErlangTuple): OtpErlangObject? = term.elementAt(0)
        private fun toValue(term: OtpErlangTuple): OtpErlangObject? = term.elementAt(1)
        private fun toArguments(term: OtpErlangTuple): OtpErlangList? = term.elementAt(2) as? OtpErlangList

        private fun valueString(term: OtpErlangTuple): String =
                toValue(term)
                        ?.let { valueToString(it) }
                        ?: AbstractCode.unknown("value", "Attribute type value", term)

        // A type body is a type expression, so an anonymous `_` variable means `any()` (see
        // [anonymousVariableToAny]).  Only the body is rewritten; the head parameter list ([argumentsMacroString])
        // declares named variables and is left untouched.
        private fun valueToString(value: OtpErlangObject): String =
                AbstractCode.toString(anonymousVariableToAny(value))
    }
}
