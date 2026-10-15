package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret

/**
 * The name each declaring form writes is a declaration, as a `def`'s is: Ctrl+Click there shows usages, and Find Usages
 * from it lists its uses. A head that a definer's own `defmacro` writes, as `embed_template(name, contents)` in
 * `Mix.Generator`, declares nothing but the macro, so its parameters stay variables.
 */
@Suppress("UnstableApiUsage")
class DeclaringHeadTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testDef() {
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: M.snoc(1, 2)\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  def sn<caret>oc(q, x), do: [x | q]\nend\n")

        assertDeclares("use.ex:2")
    }

    fun testDelegationHead() {
        target()
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: M.snoc(1, 2)\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate sn<caret>oc(q, x), to: T\nend\n")

        assertDeclares("use.ex:2")
    }

    fun testDelegationHeadInANestedModule() {
        target()
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: Outer.Inner.snoc(1, 2)\nend\n")
        myFixture.configureByText(
            "m.ex",
            "defmodule Outer do\n  defmodule Inner do\n    defdelegate sn<caret>oc(q, x), to: T\n  end\nend\n"
        )

        assertDeclares("use.ex:2")
    }

    fun testEExName() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: T.hello(1)\nend\n")
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  EEx.function_from_string(:def, :hel<caret>lo, \"<%= a %>\", [:a])\nend\n"
        )

        assertDeclares("use.ex:2")
    }

    fun testEmbedName() {
        myFixture.addFileToProject(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_template(name, contents) do\n    {name, contents}\n  end\nend\n"
        )
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  embed_template(:lo<caret>g, \"<%= @a %>\")\n\n" +
                "  def run, do: log_template(a: 1)\nend\n"
        )

        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testEExWrittenHead() {
        myFixture.addFileToProject(
            "use.ex",
            "defmodule U do\n  require EEx\n  def run, do: EEx.function_from_file(:def, :x, \"x.eex\")\nend\n"
        )
        myFixture.configureByText(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_fi<caret>le(kind, name, file, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, file, args, options}\n  end\nend\n"
        )

        assertDeclares("use.ex:3")
        assertNameIsUsed("eex.ex:3")
    }

    fun testEmbedWrittenHead() {
        myFixture.addFileToProject(
            "use.ex",
            "defmodule U do\n  require Mix.Generator\n  def run, do: Mix.Generator.embed_template(:x, \"y\")\nend\n"
        )
        myFixture.configureByText(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_tem<caret>plate(name, contents) do\n" +
                "    {name, contents}\n  end\nend\n"
        )

        assertDeclares("use.ex:3")
        assertNameIsUsed("generator.ex:3")
    }

    fun testGuardedWrittenHead() {
        myFixture.addFileToProject(
            "use.ex",
            "defmodule U do\n  require Mix.Generator\n  def run, do: Mix.Generator.embed_template(:x, \"y\")\nend\n"
        )
        myFixture.configureByText(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_tem<caret>plate(name, contents) when is_atom(name) do\n" +
                "    {name, contents}\n  end\nend\n"
        )

        assertDeclares("use.ex:3")
        assertNameIsUsed("generator.ex:2", "generator.ex:3")
    }

    /** Kernel's own `defmacro defdelegate(funs, opts)` is shaped like a delegation of `funs`, yet `funs` is its parameter. */
    fun testDelegationWrittenHead() {
        myFixture.configureByText(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro defdelegate(funs, opts) do\n    {fu<caret>ns, opts}\n  end\nend\n"
        )

        assertEquals(
            myFixture.file.text.indexOf("funs"),
            myFixture.gotoDeclarationTargetsAtCaret()?.singleOrNull()?.destination?.textOffset
        )
    }

    fun testParenthesizedDelegationWrittenHead() {
        myFixture.configureByText(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro(defdelegate(funs, opts), do: {fu<caret>ns, opts})\nend\n"
        )

        assertEquals(
            myFixture.file.text.indexOf("funs"),
            myFixture.gotoDeclarationTargetsAtCaret()?.singleOrNull()?.destination?.textOffset
        )
    }

    fun testParenthesizedGuardedDelegationWrittenHead() {
        myFixture.configureByText(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro(defdelegate(funs, opts) when is_list(opts), do: {fu<caret>ns, opts})\nend\n"
        )

        assertEquals(
            myFixture.file.text.indexOf("funs"),
            myFixture.gotoDeclarationTargetsAtCaret()?.singleOrNull()?.destination?.textOffset
        )
    }

    /** A head named like a definer, as in Kernel's own `defmacro def(call, expr \\ nil)`. */
    fun testDefinerNamedHead() {
        myFixture.addFileToProject("use.ex", "defmodule U do\n  require K\n  def run, do: K.def(1)\nend\n")
        myFixture.configureByText("k.ex", "defmodule K do\n  defmacro de<caret>f(call, expr \\\\ nil), do: {call, expr}\nend\n")

        assertDeclares("use.ex:3")
    }

    /** An EEx name that is not an atom is a call: it declares nothing, and Go To from it lands on its definition. */
    fun testComputedEExNameIsACall() {
        eex()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  def make_name, do: :x\n  EEx.function_from_string(:def, make_na<caret>me(), \"hi\")\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("make_name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    /** A call in an embed name's interpolation runs when the embed is defined: Go To from it lands on its definition. */
    fun testCallInAnInterpolatedEmbedNameIsACall() {
        generator()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  def make_name, do: \"x\"\n" +
                "  embed_template(:\"#{make_na<caret>me()}\", \"y\")\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("make_name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    fun testFindUsagesListsACallInAnInterpolatedEmbedName() {
        generator()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  def make_na<caret>me, do: \"x\"\n" +
                "  embed_template(:\"#{make_name()}\", \"y\")\nend\n"
        )

        assertEquals(listOf("t.ex:4"), uses())
    }

    fun testCallInAnInterpolatedEExNameIsACall() {
        eex()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  def make_name, do: \"x\"\n" +
                "  EEx.function_from_string(:def, :\"#{make_na<caret>me()}\", \"hi\")\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("make_name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    fun testVariableInAnInterpolatedEmbedNameIsRead() {
        generator()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n" +
                "  for name <- [:a] do\n    embed_template(:\"#{na<caret>me}_x\", \"y\")\n  end\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    /** A call in a clause head's `unquote` runs when the clause is defined: Go To from it lands on its definition. */
    fun testCallInAnUnquotedClauseNameIsACall() {
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  def make_name, do: :x\n\n  defmacro m do\n    quote do\n" +
                "      def unquote(make_na<caret>me())(a), do: a\n    end\n  end\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("make_name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    /** A call named like an embed that is not `Mix.Generator`'s declares nothing. */
    fun testQualifiedLookalikeEmbedDeclaresNothing() {
        myFixture.addFileToProject("other.ex", "defmodule Other do\n  def embed_text(name, contents), do: {name, contents}\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  def run, do: Other.embed_text(:er<caret>ror, \"x\")\nend\n")

        myFixture.assertNoNavigationAtCaret()
    }

    fun testLocalLookalikeEmbedDeclaresNothing() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def embed_text(name, contents), do: {name, contents}\n" +
                "  def run, do: embed_text(:er<caret>ror, \"x\")\nend\n"
        )

        myFixture.assertNoNavigationAtCaret()
    }

    /** An embed's name declares even when it also names a module: Ctrl+Click there shows usages. */
    fun testEmbedNameThatNamesAModuleDeclares() {
        generator()
        myFixture.addFileToProject("target.ex", "defmodule :target do\nend\n")
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  embed_template(:tar<caret>get, \"y\")\n  def run, do: target_template([])\nend\n"
        )

        myFixture.assertShowUsagesChosenAtCaret()
    }

    /** The atom a lookalike embed takes is an ordinary atom: Go To from one naming a module lands on that module. */
    fun testQualifiedLookalikeEmbedsAtomNamesItsModule() {
        myFixture.addFileToProject("other.ex", "defmodule Other do\n  def embed_text(name, contents), do: {name, contents}\nend\n")
        myFixture.addFileToProject("target.ex", "defmodule :target do\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  def run, do: Other.embed_text(:tar<caret>get, \"x\")\nend\n")

        assertEquals(listOf("target.ex"), myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.containingFile?.name })
    }

    fun testLocalLookalikeEmbedsAtomNamesItsModule() {
        myFixture.addFileToProject("target.ex", "defmodule :target do\nend\n")
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def embed_text(name, contents), do: {name, contents}\n" +
                "  def run, do: embed_text(:tar<caret>get, \"x\")\nend\n"
        )

        assertEquals(listOf("target.ex"), myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.containingFile?.name })
    }

    private fun eex() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
    }

    private fun generator() {
        myFixture.addFileToProject(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_template(name, contents) do\n    {name, contents}\n  end\nend\n"
        )
    }

    private fun target() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def snoc(q, x), do: [x | q]\nend\n")
    }

    /** Ctrl+Click shows usages, and Find Usages from the name lists [use]. */
    private fun assertDeclares(use: String) {
        myFixture.assertShowUsagesChosenAtCaret()
        assertTrue("Find Usages: ${uses()}", use in uses())
    }

    /** Find Usages from the first parameter `name` after the caret lists exactly [expected]. */
    private fun assertNameIsUsed(vararg expected: String) {
        val text = myFixture.file.text
        myFixture.editor.caretModel.moveToOffset(text.indexOf("name", myFixture.caretOffset) + 1)

        assertEquals(expected.toList(), uses())
    }

    private fun uses(): List<String> =
        myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { usage ->
            val text = usage.file.viewProvider.document?.text ?: usage.file.text
            "${usage.file.name}:${text.substring(0, usage.range.startOffset).count { it == '\n' } + 1}"
        }.sorted()
}
