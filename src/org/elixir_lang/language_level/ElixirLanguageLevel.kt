package org.elixir_lang.language_level

/**
 * Which shape of quoted form to emit, as a threshold on the Elixir version that produced it.
 *
 * Elixir's quoted form changes between versions, so a single answer is wrong for every version but
 * one. Each constant marks a version at which a divergence appeared; the predicates below read as
 * "this language level is at least that version", so a language level answers every predicate for every earlier
 * constant too.
 *
 * All the divergences known so far are **additive** - a newer Elixir emits metadata, or a richer
 * node, where an older one emitted less - which is exactly what a monotonic threshold expresses.
 * Adding a version therefore costs one constant and one predicate. Should a future Elixir *remove*
 * something instead, a monotonic threshold can no longer express it and this becomes a hierarchy;
 * likewise if a divergence ever needs different *construction* rather than a different *decision*.
 *
 * Every threshold here was pinned against Elixir's own history and confirmed by quoting the
 * construct with the reference implementation on either side of the boundary - see each constant.
 */
enum class ElixirLanguageLevel {
    /** Everything before Elixir 1.12.0, and the floor - nothing resolves below it. */
    V1_11,

    /**
     * Elixir 1.12.0 stopped consuming a `\` ending a line in an **interpolating** sigil, and began
     * emitting a leading empty binary for a heredoc opening on an interpolation.
     *
     * elixir-lang/elixir `8c29984ed` moved `\<newline>` out of `elixir_interpolation:extract/8`
     * into `unescape_chars`, which sigil parts never reach; its deleted clauses were guarded on
     * `Interpol = true`, so `~S` is unaffected. `51d90f193` made the tokenizer strip a heredoc's
     * artificial leading newline after extraction rather than before. Both first released in v1.12.0.
     *
     * `51d90f193` also advanced the line past a `\` ending a line in a **non-interpolating** sigil. 1.11's
     * `extract/8` took the two characters in its `[$\\, Char | Rest]` clause, which counts columns and no line, so
     * in `~S(a\` + newline + `b) in x` everything after the sigil is one line lower. Read via
     * [countsEscapedNewlineInLiteralSigilLine].
     *
     * 1.12.0 also added the step operator, `first..last//step` (elixir-lang/elixir #10810). Before it `//` is two
     * divisions, and an operator before `/` lexes as an identifier, so `x..y//1` is `x..y((/)/1)` and `[..//: 1]` is
     * `[..(/([/: 1]))]`. Read by the parser via [hasStepOperator].
     *
     * 1.12.0 also rejects a letter directly after a decimal number, where 1.11 ended the number there, so `1and 2` was
     * `1 and 2` (elixir-lang/elixir 6b2cc2332, "Raise clearer error message on number followed by identifiers"). Read
     * by the parser via [endsDecimalNumberBeforeWord].
     */
    V1_12,

    /**
     * Elixir 1.13.0 unescapes an escaped terminator inside a sigil heredoc, so `\"""` quotes as
     * `"""` where 1.12.3 and earlier keep the backslash and quote as `\"""`.
     *
     * elixir-lang/elixir `ffd891a34` added the `[$\\, Last, Last, Last | Rest]` clause to
     * `elixir_interpolation:extract/8`, first released in v1.13.0. Not conditioned on the
     * interpolation flag, so `~s` and `~S` alike; a plain heredoc reaches the same text through
     * `unescape_tokens` and a sigil line's terminator was already unescaped in v1.12.3.
     *
     * 1.13.0 also gives a remote call the line of its name, where 1.12.3 gave it the line of the `.`, so
     * `:erlang.` + newline + `get(1)` is a call on line 2; the `.` node keeps the dot's line in both. From
     * elixir-lang/elixir 376ff1e51 ("Add more token metadata to aliases and remote calls", #11038), whose
     * `build_dot` carries the identifier's location. Read via [putsRemoteCallOnNameLine].
     */
    V1_13,

    /**
     * Elixir 1.14.0 accepts `..` with no operands, as the nullary operator `{:.., meta, []}`; 1.13.4
     * and earlier reject it.
     *
     * elixir-lang/elixir 6447f440d ("Add .. as a nullary operator that returns 0..-1//1", #11623),
     * first released in v1.14.0.
     *
     * 1.14.0 also normalises an identifier token - variables, calls, remote names, unquoted atoms and keyword keys - to
     * NFC, and MICRO SIGN (U+00B5) in it to GREEK SMALL LETTER MU (U+03BC), but not a quoted atom or name
     * (elixir-lang/elixir e7001455d, "nfc and additional normalizations for identifiers", #11859). 1.13.4 and earlier
     * reject an identifier that is not NFC. Read via [normalizesIdentifiers].
     */
    V1_14,

    /**
     * Elixir 1.15.0 added `from_brackets: true` to the `Access.get/2` metadata, but only for the
     * `bracket_expr -> access_expr bracket_arg` production - bracket access on an expression, as in
     * `[1, 2][0]`. The other four bracket forms did not get it until [V1_16_2].
     *
     * `elixir_parser.yrl`, `meta_with_from_brackets`, introduced by elixir-lang/elixir aa8e6d3fe
     * ("Add error message when piping into an expression ending in bracket-based access", #12359),
     * first released in v1.15.0.
     *
     * 1.15.0 also narrowed where a solitary `not` or `!` gets a `__block__` wrapper. That clause
     * lived in `build_block`, which every block position went through - a stab body, a file, an
     * interpolation - so `( -> ! one )` and `a not in b` were wrapped. 1.15.0 moved it into
     * `build_paren_stab`, leaving only a parenthesised single unary expression wrapped, and wrapped
     * with empty metadata rather than the parentheses' own. From elixir-lang/elixir 318681950
     * ("Apply rearrange ops only inside parens", #12296), also first released in v1.15.0.
     * `?rearrange_uop` is `Op == 'not' orelse Op == '!'`; the neighbouring `unquote_splicing`
     * clause was not touched and still wraps in every version.
     *
     * That one is read via [wrapsSolitaryUnaryNotInEveryBlock], the only predicate here true
     * *below* its threshold, because the behaviour was narrowed rather than added.
     */
    V1_15,

    /**
     * Elixir 1.16.0 added `from_interpolation: true` to the metadata of the `Kernel.to_string/1`
     * call that interpolation quotes to.
     *
     * elixir-lang/elixir 5225b33ba ("Add interpolation token metadata"), first released in v1.16.0.
     */
    V1_16_0,

    /**
     * Elixir 1.16.2 extended `from_brackets: true` to the remaining four bracket productions:
     * `bracket_expr -> dot_bracket_identifier` (`foo[:a]` and `Foo.bar[:a]`) and both
     * `bracket_at_expr` forms (`@foo[:a]` and `@1[:a]`).
     *
     * elixir-lang/elixir d8cc841ab ("Include from_brackets metadata in all cases", #13317),
     * first released in v1.16.2 (the same change reached master as eb1499ac2, released in v1.17.0).
     */
    V1_16_2,

    /**
     * Elixir 1.17.0 made `...` a nullary call - `{:..., meta, []}` - where earlier versions quoted
     * it as a variable, `{:..., meta, nil}`.
     *
     * `elixir_parser.yrl` gained `sub_matched_expr -> ellipsis_op : build_nullary_op('$1')` in
     * v1.17.0; v1.16.3 has no `ellipsis_op` production. Note this is **not** the 1.19 the code
     * comment on the plugin side claimed: quoting `...` gives `nil` on 1.16.3 and `[]` on 1.17.3.
     *
     * 1.17.0 also widened which ambiguous dual operators make the identifier before them a call.
     * `elixir_tokenizer.erl`'s `handle_space_sensitive_tokens` refused the `op_identifier`
     * conversion when the character after the sign was any of `( [ < { % + - / > :`; 1.17.0 shrank
     * that guard to `NotMarker =/= Sign, NotMarker =/= $/, NotMarker =/= $>`, so `one +(two)` went
     * from the operation `one + two` to the call `one(+two)`. From elixir-lang/elixir b8f069d08
     * ("Fix parsing of ambiguous operators followed by containers"), first released in v1.17.0.
     *
     * Read via [quotesAmbiguousDualOperatorAsCall].
     *
     * 1.17.0 also stopped a parenthesised expression from picking up metadata from parentheses that
     * merely nest around it. Below 1.17.0, `build_paren_stab`'s non-`rearrange_uop` clause ran its
     * body through `build_stab/1` and, whenever that returned a `__block__` (from a solitary
     * `not`/`!` already rearranged by an inner paren, `unquote_splicing`, or several expressions),
     * appended this layer's own `line` to its metadata: `Meta ++ meta_from_token_with_closing(...)`.
     * Each further layer of plain parentheses around the same `__block__` appended another `line`
     * entry, so `&(((&1 not in ?0..?9)))` carries `[line: N, line: N]` on that block below 1.17.0 -
     * one entry per enclosing paren beyond the innermost, not one entry total. From 1.17.0,
     * `build_paren_stab` calls `build_block/2` directly, whose single-expression clause is
     * `build_block([Expr], _Meta) -> Expr` - the passed-in metadata is discarded unconditionally, so
     * enclosing parentheses stop contributing anything and the innermost `__block__`'s own metadata
     * (empty, for the `rearrange_uop` case) is all that survives.
     *
     * Verified against `elixir_parser.yrl` at v1.14.5 (`build_stab/3`), v1.16.3 and v1.17.3
     * (`build_paren_stab/3`, `build_block/1` and `/2`), and by quoting
     * `&(((&1 not in ?0..?9)))` with each of 1.16.3 and 1.17.3.
     *
     * Read via [mergesEnclosingParenMetadataOntoBlock].
     */
    V1_17,

    /**
     * Elixir 1.18.0 unescapes the name of a quoted remote call, so `foo."bar\nbaz"()` calls
     * `:"bar\nbaz"` where 1.17.3 and earlier keep the backslash - and an invalid escape there, as in
     * `a.'\xg'`, raises `MatchError` instead of being kept.
     *
     * elixir-lang/elixir e54b87c18 ("Fix formatter adding extra escapes to remote call functions",
     * #13960) added `{ok, [UnescapedPart]} = unescape_tokens(...)` to `elixir_tokenizer:handle_dot`,
     * first released in v1.18.0.
     *
     * Read via [unescapesQuotedRemoteCallName].
     */
    V1_18,

    /**
     * Elixir 1.19.0 answers `{:error, _}` where 1.18 raises for an invalid escape in a quoted
     * remote-call name (`MatchError`; elixir-lang/elixir 41151190e, #14587) and for invalid UTF-8 in
     * a charlist, as in `'\xFF'` (`UnicodeConversionError`; 71e1ddc64, #14666). Both first released
     * in v1.19.0.
     *
     * 1.19.0 also advances the line past a character literal that is a newline, `?` + newline or `?\` + newline,
     * where 1.18 counted only columns, so everything after it was one line lower (elixir-lang/elixir 6fbc6e08a,
     * "Advance line when processing ? followed by <LF> and \<LF>"). Read via [countsNewlineInCharacter].
     *
     * 1.19.0 also gives the `in` of `not in` its own location, where 1.18 gave it the location of `not`, which differs
     * when a line continuation separates them (elixir-lang/elixir 8ac8230e1, "Properly handle column for 'in' in 'not in'
     * operator", and a2baac915). Read via [putsInOfNotInOnItsOwnLine].
     */
    V1_19,

    /**
     * Elixir 1.20.0 added `line` metadata to two `__block__` forms that previously carried none: a
     * `do:` block's value now carries the line of its own `do` token
     * (elixir-lang/elixir 90e1826c7), and a 0-byte file's implicit top-level block now carries
     * `line: 1` (elixir-lang/elixir 7da1b76b6). Both first released in v1.20.0-rc.0. Only `line` is
     * added, not `column` - the rest of each commit is gated behind `?columns()`/`?token_metadata()`,
     * which neither this plugin nor its reference quoter enables.
     *
     * Read via [emitsLineMetadataOnBlock].
     *
     * 1.20.0 also counts a `\` + newline after a space as space, and stopped `-\` + newline making the identifier
     * before it a call (elixir-lang/elixir 78fb31201, "Consistently treat \ followed by newlines as horizontal
     * space"). Read by the parser via [countsEscapedNewlineAsSpace].
     */
    V1_20;

    /**
     * Whether a `\` ending a line survives extraction into the buffer. A sigil then keeps the
     * backslash and newline, because sigil parts skip `unescape_tokens`, while a plain string or
     * heredoc unescapes them away and is left with an empty segment.
     */
    val keepsEscapedNewlineInExtractedBuffer: Boolean get() = this >= V1_12

    /** Whether a `\` ending a line in a non-interpolating sigil line advances the line of what follows. */
    val countsEscapedNewlineInLiteralSigilLine: Boolean get() = this >= V1_12

    /** Whether `?` + newline or `?\` + newline advances the line of what follows. */
    val countsNewlineInCharacter: Boolean get() = this >= V1_19

    /** Whether the `in` of `not in` carries its own line rather than the line of `not`. */
    val putsInOfNotInOnItsOwnLine: Boolean get() = this >= V1_19

    /** Whether a remote call split by a newline after its `.` carries its name's line rather than the dot's. */
    val putsRemoteCallOnNameLine: Boolean get() = this >= V1_13

    /** An identifier token quoted as NFC, with `µ` (U+00B5) as `μ` (U+03BC). */
    val normalizesIdentifiers: Boolean get() = this >= V1_14

    /** `foo."bar\nbaz"()` calling `:"bar\nbaz"` rather than `:"bar\\nbaz"`. */
    val unescapesQuotedRemoteCallName: Boolean get() = this >= V1_18

    /** The leading `""` a heredoc gets when its first content is `#{...}`. */
    val emitsEmptyLeadingHeredocSegment: Boolean get() = this >= V1_12

    /** `\"""` in a `~S"""` heredoc - the terminator alone, rather than backslash and terminator. */
    val unescapesSigilHeredocTerminator: Boolean get() = this >= V1_13

    /** `[1, 2][0]` and friends - the `bracket_expr -> access_expr bracket_arg` production. */
    val emitsFromBracketsOnBracketedExpression: Boolean get() = this >= V1_15

    /**
     * Whether a solitary `not`/`!` is wrapped in `__block__` in *every* block position, rather than
     * only inside parentheses. True below [V1_15] - see there for why this one reads downwards.
     */
    val wrapsSolitaryUnaryNotInEveryBlock: Boolean get() = this < V1_15

    /**
     * Whether `&` must be immediately followed by its digit for the two to be one capture argument.
     * `&1` always is; `& 1` is too below 1.15.0, but from 1.15.0 it is `&` applied to `1`, which
     * binds the rest of the expression - so `& & 1 + & 2` is `&((&1) + (&2))` up to 1.14.5 and
     * `&(&(1 + &2))` from 1.15.0.
     *
     * `elixir_parser.yrl`'s `access_expr -> capture_op_eol int` became
     * `access_expr -> capture_int int`, and `elixir_tokenizer.erl` emits `capture_int` only for an
     * adjacent digit; the `_eol` token it replaced permitted whitespace. From elixir-lang/elixir
     * 9fb3cf603 ("Fix ambiguity in &INT with brackets"), first released in v1.15.0.
     *
     * Read by the parser rather than the quoter, unlike everything else here: the divergence is in
     * how the tokens bind, which no reshape of the quoted form can express.
     */
    val requiresAdjacentCaptureArgument: Boolean get() = this >= V1_15

    /** The `Kernel.to_string/1` call that `"a#{b}c"` quotes to. */
    val emitsFromInterpolation: Boolean get() = this >= V1_16_0

    /** `foo[:a]`, `Foo.bar[:a]`, `@foo[:a]` and `@1[:a]` - every bracket form. */
    val emitsFromBracketsOnEveryBracketForm: Boolean get() = this >= V1_16_2

    /** `...` as `{:..., meta, []}` rather than `{:..., meta, nil}`. */
    val quotesEllipsisAsNullaryCall: Boolean get() = this >= V1_17

    /**
     * `one +(two)` as the call `one(+two)` rather than the operation `one + two` - a container, `%`
     * or the opposite sign after a spaced dual operator. `one +two` is a call in every version this
     * plugin supports and is not affected; `one ++two` and `one +/two` are operations in every
     * version, being the exclusions 1.17.0 kept.
     */
    val quotesAmbiguousDualOperatorAsCall: Boolean get() = this >= V1_17

    /**
     * Whether a plain pair of parentheses around an expression that already quotes to a `__block__`
     * appends its own `line` to that block's metadata, rather than leaving the block's metadata as
     * the inner parentheses (or expression) produced it. True below [V1_17] - see there for why this
     * one reads downwards, like [wrapsSolitaryUnaryNotInEveryBlock].
     */
    val mergesEnclosingParenMetadataOntoBlock: Boolean get() = this < V1_17

    /**
     * Whether a `do:` block's `__block__` (and an empty file's implicit top-level `__block__`)
     * carries `line` metadata instead of `[]`.
     */
    val emitsLineMetadataOnBlock: Boolean get() = this >= V1_20

    /** Whether `//` is the step operator rather than two divisions. Read by the parser, like [requiresAdjacentCaptureArgument]. */
    val hasStepOperator: Boolean get() = this >= V1_12

    /** Whether a decimal number ends before letters that follow it, as a based number does in every version. */
    val endsDecimalNumberBeforeWord: Boolean get() = this < V1_12

    /**
     * Whether a `\` + newline next to a spaced `+` or `-` after an identifier counts as space, so `f -\` + newline +
     * `var` is a subtraction and `f \` + newline + `-var` the call `f(-var)`; below, both are the other way round.
     * Read by the parser, like [requiresAdjacentCaptureArgument].
     */
    val countsEscapedNewlineAsSpace: Boolean get() = this >= V1_20

    companion object {
        /**
         * The language level to assume when the Elixir version behind an element cannot be determined - no
         * module, no Elixir SDK, or an SDK whose version string carries no version.
         *
         * Deliberately the newest rather than the oldest: most users are on a recent Elixir, so a
         * wrong-but-modern quoted form is the least surprising default. It is also the direction
         * that ages well, since a new threshold added below shifts the fallback forward with it.
         */
        @JvmStatic
        val FALLBACK: ElixirLanguageLevel = entries.last()

        /** Leading `MAJOR.MINOR[.PATCH]`, wherever it sits in the string. */
        private val VERSION = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

        /**
         * The language level for an Elixir version, or [FALLBACK] when [version] carries no version number.
         *
         * [version] may be a bare version (`"1.16.2"`), a mise-style version with a build
         * tag (`"1.13.4-otp-24"`), or a whole SDK version string
         * (`"mise Elixir 1.13.4 (OTP 24)"`). Anything after the version number is ignored, which
         * also means a pre-release resolves as its release.
         */
        @JvmStatic
        fun of(version: String?): ElixirLanguageLevel {
            val match = version?.let { VERSION.find(it) } ?: return FALLBACK
            val (major, minor, patch) = match.destructured
            val numbers = Triple(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())

            return when {
                numbers >= Triple(1, 20, 0) -> V1_20
                numbers >= Triple(1, 19, 0) -> V1_19
                numbers >= Triple(1, 18, 0) -> V1_18
                numbers >= Triple(1, 17, 0) -> V1_17
                numbers >= Triple(1, 16, 2) -> V1_16_2
                numbers >= Triple(1, 16, 0) -> V1_16_0
                numbers >= Triple(1, 15, 0) -> V1_15
                numbers >= Triple(1, 14, 0) -> V1_14
                numbers >= Triple(1, 13, 0) -> V1_13
                numbers >= Triple(1, 12, 0) -> V1_12
                else -> V1_11
            }
        }

        private operator fun Triple<Int, Int, Int>.compareTo(other: Triple<Int, Int, Int>): Int =
            compareValuesBy(this, other, Triple<Int, Int, Int>::first, Triple<Int, Int, Int>::second, Triple<Int, Int, Int>::third)
    }
}
