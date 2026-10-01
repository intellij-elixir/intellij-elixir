package org.elixir_lang.expander

/**
 * The message Elixir gives for each error [Expansion.Error] can name, as one pattern matching every supported
 * release's wording.
 */
object ErrorKinds {
    private val PATTERNS = mapOf(
        // elixir_expand
        "undefined_var" to """^undefined variable "[^"]+"( \(context [^)]+\))?$""",
        "undefined_var_pin" to
            """^undefined variable \^\S+\. No variable "[^"]+"( \(context [^)]+\))? has been defined before the current pattern$""",
        "invalid_arg_for_pin" to """^invalid argument for unary operator \^, expected an existing variable, got: \^""",
        "pin_outside_of_match" to
            """^(cannot use \^.* outside of match clauses|misplaced operator \^.*The pin operator \^ is supported only inside matches)""",
        "unbound_underscore" to """^invalid use of _\. ("_" represents a value to be ignored|_ can only be used inside patterns)""",
        "invalid_expr_in_guard" to """^invalid expression in guards?, .+ is not allowed in guards\. To learn more about guards""",
        "invalid_expr_in_bitsize" to
            """^.+ is not allowed (inside a |in )?bitstring size specifier\. The size specifier in matches works like guards\.""",
        "parallel_bitstring_match" to """^binary patterns cannot be matched in parallel using "=", excess pattern: """,
        "unhandled_arrow_op" to """^(unhandled|misplaced) operator ->""",
        "unhandled_type_op" to """^misplaced operator ::/2\n\nThe :: operator is typically used in bitstrings""",
        "unhandled_cons_op" to """^misplaced operator \|/2\n\nThe \| operator is typically used between brackets""",
        "__cursor__" to """^reserved special form __cursor__ cannot be expanded""",
        "invalid_call" to """^invalid call """,
        "missing_option" to """^missing :\S+ option in "\w+"$""",
        "invalid_args" to """^invalid arguments for "\w+"$""",
        "invalid_pattern_in_match" to """^invalid pattern in match, \S+ is not allowed in matches$""",
        "stacktrace_not_allowed" to
            """^__STACKTRACE__ is available only inside catch and rescue clauses of try expressions$""",
        "underscore_in_cond" to """^invalid use of _ inside "cond"\. If you want the last clause to always match""",
        "invalid_alias" to """^invalid alias: ".+"\. If you wanted to define an alias, an alias must expand to an atom""",
        "expected_compile_time_module" to
            """^invalid argument for (alias|require|import), expected a compile time atom or alias, got: """,
        "as_in_multi_alias_call" to """^:as option is not supported by multi-alias call$""",
        "unsupported_option" to """^unsupported option :\S+ given to (alias|require|import|quote)$""",
        "options_are_not_keyword" to
            """^invalid options for (alias|require|import|quote), expected a keyword list, got: """,
        "unquote_outside_quote" to """^unquote(_splicing)? called outside quote$""",
        "invalid_bind_quoted_for_quote" to
            """^invalid :bind_quoted for quote, expected a keyword list of variable names, got: """,
        "quote_in_pattern_with_unquote" to """^unquote is not allowed when quote is used inside a pattern or guard""",
        // elixir_quote's ArgumentErrors
        "quote_unquote_splicing" to
            """^unquote_splicing only works inside arguments and block contexts, wrap it in parens if you want it to work with one-liners$""",
        "quote_invalid_runtime_option" to """^invalid runtime value for option :(unquote|generated) in quote, got: """,
        // elixir_dispatch
        "ambiguous_call" to
            """^(function \S+ imported from both \S+ and \S+, call is ambiguous|conflicting \S+ import from modules \S+ and \S+)""",
        // elixir_aliases
        "invalid_alias_module" to """^alias cannot be inferred automatically for module: .+, please use the :as option""",
        "invalid_alias_for_as" to """^invalid value for option :as, expected (an alias, got: |a simple alias, got nested alias: )""",
        "unloaded_module" to """^module \S+ is not loaded and could not be found$""",
        "scheduled_module" to """^module \S+ is not loaded but was defined\.""",
        "circular_module" to """^you are trying to use/import/require the module \S+ which is currently being defined""",
        // elixir_import
        "invalid_option" to
            """^invalid :(only|except) option for import, expected (a keyword list with integer values$|value to be )""",
        "only_and_except_given" to """^:only and :except can only be given together to import when :only is """,
        "duplicated_import" to """^invalid :(only|except) option for import, \S+/\d+ is duplicated$""",
        "invalid_import" to """^cannot import \S+ because it is undefined or private$""",
        "special_form_conflict" to """^cannot import \S+ because it conflicts with Elixir special forms""",
        "no_macros" to """^could not load macros from module \S+$""",
        // elixir_clauses
        "recursive" to """^(recursive|cyclic) variable definition in patterns:\n\n""",
        "bad_or_missing_clauses" to
            """^(expected -> clauses for :\w+ in "\w+"|invalid "\w+" block in "\w+", it expects "pattern -> expr" clauses)$""",
        "duplicated_clauses" to """^duplicated? (:\w+|"\w+") clauses given for "\w+"$""",
        "unexpected_option" to """^unexpected option :\w+ in "\w+"$""",
        "wrong_number_of_args_for_clause" to
            """^expected (one argument|one or two args) for (:\w+|"\w+") clauses \(->\) in "\w+"$""",
        "multiple_after_clauses_in_receive" to """^expected a single -> clause for :after in "receive"$""",
        "invalid_rescue_clause" to
            """^invalid "rescue" clause\. The clause should match on an alias, a variable or be in the "var in \[alias\]" format""",
        // elixir_fn
        "defaults_in_args" to """^anonymous functions cannot have optional arguments$""",
        "clauses_with_different_arities" to """^cannot mix clauses with different arities in anonymous functions$""",
        // elixir_map
        "repeated_key" to """^key .+ will be overridden in map$""",
        "update_syntax_in_wrong_context" to """^cannot use map/struct update syntax in (match|guard), got: """,
        "not_kv_pair" to """^expected key-value pairs in a map, got: """,
        "invalid_variable_in_map_key_match" to
            """^cannot use variable \S+ as map key inside a pattern\. Map keys in patterns can only be literals""",
        "invalid_pin_in_map_key_match" to
            """^cannot use pin operator \^\S+ inside a data structure as a map key in a pattern\.""",
        // elixir_bitstring
        "unsized_binary" to
            """^a binary field without size is only allowed at the end of a binary pattern, at the right side of binary concatenation and (and )?never allowed in binary generators""",
        "unaligned_binary" to """^expected .+ to be a binary but its number of bits is not divisible by 8$""",
        "unknown_match" to """^a bitstring only accepts binaries, numbers, and variables inside a match, got: """,
        "nested_match" to """^cannot pattern match inside a bitstring that is already in match, got: """,
        "bittype_mismatch" to """^conflicting \w+ specification for bit field: ".*" and ".*"$""",
        "undefined_bittype" to """^unknown bitstring specifier: """,
        "bad_unit_argument" to """^unit in bitstring expects an integer as argument, got: """,
        "bittype_literal_bitstring" to """^literal <<>> in bitstring supports only type specifiers""",
        "bittype_literal_string" to """^literal string in bitstring supports only endianness and type specifiers""",
        "bittype_utf" to """^size and unit are not supported on utf types$""",
        "bittype_signed" to """^signed and unsigned specifiers are supported only on integer and float types$""",
        "bittype_float_size" to """^float requires size\*unit to be (16, 32, or 64|32 or 64) \(default\), got: """,
        "bittype_unit" to """^integer and float types require a size specifier if the unit specifier is given$""",
        "invalid_literal" to """^invalid literal .+ in <<>>$""",
        "bad_size_argument" to """^size in bitstring expects an integer or a variable as argument, got: """,
        "undefined_var_in_spec" to """^undefined variable ".+" in bitstring segment\. If the size of the binary is a variable""",
    ).mapValues { (_, pattern) -> Regex(pattern, RegexOption.DOT_MATCHES_ALL) }

    /** The kinds Elixir raises at expansion as an exception other than `CompileError`, whose message has no line. */
    private val WITHOUT_LINE = setOf("quote_unquote_splicing", "quote_invalid_runtime_option")

    fun pattern(kind: String): Regex = PATTERNS[kind] ?: throw AssertionError("no message pattern for error $kind")

    fun hasLine(kind: String): Boolean = kind !in WITHOUT_LINE
}
