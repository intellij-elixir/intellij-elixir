package org.elixir_lang.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil

/**
 * Quick Documentation heads each function with the signature Elixir records for it, then each `@spec`, then each
 * clause's head as written, with its keyword.
 */
class SignatureHeaderQuickDocumentationTest : QuickDocumentationTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testTheSignatureThenTheSpecThenTheHead() =
        assertHeader(
            """
            defmodule Header.Socket do
              defstruct [:transport_pid]
            end

            defmodule Header do
              alias Header.Socket

              @doc "Connected."
              @spec connected?(Socket.t()) :: boolean
              def connected?(%Socket{transport_pid: pid}), do: pid != nil

              def calls(s), do: connec<caret>ted?(s)
            end
            """,
            "<i>module</i> <b>Header</b>",
            "connected?(socket)",
            "  @spec connected?(Socket.t()) :: boolean",
            "  def connected?(%Socket{transport_pid: pid})"
        )

    fun testNoSpecificationsSectionIsLeft() {
        myFixture.configureByText(
            "sections.ex",
            """
            defmodule Sections do
              @spec add(integer, integer) :: integer
              def add(a, b), do: a + b

              def calls, do: ad<caret>d(1, 2)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertFalse("Expected no Specifications section, got: $documentation", "Specifications" in documentation)
    }

    fun testDefaultsAreKeptInTheSignature() =
        assertHeader(
            """
            defmodule Splits do
              def split(string, pattern \\ " ", options \\ []), do: {string, pattern, options}

              def calls(s), do: spl<caret>it(s)
            end
            """,
            "<i>module</i> <b>Splits</b>",
            """split(string, pattern \\ " ", options \\ [])""",
            """  def split(string, pattern \\ " ", options \\ [])"""
        )

    fun testEachClauseHeadUnderOneSignature() =
        assertHeader(
            """
            defmodule Clauses do
              def m4(a, b), do: {a, b}
              def m4(c, d), do: {c, d}

              def calls(x), do: m<caret>4(x, x)
            end
            """,
            "<i>module</i> <b>Clauses</b>",
            "m4(a, b)",
            "  def m4(a, b)",
            "  def m4(c, d)"
        )

    fun testAPrivateFunction() =
        assertHeader(
            """
            defmodule Private do
              defp p(%{} = m, [h | t]), do: {m, h, t}

              def calls(x), do: <caret>p(x, x)
            end
            """,
            "<i>module</i> <b>Private</b>",
            "p(m, list)",
            "  defp p(%{} = m, [h | t])"
        )

    fun testAMacro() =
        assertHeader(
            """
            defmodule Macros do
              defmacro twice(expression), do: {expression, expression}

              def calls(x), do: twi<caret>ce(x)
            end
            """,
            "<i>module</i> <b>Macros</b>",
            "twice(expression)",
            "  defmacro twice(expression)"
        )

    fun testAGuard() =
        assertHeader(
            """
            defmodule Guards do
              defguard is_pos(x) when x > 0

              def calls(x) when is_p<caret>os(x), do: x
            end
            """,
            "<i>module</i> <b>Guards</b>",
            "is_pos(x)",
            "  defguard is_pos(x) when x > 0"
        )

    fun testADelegation() {
        myFixture.addFileToProject("delegation_target.ex", "defmodule Delegated.Target do\n  def run(x, y), do: {x, y}\nend\n")

        assertHeader(
            """
            defmodule Delegated do
              @doc "Goes."
              defdelegate go(x, y \\ 1), to: Delegated.Target, as: :run

              def calls(x), do: g<caret>o(x)
            end
            """,
            "<i>module</i> <b>Delegated</b>",
            """go(x, y \\ 1)""",
            """  defdelegate go(x, y \\ 1)"""
        )
    }

    fun testAnEexFunctionIsHeadedByItsSignatureAlone() {
        myFixture.copyFileToProject("eex.ex")

        assertHeader(
            """
            defmodule Pages do
              require EEx

              @doc "Renders."
              EEx.function_from_string(:def, :page, "<%= a %><%= b %>", [:_a, :b])

              def calls(x), do: pa<caret>ge(x, x)
            end
            """,
            "<i>module</i> <b>Pages</b>",
            "page(a, b)"
        )
    }

    fun testAnEexFunctionIsHeadedByTheNamesOfALiteralTail() {
        myFixture.copyFileToProject("eex.ex")

        assertHeader(
            """
            defmodule Pages do
              require EEx

              @doc "Renders."
              EEx.function_from_string(:def, :page, "<%= a %><%= b %>", [:a | [:b]])

              def calls(x), do: pa<caret>ge(x, x)
            end
            """,
            "<i>module</i> <b>Pages</b>",
            "page(a, b)"
        )
    }

    /** A tail known only once evaluated leaves no signature to know: the name and the arities it may have stand in. */
    fun testAnEexFunctionWithAnOpenTailIsHeadedByItsNameAndArities() {
        myFixture.copyFileToProject("eex.ex")

        assertHeader(
            """
            defmodule Pages do
              require EEx

              @doc "Renders."
              EEx.function_from_string(:def, :page, "<%= a %>", [:a | @rest])

              def calls(x), do: pa<caret>ge(x)
            end
            """,
            "<i>module</i> <b>Pages</b>",
            "page/1.."
        )
    }

    fun testAnEmbedIsHeadedByItsSignatureAlone() {
        myFixture.copyFileToProject("mix_generator.ex")

        assertHeader(
            """
            defmodule Embeds do
              import Mix.Generator

              @doc "Embedded."
              embed_template(:page, "<%= @a %>")

              def calls(x), do: page_temp<caret>late(x)
            end
            """,
            "<i>module</i> <b>Embeds</b>",
            "page_template(assigns)"
        )
    }

    fun testACallbackKeepsItsAttributeAsItsHead() {
        myFixture.configureByText(
            "callbacks.ex",
            """
            defmodule Callbacks do
              @doc "Calls back."
              @callback cb(term) :: term
            end
            """.trimIndent()
        )

        val provider = ElixirDocumentationProvider()
        val callback = provider.getDocumentationElementForLink(psiManager, "c:Callbacks.cb/1", myFixture.file)
        val documentation = callback?.let { provider.generateDoc(it, null) }.orEmpty()

        assertEquals(documentation, listOf("<i>module</i> <b>Callbacks</b>", "@callback cb(term) :: term"), definition(documentation))
    }

    fun testEachArityLinksItsSignature() {
        myFixture.configureByText(
            "linked.ex",
            """
            defmodule Linked do
              @doc "One."
              def f([h | t]), do: {h, t}

              @doc "Two."
              def f(a, b), do: {a, b}

              def calls(x), do: <caret>f(x)
            end
            """.trimIndent()
        )

        val documentation = assertDefinitionAtCaret(
            "<i>module</i> <b>Linked</b>",
            "<a href=\"#arity-1\">f(list)</a>",
            "  def f([h | t])",
            "<a href=\"#arity-2\">f(a, b)</a>",
            "  def f(a, b)"
        )

        assertTrue("Expected the first entry titled by its signature, got: $documentation", "<a id=\"arity-1\"></a><b>f(list)</b>" in documentation)
        assertTrue("Expected the second entry titled by its signature, got: $documentation", "<a id=\"arity-2\"></a><b>f(a, b)</b>" in documentation)
    }

    /** Declarations whose arities overlap are one entry, which names each full arity by its signature. */
    fun testOneEntryCoveringTwoKeysHasASignatureForEach() =
        assertHeader(
            """
            defmodule Keys do
              def g2(a, b), do: {a, b}
              def g2(a, b \\ 1, c \\ 2), do: {a, b, c}

              def calls(x), do: g<caret>2(x, x)
            end
            """,
            "<i>module</i> <b>Keys</b>",
            "g2(a, b)",
            """g2(a, b \\ 1, c \\ 2)""",
            """  def g2(a, b \\ 1, c \\ 2)""",
            "  def g2(a, b)"
        )

    /** Its arity is known only once the splice expands, so no signature can be known for it: its head names it. */
    fun testAHeadWhoseArityIsOpenIsNamedByItsHead() =
        assertHeader(
            """
            defmodule Spliced do
              def f(unquote_splicing(args)), do: args

              def calls(x), do: <caret>f(x)
            end
            """,
            "<i>module</i> <b>Spliced</b>",
            "def f(unquote_splicing(args))"
        )

    /** A head a `use` injects is in the module that calls `use`, which names its struct. */
    fun testAStructPatternAUseInjectsIsNamedByTheModuleThatUsesIt() =
        assertInjectedTitle("def f(%__MODULE__{}), do: :ok", "f(injected)")

    fun testAStructPatternAUseInjectsIsNamedByItsVariable() =
        assertInjectedTitle("def f(%__MODULE__{} = s), do: s", "f(s)")

    /**
     * A link names no use, so the module that calls `use` is not known, and with it the signature Elixir records, which
     * names the struct by that module: the head stands alone.
     */
    fun testAStructPatternAUseInjectsHasNoSignatureAtALink() =
        assertInjectedTitle("def f(%__MODULE__{}), do: :ok", "def f(%__MODULE__{})", linked = true)

    fun testANestedStructPatternAUseInjectsHasNoSignatureAtALink() =
        assertInjectedTitle("def f(%__MODULE__.Inner{}), do: :ok", "def f(%__MODULE__.Inner{})", linked = true)

    /**
     * Quick Documentation of `f/1`, which `Injected` gets from [head] in a `use`'s `quote`, at a call of it, or by the
     * link `Injected.f/1` where [linked], heads it first with [title].
     */
    private fun assertInjectedTitle(head: String, title: String, linked: Boolean = false) {
        myFixture.configureByText(
            "injected.ex",
            """
            defmodule Injector do
              defmacro __using__(_) do
                quote do
                  defstruct [:a]

                  @doc "Injected."
                  $head
                end
              end
            end

            defmodule Injected do
              use Injector
            end

            defmodule Cal${if (linked) "<caret>" else ""}ler do
              def calls(x), do: Injected.${if (linked) "" else "<caret>"}f(x)
            end
            """.trimIndent()
        )

        val documentation =
            if (linked) myFixture.followedLinkAtCaret(project, "psi_element://Injected.f/1").orEmpty() else quickDocumentationAtCaret().orEmpty()
        val definition = definition(documentation).map(StringUtil::unescapeXmlEntities)

        assertEquals(documentation, title, definition.first { !it.startsWith("<i>module</i>") })
    }

    fun testACompiledModuleGivesTheSignatureThenTheSpecThenTheHead() =
        assertCompiledHeader(
            "CompiledHeader",
            "connected?",
            "<i>module</i> <b>CompiledHeader</b>",
            "connected?(socket)",
            "  @spec connected?(CompiledHeader.Socket.t()) :: boolean()",
            "  def connected?(%CompiledHeader.Socket{transport_pid: pid})"
        )

    /**
     * Its debug info decompiles each arity a default covers as a function of its own; the `Docs` chunk records only the
     * full one, which keeps its defaults.
     */
    fun testACompiledModuleKeepsTheDefaultsItsDocsChunkRecords() =
        assertCompiledHeader(
            "CompiledHeader",
            "split",
            "<i>module</i> <b>CompiledHeader</b>",
            """<a href="#arity-1">split(string)</a>""",
            "  def split(string)",
            """<a href="#arity-2">split(string, pattern)</a>""",
            "  def split(string, pattern)",
            """<a href="#arity-3">split(string, pattern \\ " ", options \\ [])</a>""",
            "  def split(string, pattern, options)"
        )

    /** With no debug info the decompiled heads are one function, which the `Docs` chunk titles once. */
    fun testACompiledModuleWithoutDebugInfoIsTitledByItsDocsChunk() =
        assertCompiledHeader(
            "CompiledHeaderDocsOnly",
            "split",
            "<i>module</i> <b>CompiledHeaderDocsOnly</b>",
            """split(string, pattern \\ " ", options \\ [])""",
            "  def split(string)",
            """  def split(string, pattern \\ " ", options \\ [])""",
            "  def split(string, pattern)"
        )

    /**
     * Quick Documentation at a call of [function] in [module], compiled by the leg's Elixir from [COMPILED], has its
     * first definition block's lines, unescaped, as [lines].
     */
    private fun assertCompiledHeader(module: String, function: String, vararg lines: String) {
        myFixture.addCompiledLibrary(COMPILED, CompiledBuild("CompiledHeader"), CompiledBuild("CompiledHeaderDocsOnly", debugInfo = false))
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def calls(a), do: $module.<caret>$function(a)\nend\n")
        assertDefinitionAtCaret(*lines)
    }

    /** Quick Documentation at the caret in [text] has its first definition block's lines, unescaped, as [lines]. */
    private fun assertHeader(text: String, vararg lines: String) {
        myFixture.configureByText("header.ex", text.trimIndent())
        assertDefinitionAtCaret(*lines)
    }

    /** Quick Documentation at the caret, returned, has its first definition block's lines, unescaped, as [lines]. */
    private fun assertDefinitionAtCaret(vararg lines: String): String {
        val documentation = quickDocumentationAtCaret().orEmpty()

        assertEquals(documentation, lines.toList(), definition(documentation).map(StringUtil::unescapeXmlEntities))

        return documentation
    }

    /** The lines of [documentation]'s first definition block, as rendered. */
    private fun definition(documentation: String): List<String> =
        documentation
            .substringAfter(DocumentationMarkup.DEFINITION_START)
            .substringBefore(DocumentationMarkup.DEFINITION_END)
            .lines()
            .filter(String::isNotEmpty)

    private companion object {
        /** The modules compiled for the `.beam` methods: `Compiled` is renamed for each build. */
        const val COMPILED = """
defmodule Compiled.Socket do
  defstruct [:transport_pid]
end

defmodule Compiled do
  alias Compiled.Socket

  @doc "Connected."
  @spec connected?(Socket.t()) :: boolean
  def connected?(%Socket{transport_pid: pid}), do: pid != nil

  @doc "Splits."
  def split(string, pattern \\ " ", options \\ []), do: {string, pattern, options}
end
"""
    }
}
