package org.elixir_lang.model.psi.module

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret

/**
 * Rename writes a module's new name in a spelling that is valid at each place it is written: an atom declaration
 * stays an atom, an absolute spelling stays absolute, and a name that is not a module name is refused.
 */
class AtomNamedModuleRenameTest : PlatformTestCase() {
    fun testSeedIsTheAliasSpelling() {
        assertEquals("A.B", seedAt("defmodule :\"Elixir.A.B<caret>\" do\nend\n"))
        assertEquals(":foo", seedAt("defmodule :fo<caret>o do\nend\n"))
        assertEquals("A.B", seedAt("defmodule A.<caret>B do\nend\n"))
    }

    fun testRenameWritesASpellingValidAtEachPlace() {
        assertRename(
            "A.C",
            declaration = "defmodule :\"Elixir.A.B<caret>\" do\n  def f, do: :ok\nend\n",
            caller = CALLER_OF_AB,
            expectedDeclaration = "defmodule :\"Elixir.A.C\" do\n  def f, do: :ok\nend\n",
            expectedCaller = "defmodule Caller do\n  alias A.C\n  def run, do: {C.f(), A.C.f()}\nend\n",
        )
    }

    fun testAbsoluteNewNameStaysAbsoluteAtEachPlace() {
        assertRename(
            "Elixir.A.C",
            declaration = "defmodule :\"Elixir.A.B<caret>\" do\n  def f, do: :ok\nend\n",
            caller = CALLER_OF_AB,
            expectedDeclaration = "defmodule :\"Elixir.A.C\" do\n  def f, do: :ok\nend\n",
            expectedCaller = "defmodule Caller do\n  alias Elixir.A.C\n  def run, do: {C.f(), Elixir.A.C.f()}\nend\n",
        )
    }

    fun testNestedAtomDeclaration() {
        val declaration = "defmodule Outer do\n  defmodule :\"Elixir.A.B<caret>\" do\n    def f, do: :ok\n  end\nend\n"
        myFixture.configureByText("declaration.ex", declaration)

        assertEquals("A.B", moduleSymbolAtCaret().moduleName)

        assertRename(
            "A.C",
            declaration = declaration,
            caller = CALLER_OF_AB,
            expectedDeclaration =
                "defmodule Outer do\n  defmodule :\"Elixir.A.C\" do\n    def f, do: :ok\n  end\nend\n",
            expectedCaller = "defmodule Caller do\n  alias A.C\n  def run, do: {C.f(), A.C.f()}\nend\n",
        )
    }

    fun testNestedElixirHeadedDeclaration() {
        assertRename(
            "A.E",
            declaration = NESTED_ELIXIR_HEADED,
            caller = OUTSIDE_CALLER_OF_AD,
            expectedDeclaration = nestedElixirHeaded("Elixir.A.E"),
            expectedCaller = "defmodule Outside do\n  def run, do: A.E.f()\nend\n",
        )
    }

    fun testNestedElixirHeadedDeclarationGivenAnAbsoluteName() {
        assertRename(
            "Elixir.A.E",
            declaration = NESTED_ELIXIR_HEADED,
            caller = OUTSIDE_CALLER_OF_AD,
            expectedDeclaration = nestedElixirHeaded("Elixir.A.E"),
            expectedCaller = "defmodule Outside do\n  def run, do: Elixir.A.E.f()\nend\n",
        )
    }

    fun testNestedAliasDeclarationGivenAnAbsoluteName() {
        assertRename(
            "Elixir.Fresh",
            declaration = "defmodule Outer do\n  defmodule In<caret>ner do\n    def f, do: :ok\n  end\nend\n",
            caller = "defmodule Outside do\n  def run, do: {Outer.Inner.f(), Elixir.Outer.Inner.f()}\nend\n",
            expectedDeclaration = "defmodule Outer do\n  defmodule Elixir.Fresh do\n    def f, do: :ok\n  end\nend\n",
            expectedCaller = "defmodule Outside do\n  def run, do: {Elixir.Fresh.f(), Elixir.Fresh.f()}\nend\n",
        )
    }

    fun testNestedAliasDeclarationGivenAnAlias() {
        assertRename(
            "Fresh",
            declaration = "defmodule Outer do\n  defmodule In<caret>ner do\n    def f, do: :ok\n  end\nend\n",
            caller = "defmodule Outside do\n  def run, do: {Outer.Inner.f(), Elixir.Outer.Inner.f()}\nend\n",
            expectedDeclaration = "defmodule Outer do\n  defmodule Fresh do\n    def f, do: :ok\n  end\nend\n",
            expectedCaller = "defmodule Outside do\n  def run, do: {Outer.Fresh.f(), Elixir.Outer.Fresh.f()}\nend\n",
        )
    }

    fun testNestedAtomDeclarationGivenAnAlias() {
        myFixture.configureByText("declaration.ex", "defmodule Outer2 do\n  defmodule :fo<caret>o do\n  end\nend\n")
        myFixture.renameTargetAtCaret("Bar")

        assertEquals("defmodule Outer2 do\n  defmodule :\"Elixir.Bar\" do\n  end\nend\n", myFixture.file.text)
    }

    fun testNonAliasNewNameIsRefusedForAnAtomWithAnAliasSpelling() =
        assertRefused("foo", "defmodule :\"Elixir.A.B<caret>\" do\nend\n")

    fun testNonAliasNewNameIsRefusedForAnAlias() = assertRefused("foo", "defmodule A.B<caret> do\nend\n")

    fun testNonAliasNewNameIsRefusedForANestedAlias() =
        assertRefused("foo", "defmodule Outer do\n  defmodule In<caret>ner do\n  end\nend\n")

    fun testNewNameWithoutAnAliasIsRefusedForAPlainAtom() = assertRefused("foo", "defmodule :fo<caret>o do\nend\n")

    fun testQuotedNewNameIsRefusedForAPlainAtom() = assertRefused(":\"a b\"", "defmodule :fo<caret>o do\nend\n")

    fun testAtomNewNameIsAcceptedForAPlainAtom() {
        myFixture.configureByText("declaration.ex", "defmodule :fo<caret>o do\nend\n")
        myFixture.renameTargetAtCaret(":bar")

        assertEquals("defmodule :bar do\nend\n", myFixture.file.text)
    }

    fun testAliasNewNameIsAcceptedForAPlainAtom() {
        myFixture.configureByText("declaration.ex", "defmodule :fo<caret>o do\nend\n")
        myFixture.renameTargetAtCaret("Bar")

        assertEquals("defmodule :\"Elixir.Bar\" do\nend\n", myFixture.file.text)
    }

    private fun moduleSymbolAtCaret(): ModuleSymbol =
        myFixture.renameTargetsAtCaret().filterIsInstance<ModuleSymbol>().single()

    private fun seedAt(declaration: String): String {
        myFixture.configureByText("declaration.ex", declaration)

        return moduleSymbolAtCaret().targetName
    }

    private fun assertRename(
        newName: String,
        declaration: String,
        caller: String,
        expectedDeclaration: String,
        expectedCaller: String,
    ) {
        val callerFile = myFixture.addFileToProject("caller.ex", caller)
        myFixture.configureByText("declaration.ex", declaration)
        myFixture.renameTargetAtCaret(newName)

        assertEquals(expectedDeclaration, myFixture.file.text)
        assertEquals(expectedCaller, textOf(callerFile))
    }

    private fun assertRefused(newName: String, declaration: String) {
        myFixture.configureByText("declaration.ex", declaration)
        val before = myFixture.file.text
        val failure = runCatching { myFixture.renameTargetAtCaret(newName) }.exceptionOrNull()

        assertTrue(
            "Renaming to $newName should be refused, but " + (failure?.let { "failed with $it" } ?: "renamed it"),
            generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("$newName is not a module name") }
        )
        assertEquals(before, myFixture.file.text)
    }

    private fun textOf(file: PsiFile): String = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!.text

    private fun nestedElixirHeaded(name: String): String =
        "defmodule Outer do\n  defmodule A.C do\n    def f, do: :ok\n  end\n\n" +
            "  defmodule $name do\n    def f, do: :ok\n  end\n\n" +
            "  def run, do: $name.f()\nend\n"

    private companion object {
        const val CALLER_OF_AB = "defmodule Caller do\n  alias A.B\n  def run, do: {B.f(), A.B.f()}\nend\n"

        val NESTED_ELIXIR_HEADED =
            "defmodule Outer do\n  defmodule A.C do\n    def f, do: :ok\n  end\n\n" +
                "  defmodule Elixir.A.<caret>D do\n    def f, do: :ok\n  end\n\n" +
                "  def run, do: Elixir.A.D.f()\nend\n"

        const val OUTSIDE_CALLER_OF_AD = "defmodule Outside do\n  def run, do: A.D.f()\nend\n"
    }
}
