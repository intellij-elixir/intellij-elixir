package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.INVALID_MODULE_NAME_IS_ARGUMENT_ERROR
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/** What [Expander.expand] gives a node. */
sealed class Expansion {
    /**
     * The state and env Elixir leaves after the node.
     *
     * @property value the term the node expands to
     * @property ast the node `elixir_expand:expand/3` returns, where the run asked for it
     * @property variable the variable the node expands to, where [value] is [VARIABLE_NODE]
     */
    data class Expanded(
        val state: ExState,
        val env: Env,
        val value: Term,
        val ast: ExpandedAst,
        val variable: ElixirAst? = null,
    ) : Expansion()

    /** An expansion that goes no further: Elixir raises there, or what it does there isn't ported. */
    sealed class Stop : Expansion() {
        /**
         * The env the node stopped at so far would return under Elixir's threading: stamped by the block that holds it,
         * then overwritten by each construct it passes, or `null` before either. In the body because a stop's identity
         * is compared, so a stamp can't be a copy.
         */
        var envAtStop: Env? = null
    }

    /**
     * Elixir's expander raises at [at], the node whose metadata it reports.
     *
     * @property kind the error's reason as atom text, such as `undefined_var`, or `null` for a raise the evaluator
     *   computed whose exception isn't known
     */
    data class Error(val kind: String?, val at: ElixirAst) : Stop() {
        companion object {
            /**
             * The exception Elixir at [level] raises for an error of [kind]: an exception's module is itself; a bare
             * kind is a `CompileError`, but for an invalid module name from [INVALID_MODULE_NAME_IS_ARGUMENT_ERROR],
             * an `ArgumentError`, and the kinds Elixir raises as another exception, which isn't known here, as is a
             * `null` kind's.
             */
            fun exception(kind: String?, level: ElixirLanguageLevel): String? =
                when {
                    kind == null || kind.startsWith("Elixir.") -> kind
                    kind == "invalid_module_name" && INVALID_MODULE_NAME_IS_ARGUMENT_ERROR.isSufficient(level) ->
                        "Elixir.ArgumentError"
                    kind in OTHER_EXCEPTIONS -> null
                    else -> "Elixir.CompileError"
                }

            /** The bare kinds Elixir raises at expansion as an exception other than `CompileError`. */
            private val OTHER_EXCEPTIONS = setOf(
                "quote_unquote_splicing",
                "quote_invalid_runtime_option",
                "var_bang_not_a_variable",
                "var_bang_context_not_atom",
                "alias_bang_function_clause",
                "struct_unknown_key",
                "struct_missing_enforced_keys",
                "struct_twice",
                "struct_fields_not_list",
                "struct_reserved_key",
                "struct_invalid_default",
                "struct_field_not_atom",
                "enforce_key_not_atom",
                "enforce_keys_not_defined",
                "derive_not_available",
                "derive_not_a_protocol",
                "derive_not_an_implementation",
                "delegate_no_to",
                "delegate_self",
                "delegate_guard",
                "delegate_call",
                "delegate_arg",
                "delegate_options",
                "protocol_def_no_args",
                "protocol_def_bad",
                "impl_outside_no_for",
                "impl_no_do",
                "impl_bad_opt",
                "impl_options",
                "impl_not_available",
                "impl_not_a_protocol",
                "invalid_defguard",
                "defguard_two_whens",
                "overridable_not_defined",
                "overridable_bad_element",
                "overridable_undefined_behaviour",
                "overridable_not_a_behaviour",
                "overridable_missing_behaviour",
                "definer_outside_module",
                "definer_inside_function",
                "definer_in_match",
                "definer_in_guard",
                "reserved_word",
                "attribute_outside_module",
                "attribute_in_match_or_guard",
                "attribute_set_in_function",
                "behavior_attribute",
                "attribute_arity",
                "invalid_attribute_value",
                "invalid_if_keys",
                "kernel_invalid_match",
                "kernel_invalid_guard",
                "use_invalid_arguments",
                "sigil_function_clause",
                "sigil_w_modifier",
                "regex_invalid_option",
                "hex_escape_invalid",
                "hex_escape_missing",
                "unicode_escape_invalid",
                "unicode_escape_missing",
                "unicode_code_point_reserved",
                "iso_invalid_format",
                "iso_invalid_date",
                "iso_invalid_time",
                "iso_missing_offset",
                "iso_non_utc_offset",
                "iso_date_out_of_range",
                "pipe_bad_target",
                "pipe_special_form",
                "pipe_fn",
                "pipe_unary",
                "pipe_from_brackets",
                "pipe_operator_arity",
                "in_invalid_guard_argument",
                "concat_not_binary",
                "concat_unknown_size",
                "destructure_function_clause",
                "range_not_integers",
                "range_zero_step",
            )
        }
    }

    /** [at] reaches a clause, or a branch of one, that isn't ported. */
    data class Unported(val at: ElixirAst) : Stop()

    /** [at] calls a macro, as [dispatch] finds it, whose expansion isn't modelled. */
    data class Opaque(val at: ElixirAst, val dispatch: Dispatch) : Expansion()
}

/** The node `elixir_expand:expand/3` returns for a node, built by the clause that expands it. */
sealed class ExpandedAst {
    class Built(val node: ElixirAst) : ExpandedAst()

    /** The run didn't ask for the node. */
    data object NotAsked : ExpandedAst()
}
