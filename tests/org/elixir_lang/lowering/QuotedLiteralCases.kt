package org.elixir_lang.lowering

import org.junit.Assert.assertEquals

/**
 * Each text [QuotedLiteralTest] answers at the newest level, and [QuotedLiteralOracleTest] asks the leg's Elixir about,
 * by the `Macro.quoted_literal?/1` clause it reaches.
 */
internal val QUOTED_LITERAL_CASES: List<Pair<String, Boolean>> = listOf(
    // `{:__aliases__, _, args}`
    "Foo.Bar" to true,
    "__MODULE__.A" to true,
    "x.A" to false,
    // `{:%, _, [left, right]}`
    "%URI{path: \"/\"}" to true,
    "%URI{path: x}" to false,
    "%x{}" to false,
    // `{:%{}, _, args}`
    "%{a: 1}" to true,
    "%{\"a\" => [1, 2]}" to true,
    "%{}" to true,
    "%{a => 1}" to false,
    "%{m | a: 1}" to false,
    // `{:{}, _, args}`
    "{1, 2, 3}" to true,
    "{}" to true,
    "{1}" to true,
    "{1, x, 3}" to false,
    // `{:__MODULE__, _, ctx}` when `is_atom(ctx)`
    "__MODULE__" to true,
    "__MODULE__()" to false,
    // `{:<<>>, _, segments}`
    "<<1, \"a\">>" to true,
    "<<>>" to true,
    // `{left, right}`
    "{:a, 1}" to true,
    "{:a, x}" to false,
    // a list
    "[1, :a]" to true,
    "[]" to true,
    "[a: 1, b: \"x\"]" to true,
    "'abc'" to true,
    "[x]" to false,
    "[1 | 2]" to false,
    // an atom, a number or a binary; anything else
    ":a" to true,
    ":\"a b\"" to true,
    "nil" to true,
    "true" to true,
    "1" to true,
    "0x10" to true,
    "1.0" to true,
    "\"s\"" to true,
    "\"\"\"\nheredoc\n\"\"\"" to true,
    "x" to false,
    "f(1)" to false,
    "URI.parse(\"/\")" to false,
    "-1" to false,
    "~w(a b)" to false,
    "\"a#{1}b\"" to false,
    "1 + 2" to false,
    "&f/1" to false,
    "(1; 2)" to false,
    // a segment: an integer or a binary
    "<<1>>" to true,
    "<<\"a\">>" to true,
    "<<1.0>>" to false,
    // a segment `term :: modifier`
    "<<\"a\"::binary>>" to true,
    "<<1::8>>" to false,
    "<<x::8>>" to false,
    // any other segment
    "<<x>>" to false,
    // a modifier `left - right`
    "<<1::big-size(8)>>" to true,
    "<<1::binary-size(4)>>" to true,
    // `size` and `unit` with an integer
    "<<1::size(8)>>" to true,
    "<<1::unit(8)>>" to true,
    "<<1::size(n)>>" to false,
    // `left * right`, both integers
    "<<1::8*2>>" to true,
    "<<1::size(8)*2>>" to false,
    // a modifier with no arguments, by `:elixir_bitstring.validate_spec/2`
    "<<1::big>>" to true,
    "<<1::utf8>>" to true,
    "<<1::big()>>" to true,
    "<<1::size>>" to false,
    "<<1::foo>>" to false,
    // any other modifier
    "<<1::foo(1)>>" to false,
)

/** Asserts [answer] gives each key in [expected] its answer, with every row shown on a failure. */
internal fun <K> assertEachAnswers(expected: List<Pair<K, Boolean>>, answer: (K) -> Boolean) =
    assertEquals(
        expected.joinToString("\n") { (key, literal) -> "$key: $literal" },
        expected.joinToString("\n") { (key, _) -> "$key: ${answer(key)}" },
    )
