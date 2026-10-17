package org.elixir_lang.documentation

import com.intellij.openapi.util.text.StringUtil

/** A link in documentation that writes its module as an atom is drawn and followed as the same link written as an alias. */
class AtomWrittenLinkTest : QuickDocumentationTestCase() {
    private fun hrefsIn(moduledoc: String): List<String> {
        myFixture.addFileToProject(
            "other.ex",
            "defmodule Other do\n  @callback g(term) :: term\n\n  @doc \"Does f.\"\n  def f(a), do: a\nend\n"
        )
        myFixture.configureByText(
            "docs.ex",
            "defmodule Do<caret>cs do\n  @moduledoc \"\"\"\n  $moduledoc\n  \"\"\"\nend\n"
        )

        return Regex("""href="([^"]*)"""")
            .findAll(quickDocumentationAtCaret().orEmpty())
            .map { StringUtil.unescapeXmlEntities(it.groupValues[1]) }
            .toList()
    }

    private fun assertLinked(href: String, moduledoc: String) {
        val hrefs = hrefsIn(moduledoc)

        assertTrue("Expected $href among the links, got: $hrefs", href in hrefs)
    }

    fun testAFunctionLinkWrittenAsAQuotedAtom() {
        assertLinked("psi_element://:\"Elixir.Other\".f/1", "See `:\"Elixir.Other\".f/1`.")
    }

    fun testACallbackLinkWrittenAsAQuotedAtom() {
        assertLinked("psi_element://c::\"Elixir.Other\".g/1", "See `c::\"Elixir.Other\".g/1`.")
    }

    fun testAModuleWrittenAsAQuotedAtomInCodeIsPlainCode() {
        assertEquals(emptyList<String>(), hrefsIn("See `:\"Elixir.Other\"`."))
    }

    fun testAModuleWrittenAsAnUnquotedAtomInCodeIsPlainCode() {
        myFixture.addFileToProject("binary.ex", "defmodule :binary do\n  def f(a), do: a\nend\n")

        assertEquals(emptyList<String>(), hrefsIn("Open it in `:binary` mode."))
    }

    fun testAFunctionLinkWrittenAsAnUnquotedAtom() {
        myFixture.addFileToProject("lists.ex", "defmodule :lists_like do\n  def f(a), do: a\nend\n")

        assertLinked("psi_element://:lists_like.f/1", "See `:lists_like.f/1`.")
    }

    /** A function the atom's module does not have is plain code, as when the module is written as an alias. */
    fun testAQuotedAtomLinkToAMissingFunctionIsPlainCode() {
        assertEquals(emptyList<String>(), hrefsIn("See `:\"Elixir.Other\".missing/9`."))
    }

    /** A `c:` link is drawn only where it opens a callback, as a plain function link is drawn only where it opens a function. */
    fun testACallbackLinkToAFunctionThatIsNoCallbackIsPlainCode() {
        assertEquals(emptyList<String>(), hrefsIn("See `c:Other.f/1`."))
    }

    fun testACallbackLinkToAMissingCallbackIsPlainCode() {
        assertEquals(emptyList<String>(), hrefsIn("See `c:Other.missing/1`."))
    }

    /** An arity no function has is no link, and drawing the document goes on. */
    fun testAnArityTooBigForAnIntegerIsPlainCode() {
        assertEquals(listOf("psi_element://Other"), hrefsIn("See `Other.f/99999999999`, then `Other`."))
    }

    fun testALocalLinkWithAnArityTooBigForAnIntegerIsPlainCode() {
        assertEquals(listOf("psi_element://Other"), hrefsIn("See `f/99999999999`, then `Other`."))
    }

    fun testAFunctionLinkWrittenAsAnAliasStillLinks() {
        assertLinked("psi_element://Other.f/1", "See `Other.f/1`.")
    }

    /** An operator whose name holds a colon is linked as any other function is. */
    fun testAFunctionLinkToAColonOperatorLinks() {
        myFixture.addFileToProject("ops.ex", "defmodule Ops do\n  @doc \"Annotates.\"\n  defmacro left :: right, do: {left, right}\nend\n")

        assertEquals(listOf("psi_element://Ops.::/2"), hrefsIn("See `Ops.::/2`."))
    }

    fun testFollowingAFunctionLinkWrittenAsAQuotedAtomOpensTheFunction() {
        hrefsIn("See `:\"Elixir.Other\".f/1`.")

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://:\"Elixir.Other\".f/1")

        assertTrue("Expected f's docs, got: $opened", opened?.contains("Does f.") == true)
    }

    fun testFollowingACallbackLinkWrittenAsAQuotedAtomOpensTheCallback() {
        myFixture.addFileToProject(
            "documented.ex",
            "defmodule Documented do\n  @doc \"Says g.\"\n  @callback g(term) :: term\nend\n"
        )

        hrefsIn("See `c::\"Elixir.Documented\".g/1`.")

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://c::\"Elixir.Documented\".g/1")

        assertTrue("Expected g's callback docs, got: $opened", opened?.contains("Says g.") == true)
    }
}
