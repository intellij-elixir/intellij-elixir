package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver

/**
 * [RecordedSignature] where no compile can check it: an older release's answer, a function Elixir records nothing for,
 * and what the plugin cannot evaluate.
 */
class RecordedSignatureTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } finally {
            super.tearDown()
        }
    }

    fun testVarBangBeforeItsClause() {
        assertEquals("vb(arg)", recorded("def vb(var!(v)), do: v", "vb/1", "1.13.4"))
        assertEquals("vb(v)", recorded("def vb(var!(v)), do: v", "vb/1", "1.14.0"))
    }

    fun testFnBeforeItsClause() {
        assertEquals("fn", recorded("defmacro unquote(:fn)(clauses), do: clauses", "fn/1", "1.12.3"))
        assertEquals("fn(clauses)", recorded("defmacro unquote(:fn)(clauses), do: clauses", "fn/1", "1.13.0"))
    }

    fun testPrivateFunctionUsesThePort() =
        assertEquals("p(m, list)", recorded("defp p(%{} = m, [h | t]), do: {m, h, t}", "p/2"))

    fun testAnAttributeThePluginCannotReadIsAnArgument() =
        assertEquals("f(arg)", recorded("@x Foo.bar()\ndef f(@x), do: :ok", "f/1"))

    fun testADefaultKeepsAnAttributeThePluginCannotRead() =
        assertEquals("g(a \\\\ @x)", recorded("@x Foo.bar()\ndef g(a \\\\ @x), do: a", "g/1"))

    /** Elixir prints the struct's every field, which only its module's compile knows. */
    fun testADefaultKeepsAStructAttributeAsWritten() =
        assertEquals("g(a \\\\ @s)", recorded("defstruct [:a]\n@s %M{}\ndef g(a \\\\ @s), do: a", "g/1"))

    /** Elixir prints a float with its digits grouped, `100_000.0` for `100000.0`, which the plugin does not write. */
    fun testADefaultKeepsAFloatAttributeAsWritten() =
        assertEquals("g(a \\\\ @f)", recorded("@f 100000.0\ndef g(a \\\\ @f), do: a", "g/1"))

    /** Elixir prints `[104, 105]` as a charlist, `~c"hi"`, which the plugin does not write. */
    fun testADefaultKeepsAnIntegerListAttributeAsWritten() =
        assertEquals("g(a \\\\ @c)", recorded("@c [104, 105]\ndef g(a \\\\ @c), do: a", "g/1"))

    /** Elixir prints `[7, 8]` as a charlist, `~c"\a\b"`, where the plugin's printer writes a list. */
    fun testADefaultKeepsAnAsciiPrintableIntegerListAttributeAsWritten() =
        assertEquals("g(a \\\\ @c)", recorded("@c [7, 8]\ndef g(a \\\\ @c), do: a", "g/1"))

    /** Elixir prints `[233]` as a list, where the plugin's printer writes a charlist. */
    fun testADefaultKeepsAUnicodePrintableIntegerListAttributeAsWritten() =
        assertEquals("g(a \\\\ @c)", recorded("@c [233]\ndef g(a \\\\ @c), do: a", "g/1"))

    /** Elixir prints a map with its keys sorted, `%{a: 2, b: 1}` for `%{b: 1, a: 2}`, which the plugin does not write. */
    fun testADefaultKeepsANonEmptyMapAttributeAsWritten() =
        assertEquals("g(a \\\\ @m)", recorded("@m %{b: 1, a: 2}\ndef g(a \\\\ @m), do: a", "g/1"))

    /** Elixir prints a binary that is not UTF-8 as a string with escapes, `"\xFF"`, which the plugin does not write. */
    fun testADefaultKeepsANonUtf8BinaryAttributeAsWritten() =
        assertEquals("g(a \\\\ @b)", recorded("@b \"\\xFF\"\ndef g(a \\\\ @b), do: a", "g/1"))

    /** Elixir prints an alias in a list as its `alias` names it, `[X.Point]`, which the plugin reads only outside one. */
    fun testADefaultKeepsAnAliasInAListAttributeAsWritten() =
        assertEquals(
            "g(a \\\\ @l)",
            recorded("alias X.Point\n@l [Point]\ndef g(a \\\\ @l), do: a", "g/1")
        )

    /** Elixir records every write of an accumulating attribute, `[:second, :first]`, which the plugin does not gather. */
    fun testADefaultKeepsARegisteredAccumulatingAttributeAsWritten() =
        assertEquals(
            "g(a \\\\ @opts)",
            recorded(
                "Module.register_attribute(__MODULE__, :opts, accumulate: true)\n@opts :first\n@opts :second\n" +
                    "def g(a \\\\ @opts), do: a",
                "g/1"
            )
        )

    fun testADefaultKeepsAnAttributeRegisteredWithAnAccumulatingListAsWritten() =
        assertEquals(
            "g(a \\\\ @opts)",
            recorded(
                "Module.register_attribute(__MODULE__, :opts, [persist: true, accumulate: true])\n@opts :first\n" +
                    "def g(a \\\\ @opts), do: a",
                "g/1"
            )
        )

    /** Options the plugin cannot read may accumulate the attribute, as `[accumulate: true]` in a variable does. */
    fun testADefaultKeepsAnAttributeRegisteredWithOptionsThePluginCannotReadAsWritten() =
        assertEquals(
            "g(a \\\\ @opts)",
            recorded(
                "options = [accumulate: true]\nModule.register_attribute(__MODULE__, :opts, options)\n" +
                    "@opts :first\ndef g(a \\\\ @opts), do: a",
                "g/1"
            )
        )

    /** Elixir records `@behaviour :gen_server` as `[:gen_server]`, as every `@behaviour` accumulates. */
    fun testADefaultKeepsABehaviourAsWritten() =
        assertEquals("k(a \\\\ @behaviour)", recorded("@behaviour :gen_server\ndef k(a \\\\ @behaviour), do: a", "k/1"))

    fun testADefaultKeepsABehaviourNamingAModuleAsWritten() =
        assertEquals("k(a \\\\ @behaviour)", recorded("@behaviour GenServer\ndef k(a \\\\ @behaviour), do: a", "k/1"))

    /** Elixir records `@compile :inline` as `[:inline]`. */
    fun testADefaultKeepsACompileAttributeAsWritten() =
        assertEquals("j(a \\\\ @compile)", recorded("@compile :inline\ndef j(a \\\\ @compile), do: a", "j/1"))

    /** `@nifs` accumulates from 1.19.0-rc.0. */
    fun testADefaultKeepsNifsAsWrittenOnceTheyAccumulate() {
        assertEquals("n(a \\\\ [a: 1])", recorded("@nifs [a: 1]\ndef n(a \\\\ @nifs), do: a", "n/1", "1.18.4"))
        assertEquals("n(a \\\\ @nifs)", recorded("@nifs [a: 1]\ndef n(a \\\\ @nifs), do: a", "n/1", "1.19.0"))
    }

    fun testAnAliasThatNamesNoModuleIsNamedByItsLastSegment() =
        assertEquals("f(thing)", recorded("def f(%Unknown.Thing{}), do: :ok", "f/1"))

    fun testAnEexNameThatIsNoAtomIsArg() {
        myFixture.copyFileToProject("eex.ex")

        assertEquals(
            "n(a, arg)",
            recorded("require EEx\nEEx.function_from_string(:def, :n, \"\", [:a, \"b\"])", "n/2")
        )
    }

    fun testEmbedTemplateTakesAssigns() {
        myFixture.copyFileToProject("mix_generator.ex")

        assertEquals("page_template(assigns)", recorded("import Mix.Generator\nembed_template(:page, \"\")", "page_template/1"))
    }

    fun testEmbedTextTakesNothing() {
        myFixture.copyFileToProject("mix_generator.ex")

        assertEquals("page_text()", recorded("import Mix.Generator\nembed_text(:page, \"\")", "page_text/0"))
    }

    fun testCallbackHasNone() = assertNull(recorded("@callback cb(term) :: term", "cb/1"))

    /** [body] in module `M` at [elixir]'s language level, else the newest: the signature of [nameArity]. */
    private fun recorded(body: String, nameArity: String, elixir: String? = null): String? {
        elixir?.let { ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of(it, null)) }

        val file = myFixture.configureByText("recorded.ex", "defmodule M do\n${body.prependIndent("  ")}\nend\n")

        return recordedIn(file, "M", nameArity)
    }
}
