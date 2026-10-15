package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.application.ReadAction
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret

/**
 * A definition written in a `quote` in a `__using__` keys to the module around the `quote`, marked injected, so it is
 * never that module's own definition of the same name and arity.
 */
class InjectedDefinitionTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testDeclarationAndUseShareTheInjectedSymbol() {
        val injector = myFixture.addFileToProject(
            "injector.ex",
            injector("defmacro snoc(q, x), do: quote(do: {unquote(q), unquote(x)})")
        )
        myFixture.configureByText(
            "user.ex",
            "defmodule User do\n  use Injector\n\n  def run(a, b), do: <caret>snoc(a, b)\nend\n"
        )
        val atUse = symbolsAt(myFixture.caretOffset)

        myFixture.configureFromExistingVirtualFile(injector.virtualFile)

        assertEquals(listOf("FunctionSymbol(Injector.snoc/2, macro=true, injected)"), atUse)
        assertEquals(atUse, symbolsAt(myFixture.file.text.indexOf("snoc")))
    }

    fun testInjectedIsNotTheInjectorsOwn() {
        myFixture.configureByText("injector.ex", injectorWithItsOwn())
        val text = myFixture.file.text

        assertEquals(listOf("FunctionSymbol(Injector.snoc/2, macro=false, injected)"), symbolsAt(text.indexOf("snoc")))
        assertEquals(listOf("FunctionSymbol(Injector.snoc/2, macro=false)"), symbolsAt(text.lastIndexOf("snoc")))
    }

    fun testRenameIsRefusedAtTheInjectedFromItsDeclaration() {
        myFixture.configureByText("injector.ex", injectorWithItsOwn())
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("snoc"))

        assertRenameRefused()
    }

    fun testRenameIsRefusedAtTheInjectedFromAUse() {
        myFixture.addFileToProject("injector.ex", injectorWithItsOwn())
        myFixture.configureByText("user.ex", "defmodule User do\n  use Injector\n\n  def run(a, b), do: <caret>snoc(a, b)\nend\n")

        assertRenameRefused()
    }

    fun testRenameIsRefusedAtTheInjectedFromAnAtomNamingIt() {
        myFixture.addFileToProject("injector.ex", injectorWithItsOwn())
        myFixture.configureByText(
            "user.ex",
            "defmodule User do\n  use Injector\n\n  def run(a, b), do: apply(User, :<caret>snoc, [a, b])\nend\n"
        )

        assertRenameRefused()
    }

    fun testRenameIsRefusedAtAnInjectedGuardFromAUse() {
        myFixture.addFileToProject("injector.ex", injector("defguard snoc(q, x) when is_list(q) or is_list(x)"))
        myFixture.configureByText("user.ex", "defmodule User do\n  use Injector\n\n  def run(a, b), do: <caret>snoc(a, b)\nend\n")

        assertRenameRefused()
    }

    fun testRenameIsRefusedAtAnInjectedMacroFromItsDeclaration() {
        myFixture.configureByText("injector.ex", injector("defmacro <caret>snoc(q, x), do: {q, x}"))

        assertRenameRefused()
    }

    fun testRenameIsRefusedAtAnInjectedPrivateFunctionWithLocalCalls() {
        myFixture.addFileToProject("user.ex", "defmodule User do\n  use Injector\n\n  def run(a), do: hidden(a)\nend\n")
        myFixture.configureByText("injector.ex", injector("defp <caret>hidden(x), do: x"))

        assertRenameRefused()
    }

    fun testRenameIsRefusedAtAnInjectedMacroWhoseCallsAreQualified() {
        myFixture.addFileToProject("user.ex", "defmodule User do\n  use Injector\nend\n")
        myFixture.addFileToProject(
            "caller.ex",
            "defmodule Caller do\n  require User\n\n  def run(a), do: User.snoc(a)\nend\n"
        )
        myFixture.configureByText("injector.ex", injector("defmacro <caret>snoc(q), do: {q}"))

        assertRenameRefused()
    }

    /** A module importing the one the `quote` lands in calls the function unqualified, which the rename does not find. */
    fun testRenameIsRefusedAtAnInjectedFunctionCalledThroughAnImport() {
        myFixture.addFileToProject("user.ex", "defmodule User do\n  use Injector\nend\n")
        myFixture.addFileToProject("caller.ex", "defmodule Caller do\n  import User\n\n  def run(a, b), do: snoc(a, b)\nend\n")
        myFixture.configureByText("injector.ex", injector("def <caret>snoc(q, x), do: {q, x}"))

        assertRenameRefused()
    }

    fun testRenameFromAnAtomNamingTheInjectorsOwnLeavesTheInjected() {
        myFixture.addFileToProject("injector.ex", injectorWithItsOwn())
        myFixture.configureByText(
            "user.ex",
            "defmodule User do\n  def run(a, b), do: apply(Injector, :<caret>snoc, [a, b])\nend\n"
        )

        myFixture.renameTargetAtCaret("pair")

        assertEquals(listOf("def snoc(q, x), do: {q, x}", "def pair(q, x), do: {q, x}"), definitionLines("injector.ex"))
    }

    /** A `def` that a `Kernel` macro quotes is not the module's own that an unqualified call names. */
    fun testFindUsagesOfAKernelQuotedDefinitionLeavesAModulesOwnCalls() {
        myFixture.addFileToProject("u.ex", "defmodule U do\n  def exception(x), do: x\n\n  def run, do: exception(1)\nend\n")
        myFixture.configureByText(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro defexc(_) do\n    quote do\n      def <caret>exception(msg), do: msg\n    end\n  end\nend\n"
        )

        assertEquals(emptyList<String>(), myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { it.file.name })
    }

    /**
     * The rename at the caret is refused because a `quote` declares the function, whose calls are in modules not known
     * until the `quote` is used, and no file changes.
     */
    private fun assertRenameRefused() {
        val unchanged = fileTexts()
        val targets = myFixture.renameTargetsAtCaret()

        assertNotEmpty(targets)

        for (target in targets) {
            val failure = runCatching { myFixture.renameTarget(target, "pair") }.exceptionOrNull()

            assertTrue(
                "Renaming $target should be refused as declared by `quote`, but " +
                    (failure?.let { "failed with $it" } ?: "renamed it"),
                generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("declared by `quote`") }
            )
        }

        assertEquals(unchanged, fileTexts())
    }

    /** Each file's text, by name. */
    private fun fileTexts(): Map<String, String> =
        readAction { myFixture.psiManager.findDirectory(myFixture.tempDirFixture.getFile("")!!)!!.files.associate { it.name to it.text } }

    private fun symbolsAt(offset: Int): List<String> =
        readAction { targetSymbols(myFixture.file, offset).map { it.toString() } }

    /** [name]'s `def` lines, trimmed, in source order. */
    private fun definitionLines(name: String): List<String> =
        readAction { myFixture.psiManager.findFile(myFixture.findFileInTempDir(name))!!.text }
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("def ") }

    private fun <T> readAction(block: () -> T): T = ReadAction.computeBlocking<T, Throwable> { block() }

    /** The injector, with a `def snoc` of its own after the `__using__` that injects one. */
    private fun injectorWithItsOwn(): String =
        injector("def snoc(q, x), do: {q, x}").replace("end\nend\n", "end\n\n  def snoc(q, x), do: {q, x}\nend\n")

    private fun injector(definition: String): String =
        "defmodule Injector do\n" +
            "  defmacro __using__(_) do\n    quote unquote: false do\n      $definition\n    end\n  end\n" +
            "end\n"
}
