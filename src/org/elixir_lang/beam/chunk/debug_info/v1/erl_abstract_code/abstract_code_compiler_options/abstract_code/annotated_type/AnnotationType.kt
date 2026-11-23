package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.annotated_type

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Scope

object AnnotationType {
    fun toString(annotationType: OtpErlangList): String {
        val annotationString = annotationString(annotationType)
        val typeString = typeString(annotationType)

        return "$annotationString :: $typeString"
    }

    private fun annotationString(annotationType: OtpErlangList): String =
            toAnnotation(annotationType)
                    ?.let { annotationToString(it) }
                    ?: AbstractCode.missing("annotation", "annotation type annotation", annotationType)

    private fun annotationToString(annotation: OtpErlangObject): String = AbstractCode.toString(annotation)

    internal fun toAnnotation(annotationType: OtpErlangList): OtpErlangObject? = annotationType.elementAt(0)
    private fun toType(annotationType: OtpErlangList): OtpErlangObject? = annotationType.elementAt(1)

    private fun typeString(annotationType: OtpErlangList): String =
            toType(annotationType)
                    ?.let { typeToString(it) }
                    ?: AbstractCode.missing("type", "annotation type type", annotationType)

    private fun typeToString(type: OtpErlangObject): String = AbstractCode.toString(type)
}
