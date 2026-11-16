package org.elixir_lang.code_insight

import org.elixir_lang.declaration.ExpanderSettingTestCase

/** Parameter Info describes a function whichever form declares it, not only `def`. */
class FormsParameterInfoTest : ExpanderSettingTestCase() {
    fun testAnEExFunctionShowsItsArgumentNames() =
        assertEquals(listOf("a, b"), shownFromEEx("[:a, :b]"))

    fun testAnEExFunctionOfAssignsShowsAssigns() =
        assertEquals(listOf("assigns"), shownFromEEx("[:assigns]"))

    /** `[:a | [:b]]` is the list `[:a, :b]`, so the function takes both. */
    fun testAnEExFunctionShowsTheArgumentNamesOfALiteralTail() =
        assertEquals(listOf("a, b"), shownFromEEx("[:a | [:b]]"))

    /** A tail known only once evaluated leaves the arity open after the names before it, which are shown. */
    fun testAnEExFunctionWithAnOpenTailShowsTheArgumentNamesBeforeIt() =
        assertEquals(listOf("a"), shownFromEEx("[:a | @rest]"))

    /**
     * Two functions of one name, each with an open tail, may be of different arities, so each is its own function, as
     * Quick Documentation lists them.
     */
    fun testEExFunctionsOfOneNameWithOpenTailsAreEachTheirOwnFunction() =
        assertEquals(listOf("a", "b"), shownFromEEx("[:a | @rest]", "[:b | @other]"))

    fun testAnEExFunctionWithoutArgumentsShowsNoParameters() =
        assertEquals(listOf("<no parameters>"), shownFromEEx("[]"))

    /** Bindings the plugin cannot read as a list leave the parameters unknown, so they are not shown as none. */
    fun testAnSdkEExFunctionWhoseBindingsAreNotAListShowsNoEmptyParameterListWithTheExpanderOn() = withTheExpanderOn {
        val shown = myFixture.parameterInfoSignaturesIn(
            "pages.ex",
            """
            defmodule Pages do
              require EEx

              @args [:a, :b]

              EEx.function_from_string(:def, :page, "<%= a %><%= b %>", @args)

              def calls(x), do: page(<caret>x, x)
            end
            """.trimIndent()
        )

        assertFalse("Expected no empty parameter list, got: $shown", "<no parameters>" in shown)
    }

    fun testEmbedTemplateShowsAssigns() =
        assertEquals(listOf("assigns"), shownFromEmbed("embed_template(:log, \"Log\")", "log_template(<caret>)"))

    fun testEmbedTextShowsNoParameters() =
        assertEquals(listOf("<no parameters>"), shownFromEmbed("embed_text(:error, \"Error\")", "error_text(<caret>)"))

    /** With a `:message` field, Elixir's own head is `exception(msg)`. */
    fun testDefexceptionShowsExceptionTakingAMessage() =
        assertEquals(listOf("msg"), shownFromException("E.exception(<caret>)"))

    fun testDefexceptionShowsMessageTakingTheException() =
        assertEquals(listOf("exception"), shownFromException("E.message(<caret>)"))

    /** `defexception @fields` takes the fields the attribute holds. */
    fun testDefexceptionWithFieldsFromAnAttributeShowsExceptionTakingAMessage() =
        assertEquals(listOf("msg"), shownFromException("E.exception(<caret>)", fields = "@fields"))

    fun testDefexceptionWithFieldsFromAnAttributeShowsMessageTakingTheException() =
        assertEquals(listOf("exception"), shownFromException("E.message(<caret>)", fields = "@fields"))

    /** Fields known only once evaluated: `message/1` is still declared, and `exception/1` takes what every one does. */
    fun testDefexceptionWithFieldsNotReadShowsMessageTakingTheException() =
        assertEquals(listOf("exception"), shownFromException("E.message(<caret>)", fields = "fields()"))

    fun testDefexceptionWithFieldsNotReadShowsExceptionTakingArgs() =
        assertEquals(listOf("args"), shownFromException("E.exception(<caret>)", fields = "fields()"))

    /** The user's own `def` overrides the generated `exception/1`, so it is the one signature. */
    fun testAUsersExceptionOverridesTheGeneratedOne() =
        assertEquals(
            listOf("reason"),
            shownFromException("E.exception(<caret>)", "def exception(reason), do: %E{message: reason}")
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
              def pair(t \\ {1, # the first
                             2}), do: t

              def calls(t), do: pair(t<caret>)
            end
            """.trimIndent()
        )

        assertEquals(listOf("t \\\\ {1, 2}"), myFixture.parameterInfoSignaturesAtCaret())
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

    /** Parameter Info at a call of `sample`, which `EEx.function_from_string` defines with each of [argumentLists]. */
    private fun shownFromEEx(vararg argumentLists: String): List<String> {
        val functions = argumentLists.joinToString("\n") { "  EEx.function_from_string(:def, :sample, \"<%= a %><%= b %>\", $it)" }

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
            """.trimIndent() + "\n$functions\n\n  def run, do: sample(<caret>)\nend\n"
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

    private fun shownFromException(call: String, body: String = "", fields: String = "[:message]"): List<String> {
        myFixture.configureByText(
            "exception.ex",
            """
            defmodule E do
              @fields [:message]

              defexception $fields
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
