package org.elixir_lang.psi

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.documentation.ElixirDocumentationProvider
import org.elixir_lang.documentation.FetchedDocs
import org.elixir_lang.documentation.SourceFileDocsHelper
import org.elixir_lang.model.psi.callback.Callback
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.call.Call

/**
 * What a module holds includes what is written in the blocks of macros that run their block in place, or whose
 * effect only their expansion knows, but not what is written in a `quote` or a function body.
 */
@Suppress("UnstableApiUsage")
class ModuleBodyThroughBlocksTest : PlatformTestCase() {
    fun testSpecInIfFindsDefInIf() =
        assertSpecFindsDefs(1, "if true do\n    @spec <caret>f() :: :ok\n    def f, do: :ok\n  end")

    fun testSpecFindsDefInIf() =
        assertSpecFindsDefs(1, "@spec <caret>f() :: :ok\n  if true do\n    def f, do: :ok\n  end")

    fun testSpecFindsDefInCaseClause() =
        assertSpecFindsDefs(1, "@spec <caret>f() :: :ok\n  case :a do\n    :a -> def f, do: :ok\n  end")

    fun testSpecFindsDefInKeywordElse() =
        assertSpecFindsDefs(1, "@spec <caret>f() :: :ok\n  if true, do: nil, else: def(f, do: :ok)")

    fun testSpecFindsDefInKeywordAfter() =
        assertSpecFindsDefs(1, "@spec <caret>f() :: :ok\n  try do: :ok, after: def(f, do: :ok)")

    fun testSpecFindsDefInUnknownMacro() =
        assertSpecFindsDefs(1, "@spec <caret>f() :: :ok\n  describe \"x\" do\n    def f, do: :ok\n  end")

    fun testSpecDoesNotFindDefInQuote() =
        assertSpecFindsDefs(0, "@spec <caret>f() :: :ok\n  quote do\n    def f, do: :ok\n  end")

    fun testSpecDoesNotFindDefInFunctionBody() =
        assertSpecFindsDefs(0, "@spec <caret>f() :: :ok\n  def g do\n    if true do\n      def f, do: :ok\n    end\n  end")

    /** The walk up finds no module for this `def`, so the walk down must not list it either. */
    fun testSpecDoesNotFindDefInIfInCallArgument() =
        assertSpecFindsDefs(0, "@spec <caret>f() :: :ok\n  Some.helper(if true do\n    def f, do: :ok\n  end)")

    fun testDocsOfDefInIfFindItsClause() = assertDocHeads(1, "if true do\n    def <caret>f(1), do: :ok\n  end")

    fun testDocsOfDefFindClauseInIf() =
        assertDocHeads(2, "def <caret>f(1), do: :ok\n\n  if true do\n    def f(2), do: :ok\n  end")

    fun testDocsOfDefFindClauseInKeywordElse() =
        assertDocHeads(2, "def <caret>f(1), do: :ok\n\n  if true, do: nil, else: def(f(2), do: :ok)")

    fun testCompileInlineInIfFindsDefInIf() =
        assertCompileInlineFindsDef("if true do\n    @compile inline: [<caret>f: 0]\n    def f, do: :ok\n  end")

    fun testCompileInlineFindsDefInIf() =
        assertCompileInlineFindsDef("@compile inline: [<caret>f: 0]\n  if true do\n    def f, do: :ok\n  end")

    fun testDefInIfImplementsBehaviourDeclaredInIf() =
        assertDefImplementsCallback("if true do\n    @behaviour B\n    def <caret>cb, do: :ok\n  end")

    fun testDefInIfImplementsBehaviourOfModule() =
        assertDefImplementsCallback("@behaviour B\n\n  if true do\n    def <caret>cb, do: :ok\n  end")

    fun testDefInKeywordElseImplementsBehaviourOfModule() =
        assertDefImplementsCallback("@behaviour B\n\n  if true, do: nil, else: def(<caret>cb, do: :ok)")

    fun testDefImplementsBehaviourDeclaredInKeywordElse() =
        assertDefImplementsCallback("if true, do: nil, else: @behaviour B\n\n  def <caret>cb, do: :ok")

    fun testCallbackUsagesFindDefInIfWithBehaviourInIf() =
        assertCallbackUsagesFindDef("if true do\n    @behaviour B\n    def cb, do: :ok\n  end")

    fun testCallbackUsagesFindDefInIfOfModuleWithBehaviour() =
        assertCallbackUsagesFindDef("@behaviour B\n\n  if true do\n    def cb, do: :ok\n  end")

    fun testCallbackUsagesFindDefInKeywordElseOfModuleWithBehaviour() =
        assertCallbackUsagesFindDef("@behaviour B\n\n  if true, do: nil, else: def(cb, do: :ok)")

    fun testDefoverridableFindsCallbackInIf() {
        myFixture.configureByText(
            "b.ex",
            """
            defmodule B do
              if true do
                @callback cb() :: :ok
              end

              defmacro __using__(_opts) do
                quote do
                  @behaviour B

                  def cb, do: :ok

                  defoverridable <caret>cb: 0
                end
              end
            end
            """.trimIndent()
        )

        val callbacks = symbolsAtCaret().filterIsInstance<Callback>()

        assertEquals(listOf("B.cb"), callbacks.map { "${it.moduleName}.${it.name}" })
    }

    fun testDocLinkOfDefInIfFindsDefInIf() =
        assertDocLinkFindsDef("if true do\n    def <caret>f, do: :ok\n    def g, do: :ok\n  end")

    fun testDocLinkOfDefFindsDefInCase() =
        assertDocLinkFindsDef("def <caret>f, do: :ok\n\n  case :a do\n    :a -> def g, do: :ok\n  end")

    private fun assertDocLinkFindsDef(body: String) {
        configureInModule(body)

        val def = myFixture.enclosingCallAtCaret { CallDefinitionClause.`is`(it) }!!
        val target = ElixirDocumentationProvider().getDocumentationElementForLink(psiManager, "g/0", def)

        val targetDef = target?.let { PsiTreeUtil.getParentOfType(it, Call::class.java, false) }

        assertEquals("g", targetDef?.let { CallDefinitionClause.nameArityInterval(it, ResolveState.initial())?.name })
    }

    fun testModuledocControl() = assertModuledoc("@moduledoc \"doc\"")

    fun testModuledocInIf() = assertModuledoc("if true do\n    @moduledoc \"doc\"\n  end")

    /** Elixir keeps the last `@moduledoc`, warning that it redefines the first. */
    fun testModuledocLastOfTwoWins() = assertModuledoc("@moduledoc \"first\"\n  @moduledoc \"doc\"")

    fun testModuledocLastWrittenWinsAcrossBranches() =
        assertModuledoc("if Mix.env() == :prod do\n    @moduledoc \"prod\"\n  else\n    @moduledoc \"doc\"\n  end")

    /** Metadata, as in `@moduledoc since: "1.0.0"`, merges into the doc rather than replacing it. */
    fun testModuledocMetadataAfterDocKeepsDoc() = assertModuledoc("@moduledoc \"doc\"\n  @moduledoc since: \"1.0.0\"")

    fun testModuledocMetadataListAfterDocKeepsDoc() =
        assertModuledoc("@moduledoc \"doc\"\n  @moduledoc [since: \"1.0.0\"]")

    /** Elixir takes any list as metadata. */
    fun testModuledocEmptyListAfterDocKeepsDoc() = assertModuledoc("@moduledoc \"doc\"\n  @moduledoc []")

    fun testModuledocTupleListAfterDocKeepsDoc() =
        assertModuledoc("@moduledoc \"doc\"\n  @moduledoc [{:since, \"1.0.0\"}]")

    fun testModuledocMetadataBeforeDocKeepsDoc() = assertModuledoc("@moduledoc since: \"1.0.0\"\n  @moduledoc \"doc\"")

    fun testModuledocOnlyMetadataHasNoDoc() = assertNull(fetchModuleDocs("@moduledoc since: \"1.0.0\""))

    fun testModuledocFalseLastHidesDoc() = assertNull(fetchModuleDocs("@moduledoc \"doc\"\n  @moduledoc false"))

    fun testModuledocNilLastHidesDoc() = assertNull(fetchModuleDocs("@moduledoc \"doc\"\n  @moduledoc nil"))

    fun testModuledocFalseThenMetadataHidesDoc() =
        assertNull(fetchModuleDocs("@moduledoc \"doc\"\n  @moduledoc false\n  @moduledoc since: \"1.0.0\""))

    fun testModuledocFalseThenOneItCannotRenderHidesDoc() =
        assertNull(fetchModuleDocs("@moduledoc \"doc\"\n  @moduledoc false\n  @moduledoc File.read!(\"README.md\")"))

    fun testModuledocSigilHeredoc() =
        assertEquals(
            "doc\n",
            (fetchModuleDocs("@moduledoc ~S\"\"\"\n  doc\n  \"\"\"") as FetchedDocs.ModuleDocumentation).moduledoc
        )

    /**
     * Elixir would show the file; the plugin cannot read it, so it keeps the doc it can show rather than none.
     */
    fun testModuledocKeepsEarlierDocOverOneItCannotRender() =
        assertModuledoc("@moduledoc \"doc\"\n  @moduledoc File.read!(\"README.md\")")

    private fun assertModuledoc(body: String) {
        val docs = fetchModuleDocs(body)

        assertEquals("doc", assertInstanceOf(docs, FetchedDocs.ModuleDocumentation::class.java).moduledoc)
    }

    private fun fetchModuleDocs(body: String): FetchedDocs? {
        myFixture.configureByText("m.ex", "defmodule <caret>M do\n  $body\nend\n")

        return SourceFileDocsHelper.fetchDocs(myFixture.enclosingCallAtCaret { Module.`is`(it) }!!)
    }

    fun testSpecFindsDefTypedIntoIfAfterAsking() {
        configureInModule("@spec <caret>f() :: :ok\n  if true do\n    :ok\n  end")
        val spec = myFixture.caretOffset
        assertEquals(0, specResults())

        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(":ok\n  end"))
        myFixture.type("def f, do: ")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.editor.caretModel.moveToOffset(spec)

        assertEquals(1, specResults())
    }

    private fun assertSpecFindsDefs(count: Int, body: String) {
        configureInModule(body)

        assertEquals(count, specResults())
    }

    private fun specResults(): Int =
        (myFixture.getReferenceAtCaretPositionWithAssertion() as PsiPolyVariantReference)
            .multiResolve(false)
            .count { it.isValidResult }

    private fun assertDocHeads(count: Int, body: String) {
        configureInModule(body)

        val docs = SourceFileDocsHelper.fetchDocs(myFixture.enclosingCallAtCaret { CallDefinitionClause.`is`(it) }!!)

        assertEquals(count, assertInstanceOf(docs, FetchedDocs.FunctionOrMacroDocumentation::class.java).heads.size)
    }

    private fun assertCompileInlineFindsDef(body: String) {
        configureInModule(body)

        assertEquals(listOf("f/0"), symbolsAtCaret().filterIsInstance<FunctionSymbol>().map { "${it.name}/${it.arity}" })
    }

    private fun symbolsAtCaret(): List<Symbol> {
        val offset = myFixture.caretOffset

        return PsiSymbolReferenceService.getService()
            .getReferences(myFixture.enclosingCallAtCaret()!!)
            .filter { it.absoluteRange.containsOffset(offset) }
            .flatMap { it.resolveReference() }
    }

    private fun assertDefImplementsCallback(body: String) {
        configureWithBehaviour(body)

        val defClause = myFixture.enclosingCallAtCaret { CallDefinitionClause.`is`(it) }!!
        val callbacks = PsiSymbolReferenceService.getService()
            .getReferences(defClause)
            .flatMap { it.resolveReference() }
            .filterIsInstance<Callback>()

        assertEquals(listOf("B.cb"), callbacks.map { "${it.moduleName}.${it.name}" })
    }

    private fun assertCallbackUsagesFindDef(body: String) {
        configureWithBehaviour(body, callbackCaret = true)

        val implementations = myFixture.singleTargetPsiUsagesAtCaret(project)
            .count { usage ->
                usage.file.findElementAt(usage.range.startOffset)
                    ?.let { PsiTreeUtil.getParentOfType(it, Call::class.java, false) }
                    ?.let { generateSequence(it) { call -> PsiTreeUtil.getParentOfType(call, Call::class.java) } }
                    ?.any { CallDefinitionClause.`is`(it) } == true
            }

        assertEquals(1, implementations)
    }

    private fun configureInModule(body: String) {
        myFixture.configureByText("m.ex", "defmodule M do\n  $body\nend\n")
    }

    private fun configureWithBehaviour(body: String, callbackCaret: Boolean = false) {
        val name = if (callbackCaret) "<caret>cb" else "cb"

        myFixture.configureByText(
            "m.ex",
            "defmodule B do\n  @callback $name() :: :ok\nend\n\ndefmodule M do\n  $body\nend\n"
        )
    }
}
