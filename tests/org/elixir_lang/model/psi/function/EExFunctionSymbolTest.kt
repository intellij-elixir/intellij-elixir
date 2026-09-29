package org.elixir_lang.model.psi.function

import com.intellij.find.usages.impl.searchTargets
import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.lineAt
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * A function `EEx.function_from_string` declares is navigated, searched and renamed like a `def` from every place that
 * names it.
 */
@Suppress("UnstableApiUsage")
class EExFunctionSymbolTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        myFixture.copyFileToProject("eex.ex")
    }

    fun testGoToDeclarationFromACallLandsOnTheDeclaredName() = assertGoToDeclarationFrom("do: <caret>render(a, b) # @local\n")

    fun testGoToDeclarationFromACaptureLandsOnTheDeclaredName() = assertGoToDeclarationFrom("&Host.<caret>render/2")

    fun testGoToDeclarationFromApplyLandsOnTheDeclaredName() = assertGoToDeclarationFrom("apply(Host, :<caret>render")

    fun testGoToDeclarationFromAnImportOnlyKeyLandsOnTheDeclaredName() = assertGoToDeclarationFrom("only: [<caret>render")

    fun testGoToDeclarationFromASpecLandsOnTheDeclaredName() = assertGoToDeclarationFrom("@spec <caret>render")

    fun testFindUsagesAtTheDeclarationFindsEveryUseOfThatArityAlone() {
        configure(":<caret>render, \"")

        val lines = myFixture.psiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .map { myFixture.lineAt(it.range.startOffset) }
            .sorted()

        assertEquals(
            listOf(
                "@spec render(term(), term()) :: term()",
                "def applied(a, b), do: apply(Host, :render, [a, b])",
                "def captured, do: &Host.render/2",
                "def local_forward(a, b), do: render(a, b) # @local_forward",
                "def local_site(a, b), do: render(a, b) # @local",
                "import Host, only: [render: 2]"
            ),
            lines
        )
    }

    /** `&Host.blank/1` and `apply(Host, :blank, [a])` name a `blank/1` that does not exist, so neither uses `blank/0`. */
    fun testFindUsagesOfAZeroArityDeclarationSkipsACaptureAndAnApplyOfAnotherArity() {
        myFixture.configureByText(
            "host.ex",
            """
            defmodule Host do
              require EEx

              EEx.function_from_string(:def, :<caret>blank, "", [])

              def used, do: blank()
              def captured, do: &Host.blank/1
              def applied(a), do: apply(Host, :blank, [a])
            end
            """.trimIndent()
        )

        val calls = myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }

        assertEquals(listOf("blank"), calls.map { myFixture.file.text.substring(it.range.startOffset, it.range.endOffset) })
        assertEquals(myFixture.file.text.indexOf("blank()"), calls.single().range.startOffset)
    }

    fun testTheSearchTargetAtTheDeclarationPresentsTheDeclaredFunction() {
        configure(":<caret>render, \"")

        assertEquals(
            listOf("def render(q, x)"),
            searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText }
        )
    }

    fun testRenameAtTheDeclarationRenamesTheNameAndEveryUseOfThatArity() {
        configure(":<caret>render, \"")

        myFixture.renameTargetAtCaret("paint")

        assertEquals(source("paint"), myFixture.editor.document.text)
    }

    private fun assertGoToDeclarationFrom(anchor: String) {
        configure(anchor)

        val destination = myFixture.gotoDeclarationDestinationAtCaret()

        assertNotNull("Go To Declaration from `$anchor` went nowhere", destination)
        assertEquals(
            "EEx.function_from_string(:def, :render, \"<%= inspect({q, x}) %>\", [:q, :x])",
            generateSequence(destination) { it.parent }.first { it.text.startsWith("EEx.") }.text
        )
    }

    /** [source] with the caret where `<caret>` sits in [anchor], which must occur in it once without the marker. */
    private fun configure(anchor: String) {
        val text = source("render")
        val plain = anchor.replace("<caret>", "")
        val start = text.indexOf(plain)
        assertTrue("`$plain` is not in the fixture", start >= 0 && text.indexOf(plain, start + 1) < 0)

        myFixture.configureByText("host.ex", text.replaceRange(start, start + plain.length, anchor))
    }

    private fun source(name: String): String =
        """
        defmodule Host do
          require EEx

          def local_forward(a, b), do: $name(a, b) # @local_forward

          @spec $name(term(), term()) :: term()
          EEx.function_from_string(:def, :$name, "<%= inspect({q, x}) %>", [:q, :x])

          def local_site(a, b), do: $name(a, b) # @local
        end

        defmodule User do
          import Host, only: [$name: 2]

          def captured, do: &Host.$name/2
          def other_arity, do: &Host.render/3
          def applied(a, b), do: apply(Host, :$name, [a, b])
        end
        """.trimIndent()
}
