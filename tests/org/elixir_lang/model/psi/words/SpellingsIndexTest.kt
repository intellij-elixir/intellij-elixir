package org.elixir_lang.model.psi.words

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.util.indexing.FileContentImpl
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/** The atoms a file spells where the platform's literal word search for them finds nothing. */
class SpellingsIndexTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testLiteralSpellingsAreNotKeys() {
        for (text in listOf("M.foo(1)", "{M, :\"foo\", 1}", "[foo: 1]")) {
            assertFalse("`foo` is a key of `$text`: ${keys(text)}", "foo" in keys(text))
        }
    }

    fun testEscapedNameIsAKey() = assertKey("M.\"fo\\x6f\"(1)", "foo")

    fun testOperatorIsAKey() = assertKey("1 <~> 2", "<~>")

    /** The `x` of `\x78` is inside the name's text, with a digit after it. */
    fun testOneLetterQuotedRemoteNameIsAKey() = assertKey("M.\"\\x78\"()", "x")

    /** The platform's scanner drops a word over 100 characters. */
    fun testLongNameIsAKey() {
        val name = "a".repeat(101)

        assertKey("defmodule M do\n  def $name(x), do: x\nend\n", name)
    }

    fun testShortNameIsNotAKey() {
        val name = "a".repeat(100)

        assertFalse("`$name` is a key", name in keys("defmodule M do\n  def $name(x), do: x\nend\n"))
    }

    /** The attribute's name is the decomposed `cafe` + U+0301; the key is the composed `café`, spelled in the brackets. */
    fun testAttributeBracketReadIsWalked() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))

        assertKey("@cafe\u0301[:caf\u00e9]", "caf\u00e9")
    }

    fun testEscapedSigilIsMarked() {
        val keys = keys(sigil("{M.\"fo\\x6f\"(1)}"))

        assertTrue("`~H\\` is not a key: $keys", SpellingsIndex.ESCAPED_SIGIL_MARKER in keys)
        assertTrue("`~H` is not a key: $keys", SpellingsIndex.SIGIL_MARKER in keys)
    }

    fun testPlainSigilIsMarked() {
        val keys = keys(sigil("<p>Caf\u00e9 \\ </p>{M.foo(1)}"))

        assertTrue("`~H` is not a key: $keys", SpellingsIndex.SIGIL_MARKER in keys)
        assertFalse("`~H\\` is a key: $keys", SpellingsIndex.ESCAPED_SIGIL_MARKER in keys)
    }

    fun testFileWithoutSigilIsNotMarked() {
        val keys = keys("M.\"fo\\x6f\"(1)")

        assertFalse("`~H` is a key: $keys", SpellingsIndex.SIGIL_MARKER in keys)
        assertFalse("`~H\\` is a key: $keys", SpellingsIndex.ESCAPED_SIGIL_MARKER in keys)
    }

    /** `~E` and `~L` are injected with EEx, which holds Elixir, as `~H` is with HEEx. */
    fun testEexSigilsAreMarked() {
        for (name in listOf("E", "L")) {
            val keys = keys(sigil("<p><%= M.\"fo\\x6f\"(1) %></p>", name))

            assertTrue("`~H\\` is not a key of ~$name: $keys", SpellingsIndex.ESCAPED_SIGIL_MARKER in keys)
            assertTrue("`~H` is not a key of ~$name: $keys", SpellingsIndex.SIGIL_MARKER in keys)
        }
    }

    /** Braces are Elixir in HEEx but markup in EEx, whose Elixir is only `<%= ... %>`. */
    fun testEexBracesAreNotElixir() {
        val keys = keys(sigil("<p>{Caf\u00e9}</p>", "E"))

        assertTrue("`~H` is not a key: $keys", SpellingsIndex.SIGIL_MARKER in keys)
        assertFalse("`~H\\` is a key: $keys", SpellingsIndex.ESCAPED_SIGIL_MARKER in keys)
    }

    /** `~r` is injected with a regex and `~S` with nothing: neither holds Elixir. */
    fun testSigilsWithoutElixirAreNotMarked() {
        for (name in listOf("r", "S")) {
            val keys = keys(sigil("<%= M.\"fo\\x6f\"(1) %>", name))

            assertFalse("`~H` is a key of ~$name: $keys", SpellingsIndex.SIGIL_MARKER in keys)
        }
    }

    private fun sigil(body: String, name: String = "H") =
        "defmodule C do\n  def render(assigns) do\n    ~$name\"\"\"\n    $body\n    \"\"\"\n  end\nend\n"

    private fun assertKey(text: String, key: String) {
        val keys = keys(text)

        assertTrue("`$key` is not a key of `$text`: $keys", key in keys)
    }

    private fun keys(text: String): Set<String> {
        myFixture.configureByText("spellings.ex", text)

        return runReadActionBlocking {
            Spellings.INDEXER.map(FileContentImpl.createByFile(myFixture.file.virtualFile, project)).keys
        }
    }
}
