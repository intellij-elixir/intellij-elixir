package org.elixir_lang.expander

/**
 * The BIFs `String.to_integer/1`, `String.to_float/1` and `Path`'s joins reach answer as Erlang does: a value where it
 * gives one, and a raise where it refuses the argument.
 */
class ConfigRowsHeavyTest : ConfigHeavyTestCase() {
    fun testAnInteger() = assertEquals("k=900 k1=1", keyBeside("String.to_integer(\"900\")"))

    fun testAnIntegerWithTwoSignsRaises() = assertRaises("String.to_integer(\"+-1\")")

    fun testAnIntegerWithARepeatedSignRaises() = assertRaises("String.to_integer(\"++1\")")

    fun testAnIntegerOfANonAsciiDigitRaises() = assertRaises("String.to_integer(\"٣\")")

    fun testAFloatPastADoublesRangeRaises() = assertRaises("String.to_float(\"1.0e400\")")

    /** ERTS reads a float's text up to its first NUL. */
    fun testAFloatBeforeANul() =
        assertEquals("k=1.5 k1=1", keyBeside("String.to_float(<<\"1.5\", 0, \"x\">>)"))

    fun testAFloatWithTextBeforeANulRaises() = assertRaises(":erlang.binary_to_float(<<\"1.5x\", 0>>)")

    fun testAFloatWithACommaForItsPoint() = assertEquals("k=1.5 k1=1", keyBeside("String.to_float(\"1,5\")"))

    fun testAJoinOfTwo() = assertEquals("k=\"a/b\"", keyAlone("Path.join(\"a\", \"b\")"))

    fun testAJoinOfAList() = assertEquals("k=\"a/b/c\"", keyAlone("Path.join([\"a\", \"b\", \"c\"])"))

    fun testAJoinOfOne() = assertEquals("k=\"a\"", keyAlone("Path.join([\"a\"])"))

    fun testAJoinOfTrailingSeparators() = assertEquals("k=\"a/b\"", keyAlone("Path.join(\"a/\", \"/b/\")"))

    fun testAJoinOfAnEmptyPath() = assertEquals("k=\"a\"", keyAlone("Path.join(\"\", \"a\")"))

    fun testABasename() = assertEquals("k=\"b.c\"", keyAlone("Path.basename(\"a/b.c\")"))

    fun testABasenameLessAnExtension() = assertEquals("k=\"b\"", keyAlone("Path.basename(\"a/b.c\", \".c\")"))

    fun testADirname() = assertEquals("k=\"a\"", keyAlone("Path.dirname(\"a/b.c\")"))

    fun testASplit() = assertEquals("k=[\"a\", \"b\", \"c\"]", keyAlone("Path.split(\"a/b/c\")"))

    fun testAnExpansionAgainstTheConfigDirectory() =
        assertEquals("k=\"a\"", keyAlone("Path.expand(\"../a\", __DIR__) |> Path.basename()"))

    fun testAnImportOfAJoinedPath() =
        assertEquals(
            "k=1",
            keys("import_config Path.join(\"sub\", \"o.exs\")\n", "k", extra = arrayOf("config/sub/o.exs" to "import Config\nconfig :app, k: 1\n")),
        )

    /** [call] raises in Elixir, so its rescue gives the key. */
    private fun assertRaises(call: String) =
        assertEquals("k=:raised k1=1", keyBeside("(try do $call rescue _ -> :raised end)"))
}
