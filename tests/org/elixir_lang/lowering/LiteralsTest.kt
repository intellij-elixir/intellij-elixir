package org.elixir_lang.lowering

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.Digits

/** Each expected term is `Code.string_to_quoted(code, columns: true, token_metadata: true)` on that version's Elixir. */
class LiteralsTest : LoweringTestCase() {
    fun testNumbersAndCharactersHaveNoMetadata() =
        assertLowers("[1, 0x1F, 0o7, 0b1, 1_000, 1.5, 1.0e3, ?a, ?\\n]", "[1, 31, 7, 1, 1000, 1.5, 1000.0, 97, 10]")

    fun testAtomsHaveNoMetadata() =
        assertLowers("""[:a, :"a b", :'a', true, false, nil]""", """[:a, :"a b", :a, true, false, nil]""")

    fun testAnUnquotedIdentifierIsNormalisedFrom114() = assertLowers(
        "[:\u00B5, :\"\u00B5 b\", [\u00B5: 1]]",
        "1.13.4" to "[:\u00B5, :\"\u00B5 b\", [\u00B5: 1]]",
        "1.14.5" to "[:\u03BC, :\"\u00B5 b\", [\u03BC: 1]]",
    )

    fun testAnInterpolatedAtomIsABinaryToAtomCall() = assertLowers(
        """:"a#{1}"""",
        "1.12.3" to """{{:., [line: 1, column: 1], [:erlang, :binary_to_atom]}, [line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["a", {:"::", [line: 1, column: 4], [{{:., [line: 1, column: 4], [Kernel, :to_string]}, [closing: [line: 1, column: 7], line: 1, column: 4], [1]}, {:binary, [line: 1, column: 4], nil}]}]}, :utf8]}""",
        "1.13.4" to """{{:., [line: 1, column: 1], [:erlang, :binary_to_atom]}, [delimiter: "\"", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["a", {:"::", [line: 1, column: 4], [{{:., [line: 1, column: 4], [Kernel, :to_string]}, [closing: [line: 1, column: 7], line: 1, column: 4], [1]}, {:binary, [line: 1, column: 4], nil}]}]}, :utf8]}""",
        "1.16.3" to """{{:., [line: 1, column: 1], [:erlang, :binary_to_atom]}, [delimiter: "\"", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["a", {:"::", [line: 1, column: 4], [{{:., [line: 1, column: 4], [Kernel, :to_string]}, [from_interpolation: true, closing: [line: 1, column: 7], line: 1, column: 4], [1]}, {:binary, [line: 1, column: 4], nil}]}]}, :utf8]}""",
    )

    fun testAnInterpolatedCharListAtomReportsADoubleQuoteUntil118() = assertLowers(
        """:'a#{1}'""",
        "1.17.3" to """{{:., [line: 1, column: 1], [:erlang, :binary_to_atom]}, [delimiter: "\"", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["a", {:"::", [line: 1, column: 4], [{{:., [line: 1, column: 4], [Kernel, :to_string]}, [from_interpolation: true, closing: [line: 1, column: 7], line: 1, column: 4], [1]}, {:binary, [line: 1, column: 4], nil}]}]}, :utf8]}""",
        "1.18.4" to """{{:., [line: 1, column: 1], [:erlang, :binary_to_atom]}, [delimiter: "'", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["a", {:"::", [line: 1, column: 4], [{{:., [line: 1, column: 4], [Kernel, :to_string]}, [from_interpolation: true, closing: [line: 1, column: 7], line: 1, column: 4], [1]}, {:binary, [line: 1, column: 4], nil}]}]}, :utf8]}""",
    )

    fun testAnAliasHasItsLastSegmentFrom113() = assertLowers(
        "Foo",
        "1.12.3" to "{:__aliases__, [line: 1, column: 1], [:Foo]}",
        "1.13.4" to "{:__aliases__, [last: [line: 1, column: 1], line: 1, column: 1], [:Foo]}",
    )

    fun testAQualifiedAliasIsAtItsFirstSegment() = assertLowers(
        "Foo.\nBar",
        "1.12.3" to "{:__aliases__, [line: 1, column: 1], [:Foo, :Bar]}",
        "1.13.4" to "{:__aliases__, [last: [line: 2, column: 1], line: 1, column: 1], [:Foo, :Bar]}",
    )

    fun testAStringWithoutInterpolationIsABinary() = assertLowers("\"x\"", "\"x\"")

    fun testAnInterpolatedStringIsABitString() = assertLowers(
        """"a#{1}b"""",
        "1.15.8" to """{:<<>>, [delimiter: "\"", line: 1, column: 1], ["a", {:"::", [line: 1, column: 3], [{{:., [line: 1, column: 3], [Kernel, :to_string]}, [closing: [line: 1, column: 6], line: 1, column: 3], [1]}, {:binary, [line: 1, column: 3], nil}]}, "b"]}""",
        "1.16.3" to """{:<<>>, [delimiter: "\"", line: 1, column: 1], ["a", {:"::", [line: 1, column: 3], [{{:., [line: 1, column: 3], [Kernel, :to_string]}, [from_interpolation: true, closing: [line: 1, column: 6], line: 1, column: 3], [1]}, {:binary, [line: 1, column: 3], nil}]}, "b"]}""",
    )

    fun testAnInterpolatedCharListIsAToCharListCall() = assertLowers(
        """'a#{1}'""",
        "1.15.8" to """{{:., [line: 1, column: 1], [List, :to_charlist]}, [delimiter: "'", line: 1, column: 1], [["a", {{:., [line: 1, column: 3], [Kernel, :to_string]}, [closing: [line: 1, column: 6], line: 1, column: 3], [1]}]]}""",
        "1.16.3" to """{{:., [line: 1, column: 1], [List, :to_charlist]}, [delimiter: "'", line: 1, column: 1], [["a", {{:., [line: 1, column: 3], [Kernel, :to_string]}, [from_interpolation: true, closing: [line: 1, column: 6], line: 1, column: 3], [1]}]]}""",
    )

    fun testAnInterpolatedHeredocHasItsIndentationFrom113() = assertLowers(
        "\"\"\"\n  a#{1}\n  \"\"\"",
        "1.11.4" to """{:<<>>, [delimiter: "\"\"\"", line: 1, column: 1], ["a", {:"::", [line: 2, column: 2], [{{:., [line: 2, column: 2], [Kernel, :to_string]}, [closing: [line: 2, column: 5], line: 2, column: 2], [1]}, {:binary, [line: 2, column: 2], nil}]}, "\n"]}""",
        "1.12.3" to """{:<<>>, [delimiter: "\"\"\"", line: 1, column: 1], ["a", {:"::", [line: 2, column: 4], [{{:., [line: 2, column: 4], [Kernel, :to_string]}, [closing: [line: 2, column: 7], line: 2, column: 4], [1]}, {:binary, [line: 2, column: 4], nil}]}, "\n"]}""",
        "1.13.4" to """{:<<>>, [delimiter: "\"\"\"", indentation: 2, line: 1, column: 1], ["a", {:"::", [line: 2, column: 4], [{{:., [line: 2, column: 4], [Kernel, :to_string]}, [closing: [line: 2, column: 7], line: 2, column: 4], [1]}, {:binary, [line: 2, column: 4], nil}]}, "\n"]}""",
    )

    fun testAHeredocLineStartingWithAnInterpolation() = assertLowers(
        "\"\"\"\n  #{{1, 2, 3}}\n  \"\"\"",
        "1.11.4" to """{:<<>>, [delimiter: "\"\"\"", line: 1, column: 1], [{:"::", [line: 2, column: 1], [{{:., [line: 2, column: 1], [Kernel, :to_string]}, [closing: [line: 2, column: 12], line: 2, column: 1], [{:{}, [closing: [line: 2, column: 11], line: 2, column: 3], [1, 2, 3]}]}, {:binary, [line: 2, column: 1], nil}]}, "\n"]}""",
        "1.12.3" to """{:<<>>, [delimiter: "\"\"\"", line: 1, column: 1], ["", {:"::", [line: 2, column: 3], [{{:., [line: 2, column: 3], [Kernel, :to_string]}, [closing: [line: 2, column: 14], line: 2, column: 3], [{:{}, [closing: [line: 2, column: 13], line: 2, column: 5], [1, 2, 3]}]}, {:binary, [line: 2, column: 3], nil}]}, "\n"]}""",
    )

    fun testACharListHeredocIsAList() = assertLowers("'''\n  a\n  '''", "~c\"a\\n\"")

    fun testAHeredocKeepsItsBlankAndOutdentedLines() = assertLowers(
        "\"\"\"\n  a\n\n \n b\nc\n  \"\"\"",
        "\"a\\n\\n\\nb\\nc\\n\""
    )

    /** Elixir 1.20 rejects a raw form feed in a string; before that, `trim_space/2` left it in the line's text. */
    fun testAHeredocLineKeepsAFormFeedAfterItsIndentation() = assertLowers(
        "\"\"\"\n \u000Ca\n  \"\"\"",
        OLDEST to "\"\\fa\\n\""
    )

    fun testAnInterpolatedSigil() = assertLowers(
        """~s(a#{1})""",
        "1.15.8" to """{:sigil_s, [delimiter: "(", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["a", {:"::", [line: 1, column: 5], [{{:., [line: 1, column: 5], [Kernel, :to_string]}, [closing: [line: 1, column: 8], line: 1, column: 5], [1]}, {:binary, [line: 1, column: 5], nil}]}]}, []]}""",
        "1.16.3" to """{:sigil_s, [delimiter: "(", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["a", {:"::", [line: 1, column: 5], [{{:., [line: 1, column: 5], [Kernel, :to_string]}, [from_interpolation: true, closing: [line: 1, column: 8], line: 1, column: 5], [1]}, {:binary, [line: 1, column: 5], nil}]}]}, []]}""",
    )

    fun testASigilHeredocHasItsIndentation() = assertLowers(
        "~S\"\"\"\n  x\n  \"\"\"",
        """{:sigil_S, [delimiter: "\"\"\"", line: 1, column: 1], [{:<<>>, [indentation: 2, line: 1, column: 1], ["x\n"]}, []]}"""
    )

    fun testASigilsModifiersAreACharList() = assertLowers(
        "~r/x/i",
        """{:sigil_r, [delimiter: "/", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["x"]}, ~c"i"]}"""
    )

    fun testAListsTrailingKeywordsArePairs() = assertLowers("[1, a: 2]", "[1, {:a, 2}]")

    fun testAQuotedKeywordKeyIsAnAtom() = assertLowers("""["a b": 1]""", """["a b": 1]""")

    fun testAnInterpolatedKeywordKeyHasItsDelimiterFrom118() = assertLowers(
        """["a#{1}": 1]""",
        "1.17.3" to """[{{{:., [line: 1, column: 2], [:erlang, :binary_to_atom]}, [format: :keyword, line: 1, column: 2], [{:<<>>, [line: 1, column: 2], ["a", {:"::", [line: 1, column: 4], [{{:., [line: 1, column: 4], [Kernel, :to_string]}, [from_interpolation: true, closing: [line: 1, column: 7], line: 1, column: 4], [1]}, {:binary, [line: 1, column: 4], nil}]}]}, :utf8]}, 1}]""",
        "1.18.4" to """[{{{:., [line: 1, column: 2], [:erlang, :binary_to_atom]}, [delimiter: "\"", format: :keyword, line: 1, column: 2], [{:<<>>, [line: 1, column: 2], ["a", {:"::", [line: 1, column: 4], [{{:., [line: 1, column: 4], [Kernel, :to_string]}, [from_interpolation: true, closing: [line: 1, column: 7], line: 1, column: 4], [1]}, {:binary, [line: 1, column: 4], nil}]}]}, :utf8]}, 1}]""",
    )

    fun testAnEmptyTupleHasItsClosing() = assertLowers("{}", "{:{}, [closing: [line: 1, column: 2], line: 1, column: 1], []}")

    fun testAOneTupleHasItsClosing() = assertLowers("{1}", "{:{}, [closing: [line: 1, column: 3], line: 1, column: 1], [1]}")

    fun testAPairIsItsOwnTerm() = assertLowers("{1, 2}", "{1, 2}")

    fun testATupleOpenedBeforeANewlineCountsIt() = assertLowers(
        "{\n1, 2, 3}",
        "{:{}, [newlines: 1, closing: [line: 2, column: 8], line: 1, column: 1], [1, 2, 3]}"
    )

    fun testAMapIsAtItsPercentFrom117() = assertLowers(
        "%{a: 1}",
        "1.16.3" to "{:%{}, [closing: [line: 1, column: 7], line: 1, column: 2], [a: 1]}",
        "1.17.3" to "{:%{}, [closing: [line: 1, column: 7], line: 1, column: 1], [a: 1]}",
    )

    fun testAMapKeyWithMetadataHasItsAssociationFrom118() = assertLowers(
        "%{{1, 2, 3} => 1}",
        "1.17.3" to "{:%{}, [closing: [line: 1, column: 17], line: 1, column: 1], [{{:{}, [closing: [line: 1, column: 11], line: 1, column: 3], [1, 2, 3]}, 1}]}",
        "1.18.4" to "{:%{}, [closing: [line: 1, column: 17], line: 1, column: 1], [{{:{}, [assoc: [line: 1, column: 13], closing: [line: 1, column: 11], line: 1, column: 3], [1, 2, 3]}, 1}]}",
    )

    fun testAMapUpdateIsAPipe() = assertLowers(
        "%{%{} | a: 1}",
        "1.16.3" to "{:%{}, [closing: [line: 1, column: 13], line: 1, column: 2], [{:|, [line: 1, column: 7], [{:%{}, [closing: [line: 1, column: 5], line: 1, column: 4], []}, [a: 1]]}]}",
        "1.17.3" to "{:%{}, [closing: [line: 1, column: 13], line: 1, column: 1], [{:|, [line: 1, column: 7], [{:%{}, [closing: [line: 1, column: 5], line: 1, column: 3], []}, [a: 1]]}]}",
    )

    fun testAStructsMapIsAtItsCurly() = assertLowers(
        "%Foo{a: 1}",
        "1.12.3" to "{:%, [line: 1, column: 1], [{:__aliases__, [line: 1, column: 2], [:Foo]}, {:%{}, [closing: [line: 1, column: 10], line: 1, column: 5], [a: 1]}]}",
        "1.20.4" to "{:%, [line: 1, column: 1], [{:__aliases__, [last: [line: 1, column: 2], line: 1, column: 2], [:Foo]}, {:%{}, [closing: [line: 1, column: 10], line: 1, column: 5], [a: 1]}]}",
    )

    fun testABitStringHasItsClosing() =
        assertLowers("<<1, 2>>", "{:<<>>, [closing: [line: 1, column: 7], line: 1, column: 1], [1, 2]}")

    fun testAnUpdatePipeHasTheNewlinesAfterIt() = assertLowers(
        "%{%{} |\n a: 1}",
        "1.11.4" to "{:%{}, [closing: [line: 2, column: 6], line: 1, column: 2], [{:|, [newlines: 1, line: 1, column: 7], [{:%{}, [closing: [line: 1, column: 5], line: 1, column: 4], []}, [a: 1]]}]}",
        "1.20.4" to "{:%{}, [closing: [line: 2, column: 6], line: 1, column: 1], [{:|, [newlines: 1, line: 1, column: 7], [{:%{}, [closing: [line: 1, column: 5], line: 1, column: 3], []}, [a: 1]]}]}",
    )

    fun testAnUpdatePipeHasTheNewlinesBeforeIt() = assertLowers(
        "%{%{}\n| a: 1}",
        "1.11.4" to "{:%{}, [closing: [line: 2, column: 7], line: 1, column: 2], [{:|, [newlines: 1, line: 2, column: 1], [{:%{}, [closing: [line: 1, column: 5], line: 1, column: 4], []}, [a: 1]]}]}",
        "1.20.4" to "{:%{}, [closing: [line: 2, column: 7], line: 1, column: 1], [{:|, [newlines: 1, line: 2, column: 1], [{:%{}, [closing: [line: 1, column: 5], line: 1, column: 3], []}, [a: 1]]}]}",
    )

    fun testAnUpdatePipesNewlinesAfterItReplaceThoseBeforeIt() = assertLowers(
        "%{%{}\n\n|\n a: 1}",
        "1.16.3" to "{:%{}, [closing: [line: 4, column: 6], line: 1, column: 2], [{:|, [newlines: 1, line: 3, column: 1], [{:%{}, [closing: [line: 1, column: 5], line: 1, column: 4], []}, [a: 1]]}]}",
        "1.17.3" to "{:%{}, [closing: [line: 4, column: 6], line: 1, column: 1], [{:|, [newlines: 1, line: 3, column: 1], [{:%{}, [closing: [line: 1, column: 5], line: 1, column: 3], []}, [a: 1]]}]}",
    )

    fun testAnUpdatePipeHasTheNewlinesBeforeItAfterASigil() = assertLowers(
        "%{~c\"a\"\n| a: 1}",
        "1.16.3" to "{:%{}, [closing: [line: 2, column: 7], line: 1, column: 2], [{:|, [newlines: 1, line: 2, column: 1], [{:sigil_c, [delimiter: \"\\\"\", line: 1, column: 3], [{:<<>>, [line: 1, column: 3], [\"a\"]}, []]}, [a: 1]]}]}",
        "1.17.3" to "{:%{}, [closing: [line: 2, column: 7], line: 1, column: 1], [{:|, [newlines: 1, line: 2, column: 1], [{:sigil_c, [delimiter: \"\\\"\", line: 1, column: 3], [{:<<>>, [line: 1, column: 3], [\"a\"]}, []]}, [a: 1]]}]}",
    )

    fun testAnUpdatePipeAfterAnEscapedNewlineCharacter() = assertLowers(
        "%{?\\\n| a: 1}",
        "1.18.4" to "{:%{}, [closing: [line: 1, column: 12], line: 1, column: 1], [{:|, [line: 1, column: 6], [10, [a: 1]]}]}",
        "1.19.5" to "{:%{}, [closing: [line: 2, column: 7], line: 1, column: 1], [{:|, [line: 2, column: 1], [10, [a: 1]]}]}",
    )

    fun testAStructUpdatePipeHasItsNewlines() = assertLowers(
        "%Foo{%{} |\n\n a: 1}",
        "1.11.4" to "{:%, [line: 1, column: 1], [{:__aliases__, [line: 1, column: 2], [:Foo]}, {:%{}, [closing: [line: 3, column: 6], line: 1, column: 5], [{:|, [newlines: 2, line: 1, column: 10], [{:%{}, [closing: [line: 1, column: 8], line: 1, column: 7], []}, [a: 1]]}]}]}",
        "1.20.4" to "{:%, [line: 1, column: 1], [{:__aliases__, [last: [line: 1, column: 2], line: 1, column: 2], [:Foo]}, {:%{}, [closing: [line: 3, column: 6], line: 1, column: 5], [{:|, [newlines: 2, line: 1, column: 10], [{:%{}, [closing: [line: 1, column: 8], line: 1, column: 6], []}, [a: 1]]}]}]}",
    )

    fun testAParenthesizedLeftAliasKeepsItsMetadata() = assertLowers(
        "(Foo).Bar",
        "1.12.3" to "{:__aliases__, [line: 1, column: 2], [:Foo, :Bar]}",
        "1.17.3" to "{:__aliases__, [last: [line: 1, column: 7], line: 1, column: 2], [:Foo, :Bar]}",
        "1.18.4" to "{:__aliases__, [parens: [line: 1, column: 1, closing: [line: 1, column: 5]], last: [line: 1, column: 7], line: 1, column: 2], [:Foo, :Bar]}",
        "1.20.4" to "{:__aliases__, [parens: [closing: [line: 1, column: 5], line: 1, column: 1], last: [line: 1, column: 7], line: 1, column: 2], [:Foo, :Bar]}",
    )

    fun testAParenthesizedLeftAliasKeepsItsEndOfExpression() = assertLowers(
        "(Foo\n).Bar",
        "1.16.3" to "{:__aliases__, [last: [line: 2, column: 3], line: 1, column: 2], [:Foo, :Bar]}",
        "1.17.3" to "{:__aliases__, [end_of_expression: [newlines: 1, line: 1, column: 5], last: [line: 2, column: 3], line: 1, column: 2], [:Foo, :Bar]}",
        "1.20.4" to "{:__aliases__, [parens: [closing: [line: 2, column: 1], line: 1, column: 1], end_of_expression: [newlines: 1, line: 1, column: 5], last: [line: 2, column: 3], line: 1, column: 2], [:Foo, :Bar]}",
    )

    fun testADoubledBackslashBeforeANewlineInALiteralSigilIsKept() = assertLowers(
        "~S(a\\\\\nb)",
        """{:sigil_S, [delimiter: "(", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], ["a\\\\\nb"]}, []]}""",
    )

    /** Elixir's tokenizer rejects each of these, so there is no quoted form to compare with. */
    fun testANumberElixirRejectsIsBrokenCode() = assertEachBroken(
        "0b2", "0o9", "0x1g", "0b12", "0z1", "0B1", "0X1", "123abc", "1e10", "1.2a", "1.0e400", "1.0e+400", "2.0e308",
        "1__0", "1_", "0b_1", "0b", "0x", "1.0e",
    )

    fun testDigitGroupsApartByWhitespaceAreBrokenCode() =
        assertEachBroken("1 2", "1_0 2", "1.0 2", "1.0e1 2", "1 2.0", "1.0 2 3", "1.0 2.0", "[1.0 2]", "1 + 2 3")

    /** A keyword after a number is read as the word it is, so the number is valid. */
    fun testAKeywordAfterANumberIsTheKeyword() {
        assertLowers("0b1and 1", "{:and, [line: 1, column: 4], [1, 1]}")
        assertLowers("0o7and 1", "{:and, [line: 1, column: 4], [7, 1]}")
        assertLowers("0b1or 1", "{:or, [line: 1, column: 4], [1, 1]}")
        assertLowers("0b1in [1]", "{:in, [line: 1, column: 4], [1, [1]]}")
    }

    /** `and`'s `a` is a hexadecimal digit, so Elixir reads `0x1a` and then `nd`. */
    fun testAKeywordAfterAHexadecimalNumberIsPartOfTheNumber() = assertBroken("0x1and 1")

    fun testANumberElixirAcceptsLowersAsItsValue() {
        assertLowers("0b1_0", "2")
        assertLowers("1_000", "1000")
        assertLowers("1.0e-400", "0.0")
        assertLowers("1.7976931348623157e308", "1.7976931348623157e308")
    }

    /**
     * The lexer reads `0or` as an octal number with the digits `r`. Before 1.12 Elixir reads `0` and then `or`, so
     * [rejectedNumber] accepts it, but those digits have no value to lower.
     */
    fun testDigitsThatAreNoValueAreBrokenCode() = assertEachBroken("0or", "0or 1", "0or:")

    /** The digits of `0b2` are no number of their own: they are asked about their number, never alone. */
    fun testDigitsOutsideTheirBaseAreBrokenCode() {
        val lowered = lower("0b2") { PsiTreeUtil.findChildOfType(it, Digits::class.java)!! }

        assertEquals(ERROR, placeholderReasons(lowered))
    }

    /** Each of [codes] is broken code at the oldest and newest Elixir; a failure lists every pair that is not. */
    private fun assertEachBroken(vararg codes: String) = assertEquals(
        emptyList<Pair<String, String>>(),
        codes
            .flatMap { code -> listOf(OLDEST, NEWEST).map { code to it } }
            .filterNot { (code, version) -> isBroken(lower(code, version)) },
    )
}
