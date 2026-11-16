package org.elixir_lang.documentation

import com.intellij.openapi.util.text.StringUtil

/**
 * With Run the expander on, Quick Documentation shows a definition the expander cannot confirm with one plain line
 * saying so, and each copy of a module defined more than once as its own entry.
 */
class UncertainEntryQuickDocumentationTest : QuickDocumentationTestCase() {
    /** Elixir doesn't compile `Bar`, whose `import` raises, so the index's `g/1` may not be what `g(1)` calls. */
    fun testAFindFromTheIndexCarriesThePlainLine() {
        val documentation = documentationOfG("import Foo, only: [Foo, g: 1]")

        assertTrue("Expected g's docs, got: $documentation", "Gs." in documentation)
        assertTrue("Expected the plain line, got: $documentation", PLAIN_LINE in documentation)
        assertEquals("Expected the plain line once, got: $documentation", 1, StringUtil.getOccurrenceCount(documentation, PLAIN_LINE))
    }

    fun testAConfirmedFindCarriesNoLine() {
        val documentation = documentationOfG("import Foo, only: [g: 1]")

        assertTrue("Expected g's docs, got: $documentation", "Gs." in documentation)
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    /** A local call in a `quote` runs in the module the quote's code is injected into, which may define the name itself. */
    fun testALocalCallInAQuoteCarriesThePlainLine() {
        val documentation = documentationInUsing("quote do\n      def f(y), do: hel<caret>per(y)\n    end")

        assertTrue("Expected helper's docs, got: $documentation", "Helps." in documentation)
        assertPlainLineOnce(documentation)
    }

    /** A `quote` records the import a call in it reaches where it is written, so the call reaches it wherever it is injected. */
    fun testAnImportedCallInAQuoteCarriesNoLine() {
        myFixture.addFileToProject("foo.ex", "defmodule Foo do\n  @doc \"Gs.\"\n  def g(x), do: x\nend\n")
        val documentation = documentationInUsing("quote do\n      def f(y), do: <caret>g(y)\n    end", "import Foo")

        assertTrue("Expected g's docs, got: $documentation", "Gs." in documentation)
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    /** What a `quote` unquotes runs in the macro, in the module the `quote` is written in. */
    fun testALocalCallAQuoteUnquotesCarriesNoLine() {
        val documentation = documentationInUsing("quote do\n      unquote(hel<caret>per(1))\n    end")

        assertTrue("Expected helper's docs, got: $documentation", "Helps." in documentation)
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    /** `unquote: false` keeps `unquote` as code, so the call in it runs in the module the code is injected into. */
    fun testALocalCallInAnUnquoteAQuoteKeepsAsCodeCarriesThePlainLine() {
        val documentation = documentationInUsing("quote unquote: false do\n      unquote(hel<caret>per(1))\n    end")

        assertTrue("Expected helper's docs, got: $documentation", "Helps." in documentation)
        assertPlainLineOnce(documentation)
    }

    /** The outer `quote` quotes a nested `quote`'s options as its own code, so it unquotes the call there. */
    fun testALocalCallANestedQuotesOptionsUnquoteCarriesNoLine() {
        val documentation = documentationInUsing("quote do\n      quote bind_quoted: [s: unquote(hel<caret>per(1))] do\n        s\n      end\n    end")

        assertTrue("Expected helper's docs, got: $documentation", "Helps." in documentation)
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    /**
     * The walk documents a function the `quote` defines at a local call of it in the quote, where the expander, which
     * reads no quote's definitions, documents nothing.
     */
    fun testALocalCallOfAFunctionTheQuoteDefinesIsDocumentedOnlyByTheWalk() {
        val quote = "quote do\n      @doc \"Ks.\"\n      def k(x), do: x\n      def f(y), do: <caret>k(y)\n    end"
        val walked = documentationInUsing(quote, expander = false)

        assertTrue("Expected k's docs, got: $walked", "Ks." in walked)

        val expanded = documentationInUsing(quote)

        assertEquals("Expected no docs, got: $expanded", "", expanded)
    }

    /** `__MODULE__` in a `quote` is the module the code is injected into, which may define the name itself. */
    fun testACallOnTheQuotesModuleCarriesThePlainLine() {
        val documentation = documentationInUsing("quote do\n      def f(y), do: __MODULE__.hel<caret>per(y)\n    end")

        assertTrue("Expected helper's docs, got: $documentation", "Helps." in documentation)
        assertPlainLineOnce(documentation)
    }

    /** A module under `__MODULE__` in a `quote` is under the module the code is injected into. */
    fun testACallOnAModuleUnderTheQuotesModuleCarriesThePlainLine() {
        myFixture.addFileToProject("sub.ex", "defmodule U.Sub do\n  @doc \"Subs.\"\n  def f(x), do: x\nend\n")
        val documentation = documentationInUsing("quote do\n      def g(y), do: __MODULE__.Sub.<caret>f(y)\n    end")

        assertTrue("Expected f's docs, got: $documentation", "Subs." in documentation)
        assertPlainLineOnce(documentation)
    }

    fun testEachCopyOfAModuleIsItsOwnEntry() {
        expanderOn()
        configureTwice()

        val documentation = documentationAtTwiceG()

        assertTrue("Expected the first copy, got: $documentation", "First copy." in documentation)
        assertTrue("Expected the second copy, got: $documentation", "Second copy." in documentation)
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    /** Only one copy of the module defines `g/1`, so only the line says that which copy loads is unknown. */
    fun testALoneCopyOfAModuleCarriesThePlainLine() {
        expanderOn()
        myFixture.addFileToProject("first.ex", "defmodule Twice do\n  @doc \"First copy.\"\n  def g(a), do: a\nend\n")
        myFixture.addFileToProject("second.ex", "defmodule Twice do\n  def h(a), do: a\nend\n")

        val documentation = documentationAtTwiceG()

        assertTrue("Expected the first copy, got: $documentation", "First copy." in documentation)
        assertEquals("Expected the plain line once, got: $documentation", 1, StringUtil.getOccurrenceCount(documentation, PLAIN_LINE))
    }

    /** Each copy delegates to its own target, so each copy's entry is what its delegation documents. */
    fun testEachCopyOfAModuleDocumentsWhatItsOwnDelegationReaches() {
        expanderOn()
        myFixture.addFileToProject(
            "first.ex",
            "defmodule A do\n  @doc \"A's.\"\n  def g(a), do: a\nend\n\ndefmodule Twice do\n  defdelegate g(a), to: A\nend\n"
        )
        myFixture.addFileToProject(
            "second.ex",
            "defmodule B do\n  @doc \"B's.\"\n  def g(a), do: a\nend\n\ndefmodule Twice do\n  defdelegate g(a), to: B\nend\n"
        )

        val documentation = documentationAtTwiceG()

        assertTrue("Expected the first copy's target, got: $documentation", "A's." in documentation)
        assertTrue("Expected the second copy's target, got: $documentation", "B's." in documentation)
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    /** A function of no module in a doc is its own module's, so each copy's target links `h/1` to its own `h/1`. */
    fun testEachCopysEntryLinksAFunctionOfNoModuleToItsOwnModule() {
        expanderOn()
        myFixture.addFileToProject(
            "first.ex",
            "defmodule A do\n  @doc \"A's `h/1`.\"\n  def g(a), do: a\n  def h(a), do: a\nend\n\ndefmodule Twice do\n  defdelegate g(a), to: A\nend\n"
        )
        myFixture.addFileToProject(
            "second.ex",
            "defmodule B do\n  @doc \"B's `h/1`.\"\n  def g(a), do: a\n  def h(a), do: a\nend\n\ndefmodule Twice do\n  defdelegate g(a), to: B\nend\n"
        )

        val documentation = documentationAtTwiceG()
        val second = documentation.substringAfter("B's ", missingDelimiterValue = "")

        assertTrue("Expected the second copy's link to B.h/1, got: $documentation", "href=\"psi_element://B.h/1\"" in second.substringBefore("</p>"))
    }

    /** A followed link shows what a call of its name shows: each copy of the module as its own entry. */
    fun testALinkShowsEachCopyOfAModuleAsItsOwnEntry() {
        expanderOn()
        configureTwice()
        myFixture.configureByText("caller.ex", "defmodule Cal<caret>ler do\n  @moduledoc \"See `Twice.g/1`.\"\nend\n")

        val documentation = myFixture.followedLinkAtCaret(project, "psi_element://Twice.g/1").orEmpty()

        assertTrue("Expected the first copy, got: $documentation", "First copy." in documentation)
        assertTrue("Expected the second copy, got: $documentation", "Second copy." in documentation)
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    /** Each copy makes `g` with `EEx`, from bindings in an attribute, which the expander reads to know its arity. */
    fun testEachCopyOfAModuleMadeWithEExIsItsOwnEntry() {
        expanderOn()
        myFixture.addFileToProject("first.ex", eexTwice("First copy."))
        myFixture.addFileToProject("second.ex", eexTwice("Second copy."))

        val documentation = documentationAtTwiceG()

        assertTrue("Expected the first copy, got: $documentation", "First copy." in documentation)
        assertTrue("Expected the second copy, got: $documentation", "Second copy." in documentation)
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    private fun assertPlainLineOnce(documentation: String) =
        assertEquals("Expected the plain line once, got: $documentation", 1, StringUtil.getOccurrenceCount(documentation, PLAIN_LINE))

    /** `Twice`, making `g` with `EEx` from the bindings in `@args`, documented as [doc]. */
    private fun eexTwice(doc: String): String =
        "defmodule Twice do\n  require EEx\n  @args [:a]\n  @doc \"$doc\"\n  EEx.function_from_string(:def, :g, \"x\", @args)\nend\n"

    private fun configureTwice() {
        myFixture.addFileToProject("first.ex", "defmodule Twice do\n  @doc \"First copy.\"\n  def g(a), do: a\nend\n")
        myFixture.addFileToProject("second.ex", "defmodule Twice do\n  @doc \"Second copy.\"\n  def g(a), do: a\nend\n")
    }

    /**
     * Quick Documentation at the caret in [quote], which `U.__using__/1` returns, below [directive] in `U`, with the
     * expander on where [expander].
     */
    private fun documentationInUsing(quote: String, directive: String = "", expander: Boolean = true): String {
        if (expander) expanderOn()
        myFixture.configureByText(
            "u.ex",
            "defmodule U do\n  $directive\n\n  @doc \"Helps.\"\n  def helper(x), do: x\n\n  defmacro __using__(_) do\n    $quote\n  end\nend\n"
        )

        return quickDocumentationAtCaret().orEmpty()
    }

    /** Quick Documentation at a call of `Twice.g(1)`. */
    private fun documentationAtTwiceG(): String {
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def f, do: Twice.g<caret>(1)\nend\n")

        return quickDocumentationAtCaret().orEmpty()
    }

    /** Quick Documentation at `g(1)` in a module that imports `Foo` through [import]. */
    private fun documentationOfG(import: String): String {
        expanderOn()
        myFixture.addFileToProject("foo.ex", "defmodule Foo do\n  @doc \"Gs.\"\n  def g(x), do: x\nend\n")
        myFixture.configureByText("bar.ex", "defmodule Bar do\n  $import\n  def h, do: <caret>g(1)\nend\n")

        return quickDocumentationAtCaret().orEmpty()
    }
}
