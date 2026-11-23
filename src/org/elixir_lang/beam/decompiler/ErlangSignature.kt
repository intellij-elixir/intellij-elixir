package org.elixir_lang.beam.decompiler

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.AnnotatedType
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Clause.toPatternSequence
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Function
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Type
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Var
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.annotated_type.AnnotationType
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Spec
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type.BoundedFun
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type.Fun
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type.Product
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type.bounded_fun.FunBound
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type.`fun`.ParameterReturn
import org.elixir_lang.code.sanitizeErlangVariableName

/**
 * An Erlang function's parameter names, chosen in the order OTP's `beam_doc` chooses them for the `Docs` chunk's
 * signature, after the `-doc` line it reads first, which the abstract code does not keep.
 */
object ErlangSignature {
    fun parameters(function: Function): List<String> =
        function.spec?.let(::specNames)
            ?: loneClauseNames(function)
            ?: function.clauses.firstOrNull()?.parameters.orEmpty()

    /** A spec of one clause whose every argument is a variable, or a variable annotated with a type. */
    private fun specNames(spec: Spec): List<String>? {
        val arguments = spec.definitions?.singleOrNull()?.let(::funArguments) ?: return null

        return arguments.map { argument ->
            (variable(argument) ?: annotatedVariable(argument))?.let(Var::nameToString) ?: return null
        }
    }

    /** `{type, _, fun, [{type, _, product, Arguments}, Return]}`, maybe bounded: `{type, _, bounded_fun, [Fun, _]}`. */
    private fun funArguments(type: OtpErlangObject): List<OtpErlangObject>? =
        Type.ifTo(type) { tuple ->
            BoundedFun.ifTo(tuple) { boundedFun ->
                (BoundedFun.funBound(boundedFun) as? OtpErlangList)?.let(FunBound::toFun)?.let(::funArguments)
            } ?: Fun.ifTo(tuple) { `fun` ->
                (Fun.toParameterReturn(`fun`) as? OtpErlangList)?.let(ParameterReturn::toParameter)?.let(::operands)
            }
        }

    private fun operands(product: OtpErlangObject): List<OtpErlangObject>? =
        Type.ifTo(product) { tuple -> Product.ifTo(tuple) { (Product.toOperands(it) as? OtpErlangList)?.toList() } }

    private fun annotatedVariable(term: OtpErlangObject): OtpErlangAtom? =
        AnnotatedType.ifTo(term) { annotatedType ->
            (AnnotatedType.toAnnotationType(annotatedType) as? OtpErlangList)
                ?.let(AnnotationType::toAnnotation)
                ?.let(::variable)
        }

    /** A lone clause whose every parameter is a variable other than `_`, each with its leading `_` trimmed. */
    private fun loneClauseNames(function: Function): List<String>? {
        val clause = function.clauses.singleOrNull() ?: return null
        val patterns = toPatternSequence(clause.term) as? OtpErlangList ?: return null

        return patterns.map { pattern ->
            val name = variable(pattern)?.atomValue()?.takeIf { it != "_" } ?: return null

            sanitizeErlangVariableName(name.trimStart('_'))
        }
    }

    private fun variable(term: OtpErlangObject): OtpErlangAtom? = Var.ifTo(term) { Var.toName(it) as? OtpErlangAtom }
}
