package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionNavigationHandler
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionPayload
import com.intellij.codeInsight.hints.declarative.SymbolPointerInlayActionPayload
import com.intellij.openapi.application.ReadAction
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.model.psi.module.ModuleSymbol
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import java.util.concurrent.Callable

@Suppress("UnstableApiUsage")
class ModuleAttributeValueHintsProviderTest : InlayHintsTestCase() {
    override val providerId = "elixir.module.attribute.value"

    override fun provider(): InlayHintsProvider = ModuleAttributeValueHintsProvider()

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testItIsOnByDefault() {
        val info = providerInfo()

        assertInstanceOf(info.provider, ModuleAttributeValueHintsProvider::class.java)
        assertTrue(info.isEnabledByDefault)
    }

    fun testALiteralShowsAsWritten() = assertHints(
        """
        defmodule Timeouts do
          @timeout 5000
          @m Foo
          @config [key: 1]
          @atom :a
          @map %{a: "b"}

          def timeout, do: @timeout/*<# 5000 #>*/
          def new, do: %@m/*<# Foo #>*/{}
          def key, do: @config/*<# [key: 1] #>*/[:key]
          def atom, do: @atom/*<# :a #>*/
          def map, do: @map/*<# %{a: "b"} #>*/
        end
        """
    )

    fun testWhatIsNotALiteralShowsNothing() = assertHints(
        """
        defmodule Values do
          @negative -1
          @call f(1)
          @sigil ~w(a b)
          Module.register_attribute(__MODULE__, :acc, accumulate: true)
          @acc 1

          def negative, do: @negative
          def call, do: @call
          def sigil, do: @sigil
          def acc, do: @acc
        end
        """
    )

    fun testAnAccumulatingAttributeReadInAnExpressionShowsNothing() = assertHints(
        """
        defmodule Values do
          Module.register_attribute(__MODULE__, :acc, accumulate: true)
          @acc 1

          def sum, do: Enum.sum(@acc)
          def bound, do: (x = @acc; x)
          def call, do: length(@acc)
        end
        """
    )

    fun testADeclarationShowsNothing() = assertHints(
        """
        defmodule Declared do
          @timeout 5000
          @timeout 6000
          @timeout(7000)
        end
        """
    )

    fun testAQuotedLiteralAcrossLinesShowsNothing() = assertHints(
        """
        defmodule Multiline do
          @heredoc ${"\"\"\""}
          text
          ${"\"\"\""}
          @charlist 'a
          b'
          @atom :"a
          b"

          def heredoc, do: @heredoc
          def charlist, do: @charlist
          def atom, do: @atom
        end
        """
    )

    fun testSpacesInQuotedContentsShowAsWritten() = assertHints(
        """
        defmodule Spaced do
          @string "x   y"
          @atom :"a   b"

          def string, do: @string/*<# "x   y" #>*/
          def atom, do: @atom/*<# :"a   b" #>*/
        end
        """
    )

    fun testFortyCharactersShowInFull() {
        val value = "\"" + "a".repeat(38) + "\""

        assertLongValueShows(value, value.take(30), value.drop(30))
    }

    fun testFortyOneCharactersShowThirtyNineAndAnEllipsis() {
        val value = "\"" + "a".repeat(39) + "\""
        val shown = value.take(39) + Typography.ellipsis

        assertLongValueShows(value, shown.take(30), shown.drop(30))
    }

    /** The platform cuts a text node by UTF-16 length, so a character outside the BMP counts twice. */
    fun testCharactersOutsideTheBmpAreNotCut() {
        val value = "\"" + "\uD835\uDC9C".repeat(16) + "\""

        assertLongValueShows(value, value.take(29), value.drop(29))
    }

    fun testAMultiLineListShowsOnOneLineWithoutComments() = assertHints(
        """
        defmodule Listed do
          @list [
            # first
            1,
            2 # second
          ]

          def list, do: @list/*<# [ 1, 2 ] #>*/
        end
        """
    )

    fun testForShowsItsModule() = assertHints(
        """
        defimpl Inspect, for: Tuple do
          def inspect(_, _), do: @for/*<# Tuple #>*/
          def module, do: @(for)/*<# Tuple #>*/
        end
        """
    )

    fun testForWithoutForShowsTheEnclosingModule() = assertHints(
        """
        defmodule Outer do
          defimpl Inspect do
            def inspect(_, _), do: @for/*<# Outer #>*/
          end
        end
        """
    )

    fun testForAsAnOperandShowsItsModule() = assertHints(
        """
        defimpl Inspect, for: Tuple do
          def inspect(term, _) do
            if term == @for/*<# Tuple #>*/ do
              "{}"
            end
          end
        end
        """
    )

    fun testForWithAForListShowsNothing() = assertHints(
        """
        defimpl Inspect, for: [Tuple, List] do
          def inspect(_, _), do: @for
        end
        """
    )

    fun testForOfAVariableShowsNothing() = assertHints(
        """
        for target <- [Tuple, List] do
          defimpl Inspect, for: target do
            def inspect(_, _), do: @for
          end
        end
        """
    )

    fun testForOfAnUnquoteFragmentShowsNothing() = assertHints(
        """
        for target <- [Tuple, List] do
          defimpl Inspect, for: unquote(target) do
            def inspect(_, _), do: @for
          end
        end
        """
    )

    fun testForOfAnAtomShowsItsModuleAsElixirWritesIt() = assertHints(
        """
        defimpl Inspect, for: :"target name" do
          def inspect(_, _), do: @for/*<# :"target name" #>*/
        end

        defimpl Inspect, for: :"Elixir.Target Name" do
          def inspect(_, _), do: @for/*<# :"Elixir.Target Name" #>*/
        end

        defimpl Inspect, for: :queue do
          def inspect(_, _), do: @for/*<# :queue #>*/
        end

        defimpl Inspect, for: :"Elixir.Tuple" do
          def inspect(_, _), do: @for/*<# Tuple #>*/
        end
        """
    )

    fun testProtocolShowsItsProtocol() = assertHints(
        """
        defimpl Inspect, for: Tuple do
          def inspect(_, _), do: @protocol/*<# Inspect #>*/
          def protocol, do: @(protocol)/*<# Inspect #>*/
        end
        """
    )

    fun testEveryWayToWriteAReadShows() = assertHints(
        """
        defmodule Timeouts do
          @timeout 5000

          def one, do: @timeout/*<# 5000 #>*/
          def two, do: @ timeout/*<# 5000 #>*/
          def three, do: @(timeout)/*<# 5000 #>*/
        end
        """
    )

    /** A read in a `quote` reads the module the code is injected into, so its value isn't known here. */
    fun testAReadInAQuoteShowsNothing() = assertHints(
        """
        defmodule Lib do
          @timeout 5000

          defmacro __using__(_) do
            quote do
              def timeout, do: @timeout
            end
          end
        end
        """
    )

    fun testAnUnquotedReadInAQuoteShowsItsValue() = assertHints(
        """
        defmodule Lib do
          @timeout 5000

          defmacro __using__(_) do
            quote do
              def timeout, do: unquote(@timeout/*<# 5000 #>*/)
            end
          end
        end
        """
    )

    fun testAnUnquotedReadInAQuoteShowsTheValueOutsideTheQuote() = assertHints(
        """
        defmodule Lib do
          @timeout 5000

          defmacro build do
            quote do
              @timeout 6000
              def timeout, do: unquote(@timeout/*<# 5000 #>*/)
            end
          end
        end
        """
    )

    fun testReadsOfTheMicroSignShow() = assertMicroSignReadsShow("1.14.0")

    fun testReadsOfTheMicroSignShowBefore1_14() = assertMicroSignReadsShow("1.13.0")

    fun testAReadOpensItsDeclaration() = eachExpander {
        val actionData = onlyNode(
            """
            defmodule Timeouts do
              @timeout 5000

              def timeout, do: @timeout
            end
            """
        ).actionData!!

        assertEquals(PsiPointerInlayActionNavigationHandler.HANDLER_ID, actionData.handlerId)
        assertEquals(
            "@timeout 5000",
            ReadAction.nonBlocking(Callable {
                ((actionData.payload as PsiPointerInlayActionPayload).pointer.element as AtUnqualifiedNoParenthesesCall<*>).text
            }).executeSynchronously()
        )
    }

    fun testForOpensItsModule() = assertOpensModule(
        """
        defmodule Target do
        end

        defimpl Inspect, for: Target do
          def inspect(_, _), do: @for
        end
        """,
        "Target"
    )

    fun testProtocolOpensItsProtocol() = assertOpensModule(
        """
        defprotocol Describe do
          def describe(term)
        end

        defimpl Describe, for: Tuple do
          def describe(_), do: @protocol
        end
        """,
        "Describe"
    )

    fun testAnUnresolvedForOpensNothing() = eachExpander {
        assertEquals(
            TextNode("Missing.Module", null),
            onlyNode(
                """
                defimpl Inspect, for: Missing.Module do
                  def inspect(_, _), do: @for
                end
                """
            )
        )
    }

    private fun assertOpensModule(text: String, module: String) = eachExpander {
        val node = onlyNode(text)
        val actionData = node.actionData!!

        assertEquals(module, node.text)
        assertEquals(SymbolNavigationHandler.HANDLER_ID, actionData.handlerId)
        assertEquals(
            module,
            ReadAction.nonBlocking(Callable {
                ((actionData.payload as SymbolPointerInlayActionPayload).pointer.dereference() as ModuleSymbol).moduleName
            }).executeSynchronously()
        )
    }

    /** Asserts a read of an attribute set to [value] shows as the text [nodes]. */
    private fun assertLongValueShows(value: String, vararg nodes: String) = assertHints(
        """
        defmodule Long do
          @long $value

          def long, do: @long/*<# ${nodes.joinToString("|")} #>*/
        end
        """
    )

    /** Asserts reads of the micro sign show at Elixir [version]. */
    private fun assertMicroSignReadsShow(version: String) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir(version))

        assertHints(
            "defmodule Timeouts do\n  @µ 5000\n\n  def one, do: @µ/*<# 5000 #>*/\n  def two, do: @µ/*<# 5000 #>*/\nend\n"
        )
    }

    /** The one text node of the one hint over [text]. */
    private fun onlyNode(text: String): TextNode {
        myFixture.configureByText("test.ex", text.trimIndent())

        return textNodes().single().single()
    }
}
