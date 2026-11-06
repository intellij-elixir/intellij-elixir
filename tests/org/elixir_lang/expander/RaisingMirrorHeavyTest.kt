package org.elixir_lang.expander

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import org.elixir_lang.declaration.DistinctKeyScheme
import org.elixir_lang.expander.ExpanderSourceRows.candidates
import org.elixir_lang.expander.ExpanderSourceRows.lineOf
import org.elixir_lang.expander.ExpanderSourceRows.rows
import org.elixir_lang.expander.ExpanderSourceRows.withExpanderSwitch
import org.elixir_lang.model.psi.config.caret

/**
 * A source file's answers about SDK modules whose decompiled mirrors' expansions end their runs, the expander behind
 * every feature: `URI`, a struct, `Integer`, with guards, and `GenServer`, a used module. They are read from the
 * `.beam`s, not from the mirrors' expansions.
 */
class RaisingMirrorHeavyTest : LegSdkFixtureTestCase() {
    fun testAnswersAboutModulesWhoseMirrorsRaise() {
        val scheme = DistinctKeyScheme(myFixture.testRootDisposable)
        val file = caller(SOURCE)

        val answers = withExpanderSwitch {
            rows(
                "mirrors" to listOf("Elixir.URI", "Elixir.Integer", "Elixir.GenServer").joinToString("\n", transform = ::ending),
                "struct call" to candidates(file, "URI.to_string(uri)"),
                "imported guard" to candidates(file, "is_odd(x)"),
                "remote guard" to candidates(file, "Integer.is_even(x)"),
                "used module's call" to candidates(file, "GenServer.call(pid, :x)"),
                "declaration of the struct call" to destinations("URI.to_s<caret>tring(uri)", ::line),
                "declaration of the imported guard" to destinations("is_o<caret>dd(x)", ::line),
                "declaration of the struct" to destinations("%UR<caret>I{}", ::line),
                "imported guard's highlighting" to keys(scheme, "is_odd(x)", "is_odd"),
                "remote guard's highlighting" to keys(scheme, "Integer.is_even(x)", "is_even"),
                // Last, as completion may insert what it offers.
                "completion after Integer.is_" to completion(),
            )
        }

        assertEquals(
            rows(
                "mirrors" to "Elixir.URI ends its run\nElixir.Integer ends its run\nElixir.GenServer ends its run",
                "struct call" to "URI.to_string/1 OWN VALID via []",
                "imported guard" to "Integer.is_odd/1 IMPORT VALID via [import Integer]",
                "remote guard" to "Integer.is_even/1 OWN VALID via []",
                "used module's call" to "GenServer.call/2 OWN VALID via []\nGenServer.call/3 OWN WRONG_ARITY via []",
                "declaration of the struct call" to "Elixir.URI.beam: def to_string(uri) do",
                "declaration of the imported guard" to "Elixir.Integer.beam: defguard is_odd(integer) do",
                "declaration of the struct" to "Elixir.URI.beam: defmodule URI do",
                "imported guard's highlighting" to "ELIXIR_GUARD_CALL",
                "remote guard's highlighting" to "ELIXIR_GUARD_CALL",
                "completion after Integer.is_" to "is_even is_odd",
            ),
            answers,
        )
    }

    /**
     * [module] and whether its SDK `.beam`'s mirror's expansion ends its run, or how it ended if not. What it raises,
     * or where it stops, moves with the leg.
     */
    private fun ending(module: String): String {
        val mirror = mirrorOf(project, appBeams.getValue(module))!!
        val ended = expansion(ProjectModules.getInstance(project), mirror).modules.single().ended

        return "$module ${if (ended.endsRun) "ends its run" else ended}"
    }

    /** [element]'s file's name and its line's text, which, unlike its number, the leg doesn't move. */
    private fun line(element: PsiElement): String =
        "${element.containingFile.originalFile.name}: ${element.containingFile.text.lines()[lineOf(element) - 1].trim()}"

    /** What completion after `Integer.is_` in the caller offers, sorted. */
    private fun completion(): String {
        myFixture.caret(CALLER, "Integer.is_<caret>")

        return myFixture.completeBasic().orEmpty().map { it.lookupString }.sorted().joinToString(" ")
    }

    /** The keys highlighting gives [name] where it starts [context] in the caller. */
    private fun keys(scheme: DistinctKeyScheme, context: String, name: String): String {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(CALLER))
        val offset = SOURCE.indexOf(context) + context.indexOf(name)

        return myFixture.doHighlighting().asSequence()
            .filter { it.forcedTextAttributes != null && it.startOffset == offset && it.endOffset == offset + name.length }
            .flatMap { scheme.keys(it.forcedTextAttributes) }
            .map(TextAttributesKey::getExternalName)
            .sorted()
            .joinToString(" ")
    }

    private companion object {
        val SOURCE = """
            defmodule Caller do
              use GenServer
              import Integer

              def struct(uri = %URI{}), do: URI.to_string(uri)
              def guard(x) when is_odd(x), do: Integer.is_even(x)
              def used(pid), do: GenServer.call(pid, :x)
            end
        """.trimIndent() + "\n"
    }
}
