package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase

/** Parameter Info describes a function whichever form declares it, not only `def`. */
class FormsParameterInfoTest : PlatformTestCase() {
    fun testAnEExFunctionShowsItsArgumentNames() =
        assertEquals(listOf("a, b"), shownFromEEx("[:a, :b]"))

    fun testAnEExFunctionOfAssignsShowsAssigns() =
        assertEquals(listOf("assigns"), shownFromEEx("[:assigns]"))

    fun testAnEExFunctionWithoutArgumentsShowsNoParameters() =
        assertEquals(listOf("<no parameters>"), shownFromEEx("[]"))

    fun testEmbedTemplateShowsAssigns() =
        assertEquals(listOf("assigns"), shownFromEmbed("embed_template(:log, \"Log\")", "log_template(<caret>)"))

    fun testEmbedTextShowsNoParameters() =
        assertEquals(listOf("<no parameters>"), shownFromEmbed("embed_text(:error, \"Error\")", "error_text(<caret>)"))

    fun testDefexceptionShowsExceptionTakingAMessage() =
        assertEquals(listOf("message"), shownFromException("E.exception(<caret>)"))

    fun testDefexceptionShowsMessageTakingTheException() =
        assertEquals(listOf("exception"), shownFromException("E.message(<caret>)"))

    /** The user's own `def` overrides the generated `exception/1`, so it is the one signature. */
    fun testAUsersExceptionOverridesTheGeneratedOne() =
        assertEquals(
            listOf("msg"),
            shownFromException("E.exception(<caret>)", "def exception(msg), do: %E{message: msg}")
        )

    /** A `@callback` declares a contract, not a function body, so it does not replace the generated function. */
    fun testACallbackDoesNotOverrideTheGeneratedFunction() =
        assertEquals(
            listOf("exception"),
            shownFromException("E.message(<caret>)", "@callback message(term()) :: String.t()")
        )

    fun testADelegatedOperatorShowsItsOperands() {
        myFixture.configureByText(
            "operator.ex",
            """
            defmodule Operators do
              defdelegate left <> right, to: Target
            end

            defmodule Caller do
              def run(a), do: Operators.<>(a<caret>)
            end
            """.trimIndent()
        )

        assertEquals(listOf("left, right"), myFixture.parameterInfoSignaturesAtCaret())
    }

    /** The primary list of `unquote(:ab)` names the function; a head with no secondary list takes no parameters. */
    fun testAFunctionNamedByAnUnquotedAtomWithNoParametersShowsNone() {
        myFixture.configureByText(
            "unquoted.ex",
            """
            defmodule Unquoted do
              def unquote(:ab), do: 1

              def run, do: ab(<caret>)
            end
            """.trimIndent()
        )

        assertEquals(listOf("<no parameters>"), myFixture.parameterInfoSignaturesAtCaret())
    }

    /** Elixir writes a signature on one line without comments, so a comment inside a parameter is not shown. */
    fun testACommentInsideAParameterIsNotShown() {
        myFixture.configureByText(
            "comment.ex",
            """
            defmodule Pairs do
              def pair({a, # the first
                        b}), do: {a, b}

              def calls(t), do: pair(t<caret>)
            end
            """.trimIndent()
        )

        assertEquals(listOf("{a, b}"), myFixture.parameterInfoSignaturesAtCaret())
    }

    fun testADefaultValueKeepsItsLiteralsAsWritten() {
        myFixture.configureByText(
            "literal.ex",
            """
            defmodule Greeter do
              def greet(name, greeting \\ # said first
                  "hello   there"), do: {name, greeting}

              def calls(n), do: greet(n<caret>)
            end
            """.trimIndent()
        )

        assertEquals(listOf("name, greeting \\\\ \"hello   there\""), myFixture.parameterInfoSignaturesAtCaret())
    }

    private fun shownFromEEx(arguments: String): List<String> {
        myFixture.configureByText(
            "eex.ex",
            """
            defmodule EEx do
              defmacro function_from_string(kind, name, source, args \\ [], options \\ []) do
                quote do
                  unquote(kind)
                  unquote(name)
                end
              end
            end

            defmodule Templates do
              require EEx

              EEx.function_from_string(:def, :sample, "<%= a %><%= b %>", $arguments)

              def run, do: sample(<caret>)
            end
            """.trimIndent()
        )

        return myFixture.parameterInfoSignaturesAtCaret()
    }

    private fun shownFromEmbed(embed: String, call: String): List<String> {
        myFixture.configureByText(
            "embed.ex",
            """
            defmodule Mix.Generator do
              defmacro embed_template(name, contents), do: {name, contents}
              defmacro embed_text(name, contents), do: {name, contents}
            end

            defmodule Embedded do
              require Mix.Generator

              Mix.Generator.$embed

              def run, do: $call
            end
            """.trimIndent()
        )

        return myFixture.parameterInfoSignaturesAtCaret()
    }

    private fun shownFromException(call: String, body: String = ""): List<String> {
        myFixture.configureByText(
            "exception.ex",
            """
            defmodule E do
              defexception [:message]
              $body
            end

            defmodule Caller do
              def run, do: $call
            end
            """.trimIndent()
        )

        return myFixture.parameterInfoSignaturesAtCaret()
    }
}
