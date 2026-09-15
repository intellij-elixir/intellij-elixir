package org.elixir_lang.language_level

import org.elixir_lang.language_level.ElixirLanguageLevel.*

/**
 * What Elixir does in a window of releases that the plugin has to follow when parsing, quoting or checking code.
 *
 * Code outside this enum and [ElixirLanguageLevel] asks [isSufficient], or
 * [ElixirLanguageLevelResolver.isAvailable] for an element, rather than comparing levels, so a level inserted between
 * two others or a behaviour a later release removes changes one entry. A window is half-open: an entry applies from
 * [since] up to, but not including, [removedIn]. A behaviour that is not one window is two entries.
 */
enum class ElixirLanguageFeature(val since: ElixirLanguageLevel, val removedIn: ElixirLanguageLevel? = null) {
    /**
     * A `\` ending a line survives extraction into the buffer: a sigil then keeps the backslash and newline, since sigil
     * parts skip `unescape_tokens`, while a plain string or heredoc unescapes them away and is left with an empty
     * segment. Before it, `\<newline>` in an **interpolating** sigil was consumed.
     *
     * `elixir-lang/elixir@8c29984ed` moved `\<newline>` out of `elixir_interpolation:extract/8` into `unescape_chars`,
     * which sigil parts never reach; its deleted clauses were guarded on `Interpol = true`, so `~S` is unaffected.
     * First released in v1.12.0.
     */
    ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER(V1_12),

    /**
     * A `\` ending a line in a non-interpolating sigil line advances the line of what follows. 1.11's `extract/8` took
     * the two characters in its `[$\\, Char | Rest]` clause, which counts columns and no line, so in `~S(a\` +
     * newline + `b) in x` everything after the sigil is one line lower.
     *
     * `elixir-lang/elixir@51d90f193`, first released in v1.12.0.
     */
    ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE(V1_12),

    /**
     * A heredoc whose first content is `#{...}` quotes with a leading `""`, since the tokenizer strips a heredoc's
     * artificial leading newline after extraction rather than before.
     *
     * `elixir-lang/elixir@51d90f193`, first released in v1.12.0.
     */
    EMPTY_LEADING_HEREDOC_SEGMENT(V1_12),

    /**
     * `//` is the step operator, `first..last//step`. Before it `//` is two divisions, and an operator before `/` lexes
     * as an identifier, so `x..y//1` is `x..y((/)/1)` and `[..//: 1]` is `[..(/([/: 1]))]`.
     *
     * `elixir-lang/elixir@bc187f37d` (#10810), first released in v1.12.0.
     */
    STEP_OPERATOR(V1_12),

    /**
     * A decimal number ends before letters that follow it, as a based number does on every release, so `1and 2` is
     * `1 and 2`.
     *
     * Removed by `elixir-lang/elixir@6b2cc2332` ("Raise clearer error message on number followed by identifiers"),
     * first released in v1.12.0.
     */
    DECIMAL_NUMBER_ENDS_BEFORE_WORD(V1_11, removedIn = V1_12),

    /**
     * A remote call split by a newline after its `.` carries its name's line rather than the dot's, so `:erlang.` +
     * newline + `get(1)` is a call on line 2; the `.` node keeps the dot's line on every release.
     *
     * `elixir-lang/elixir@376ff1e51` ("Add more token metadata to aliases and remote calls", #11038), whose `build_dot`
     * carries the identifier's location, first released in v1.13.0.
     */
    REMOTE_CALL_ON_NAME_LINE(V1_13),

    /**
     * `\"""` inside a sigil heredoc quotes as `"""`, where earlier releases keep the backslash. Not conditioned on the
     * interpolation flag, so `~s` and `~S` alike; a plain heredoc reaches the same text through `unescape_tokens`, and a
     * sigil line's terminator was already unescaped before.
     *
     * `elixir-lang/elixir@ffd891a34` added the `[$\\, Last, Last, Last | Rest]` clause to
     * `elixir_interpolation:extract/8`, first released in v1.13.0.
     */
    UNESCAPED_SIGIL_HEREDOC_TERMINATOR(V1_13),

    /**
     * An identifier token - a variable, call, remote name, unquoted atom or keyword key - is normalised to NFC, with
     * MICRO SIGN (U+00B5) as GREEK SMALL LETTER MU (U+03BC), but a quoted atom or name is not. Before it, an identifier
     * that is not NFC is rejected.
     *
     * `elixir-lang/elixir@e7001455d` ("nfc and additional normalizations for identifiers", #11859), first released in
     * v1.14.0.
     */
    NORMALIZED_IDENTIFIERS(V1_14),

    /**
     * `from_brackets: true` in the `Access.get/2` metadata of bracket access on an expression, `[1, 2][0]`, Elixir's
     * `bracket_expr -> access_expr bracket_arg`. The other four bracket forms follow in
     * [FROM_BRACKETS_ON_EVERY_BRACKET_FORM].
     *
     * `elixir-lang/elixir@aa8e6d3fe` ("Add error message when piping into an expression ending in bracket-based
     * access", #12359) introduced `meta_with_from_brackets`, first released in v1.15.0.
     */
    FROM_BRACKETS_ON_BRACKETED_EXPRESSION(V1_15),

    /**
     * `&` must be immediately followed by its digit for the two to be one capture argument. `&1` always is, and `& 1`
     * was too, but now it is `&` applied to `1`, which binds the rest of the expression: `& & 1 + & 2` is
     * `&((&1) + (&2))` up to 1.14.5 and `&(&(1 + &2))` from 1.15.0. Read by the parser, since the difference is in how
     * the tokens bind.
     *
     * `elixir_parser.yrl`'s `access_expr -> capture_op_eol int` became `access_expr -> capture_int int`, and
     * `elixir_tokenizer.erl` emits `capture_int` only for an adjacent digit. `elixir-lang/elixir@9fb3cf603` ("Fix
     * ambiguity in &INT with brackets"), first released in v1.15.0.
     */
    ADJACENT_CAPTURE_ARGUMENT(V1_15),

    /**
     * A solitary `not` or `!` gets a `__block__` wrapper in every block position - a stab body, a file, an
     * interpolation - so `( -> ! one )` and `a not in b` are wrapped. From 1.15.0 only a parenthesised single unary
     * expression is wrapped, with empty metadata rather than the parentheses' own. A solitary `unquote_splicing` is
     * wrapped on every release.
     *
     * Removed by `elixir-lang/elixir@318681950` ("Apply rearrange ops only inside parens", #12296), first released in
     * v1.15.0.
     */
    SOLITARY_UNARY_WRAPPED_IN_EVERY_BLOCK(V1_11, removedIn = V1_15),

    /**
     * `from_interpolation: true` in the metadata of the `Kernel.to_string/1` call that `"a#{b}c"` quotes to.
     *
     * `elixir-lang/elixir@5225b33ba` ("Add interpolation token metadata"), first released in v1.16.0.
     */
    FROM_INTERPOLATION(V1_16_0),

    /**
     * `from_brackets: true` on the remaining four bracket productions: `bracket_expr -> dot_bracket_identifier`
     * (`foo[:a]` and `Foo.bar[:a]`) and both `bracket_at_expr` forms (`@foo[:a]` and `@1[:a]`).
     *
     * `elixir-lang/elixir@d8cc841ab` ("Include from_brackets metadata in all cases", #13317), first released in v1.16.2;
     * the same change reached master as `elixir-lang/elixir@eb1499ac2`, released in v1.17.0.
     */
    FROM_BRACKETS_ON_EVERY_BRACKET_FORM(V1_16_2),

    /**
     * `...` quotes as the nullary call `{:..., meta, []}` rather than the variable `{:..., meta, nil}`.
     * `elixir_parser.yrl` gained `sub_matched_expr -> ellipsis_op : build_nullary_op('$1')` in v1.17.0; v1.16.3 has no
     * `ellipsis_op` production.
     */
    ELLIPSIS_NULLARY_CALL(V1_17),

    /**
     * `one +(two)` is the call `one(+two)` rather than the operation `one + two`: a container, `%` or the opposite sign
     * after a spaced dual operator. `one +two` is a call on every release, and `one ++two` and `one +/two` are
     * operations on every release, being the exclusions 1.17.0 kept.
     *
     * `elixir_tokenizer.erl`'s `handle_space_sensitive_tokens` refused the `op_identifier` conversion when the character
     * after the sign was any of `( [ < { % + - / > :`. `elixir-lang/elixir@b8f069d08` ("Fix parsing of ambiguous
     * operators followed by containers") shrank that guard to `NotMarker =/= Sign, NotMarker =/= $/, NotMarker =/= $>`,
     * first released in v1.17.0.
     */
    AMBIGUOUS_DUAL_OPERATOR_CALL(V1_17),

    /**
     * A plain pair of parentheses around an expression that already quotes to a `__block__` appends its own `line` to
     * that block's metadata. `build_paren_stab`'s non-`rearrange_uop` clause ran its body through `build_stab/1` and,
     * whenever that returned a `__block__` (from a solitary `not`/`!` already rearranged by an inner paren,
     * `unquote_splicing`, or several expressions), appended this layer's `line`: `Meta ++
     * meta_from_token_with_closing(...)`. Each further layer of plain parentheses appended another entry, so
     * `&(((&1 not in ?0..?9)))` carries `[line: N, line: N]` on that block.
     *
     * From 1.17.0 `build_paren_stab` calls `build_block/2` directly, whose single-expression clause
     * `build_block([Expr], _Meta) -> Expr` discards the metadata, so only the innermost `__block__`'s own metadata
     * survives. Verified against `elixir_parser.yrl` at v1.14.5, v1.16.3 and v1.17.3, and by quoting
     * `&(((&1 not in ?0..?9)))` with 1.16.3 and 1.17.3.
     */
    ENCLOSING_PARENS_MERGE_BLOCK_METADATA(V1_11, removedIn = V1_17),

    /**
     * The name of a quoted remote call is unescaped, so `foo."bar\nbaz"()` calls `:"bar\nbaz"` where earlier releases
     * keep the backslash; an invalid escape there, as in `a.'\xg'`, raises `MatchError` on 1.18 and is an error from
     * 1.19 (`elixir-lang/elixir@41151190e`, #14587).
     *
     * `elixir-lang/elixir@e54b87c18` ("Fix formatter adding extra escapes to remote call functions", #13960) added
     * `{ok, [UnescapedPart]} = unescape_tokens(...)` to `elixir_tokenizer:handle_dot`, first released in v1.18.0.
     */
    UNESCAPED_QUOTED_REMOTE_CALL_NAME(V1_18),

    /**
     * A character literal that is a newline, `?` + newline or `?\` + newline, advances the line of what follows, where
     * earlier releases counted only columns.
     *
     * `elixir-lang/elixir@6fbc6e08a` ("Advance line when processing ? followed by <LF> and \<LF>"), first released in
     * v1.19.0.
     */
    NEWLINE_COUNTED_IN_CHARACTER(V1_19),

    /**
     * The `in` of `not in` carries its own location rather than the location of `not`; the two differ when a line
     * continuation separates them.
     *
     * `elixir-lang/elixir@8ac8230e1` ("Properly handle column for 'in' in 'not in' operator") and
     * `elixir-lang/elixir@a2baac915`, first released in v1.19.0.
     */
    IN_OF_NOT_IN_ON_ITS_OWN_LINE(V1_19),

    /**
     * A `do:` block's `__block__` carries the line of its own `do` token (`elixir-lang/elixir@90e1826c7`), and a 0-byte
     * file's implicit top-level block carries `line: 1` (`elixir-lang/elixir@7da1b76b6`), where both carried none. Only
     * `line` is added, not `column`: the rest of each commit is gated behind `?columns()`/`?token_metadata()`, which
     * neither this plugin nor its reference quoter enables. First released in v1.20.0-rc.0.
     */
    LINE_METADATA_ON_BLOCK(V1_20),

    /**
     * A `\` + newline next to a spaced `+` or `-` after an identifier counts as space, so `f -\` + newline + `var` is a
     * subtraction and `f \` + newline + `-var` the call `f(-var)`; before, both read the other way round. Read by the
     * parser.
     *
     * `elixir-lang/elixir@78fb31201` ("Consistently treat \ followed by newlines as horizontal space"), first released
     * in v1.20.0.
     */
    ESCAPED_NEWLINE_AS_SPACE(V1_20);

    fun isSufficient(languageLevel: ElixirLanguageLevel): Boolean =
        languageLevel >= since && (removedIn == null || languageLevel < removedIn)
}
