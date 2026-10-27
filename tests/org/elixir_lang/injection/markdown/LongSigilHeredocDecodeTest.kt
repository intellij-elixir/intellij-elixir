package org.elixir_lang.injection.markdown

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.injection.sigilInjection
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.psi.SigilHeredocLiteral
import kotlin.system.measureTimeMillis

/** A template heredoc is injected a line at a time, so decoding each of its lines must not walk all the others. */
class LongSigilHeredocDecodeTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // From 1.13, `\"""` in a sigil heredoc decodes to `"""`, so every line holds an escape that changes.
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))
    }

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } finally {
            super.tearDown()
        }
    }

    fun testDecodingEveryLineOfALongTemplateHeredocIsQuick() {
        myFixture.configureByText(
            "long.ex",
            "defmodule Long do\n" +
                "  def render(assigns) do\n" +
                "    ~H\"\"\"\n" +
                (1..LINES).joinToString("") { "    <p>line $it \\\"\"\"</p>\n" } +
                "    \"\"\"\n" +
                "  end\n" +
                "end\n",
        )
        val sigil = PsiTreeUtil.findChildrenOfType(myFixture.file, SigilHeredocLiteral::class.java).single()
        val ranges = sigilInjection(sigil)!!.ranges
        val escaper = sigil.createLiteralTextEscaper()
        val decoded = StringBuilder()

        val millis = measureTimeMillis {
            for (range in ranges) assertTrue(escaper.decode(range, decoded))
        }

        assertTrue(decoded.contains("line $LINES \"\"\"</p>"))
        assertFalse(decoded.contains("\\\"\"\""))
        assertTrue("decoding ${ranges.size} lines took $millis ms", millis < 2_000)
    }

    private companion object {
        const val LINES = 8_000
    }
}
