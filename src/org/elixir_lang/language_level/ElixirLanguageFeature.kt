package org.elixir_lang.language_level

import com.intellij.util.text.SemVer
import org.elixir_lang.sdk.erlang.Release

/**
 * What Elixir does in a window of releases that the plugin has to follow when parsing, quoting, checking or expanding
 * code.
 *
 * Code outside this enum and [ElixirLanguageLevel] asks [isSufficient], or [ElixirLanguageLevelResolver.isAvailable]
 * for an element, rather than comparing versions. A window is half-open: an entry applies from [sinceElixir] up to, but
 * not including, [removedInElixir], and from [sinceOtp] on the Erlang/OTP running Elixir. Each Elixir boundary is the
 * first Elixir tag that shipped the change, pre-releases included, since a pre-release sorts before its release. A
 * behaviour that is not one window is two entries.
 */
enum class ElixirLanguageFeature(
    sinceElixir: String? = null,
    removedInElixir: String? = null,
    sinceOtp: String? = null,
) {
    /**
     * A `\` ending a line survives extraction into the buffer: a sigil then keeps the backslash and newline, since
     * sigil parts skip `unescape_tokens`, while a plain string or heredoc unescapes them away and is left with an empty
     * segment. Before it, `\<newline>` in an interpolating sigil or a quoted remote call name was consumed; `~S` is
     * unaffected.
     *
     * `elixir-lang/elixir@8c29984ed`, first released in v1.12.0-rc.0.
     */
    ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER(sinceElixir = "1.12.0-rc.0"),

    /**
     * A `\` ending a line in a non-interpolating sigil line advances the line of what follows, so in `~S(a\` +
     * newline + `b) in x` everything after the sigil is one line lower.
     *
     * `elixir-lang/elixir@51d90f193`, first released in v1.12.0-rc.0.
     */
    ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE(sinceElixir = "1.12.0-rc.0"),

    /**
     * A heredoc whose first content is `#{...}` quotes with a leading `""`, since the tokenizer strips a heredoc's
     * artificial leading newline after extraction rather than before.
     *
     * `elixir-lang/elixir@51d90f193`, first released in v1.12.0-rc.0.
     */
    EMPTY_LEADING_HEREDOC_SEGMENT(sinceElixir = "1.12.0-rc.0"),

    /**
     * `//` is the step operator, `first..last//step`. Before it `//` is two divisions, and an operator before `/` lexes
     * as an identifier, so `x..y//1` is `x..y((/)/1)` and `[..//: 1]` is `[..(/([/: 1]))]`.
     *
     * `elixir-lang/elixir@bc187f37d` (#10810), first released in v1.12.0-rc.0.
     */
    STEP_OPERATOR(sinceElixir = "1.12.0-rc.0"),

    /**
     * A decimal number ends before letters that follow it, as a based number does on every release, so `1and 2` is
     * `1 and 2`.
     *
     * Removed by `elixir-lang/elixir@6b2cc2332` ("Raise clearer error message on number followed by identifiers"),
     * first released in v1.12.0-rc.0.
     */
    DECIMAL_NUMBER_ENDS_BEFORE_WORD(removedInElixir = "1.12.0-rc.0"),

    /**
     * A remote call carries its name's location rather than the dot's, so `:erlang.` + newline + `get(1)` is a call on
     * line 2, and with `columns: true` a call's column is its name's; the `.` node keeps the dot's location on every
     * release.
     *
     * `elixir-lang/elixir@376ff1e51` ("Add more token metadata to aliases and remote calls", #11038), first released in
     * v1.13.0-rc.0.
     */
    REMOTE_CALL_ON_NAME_LINE(sinceElixir = "1.13.0-rc.0"),

    /**
     * With `token_metadata: true`, an alias such as `Foo.Bar` carries `last:`, the location of its last segment.
     *
     * `elixir-lang/elixir@376ff1e51` ("Add more token metadata to aliases and remote calls", #11038), first released in
     * v1.13.0-rc.0.
     */
    LAST_ON_ALIAS(sinceElixir = "1.13.0-rc.0"),

    /**
     * `\"""` inside a sigil heredoc quotes as `"""`, where earlier releases keep the backslash, in `~s` and `~S` alike.
     * A plain heredoc reaches the same text through `unescape_tokens`, and a sigil line's terminator was already
     * unescaped before.
     *
     * `elixir-lang/elixir@ffd891a34`, first released in v1.13.0-rc.1.
     */
    UNESCAPED_SIGIL_HEREDOC_TERMINATOR(sinceElixir = "1.13.0-rc.1"),

    /**
     * An identifier token - a variable, call, remote name, unquoted atom or keyword key - is normalised to NFC, with
     * MICRO SIGN (U+00B5) as GREEK SMALL LETTER MU (U+03BC), but a quoted atom or name is not. Before it, an identifier
     * that is not NFC is rejected.
     *
     * `elixir-lang/elixir@e7001455d` ("nfc and additional normalizations for identifiers", #11859), first released in
     * v1.14.0-rc.0.
     */
    NORMALIZED_IDENTIFIERS(sinceElixir = "1.14.0-rc.0"),

    /**
     * `from_brackets: true` in the `Access.get/2` metadata of bracket access on an expression, `[1, 2][0]`, Elixir's
     * `bracket_expr -> access_expr bracket_arg`. The other four bracket forms follow in
     * [FROM_BRACKETS_ON_EVERY_BRACKET_FORM].
     *
     * `elixir-lang/elixir@aa8e6d3fe` ("Add error message when piping into an expression ending in bracket-based
     * access", #12359), first released in v1.15.0-rc.0.
     */
    FROM_BRACKETS_ON_BRACKETED_EXPRESSION(sinceElixir = "1.15.0-rc.0"),

    /**
     * `&` must be immediately followed by its digit for the two to be one capture argument. `&1` always is, and `& 1`
     * was too, but now it is `&` applied to `1`, which binds the rest of the expression: `& & 1 + & 2` is
     * `&((&1) + (&2))` up to 1.14.5 and `&(&(1 + &2))` from 1.15.0. Read by the parser, since the difference is in how
     * the tokens bind.
     *
     * `elixir-lang/elixir@9fb3cf603` ("Fix ambiguity in &INT with brackets"), first released in v1.15.0-rc.0.
     */
    ADJACENT_CAPTURE_ARGUMENT(sinceElixir = "1.15.0-rc.0"),

    /**
     * A solitary `not` or `!` gets a `__block__` wrapper in every block position - a stab body, a file, an
     * interpolation - so `( -> ! one )` and `a not in b` are wrapped. From 1.15.0 only a parenthesised single unary
     * expression is wrapped, with empty metadata rather than the parentheses' own. A solitary `unquote_splicing` is
     * wrapped on every release.
     *
     * Removed by `elixir-lang/elixir@318681950` ("Apply rearrange ops only inside parens", #12296), first released in
     * v1.15.0-rc.0.
     */
    SOLITARY_UNARY_WRAPPED_IN_EVERY_BLOCK(removedInElixir = "1.15.0-rc.0"),

    /**
     * `=` carries `newlines:` for the newlines after it, as every other binary operator does; before, only those
     * before it counted.
     *
     * `elixir-lang/elixir@7b73407af` ("Respect line breaks after ="), first released in v1.15.0-rc.0.
     */
    NEWLINES_AFTER_MATCH_OPERATOR(sinceElixir = "1.15.0-rc.0"),

    /**
     * `from_interpolation: true` in the metadata of the `Kernel.to_string/1` call that `"a#{b}c"` quotes to.
     *
     * `elixir-lang/elixir@5225b33ba` ("Add interpolation token metadata"), first released in v1.16.0-rc.0.
     */
    FROM_INTERPOLATION(sinceElixir = "1.16.0-rc.0"),

    /**
     * `from_brackets: true` on the remaining four bracket productions: `bracket_expr -> dot_bracket_identifier`
     * (`foo[:a]` and `Foo.bar[:a]`) and both `bracket_at_expr` forms (`@foo[:a]` and `@1[:a]`).
     *
     * `elixir-lang/elixir@d8cc841ab` ("Include from_brackets metadata in all cases", #13317), first released in
     * v1.16.2.
     */
    FROM_BRACKETS_ON_EVERY_BRACKET_FORM(sinceElixir = "1.16.2"),

    /**
     * `...` quotes as the nullary call `{:..., meta, []}` rather than the variable `{:..., meta, nil}`, and before an
     * operand as the unary operator `{:..., meta, [operand]}` rather than a call.
     *
     * `elixir-lang/elixir@d68c8d6cd` ("Unify handling of .. and ..."), first released in v1.17.0-rc.0.
     */
    ELLIPSIS_NULLARY_CALL(sinceElixir = "1.17.0-rc.0"),

    /**
     * `one +(two)` is the call `one(+two)` rather than the operation `one + two`: a container, `%` or the opposite sign
     * after a spaced dual operator. `one +two` is a call on every release, and `one ++two` and `one +/two` are
     * operations on every release.
     *
     * `elixir-lang/elixir@b8f069d08` ("Fix parsing of ambiguous operators followed by containers"), first released in
     * v1.17.0-rc.0.
     */
    AMBIGUOUS_DUAL_OPERATOR_CALL(sinceElixir = "1.17.0-rc.0"),

    /**
     * With `columns: true`, a map's column is its `%` rather than its `{`. A struct's inner `%{}` still takes its `{`.
     *
     * `elixir-lang/elixir@fd4e6b530` ("Fix column marker for maps") and `elixir-lang/elixir@ba579f141`, first released
     * in v1.17.0-rc.0.
     */
    MAP_COLUMN_AT_PERCENT(sinceElixir = "1.17.0-rc.0"),

    /**
     * A plain pair of parentheses around an expression that already quotes to a `__block__` - a solitary `not` or `!`
     * rearranged by an inner pair, `unquote_splicing`, or several expressions - appends its own `line` to that block's
     * metadata, once per layer, so `&(((&1 not in ?0..?9)))` carries `[line: N, line: N]` on that block. Since then
     * only the innermost `__block__`'s own metadata survives.
     *
     * Removed by `elixir-lang/elixir@80af632a7` ("Wrap (a -> b) into literals instead of plain lists"), first released
     * in v1.17.0-rc.0.
     */
    ENCLOSING_PARENS_MERGE_BLOCK_METADATA(removedInElixir = "1.17.0-rc.0"),

    /**
     * The name of a quoted remote call is unescaped, so `foo."bar\nbaz"()` calls `:"bar\nbaz"` where earlier releases
     * keep the backslash; an invalid escape there, as in `a.'\xg'`, raises `MatchError` on 1.18 and is an error from
     * 1.19 (`elixir-lang/elixir@41151190e`, #14587).
     *
     * `elixir-lang/elixir@e54b87c18` ("Fix formatter adding extra escapes to remote call functions", #13960), first
     * released in v1.18.0-rc.0.
     */
    UNESCAPED_QUOTED_REMOTE_CALL_NAME(sinceElixir = "1.18.0-rc.0"),

    /**
     * With `token_metadata: true`, the key of a `=>` pair carries `assoc:`, the location of the `=>`, when the key is a
     * node with metadata.
     *
     * `elixir-lang/elixir@5b221a554` ("Add AST metadata about assoc operator location", #13978), first released in
     * v1.18.0-rc.0.
     */
    ASSOC_ON_MAP_KEY(sinceElixir = "1.18.0-rc.0"),

    /**
     * A column inside a heredoc's body counts its indentation, where 1.11 counted from the end of the indentation it
     * had already removed.
     *
     * `elixir-lang/elixir@51d90f193` ("Allow heredoc inside heredoc interpolation"), first released in v1.12.0-rc.0.
     */
    HEREDOC_INDENTATION_IN_COLUMNS(sinceElixir = "1.12.0-rc.0"),

    /**
     * Inside quoted text - a string, charlist, heredoc, sigil, quoted atom or quoted keyword key - a column counts
     * grapheme clusters as the running OTP's `unicode_util:gc/1` splits them, not code points; an escape is its `\` and
     * one cluster, except an escaped terminator, or in interpolating text an escaped `#{`, which is one column per
     * character. Everywhere else a column counts code points on every release.
     *
     * `elixir-lang/elixir@f429a27e2` ("Fix nfd cluster columns", #11231) and `elixir-lang/elixir@8f96b9a11`, first
     * released in v1.13.0-rc.0.
     */
    CLUSTER_COLUMNS_IN_QUOTED_TEXT(sinceElixir = "1.13.0-rc.0"),

    /**
     * With `token_metadata: true`, an interpolated quoted atom's `:erlang.binary_to_atom` call carries `delimiter:`.
     *
     * `elixir-lang/elixir@f10c90c30` ("Do not assume that literals in blocks have been normalized"), first released in
     * v1.13.0-rc.0.
     */
    DELIMITER_ON_QUOTED_ATOM(sinceElixir = "1.13.0-rc.0"),

    /**
     * With `token_metadata: true`, an interpolated string or charlist heredoc carries `indentation:`, as a sigil
     * heredoc always has.
     *
     * `elixir-lang/elixir@d1223e11f` (#11128), first released in v1.13.0-rc.0.
     */
    INDENTATION_ON_HEREDOC(sinceElixir = "1.13.0-rc.0"),

    /**
     * An escaped interpolation, `\#{`, advances the column by its three characters, where earlier releases advanced it
     * by one.
     *
     * `elixir-lang/elixir@f026cb375`, a backport of `elixir-lang/elixir@2d65a4613`, first released in v1.14.4.
     */
    ESCAPED_INTERPOLATION_COLUMNS(sinceElixir = "1.14.4"),

    /**
     * With `token_metadata: true`, the last expression of a block - the file, a stab body, parentheses, an
     * interpolation - carries `end_of_expression:` when an end of expression follows it, as every earlier one does.
     *
     * `elixir-lang/elixir@514615d03` ("Apply end of expression more consistently", #13355), first released in
     * v1.17.0-rc.0.
     */
    END_OF_EXPRESSION_ON_LAST_EXPRESSION(sinceElixir = "1.17.0-rc.0"),

    /**
     * With `token_metadata: true`, an end of expression after a `->` clause's first body expression goes on the `->`
     * itself when that expression has no metadata, as after `2` in `fn 1 -> 2; 3 -> 4 end`, and one after a clause with
     * no body does too, as after the first `->` in `fn 1 -> ; 2 -> 3 end`.
     *
     * Removed by `elixir-lang/elixir@d244eaf8b` ("Remove end_of_expression from ->", #13318), first released in
     * v1.17.0-rc.0.
     */
    END_OF_EXPRESSION_ON_STAB_OPERATOR(removedInElixir = "1.17.0-rc.0"),

    /**
     * With `token_metadata: true`, parentheses around one expression that has metadata add `parens:` to it, empty
     * parentheses add it to their empty `__block__`, and a `->` head of no, several or keyword arguments in
     * parentheses adds it to the `->`.
     *
     * `elixir-lang/elixir@bd7d428ca` (#13940), `elixir-lang/elixir@7d421b197` (#13973) and
     * `elixir-lang/elixir@3b01b2a63` (#13996), first released in v1.18.0-rc.0.
     */
    PARENS_ON_PARENTHESIZED_EXPRESSION(sinceElixir = "1.18.0-rc.0"),

    /**
     * An interpolated charlist atom, `:'a#{b}'`, carries `delimiter: "'"`, where earlier releases reported `"`.
     *
     * `elixir-lang/elixir@d0f7c0374` ("Fix delimiter metadata for single quote atoms and remote calls", #13966), first
     * released in v1.18.0-rc.0.
     */
    DELIMITER_OF_SINGLE_QUOTED_ATOM(sinceElixir = "1.18.0-rc.0"),

    /**
     * With `token_metadata: true`, an interpolated quoted keyword key, `"a#{b}": c`, carries `delimiter:`.
     *
     * `elixir-lang/elixir@d0f7c0374` (#13966), first released in v1.18.0-rc.0.
     */
    DELIMITER_ON_QUOTED_KEYWORD_KEY(sinceElixir = "1.18.0-rc.0"),

    /**
     * `parens:` lists `closing:` before the opening's `line` and `column`, as a parenthesised block's own metadata
     * always has, where it listed it after them.
     *
     * `elixir-lang/elixir@90e1826c7` ("Include meta information in blocks from do-end blocks"), first released in
     * v1.20.0-rc.0.
     */
    CLOSING_FIRST_IN_PARENS(sinceElixir = "1.20.0-rc.0"),

    /**
     * A character literal that is a newline, `?` + newline or `?\` + newline, advances the line of what follows, where
     * earlier releases counted only columns.
     *
     * `elixir-lang/elixir@6fbc6e08a` ("Advance line when processing ? followed by <LF> and \<LF>"), first released in
     * v1.19.0-rc.1.
     */
    NEWLINE_COUNTED_IN_CHARACTER(sinceElixir = "1.19.0-rc.1"),

    /**
     * The `in` of `not in` carries its own location rather than the location of `not`; the two differ when a line
     * continuation separates them.
     *
     * `elixir-lang/elixir@8ac8230e1` ("Properly handle column for 'in' in 'not in' operator") and
     * `elixir-lang/elixir@a2baac915`, first released in v1.19.0-rc.1.
     */
    IN_OF_NOT_IN_ON_ITS_OWN_LINE(sinceElixir = "1.19.0-rc.1"),

    /**
     * The `not` of `not in` carries `newlines:` for the newlines before `not` or after `in`, as a binary operator does.
     *
     * `elixir-lang/elixir@a2baac915` ("Address regressions on 'not in' operator"), first released in v1.19.0-rc.1.
     */
    NEWLINES_ON_NOT_IN(sinceElixir = "1.19.0-rc.1"),

    /**
     * With token metadata, the `not` of `not in` carries `operator: :"not in"`.
     *
     * Removed by `elixir-lang/elixir@5b3ae1b8c` ("Rewrite not (foo in bar) to foo not in bar"), first released in
     * v1.12.0-rc.0.
     */
    OPERATOR_ON_NOT_IN(removedInElixir = "1.12.0-rc.0"),

    /**
     * A `do`-`end` block's `__block__`, which carried no metadata, carries the position of its own `do` token, and an
     * empty file or interpolation's block is at line 1, column 1 rather than at its first end of expression, if any;
     * either carries `column` only with `columns: true`. With token metadata, a lone expression that has metadata
     * carries its `do`'s position as `parens:`. `elixir-lang/elixir@90e1826c7` and `elixir-lang/elixir@7da1b76b6`,
     * first released in v1.20.0-rc.0.
     */
    LINE_METADATA_ON_BLOCK(sinceElixir = "1.20.0-rc.0"),

    /**
     * With `token_metadata: true`, bracket access's `Access.get/2` call and its `.` carry `closing:`, and `newlines:`
     * for newlines straight after the `[`.
     *
     * `elixir-lang/elixir@480c19cb5` ("Respect keywords in access in code formatter"), first released in v1.12.0.
     */
    CLOSING_ON_BRACKET_ACCESS(sinceElixir = "1.12.0"),

    /**
     * With `token_metadata: true`, a remote call whose name is quoted, `Foo."a b"()`, carries `delimiter:`.
     *
     * `elixir-lang/elixir@d0f7c0374` ("Fix delimiter metadata for single quote atoms and remote calls", #13966), first
     * released in v1.18.0-rc.0.
     */
    DELIMITER_ON_QUOTED_REMOTE_CALL(sinceElixir = "1.18.0-rc.0"),

    /**
     * A call with a second set of parentheses, `foo(1)(2)`, keeps only the first call's `line` and `column` after its
     * own `newlines:` and `closing:`, where earlier releases appended all of the first call's metadata.
     *
     * `elixir-lang/elixir@3ee2ecabf` ("Remove duplicated metadata in nested call AST", #14122), first released in
     * v1.19.0-rc.0.
     */
    NESTED_PARENTHESES_DROP_INNER_METADATA(sinceElixir = "1.19.0-rc.0"),

    /**
     * With `token_metadata: true`, empty multiple aliases, `A.{}`, carry `closing:` and `newlines:`, as non-empty ones
     * always have.
     *
     * `elixir-lang/elixir@f56139aa2`, a cherry-pick of `elixir-lang/elixir@02968a46f` ("Add closing token metadata to
     * a.{}"), first released in v1.19.0-rc.1.
     */
    CLOSING_ON_EMPTY_MULTIPLE_ALIASES(sinceElixir = "1.19.0-rc.1"),

    /**
     * A `\` + newline next to a spaced `+` or `-` after an identifier counts as space, so `f -\` + newline + `var` is a
     * subtraction and `f \` + newline + `-var` the call `f(-var)`; before, both read the other way round. Likewise an
     * operator before `\` + newline + `/` is an identifier, as `...` is in `... \` + newline + `/0`.
     *
     * `elixir-lang/elixir@78fb31201` ("Consistently treat \ followed by newlines as horizontal space"), first released
     * in v1.20.0-rc.0.
     */
    ESCAPED_NEWLINE_AS_SPACE(sinceElixir = "1.20.0-rc.0"),

    /**
     * The `not` or `!` that `not a in b` and `!a in b` move outside the `in` keeps its own metadata, where it took the
     * `in`'s.
     *
     * `elixir-lang/elixir@e1ff7819b` ("Fix deprecation warning on !left in right"), first released in v1.20.0-rc.0.
     */
    REARRANGED_UNARY_KEEPS_ITS_METADATA(sinceElixir = "1.20.0-rc.0"),

    /**
     * A heredoc terminator after content on its line is content, where 1.11 rejects it there ("invalid location for
     * heredoc terminator") and scans a heredoc's lines for its terminator before reading its interpolations.
     *
     * `elixir-lang/elixir@51d90f193` ("Allow heredoc inside heredoc interpolation"), first released in v1.12.0-rc.0.
     */
    HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT(sinceElixir = "1.12.0-rc.0"),

    /**
     * A reserved word followed by `::` is still that word, where 1.11 reads it as a keyword key: `end::` closes its
     * block, and `not in::x` is `not in`.
     *
     * `elixir-lang/elixir@01f5196bf` ("Properly handle keywords followed by ::"), first released in v1.12.0-rc.0.
     */
    RESERVED_WORD_BEFORE_TYPE_OPERATOR(sinceElixir = "1.12.0-rc.0"),

    /**
     * `+:` or `-:` after a call name and a space is a keyword key, where 1.11 rejects it as it does an identifier.
     *
     * `elixir-lang/elixir@8df17a089`, first released in v1.12.0-rc.0.
     */
    SIGN_KEYWORD_KEY_AFTER_CALL(sinceElixir = "1.12.0-rc.0"),

    /**
     * `:..//` is an atom, where 1.11 rejects it before `'/'`.
     *
     * `elixir-lang/elixir@bc187f37d` (#10810), with [STEP_OPERATOR], first released in v1.12.0-rc.0.
     */
    STEP_ATOM(sinceElixir = "1.12.0-rc.0"),

    /**
     * A based number followed by a digit continues as a decimal number, where 1.11 reports the digit.
     *
     * `elixir-lang/elixir@6b2cc2332`, with the end of [DECIMAL_NUMBER_ENDS_BEFORE_WORD], first released in
     * v1.12.0-rc.0.
     */
    BASED_NUMBER_CONTINUES_INTO_DIGITS(sinceElixir = "1.12.0-rc.0"),

    /**
     * `**` is one token. Before it the tokenizer reads two `*`, so `x.**` is `x.*` followed by `*`, `:**` is rejected,
     * and an operator directly before `/` starts the `*`'s operand.
     *
     * `elixir-lang/elixir@af55ee589` (#11241), first released in v1.13.0-rc.0.
     */
    POWER_OPERATOR(sinceElixir = "1.13.0-rc.0"),

    /**
     * `.:` is a keyword key.
     *
     * `elixir-lang/elixir@1d56b11f0`, first released in v1.13.0-rc.0.
     */
    DOT_KEYWORD_KEY(sinceElixir = "1.13.0-rc.0"),

    /**
     * A map entry may be a call without parentheses, or `...` applied to an operand.
     *
     * `elixir-lang/elixir@4917b9681`, first released in v1.13.0-rc.0.
     */
    CALL_AND_ELLIPSIS_MAP_ENTRIES(sinceElixir = "1.13.0-rc.0"),

    /**
     * A unary operator directly before `/` is an operator reference without `&`.
     *
     * `elixir-lang/elixir@bbde3cb98`, first released in v1.13.0-rc.0.
     */
    UNARY_OPERATOR_REFERENCE(sinceElixir = "1.13.0-rc.0"),

    /**
     * Turning a quoted call name into an atom crashes with `ArgumentError` when a grapheme cluster in it has several
     * code points, as `list_to_atom` is handed a cluster.
     *
     * Appears with `elixir-lang/elixir@f429a27e2` (#11231), first released in v1.13.0-rc.0; fixed by
     * `elixir-lang/elixir@09c602d10`, first released in v1.18.0-rc.0.
     */
    GRAPHEME_CLUSTER_CRASH_IN_QUOTED_CALL_NAME(sinceElixir = "1.13.0-rc.0", removedInElixir = "1.18.0-rc.0"),

    /**
     * `..` without operands is the nullary range.
     *
     * `elixir-lang/elixir@6447f440d` (#11623), first released in v1.14.0-rc.0.
     */
    NULLARY_RANGE(sinceElixir = "1.14.0-rc.0"),

    /**
     * A sigil name may have several letters, where earlier releases reject the second.
     *
     * `elixir-lang/elixir@c402e8336` (#12448), first released in v1.15.0-rc.0.
     */
    MULTI_LETTER_SIGIL_NAMES(sinceElixir = "1.15.0-rc.0"),

    /**
     * A sigil name may hold digits after its first letter.
     *
     * `elixir-lang/elixir@496cb2c89` (#13448), first released in v1.17.0-rc.0.
     */
    DIGITS_IN_SIGIL_NAMES(sinceElixir = "1.17.0-rc.0"),

    /**
     * `import M, only: :sigils` brings in `M`'s sigils. Before it the option is invalid.
     *
     * `elixir-lang/elixir@480d64042` (#11284), first released in v1.13.0-rc.0.
     */
    IMPORT_ONLY_SIGILS(sinceElixir = "1.13.0-rc.0"),

    /**
     * `import M, only: :sigils` brings in the arity-2 `sigil_` functions and macros whose suffix is a sigil name, as
     * [MULTI_LETTER_SIGIL_NAMES] and [DIGITS_IN_SIGIL_NAMES] read one. Before it, `sigil_` and one letter of either case
     * at any arity.
     *
     * `elixir-lang/elixir@aef087966` (#12626), first released in v1.15.0-rc.2.
     */
    IMPORT_ONLY_SIGILS_READS_SIGIL_NAMES(sinceElixir = "1.15.0-rc.2"),

    /**
     * A map entry may be an expression without `=>`, Elixir's `map_base_expr`.
     *
     * `elixir-lang/elixir@d68c8d6cd`, first released in v1.17.0-rc.0.
     */
    MAP_ENTRY_WITHOUT_ASSOCIATION(sinceElixir = "1.17.0-rc.0"),

    /**
     * An operator, a line continuation and `/ARITY` is an operator reference, where earlier releases reject it.
     *
     * `elixir-lang/elixir@78fb31201`, with [ESCAPED_NEWLINE_AS_SPACE], first released in v1.20.0-rc.0.
     */
    ESCAPED_NEWLINE_BEFORE_ARITY(sinceElixir = "1.20.0-rc.0"),

    /**
     * `\x` takes exactly two hexadecimal digits: the deprecated `\xH` and `\x{H*}` are errors.
     *
     * `elixir-lang/elixir@4b48982da`, first released in v1.20.0-rc.0.
     */
    HEXADECIMAL_ESCAPE_NEEDS_TWO_DIGITS(sinceElixir = "1.20.0-rc.0"),

    /**
     * `maybe` is a reserved word Erlang prints quoted, once OTP enables the `maybe_expr` feature by default from OTP
     * 27.0-rc1 (`erlang/otp@5d45a0d9c`). The OTP running Elixir decides, not the Elixir release or the OTP its build
     * targeted.
     */
    MAYBE_RESERVED(sinceOtp = "27.0-rc1"),

    /**
     * `unicode_util:gc/1`, which splits quoted text into the grapheme clusters [CLUSTER_COLUMNS_IN_QUOTED_TEXT] counts,
     * reads Unicode 14.0's properties instead of 13.0's.
     *
     * `erlang/otp@20c89ed71f`, first released in OTP 25.0-rc1.
     */
    UNICODE_14_GRAPHEME_CLUSTERS(sinceOtp = "25.0-rc1"),

    /**
     * `unicode_util:gc/1` reads Unicode 15.0's properties.
     *
     * `erlang/otp@020d1d44e2`, first released in OTP 26.0-rc2.
     */
    UNICODE_15_GRAPHEME_CLUSTERS(sinceOtp = "26.0-rc2"),

    /**
     * `unicode_util:gc/1` reads Unicode 16.0's properties and keeps a consonant, a virama and the next consonant in one
     * cluster. It takes every script's consonants and viramas from `IndicSyllabicCategory.txt`, where Unicode's
     * `Indic_Conjunct_Break` takes a few scripts', and lets a spacing mark stand between them.
     *
     * `erlang/otp@6119a85ff1` and `erlang/otp@47655d1ff6`, first released in OTP 28.0-rc2.
     */
    INDIC_CONJUNCT_GRAPHEME_CLUSTERS(sinceOtp = "28.0-rc2"),

    /**
     * `unicode_util:gc/1` reads Unicode 17.0's properties, and joins independent vowels and U+1B0B and U+1B0C as
     * consonants and invisible stackers as viramas.
     *
     * `erlang/otp@a9c19d4b5c` and `erlang/otp@8de68766bb`, first released in OTP 29.0-rc1.
     */
    WIDER_INDIC_CONJUNCT_GRAPHEME_CLUSTERS(sinceOtp = "29.0-rc1"),

    /**
     * Bidirectional formatting characters, U+202A to U+202E and U+2066 to U+2069, are rejected in comments and quoted
     * text.
     *
     * `elixir-lang/elixir@6d408bb0c` (#11391), first released in v1.13.0-rc.1.
     */
    BIDI_CHARACTERS_REJECTED(sinceElixir = "1.13.0-rc.1"),

    /**
     * Each underscore-separated chunk of an identifier must be single-script, where before the whole identifier had to
     * resolve to one script or a highly restrictive set.
     *
     * `elixir-lang/elixir@c83334e5f` (#13693) and `elixir-lang/elixir@9924afff5`, first released in v1.18.0-rc.0.
     */
    MIXED_SCRIPT_BY_UNDERSCORE_CHUNK(sinceElixir = "1.18.0-rc.0"),

    /**
     * Line-break characters are rejected in comments.
     *
     * `elixir-lang/elixir@d507502ec`, first released in v1.19.0-rc.1.
     */
    LINE_BREAKS_REJECTED_IN_COMMENTS(sinceElixir = "1.19.0-rc.1"),

    /**
     * Line-break characters are rejected in quoted text, where 1.19 only warns (`elixir-lang/elixir@cb15a3dd4`).
     *
     * `elixir-lang/elixir@54321de13`, first released in v1.20.0-rc.0.
     */
    LINE_BREAKS_REJECTED_IN_QUOTED_TEXT(sinceElixir = "1.20.0-rc.0"),

    /**
     * An invalid escape's error names the invalid character ("invalid hex escape character", `\u{...}` for a code
     * point), where 1.11 says "missing hex sequence" or raises "invalid or reserved Unicode code point" with the
     * decimal value.
     *
     * `elixir-lang/elixir@7d3a33698`, first released in v1.12.0-rc.0.
     */
    ESCAPE_ERRORS_NAME_THE_INVALID_CHARACTER(sinceElixir = "1.12.0-rc.0"),

    /**
     * A non-ASCII or punctuated alias gets one error, "only ASCII characters, without punctuation, are allowed", naming
     * the first character that is not an ASCII letter, where earlier releases word the two cases apart.
     *
     * `elixir-lang/elixir@5deafbdc8`, first released in v1.14.0-rc.0.
     */
    ALIAS_ERROR_COVERS_PUNCTUATION(sinceElixir = "1.14.0-rc.0"),

    /**
     * The error for a letter after a decimal number quotes the character and rewords its advice.
     *
     * `elixir-lang/elixir@b53fb305a`, first released in v1.14.0-rc.0.
     */
    NUMBER_ERROR_QUOTES_THE_CHARACTER(sinceElixir = "1.14.0-rc.0"),

    /**
     * The error for content after a heredoc's opening says "after opening", at the column after the opening.
     *
     * `elixir-lang/elixir@207350fb4`, first released in v1.15.0-rc.2.
     */
    HEREDOC_OPENING_ERROR_SAYS_OPENING(sinceElixir = "1.15.0-rc.2"),

    /**
     * The mixed-script error's guidance says that scripts must be separated by underscore.
     *
     * `elixir-lang/elixir@9924afff5`, first released in v1.18.0-rc.0.
     */
    MIXED_SCRIPT_GUIDANCE_REQUIRES_UNDERSCORES(sinceElixir = "1.18.0-rc.0"),

    /**
     * `Kernel.Typespec` is required in every env, beside `Application` and `Kernel`
     * (`elixir_dispatch:default_requires/0`).
     *
     * Removed by `elixir-lang/elixir@9973a2ede` ("Remove typespec from default requires"), first released in
     * v1.17.0-rc.0.
     */
    KERNEL_TYPESPEC_REQUIRED_BY_DEFAULT(removedInElixir = "1.17.0-rc.0"),

    /**
     * A map key in a pattern may hold a pinned variable at any depth. Before it, `elixir_map:validate_kv/4` skips a key
     * that is a pin, and a pin nested inside a key raises `invalid_pin_in_map_key_match`.
     *
     * `elixir-lang/elixir@953c730cc` ("EEP 52: Allow pins inside map keys in matches", #11544), first released
     * in v1.14.0-rc.0.
     */
    PIN_IN_MAP_KEY_PATTERN(sinceElixir = "1.14.0-rc.0"),

    /**
     * `0.0` in a pattern has a clause of its own, ahead of the other literals, which warns.
     *
     * `elixir-lang/elixir@9c0fa3cfe` ("Warn when matching on 0.0 and generate erl AST for +/-0.0", #12949), first
     * released in v1.16.0-rc.0.
     */
    ZERO_FLOAT_MATCH_WARNS(sinceElixir = "1.16.0-rc.0"),

    /**
     * A `=` inside a pattern is unpacked into its sides (`elixir_clauses:parallel_match/4`), each expanded with a write
     * half of its own, and a pattern whose variables depend on each other raises `recursive`.
     *
     * `elixir-lang/elixir@860f485bd` ("Inference of patterns", #13909), first released in v1.18.0-rc.0.
     */
    PARALLEL_MATCH(sinceElixir = "1.18.0-rc.0"),

    /**
     * A variable that the pattern being expanded has already bound is written to the write half at the version the
     * next new variable will take, not at its own. So inside a tuple or list element, `{x, x} = {1, 1}` leaves `x`,
     * once the element's scope closes, at the version the next variable bound is given.
     *
     * `elixir-lang/elixir@860f485bd` ("Inference of patterns", #13909), first released in v1.18.0-rc.0. Removed by
     * `elixir-lang/elixir@603602e67` ("Implement reverse arrows for case", #15260), first released in v1.20.0-rc.5.
     */
    REPEATED_PATTERN_VARIABLE_WRITTEN_AT_NEXT_VERSION(sinceElixir = "1.18.0-rc.0", removedInElixir = "1.20.0-rc.5"),

    /**
     * `_` in a pattern takes a version, which advances the next variable's.
     *
     * `elixir-lang/elixir@603602e67` ("Implement reverse arrows for case", #15260), first released in v1.20.0-rc.5.
     */
    UNDERSCORE_TAKES_VERSION(sinceElixir = "1.20.0-rc.5"),

    /**
     * Outside a pattern, an undefined variable raises `undefined_var`, as the `on_undefined_variable` compiler option
     * defaults to. Before it, the variable warns and is expanded as a local call of no arguments.
     *
     * `elixir-lang/elixir@4b5097ca6` ("Add :on_undefined_variable compiler option", #12279), first released in
     * v1.15.0-rc.0.
     */
    UNDEFINED_VARIABLE_RAISES(sinceElixir = "1.15.0-rc.0"),

    /**
     * `::` and `|` outside a bitstring or a list have clauses of their own, which raise `unhandled_type_op` and
     * `unhandled_cons_op`. Before it, they reach local dispatch.
     *
     * `elixir-lang/elixir@d716c72f6` ("More error handling adjustments"), first released in v1.15.0-rc.0.
     */
    MISPLACED_TYPE_AND_CONS_OPERATORS(sinceElixir = "1.15.0-rc.0"),

    /**
     * `__cursor__(...)` left in the AST raises `'__cursor__'`, and is a special form. Before it, it is a local call.
     *
     * `elixir-lang/elixir@203baf36a` ("Raise on left-over __cursor__"), first released in v1.17.0-rc.0.
     */
    CURSOR_RAISES(sinceElixir = "1.17.0-rc.0"),

    /**
     * A bitstring size in a pattern is expanded as a guard, reading only the variables bound before the pattern or by
     * the bitstring itself, and an expression other than a variable or integer is allowed. Before it, the size is
     * expanded outside any match, and `elixir_bitstring` then rejects anything but a variable or integer
     * (`bad_size_argument`) and a variable the pattern bound outside the bitstring (`undefined_var_in_spec`).
     *
     * `elixir-lang/elixir@c92724d9b` ("EEP 52: Support for size expression in bitstring matching", #11558) and
     * `elixir-lang/elixir@00c35ad4d` ("Allow any expression in bitstring size outside of matches/guards"), first
     * released in v1.14.0-rc.0.
     */
    BITSTRING_SIZE_EXPANDED_AS_GUARD(sinceElixir = "1.14.0-rc.0"),

    /**
     * `^` reads the variables from before the pattern wherever a pattern is being expanded, a bitstring size
     * included. Before it, `^` needs the `match` context, which a size doesn't have, so `^` in a size raises
     * `pin_outside_of_match`.
     *
     * `elixir-lang/elixir@7454333fd` ("Require pin variable when accessing variable inside binary size in match",
     * #12588), first released in v1.15.0-rc.1.
     */
    PIN_IN_BITSTRING_SIZE(sinceElixir = "1.15.0-rc.1"),

    /**
     * A map key in a pattern may hold a variable on the right of `::`, as a bitstring size does. Before it, a variable
     * in the key's values or in a segment's size or unit raises `invalid_variable_in_map_key_match`.
     *
     * `elixir-lang/elixir@90c832788` ("Support bitstring specifies as map keys in pattern", #12586), first released in
     * v1.15.0-rc.1. In v1.15.0-rc.0 alone, `elixir-lang/elixir@7dc718b29` ("Replace inner AST for bitstring
     * modifiers", #12055) made every expanded spec read as a variable, so any bitstring key raised.
     */
    BITSTRING_SIZE_IN_MAP_KEY_PATTERN(sinceElixir = "1.15.0-rc.1"),

    /**
     * A float segment may be 16 bits wide. Before it, `float-size(16)` raises `bittype_float_size`.
     *
     * `elixir-lang/elixir@68661dfa9` ("Support 16bit floats in bitstrings", #10740), backported to v1.11.4 by
     * `elixir-lang/elixir@99ec7522d`. Elixir checks the OTP it runs on, which supports them from 24; from v1.15.0-rc.0
     * Elixir requires OTP 24.
     */
    HALF_FLOAT_SEGMENT(sinceElixir = "1.11.4", sinceOtp = "24.0-rc1"),

    /**
     * A pinned `binary` or `bitstring` segment followed by another in a pattern takes its size from the pinned value.
     * Before it, it raises `unsized_binary`.
     *
     * `elixir-lang/elixir@58b8b93ee` ("Auto infer size of matched variable in bitstrings", #13106), first released in
     * v1.16.0-rc.1.
     */
    PINNED_BINARY_SEGMENT_INFERS_SIZE(sinceElixir = "1.16.0-rc.1"),

    /**
     * A pinned segment infers its size only when another segment follows it in a pattern. Before it, a pinned segment
     * last in a nested bitstring pattern inferred one too, so that bitstring's last part was sized.
     *
     * `elixir-lang/elixir@aea1a47b8` ("Only infer size in pinned variable when needed", #13423), first released in
     * v1.16.3.
     */
    PINNED_SEGMENT_INFERS_SIZE_ONLY_WHEN_SIZED(sinceElixir = "1.16.3"),

    /**
     * Each segment of a bitstring pattern must be a variable, a bitstring, a pin, a number or a binary, or it raises
     * `unknown_match` at the segment. Before it, only a `=` anywhere in the pattern's segments raises, as
     * `nested_match`, at the bitstring.
     *
     * `elixir-lang/elixir@e4f7ee448` ("Perform type inference using reverse arrows on all non-branching constructs",
     * #14145), first released in v1.19.0-rc.0.
     */
    BITSTRING_PATTERN_SEGMENT_VALIDATED(sinceElixir = "1.19.0-rc.0"),

    /**
     * A segment's specs read the variables from before the segment's value, so `<<n::size(n)>>` can't read the `n` it
     * binds, and a binding in a size outside a pattern is dropped after the segment.
     *
     * `elixir-lang/elixir@b3e3e8c6a` ("Do not consider variables from pattern in bitstring modifier", #14738), first
     * released in v1.19.0-rc.1.
     */
    BITSTRING_SIZE_HIDES_ITS_OWN_SEGMENT(sinceElixir = "1.19.0-rc.1"),

    /**
     * A segment without `::` reports an error at the bitstring when it has no metadata of its own. Before it, at the
     * last such segment before it that has metadata.
     *
     * `elixir-lang/elixir@5d794ab17` ("Pass correct bitstring meta during expansion"), first released in v1.20.0-rc.5.
     */
    BARE_SEGMENT_PASSES_BITSTRING_META(sinceElixir = "1.20.0-rc.5"),

    /**
     * `__STACKTRACE__` in a pattern raises `invalid_pattern_in_match`. Before it, it is read there as anywhere else: it
     * raises `stacktrace_not_allowed` outside a `catch` or `rescue` clause, and binds nothing inside one.
     *
     * `elixir-lang/elixir@e4d8b3a31` ("Raise on invalid use of compiler vars in match", #11189), first released in
     * v1.13.0-rc.0.
     */
    STACKTRACE_REFUSED_IN_PATTERN(sinceElixir = "1.13.0-rc.0"),

    /**
     * A `catch` clause of three or more arguments and a guard raises `wrong_number_of_args_for_clause`, and a `for`
     * `reduce:` clause of two or more arguments and a guard raises `for_with_reduce_bad_block`. Before it, each head
     * expands as those arguments, and Elixir fails after expansion.
     *
     * `elixir-lang/elixir@0e4aaf00c` ("Correctly validate number of args for clauses with when in for and catch",
     * #13785), first released in v1.18.0-rc.0.
     */
    CATCH_WHEN_ARITY_CHECKED(sinceElixir = "1.18.0-rc.0"),

    /**
     * What the right side of a `<-` in `with` or `for` binds is not visible to its pattern or anything after it, and its
     * pattern sees the env the right side left. Before it, the variables are visible to both, and the pattern sees the
     * env from before the right side.
     *
     * `elixir-lang/elixir@82a9aa3ef` ("Make right side of <- behaviour consistent across for and with"), first released
     * in v1.13.0-rc.0.
     */
    GENERATOR_RIGHT_SIDE_SCOPED(sinceElixir = "1.13.0-rc.0"),

    /**
     * `with` takes a list before its last argument as options, as `for` always has, so `with x <- y, [else: e] do`
     * has an `else`. Before it, that list is a clause.
     *
     * `elixir-lang/elixir@bc354c3a6` ("Handle options consistently in with/1 and for/1", #12223), first released in
     * v1.15.0-rc.0.
     */
    WITH_OPTIONS_BEFORE_LAST_ARGUMENT(sinceElixir = "1.15.0-rc.0"),

    /**
     * `&0`, or any `&N` below 1, inside a capture raises `invalid_arity_for_capture`, not `unallowed_capture_arg`.
     *
     * `elixir-lang/elixir@3827a319d` ("Improve error messages from bad capture operator usage", #11656), first released
     * in v1.14.0-rc.0.
     */
    CAPTURE_ARGUMENT_BELOW_ONE_IS_INVALID_ARITY(sinceElixir = "1.14.0-rc.0"),

    /**
     * A capture of a remote call whose module part has no `&N` reports its errors at the call, not at the `&`.
     *
     * `elixir-lang/elixir@6c068176d` ("Emit consistent position meta on fn capture traces", #12033), first released in
     * v1.14.0-rc.1.
     */
    REMOTE_CAPTURE_REPORTED_AT_CALL(sinceElixir = "1.14.0-rc.1"),

    /**
     * A capture of a local call, an operator or a special form, or of a remote call whose module part has an `&N`,
     * reports its errors at the call, not at the `&`.
     *
     * `elixir-lang/elixir@a4c700b23` ("Unify caret position in diagnostics"), first released in v1.16.0-rc.0.
     */
    CAPTURE_REPORTED_AT_CALL(sinceElixir = "1.16.0-rc.0"),

    /**
     * `case`, `cond`, `receive`, `try`, `fn`, `with` and `for` each take a version once their clauses are expanded,
     * which advances the next variable's.
     *
     * `elixir-lang/elixir@603602e67` ("Implement reverse arrows for case", #15260), first released in v1.20.0-rc.5,
     * which adds the `{version, Counter}` metadata to each of them.
     */
    CLAUSES_TAKE_VERSION(sinceElixir = "1.20.0-rc.5"),

    /**
     * `alias` without `:as` of a module whose name isn't an Elixir alias, such as `alias :lists`, raises
     * `invalid_alias_module`. Before it, the alias is `Elixir.` and the text after the module's last dot.
     *
     * `elixir-lang/elixir@50579b41d` ("Compile time error when aliasing non-Elixir modules without :as", #11008),
     * first released in v1.13.0-rc.0.
     */
    IMPLICIT_ALIAS_NEEDS_ELIXIR_MODULE(sinceElixir = "1.13.0-rc.0"),

    /**
     * The pattern of `=` is expanded in the env the right side leaves, so it sees an alias, `require` or `import` made
     * there. Before it, the pattern sees the env from before the right side; the env after the `=` is the right
     * side's either way.
     *
     * `elixir-lang/elixir@739ad53fe` ("Break Macro.Env apart", #11164), first released in v1.13.0-rc.0.
     */
    PATTERN_SEES_RIGHT_SIDE_ENV(sinceElixir = "1.13.0-rc.0"),

    /**
     * `as: nil` raises `invalid_alias_for_as`, and a `require` or `import` without `:as` leaves the aliases alone.
     * Before it, `as: nil` and a missing `:as` both alias the module to itself, which removes any alias named after it.
     *
     * `elixir-lang/elixir@69255ecbc` ("Do not leak alias from Elixir root and simplify defmodule implementation"),
     * first released in v1.16.0-rc.0.
     */
    ALIAS_AS_NIL_REJECTED(sinceElixir = "1.16.0-rc.0"),

    /**
     * An alias expands one step, to the module it was stored with. Before it, the module is looked up again among the
     * aliases, and so on until it names none.
     *
     * `elixir-lang/elixir@bc45fabd9` ("Do not expand aliases recursively", #12822), first released in v1.16.0-rc.0.
     */
    ALIAS_EXPANDS_ONE_STEP(sinceElixir = "1.16.0-rc.0"),

    /**
     * An `import`'s `only:` naming a definition the module doesn't export, or an `only:` or `except:` list naming one
     * twice, warns, and the duplicate is dropped. Before it, they raise `invalid_import` and `duplicated_import`.
     *
     * `elixir-lang/elixir@b24869687` ("Introduce mechanism to collect several errors in a module", #12275), first
     * released in v1.15.0-rc.0.
     */
    IMPORT_OPTION_MISTAKES_WARN(sinceElixir = "1.15.0-rc.0"),

    /**
     * An `import` of a module without `__info__/1` leaves out its `behaviour_info/1` and `module_info/0,1` before the
     * options apply. Before it, only `module_info/0,1` is left out, and only after them.
     *
     * `elixir-lang/elixir@90dd12a5b` ("Do not import _info functions from Erlang"), first released in v1.15.0-rc.0.
     */
    ERLANG_IMPORT_DROPS_BEHAVIOUR_INFO(sinceElixir = "1.15.0-rc.0"),

    /**
     * An `import` that would bring in a special form warns and leaves it out, keeping the module's entry even when
     * nothing else is left. Before it, it raises `special_form_conflict`.
     *
     * `elixir-lang/elixir@206a81bc6` ("Refactor elixir_import to work with ok/error tuples"), first released in
     * v1.17.0-rc.0.
     */
    IMPORT_DISCARDS_SPECIAL_FORMS(sinceElixir = "1.17.0-rc.0"),

    /**
     * An `import`'s `except:` is checked before its `only:`. Before it, `only:` is checked first, and an `except:`
     * beside an `only:` list is `only_and_except_given` whatever its value.
     *
     * `elixir-lang/elixir@206a81bc6` ("Refactor elixir_import to work with ok/error tuples"), first released in
     * v1.17.0-rc.0.
     */
    IMPORT_VALIDATES_EXCEPT_FIRST(sinceElixir = "1.17.0-rc.0"),

    /**
     * `import M, only: :macros` of a module without `__info__/1` brings in no macros. Before it, it raises
     * `no_macros`.
     *
     * `elixir-lang/elixir@ac844f4db` ("Add Macro.Env.define_import/4"), first released in v1.17.0-rc.0.
     */
    IMPORT_ONLY_MACROS_WITHOUT_INFO(sinceElixir = "1.17.0-rc.0"),

    /**
     * The base of a multi-alias call (`alias base.{A, B}`) that doesn't expand to an atom raises `invalid_alias`.
     * Before it, the expander crashes.
     *
     * `elixir-lang/elixir@764235f1d` ("Fix expand crash on invalid multialias root", #14698), first released in
     * v1.19.0-rc.1.
     */
    INVALID_MULTI_ALIAS_BASE_RAISES(sinceElixir = "1.19.0-rc.1"),

    /**
     * A `require` or `import` of the module being defined raises `circular_module` before the module is looked for.
     * Before it, only a module that can't be loaded and is among the context modules raises it.
     *
     * `elixir-lang/elixir@44bdc7af2` ("Consistently raise for circular module requires", #12225), first released in
     * v1.15.0-rc.0.
     */
    CIRCULAR_MODULE_CHECKED_FIRST(sinceElixir = "1.15.0-rc.0"),

    /**
     * `import M, only: :sigils` passes over an arity-2 `sigil_` export whose letters are neither one lower-case letter
     * nor start with an upper-case one. From [DIGITS_IN_SIGIL_NAMES] until it, `is_sigil/1` crashes on one.
     *
     * `elixir-lang/elixir@b7a832ea3` ("Add missing catch-all in :elixir_import.is_sigil/1", #15264), first released in
     * v1.20.0-rc.5.
     */
    SIGIL_FILTER_TOLERATES_ANY_NAME(sinceElixir = "1.20.0-rc.5"),

    /**
     * The compiler parses source with `columns: true`, so a node's metadata carries its `column`.
     *
     * `elixir-lang/elixir@f632fc648` ("Set parser_options[:columns] to true by default", #12941), first released in
     * v1.16.0-rc.0.
     */
    COMPILER_PARSES_COLUMNS(sinceElixir = "1.16.0-rc.0"),

    /**
     * A name `quote` finds imported is marked `imports: [{arity, module}]`, for every arity imported under that name,
     * and a name two modules import at one arity raises `ambiguous_call` whatever the quoted arity. Before it, it is
     * marked `import: module` for the quoted arity only.
     *
     * `elixir-lang/elixir@169595f53` ("Change import metadata to imports as a list") and `elixir-lang/elixir@c47b731f7`
     * ("Track all arities in imports inside quotes", #11651), first released in v1.14.0-rc.0.
     */
    QUOTE_IMPORTS_EVERY_ARITY(sinceElixir = "1.14.0-rc.0"),

    /**
     * A `quote` given `file:` keeps the `line:` option in `keep: {file, line}`: a node's own line for `line: true`,
     * and 0 by default. Before it, `file:` keeps every node's own line whatever `line:` says.
     *
     * `elixir-lang/elixir@92e0e34a1` ("Respect line property when file is given", #13542), first released in
     * v1.17.0-rc.0.
     */
    QUOTE_KEEP_READS_LINE_OPTION(sinceElixir = "1.17.0-rc.0"),

    /**
     * The head of a guarded definition, not its `when`, takes the `context:` a quoted `def`, `defp`, `defmacro`,
     * `defmacrop` or `@` gives its first argument.
     *
     * `elixir-lang/elixir@9581ba791` ("Annotate function definition and not when guard"), first released in
     * v1.16.0-rc.0.
     */
    QUOTED_DEF_CONTEXT_SKIPS_GUARD(sinceElixir = "1.16.0-rc.0"),

    /**
     * A quoted node's metadata leaves out the `column` of its source. Before it, a `column` the parser gave is kept.
     *
     * `elixir-lang/elixir@cb9de080e` ("Strip column information on quote"), first released in v1.16.0-rc.0.
     */
    QUOTE_META_DROPS_COLUMN(sinceElixir = "1.16.0-rc.0"),

    /**
     * An `unquote` inside a `quote` is passed to the remote call `:elixir_quote.shallow_validate_ast/1`. Before it,
     * the unquoted expression is used as it is.
     *
     * `elixir-lang/elixir@cc68ad91c` ("Add shallow validation when unquoting AST", #13950), first released in
     * v1.18.0-rc.0.
     */
    UNQUOTE_SHALLOW_VALIDATED(sinceElixir = "1.18.0-rc.0"),

    /**
     * A `quote`'s `bind_quoted` bindings leave out the `column` of the `quote`'s metadata.
     *
     * `elixir-lang/elixir@dea845602` ("Escape meta within existing quote extensions", #14832), first released in
     * v1.19.0.
     */
    QUOTE_BINDING_META_DROPS_COLUMN(sinceElixir = "1.19.0"),

    /**
     * An `unquote` inside a `quote` is passed to `:elixir_quote.unquote/1` in place of `shallow_validate_ast/1`, and a
     * `quote` outside a pattern or guard is wrapped in `:elixir_quote.validate_quote/1`.
     *
     * `elixir-lang/elixir@3a038d176` ("Wrap quote in a function that will implement recursive types"), first released
     * in v1.20.0.
     */
    UNQUOTE_VALIDATED_BY_UNQUOTE(sinceElixir = "1.20.0"),

    /**
     * A `quote` with `unquote` in a pattern or guard raises `quote_in_pattern_with_unquote`.
     *
     * `elixir-lang/elixir@0f9072a1d` ("Cleaner error message for unquote when quote is used in a pattern", #15469),
     * first released in v1.20.2.
     */
    QUOTE_IN_PATTERN_WITH_UNQUOTE_RAISES(sinceElixir = "1.20.2"),

    /**
     * A bitstring segment whose value expands to a list or an atom raises `invalid_literal`.
     *
     * Removed by `elixir-lang/elixir@860f485bd` ("Inference of patterns", #13909), first released in v1.18.0-rc.0.
     */
    BITSTRING_LIST_OR_ATOM_SEGMENT_REJECTED(removedInElixir = "1.18.0-rc.0");


    /** The first Elixir release with this behaviour, or `null` when every supported release has it. */
    val sinceElixir: SemVer? = sinceElixir?.let(::release)

    /** The first Elixir release without this behaviour, or `null` while Elixir still has it. */
    val removedInElixir: SemVer? = removedInElixir?.let(::release)

    /** The first Erlang/OTP release with this behaviour, or `null` when it does not depend on OTP. */
    val sinceOtp: Release? = sinceOtp?.let { Release.parse(it) ?: error("not an OTP release: $it") }

    /** An OTP that cannot be determined is taken as the newest, as [ElixirLanguageLevel.FALLBACK] takes Elixir. */
    fun isSufficient(languageLevel: ElixirLanguageLevel): Boolean =
        (sinceElixir == null || languageLevel.elixir >= sinceElixir) &&
            (removedInElixir == null || languageLevel.elixir < removedInElixir) &&
            (sinceOtp == null || languageLevel.otp == null || languageLevel.otp >= sinceOtp)
}

private fun release(text: String): SemVer = SemVer.parseFromText(text) ?: error("not an Elixir release: $text")
