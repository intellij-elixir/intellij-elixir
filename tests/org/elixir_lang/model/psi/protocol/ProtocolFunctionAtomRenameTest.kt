package org.elixir_lang.model.psi.protocol

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * An MFA atom naming a protocol function names that protocol function, so renaming either renames the other, the
 * `defimpl` clauses and the calls.
 */
class ProtocolFunctionAtomRenameTest : PlatformTestCase() {
    private val source = """
        defprotocol Sizer do
          def size(t)
        end

        defimpl Sizer, for: List do
          def size(t), do: length(t)
        end

        defmodule Caller do
          def calls(x), do: {Sizer.size(x), apply(Sizer, :size, [x]), {Sizer, :size, 1}}
        end
    """.trimIndent()

    fun testRenamingFromAnMfaAtomRenamesTheProtocolFunction() {
        myFixture.configureByText("atom_rename.ex", source.replace("{Sizer, :size, 1}", "{Sizer, :si<caret>ze, 1}"))

        myFixture.renameTargetAtCaret("count")

        assertEquals(source.replace("size", "count"), myFixture.editor.document.text)
    }

    fun testRenamingTheProtocolFunctionRenamesItsMfaAtoms() {
        myFixture.configureByText("protocol_rename.ex", source.replace("  def size(t)\n", "  def si<caret>ze(t)\n"))

        myFixture.renameTargetAtCaret("count")

        assertEquals(source.replace("size", "count"), myFixture.editor.document.text)
    }
}
