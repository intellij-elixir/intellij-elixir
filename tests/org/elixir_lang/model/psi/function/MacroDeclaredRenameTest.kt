package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetsAtCaret

/**
 * A function an embed, `defexception` or an EEx `function_from_*` declares is written as that macro's call, not under
 * its own name, so a rename refuses it, from its declaration and from a use, and leaves every file as it was.
 */
@Suppress("UnstableApiUsage")
class MacroDeclaredRenameTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testEmbedFromItsName() = assertEmbedRefused(":er<caret>ror")

    fun testEmbedFromAUse() = assertEmbedRefused("error_te<caret>xt()")

    fun testExceptionFromAUse() {
        myFixture.addFileToProject("e.ex", EXCEPTION)

        assertRenameRefused(
            "defmodule U do\n  def run, do: E.excep<caret>tion(message: \"x\")\nend\n",
            "`defexception`",
            "e.ex" to EXCEPTION
        )
    }

    fun testExceptionMessageFromAUse() {
        myFixture.addFileToProject("e.ex", EXCEPTION)

        assertRenameRefused(
            "defmodule U do\n  def run(e), do: E.mess<caret>age(e)\nend\n",
            "`defexception`",
            "e.ex" to EXCEPTION
        )
    }

    /** A `defdelegate` without `as:` renames its target with it, so it is refused when its target is macro-declared. */
    fun testDelegationToAnEExFunctionFromItsHead() {
        addEEx()
        myFixture.addFileToProject("t.ex", EEX)

        assertRenameRefused(
            "defmodule D do\n  defdelegate hel<caret>lo(), to: T\nend\n",
            "`function_from_string`",
            "t.ex" to EEX,
            renamedWith = true
        )
    }

    fun testEExFromItsName() = assertEExRefused(":hel<caret>lo")

    fun testEExFromAUse() = assertEExRefused("hel<caret>lo()")

    private fun assertEmbedRefused(caret: String) {
        myFixture.addFileToProject(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_text(name, contents) do\n    {name, contents}\n  end\nend\n"
        )

        assertRenameRefused(
            EMBED.replace(caret.replace("<caret>", ""), caret),
            "`embed_text`"
        )
    }

    private fun assertEExRefused(caret: String) {
        addEEx()

        assertRenameRefused(EEX.replace(caret.replace("<caret>", ""), caret), "`function_from_string`")
    }

    private fun addEEx() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
    }

    /**
     * The rename at the caret in [text] is refused with a reason naming [macro] as what declares the renamed function,
     * or with [renamedWith] one renamed with it, and no file changes.
     */
    private fun assertRenameRefused(
        text: String,
        macro: String,
        vararg others: Pair<String, String>,
        renamedWith: Boolean = false
    ) {
        val reason = (if (renamedWith) "a function renamed with it is " else "") + "declared by $macro"
        myFixture.configureByText("caret.ex", text)
        val unchanged = myFixture.file.text
        val targets = myFixture.renameTargetsAtCaret()

        assertNotEmpty(targets)

        for (target in targets) {
            val failure = runCatching { myFixture.renameTarget(target, "fresh") }.exceptionOrNull()

            assertTrue(
                "Renaming $target should be refused as $reason, but " +
                    (failure?.let { "failed with $it" } ?: "renamed it"),
                generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(reason) }
            )
        }

        assertEquals(unchanged, myFixture.file.text)
        for ((name, expected) in others) {
            assertEquals(name, expected, myFixture.findFileInTempDir(name)?.let { String(it.contentsToByteArray()) })
        }
    }

    private companion object {
        const val EMBED = "defmodule T do\n  import Mix.Generator\n  embed_text(:error, \"x\")\n  def run, do: error_text()\nend\n"

        const val EXCEPTION = "defmodule E do\n  defexception [:message]\nend\n"

        const val EEX = "defmodule T do\n  require EEx\n  EEx.function_from_string(:def, :hello, \"hi\")\n  def run, do: hello()\nend\n"
    }
}
