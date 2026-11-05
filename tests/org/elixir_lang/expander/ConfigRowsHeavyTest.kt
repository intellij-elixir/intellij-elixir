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

    fun testAJoinOfTwo() = assertEquals("k=\"a/b\"", keys("config :app, k: Path.join(\"a\", \"b\")\n", "k"))

    fun testAJoinOfAList() = assertEquals("k=\"a/b/c\"", keys("config :app, k: Path.join([\"a\", \"b\", \"c\"])\n", "k"))

    fun testAJoinOfOne() = assertEquals("k=\"a\"", keys("config :app, k: Path.join([\"a\"])\n", "k"))

    fun testAJoinOfTrailingSeparators() = assertEquals("k=\"a/b\"", keys("config :app, k: Path.join(\"a/\", \"/b/\")\n", "k"))

    fun testAJoinOfAnEmptyPath() = assertEquals("k=\"a\"", keys("config :app, k: Path.join(\"\", \"a\")\n", "k"))

    fun testABasename() = assertEquals("k=\"b.c\"", keys("config :app, k: Path.basename(\"a/b.c\")\n", "k"))

    fun testABasenameLessAnExtension() = assertEquals("k=\"b\"", keys("config :app, k: Path.basename(\"a/b.c\", \".c\")\n", "k"))

    fun testADirname() = assertEquals("k=\"a\"", keys("config :app, k: Path.dirname(\"a/b.c\")\n", "k"))

    fun testASplit() = assertEquals("k=[\"a\", \"b\", \"c\"]", keys("config :app, k: Path.split(\"a/b/c\")\n", "k"))

    fun testAnExpansionAgainstTheConfigDirectory() =
        assertEquals("k=\"a\"", keys("config :app, k: Path.expand(\"../a\", __DIR__) |> Path.basename()\n", "k"))

    fun testAnImportOfAJoinedPath() =
        assertEquals(
            "k=1",
            keys("import_config Path.join(\"sub\", \"o.exs\")\n", "k", extra = arrayOf("config/sub/o.exs" to "import Config\nconfig :app, k: 1\n")),
        )

    /** [call] raises in Elixir, so its rescue gives the key. */
    private fun assertRaises(call: String) =
        assertEquals("k=:raised k1=1", keyBeside("(try do $call rescue _ -> :raised end)"))
}
