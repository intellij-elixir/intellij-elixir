package org.elixir_lang.language_level

import com.intellij.util.text.SemVer
import org.elixir_lang.sdk.erlang.Release

/**
 * What Elixir does in a window of releases that the plugin has to follow when parsing, quoting or checking code.
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
     * segment. Before it, `\<newline>` in an interpolating sigil was consumed; `~S` is unaffected.
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
     * A remote call split by a newline after its `.` carries its name's line rather than the dot's, so `:erlang.` +
     * newline + `get(1)` is a call on line 2; the `.` node keeps the dot's line on every release.
     *
     * `elixir-lang/elixir@376ff1e51` ("Add more token metadata to aliases and remote calls", #11038), first released in
     * v1.13.0-rc.0.
     */
    REMOTE_CALL_ON_NAME_LINE(sinceElixir = "1.13.0-rc.0"),

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
     * `...` quotes as the nullary call `{:..., meta, []}` rather than the variable `{:..., meta, nil}`.
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
     * A `do:` block's `__block__` carries the line of its own `do` token (`elixir-lang/elixir@90e1826c7`), and a 0-byte
     * file's implicit top-level block carries `line: 1` (`elixir-lang/elixir@7da1b76b6`), where both carried none. Only
     * `line` is added, not `column`: the rest of each commit is gated behind `?columns()`/`?token_metadata()`, which
     * neither this plugin nor its reference quoter enables. First released in v1.20.0-rc.0.
     */
    LINE_METADATA_ON_BLOCK(sinceElixir = "1.20.0-rc.0"),

    /**
     * A `\` + newline next to a spaced `+` or `-` after an identifier counts as space, so `f -\` + newline + `var` is a
     * subtraction and `f \` + newline + `-var` the call `f(-var)`; before, both read the other way round. Read by the
     * parser.
     *
     * `elixir-lang/elixir@78fb31201` ("Consistently treat \ followed by newlines as horizontal space"), first released
     * in v1.20.0-rc.0.
     */
    ESCAPED_NEWLINE_AS_SPACE(sinceElixir = "1.20.0-rc.0"),
    /**
     * A heredoc terminator after content on its line is content, where 1.11 rejects it there ("invalid location for
     * heredoc terminator") and scans a heredoc's lines for its terminator before reading its interpolations.
     *
     * `elixir-lang/elixir@51d90f193` ("Allow heredoc inside heredoc interpolation"), first released in v1.12.0-rc.0.
     */
    HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT(sinceElixir = "1.12.0-rc.0"),

    /**
     * `end::` closes its block, where 1.11 leaves the block open.
     *
     * `elixir-lang/elixir@01f5196bf`, first released in v1.12.0-rc.0.
     */
    TYPE_OPERATOR_AFTER_END(sinceElixir = "1.12.0-rc.0"),

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
    MIXED_SCRIPT_GUIDANCE_REQUIRES_UNDERSCORES(sinceElixir = "1.18.0-rc.0");


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
