package org.elixir_lang.refactoring

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * A definition declared in a compiled `.beam` cannot be renamed: rename refuses with a message, from a source use or
 * from the decompiled view, and writes nothing.
 */
class CompiledRenameTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/type"

    private val source = """
        defmodule UsesCompiled do
          @spec run(:queue.queue()) :: :queue.queue()
          def run(q), do: {:queue.new(), apply(:queue, :new, []), &:queue.new/0, q}
        end
    """.trimIndent()

    fun testFromACall() = assertRefused(":queue.ne<caret>w()")

    fun testFromAnApplyAtom() = assertRefused("apply(:queue, :ne<caret>w")

    fun testFromACapture() = assertRefused("&:queue.ne<caret>w/0")

    fun testFromAType() = assertRefused("@spec run(:queue.qu<caret>eue())")

    fun testFromTheDecompiledDeclaration() {
        openBeamAndMoveCaretTo("queue.beam", "def new")
        val before = myFixture.editor.document.text

        assertRefusal()
        assertEquals(before, myFixture.editor.document.text)
    }

    /** The definition is the source delegation, so a compiled target does not refuse the rename. */
    fun testFromAnImportedCallOfAMultiArityDelegationToACompiledTarget() {
        val delegating = """
            defmodule Delegator do
              defdelegate join(q), to: :queue
              defdelegate join(q, x), to: :queue
            end

            defmodule Caller do
              import Delegator

              def run(a, b), do: join(a, b)
            end
        """.trimIndent()
        myFixture.configureByText("delegator.ex", delegating.replace("do: join(a, b)", "do: jo<caret>in(a, b)"))

        myFixture.renameTargetAtCaret("renamed")

        assertEquals(
            delegating
                .replace("defdelegate join(q, x), to: :queue", "defdelegate renamed(q, x), to: :queue, as: :join")
                .replace("do: join(a, b)", "do: renamed(a, b)"),
            myFixture.editor.document.text
        )
    }

    private fun assertRefused(caretAt: String) {
        myFixture.configureByText("uses_compiled.ex", source.replace(caretAt.replace("<caret>", ""), caretAt))

        assertRefusal()
        assertEquals(source, myFixture.editor.document.text)
    }

    private fun assertRefusal() {
        val refusal = runCatching { myFixture.renameTargetAtCaret("renamed") }.exceptionOrNull()

        assertTrue("Expected the rename to be refused, got $refusal", refusal is IllegalArgumentException)
        assertTrue("Expected the refusal to say why, got ${refusal?.message}", refusal?.message?.contains("compiled") == true)
    }
}
