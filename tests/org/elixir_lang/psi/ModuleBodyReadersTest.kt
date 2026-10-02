package org.elixir_lang.psi

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.navigation.ImplementationSearcher
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.mix.DepGatherer
import org.elixir_lang.mix.Test
import org.elixir_lang.mix.project.OtpApp
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.structure_view.Model
import org.elixir_lang.structure_view.element.CallDefinition
import org.elixir_lang.structure_view.node_provider.Used

/**
 * Each feature that reads what a module defines finds a definition written inside an `if` in the module body, as
 * it finds one written directly in it.
 */
class ModuleBodyReadersTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang"

    override fun setUp() {
        super.setUp()
        // Go To Declaration needs a real DataContext.
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testImplementationGutterFindsProtocolFunctionInIf() {
        myFixture.configureByText(
            "x.ex",
            "defprotocol IfP do\n  if true do\n    def f(t)\n  end\nend\n\ndefimpl IfP, for: Atom do\n  def <caret>f(t), do: t\nend\n"
        )

        assertTargetsInclude(markerTargetsAtCaret(org.elixir_lang.code_insight.line_marker_provider.Implementation()), "def f(t)\n")
    }

    fun testProtocolGutterFindsImplementationInIf() {
        myFixture.configureByText(
            "x.ex",
            "defprotocol IfP do\n  def <caret>f(t)\nend\n\ndefimpl IfP, for: Atom do\n  if true do\n    def f(t), do: t\n  end\nend\n"
        )

        assertTargetsInclude(markerTargetsAtCaret(org.elixir_lang.code_insight.line_marker_provider.Protocol()), "def f(t), do: t")
    }

    fun testImplementationGutterFindsProtocolFunctionControl() {
        myFixture.configureByText(
            "x.ex",
            "defprotocol IfP do\n  def f(t)\nend\n\ndefimpl IfP, for: Atom do\n  def <caret>f(t), do: t\nend\n"
        )

        assertTargetsInclude(markerTargetsAtCaret(org.elixir_lang.code_insight.line_marker_provider.Implementation()), "def f(t)\n")
    }

    fun testGotoSuperFindsProtocolFunctionInIf() {
        myFixture.configureByText(
            "x.ex",
            "defprotocol IfSuperP do\n  if true do\n    def run(value)\n  end\nend\n\ndefimpl IfSuperP, for: Atom do\n  def ru<caret>n(value), do: value\nend\n"
        )

        myFixture.performEditorAction(IdeActions.ACTION_GOTO_SUPER)

        assertEquals(myFixture.file.text.indexOf("def run(value)\n") + "def ".length, myFixture.caretOffset)
    }

    fun testGotoImplementationFindsImplementationInIf() {
        myFixture.configureByText(
            "x.ex",
            "defprotocol IfGotoP do\n  def per<caret>form()\nend\n\ndefimpl IfGotoP, for: Atom do\n  if true do\n    def perform, do: :ok\n  end\nend\n"
        )

        val source = TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, ImplementationSearcher.getFlags(), myFixture.caretOffset)!!
        val implementations = DefinitionsScopedSearch.search(source).findAll()

        assertTargetsInclude(implementations.toList(), "def perform, do")
    }

    fun testGotoImplementationFindsImplementationInKeywordElse() {
        myFixture.configureByText(
            "x.ex",
            "defprotocol ElseGotoP do\n  def per<caret>form()\nend\n\ndefimpl ElseGotoP, for: Atom do\n  if true, do: nil, else: def(perform, do: :ok)\nend\n"
        )

        val source = TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, ImplementationSearcher.getFlags(), myFixture.caretOffset)!!
        val implementations = DefinitionsScopedSearch.search(source).findAll()

        assertTargetsInclude(implementations.toList(), "def(perform, do")
    }

    fun testImplementationFunctionResolvesToProtocolFunctionInIf() {
        myFixture.configureByText(
            "x.ex",
            "defprotocol IfDeclP do\n  if true do\n    def perform()\n  end\nend\n\ndefimpl IfDeclP, for: Atom do\n  def per<caret>form, do: :ok\nend\n"
        )

        val protocolClause = myFixture.assertGotoDeclarationLandsIn("perform", "a protocol function") {
            CallDefinitionClause.`is`(it)
        }

        assertTrue(Protocol.`is`(CallDefinitionClause.enclosingModularMacroCall(protocolClause)!!))
    }

    fun testImplementationResolvesToCallbackInIf() {
        myFixture.copyFileToProject("model/psi/callback/kernel.ex", "kernel.ex")
        myFixture.configureByText(
            "x.ex",
            "defmodule IfCbB do\n  if true do\n    @callback perform() :: any\n  end\nend\n\ndefmodule IfCbImpl do\n  @behaviour IfCbB\n\n  def per<caret>form, do: :ok\nend\n"
        )

        val target = myFixture.gotoDeclarationDestinationAtCaret()
        val attribute = generateSequence(target) { it.parent }.filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>().firstOrNull()

        assertEquals("@callback", attribute?.let(ElixirPsiImplUtil::moduleAttributeName))
    }

    fun testRemoteCompletionOffersDefInIf() {
        myFixture.configureByText(
            "x.ex",
            "defmodule IfComp do\n  def a, do: 1\n  def b, do: 2\n\n  if true do\n    def inside, do: 3\n  end\nend\n\ndefmodule IfCompUse do\n  def f do\n    IfComp.<caret>\n  end\nend\n"
        )

        myFixture.complete(CompletionType.BASIC, 1)

        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "inside")
    }

    fun testRemoteCompletionOffersDefInKeywordElse() {
        myFixture.configureByText(
            "x.ex",
            "defmodule ElseComp do\n  def a, do: 1\n\n  if true, do: nil, else: def(inside, do: 3)\nend\n\ndefmodule ElseCompUse do\n  def f do\n    ElseComp.<caret>\n  end\nend\n"
        )

        myFixture.complete(CompletionType.BASIC, 1)

        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "inside")
    }

    fun testGenServerRequestFindsHandlerInIf() {
        myFixture.configureByText(
            "stack.ex",
            "defmodule Stack do\n  use GenServer\n\n  def pop(pid), do: GenServer.call(pid, :<caret>pop)\n\n  if true do\n    def handle_call(:pop, _from, [head | tail]) do\n      {:reply, head, tail}\n    end\n  end\nend\n"
        )

        val destination = myFixture.gotoDeclarationDestinationAtCaret()
        val clause = generateSequence(destination) { it.parent }.filterIsInstance<Call>().firstOrNull { CallDefinitionClause.`is`(it) }

        assertEquals("handle_call", clause?.let { CallDefinitionClause.nameArityInterval(it, com.intellij.psi.ResolveState.initial())?.name })
    }

    fun testLiveViewAssignFindsAssignInIf() {
        myFixture.addFileToProject(
            "lib/w/live/c_live.ex",
            "defmodule CLive do\n  if true do\n    def mount(_p, _s, socket) do\n      assign(socket, :count, 0)\n    end\n  end\nend\n"
        )
        val template = myFixture.addFileToProject("lib/w/live/c_live.html.leex", "<%= @count %>")
        myFixture.configureFromExistingVirtualFile(template.virtualFile)
        val elixir = myFixture.file.viewProvider.allFiles.first { it.language.id == "Elixir" }
        val at = PsiTreeUtil.findChildOfType(elixir, AtOperation::class.java)!!

        assertTrue((at.reference as PsiPolyVariantReference).multiResolve(false).any { it.isValidResult })
    }

    fun testUseFindsUsingInIf() {
        myFixture.configureByText(
            "x.ex",
            "defmodule IfUsing do\n  if true do\n    defmacro __using__(_o) do\n      quote do\n        def injected_by_using(), do: :ok\n      end\n    end\n  end\nend\n\ndefmodule IfClient do\n  use IfUsing\n\n  def usage do\n    injected_by_using<caret>\n  end\nend\n"
        )

        assertTrue(validTexts().toString(), validTexts().any { it.startsWith("def injected_by_using") })
    }

    fun testUseFindsUsingInKeywordElse() {
        myFixture.configureByText(
            "x.ex",
            "defmodule ElseUsing do\n  if true, do: nil, else: (defmacro __using__(_o) do\n    quote do\n      def injected_by_using(), do: :ok\n    end\n  end)\nend\n\ndefmodule ElseClient do\n  use ElseUsing\n\n  def usage do\n    injected_by_using<caret>\n  end\nend\n"
        )

        assertTrue(validTexts().toString(), validTexts().any { it.startsWith("def injected_by_using") })
    }

    fun testUseOfCaseTemplateInIf() {
        myFixture.configureByText(
            "x.ex",
            "defmodule ExUnit.Case do\n  defmacro __using__(_o) do\n    quote do\n      def from_case(), do: :ok\n    end\n  end\nend\n\ndefmodule ExUnit.CaseTemplate do\nend\n\ndefmodule MyCase do\n  if true do\n    use ExUnit.CaseTemplate\n  end\nend\n\ndefmodule MyTest do\n  use MyCase\n\n  def u do\n    from_case<caret>\n  end\nend\n"
        )

        assertTrue(validTexts().toString(), validTexts().any { it.startsWith("def from_case") })
    }

    fun testUseWithArgumentFindsDefInIfControl() = assertUseWithArgument(inIf = false)

    fun testUseWithArgumentFindsDefInIf() = assertUseWithArgument(inIf = true)

    fun testSpecFindsTypeInIf() {
        myFixture.configureByText(
            "x.ex",
            "defmodule IfType do\n  if true do\n    @type user_id :: integer()\n  end\n\n  @spec id(user_<caret>id()) :: user_id()\n  def id(v), do: v\nend\n"
        )

        myFixture.assertGotoDeclarationLandsIn("user_id", "a @type") {
            ElixirPsiImplUtil.moduleAttributeName(it as? AtUnqualifiedNoParenthesesCall<*> ?: return@assertGotoDeclarationLandsIn false) == "@type"
        }
    }

    fun testUnqualifiedCallFindsDefInCase() {
        myFixture.configureByText(
            "x.ex",
            "defmodule CaseDef do\n  def caller do\n    defined_in_case<caret>()\n  end\n\n  case :erlang.system_info(:otp_release) do\n    _ ->\n      def defined_in_case, do: :ok\n  end\nend\n"
        )

        assertContainsElements(validTexts(), "def defined_in_case, do: :ok")
    }

    fun testUnqualifiedCallFindsDefInKeywordElse() =
        assertCallFindsDefInKeyword("if true, do: nil, else: def(defined, do: :ok)")

    fun testUnqualifiedCallFindsDefInKeywordUnlessElse() =
        assertCallFindsDefInKeyword("unless true, do: nil, else: def(defined, do: :ok)")

    fun testUnqualifiedCallFindsDefInKeywordRescue() =
        assertCallFindsDefInKeyword("try do: :ok, rescue: (_ -> def(defined, do: :ok))")

    fun testUnqualifiedCallFindsDefInKeywordWithElse() =
        assertCallFindsDefInKeyword("with :ok <- :ok, do: nil, else: (_ -> def(defined, do: :ok))")

    fun testUnqualifiedCallFindsDefInKeywordReceiveAfter() =
        assertCallFindsDefInKeyword("receive do: (_ -> nil), after: (0 -> def(defined, do: :ok))")

    private fun assertCallFindsDefInKeyword(block: String) {
        myFixture.configureByText("x.ex", "defmodule M do\n  def caller do\n    defined<caret>()\n  end\n\n  $block\nend\n")

        assertContainsElements(validTexts(), "def(defined, do: :ok)")
    }

    fun testCallInTestFindsLaterDefp() =
        assertCallFindsLaterDefp("test \"t\" do\n    helper<caret>()\n  end")

    fun testCallInTestInDescribeFindsLaterDefp() =
        assertCallFindsLaterDefp("describe \"d\" do\n    test \"t\" do\n      helper<caret>()\n    end\n  end")

    fun testCallInRouteFindsLaterDefp() =
        assertCallFindsLaterDefp("get \"/\" do\n    helper<caret>()\n  end")

    /** Elixir compiles a module-level block's branches before any `def` after it, so the `def` is not yet defined. */
    fun testCallInElseDoesNotFindLaterDefControl() =
        assertCallDoesNotFindLaterDef("if true do\n    nil\n  else\n    helper<caret>()\n  end")

    fun testCallInKeywordElseDoesNotFindLaterDef() =
        assertCallDoesNotFindLaterDef("if true, do: nil, else: helper<caret>()")

    fun testCallInKeywordDoDoesNotFindLaterDef() = assertCallDoesNotFindLaterDef("if true, do: helper<caret>()")

    fun testCallInParenthesizedKeywordElseDoesNotFindLaterDef() =
        assertCallDoesNotFindLaterDef("if true, do: nil, else: (x = 1; helper<caret>())")

    fun testCallInParenthesizedBlockDoesNotFindLaterDef() =
        assertCallDoesNotFindLaterDef("if true do\n    (x = 1; helper<caret>())\n  end")

    /** The `else:` branch's `def` is a statement of the module, but the guard on the `do:` branch still applies. */
    fun testCallInKeywordDoDoesNotFindDefInKeywordElse() =
        assertCallDoesNotFind("if true, do: helper<caret>(), else: def(helper, do: 1)")

    fun testCallInParenthesizedKeywordDoDoesNotFindDefInKeywordElse() =
        assertCallDoesNotFind("if true, do: (x = 1; helper<caret>()), else: def(helper, do: 1)")

    fun testCallInKeywordElseDoesNotFindDefInKeywordDo() =
        assertCallDoesNotFind("if true, do: def(helper, do: 1), else: helper<caret>()")

    private fun assertCallDoesNotFindLaterDef(block: String) = assertCallDoesNotFind("$block\n\n  def helper, do: 1")

    private fun assertCallDoesNotFind(body: String) {
        myFixture.configureByText("x.ex", "defmodule M do\n  $body\nend\n")

        assertEquals(emptyList<String>(), validTexts())
    }

    fun testUseOfQuotedIfInjectsDefinitionsControl() =
        assertUseOfQuotedIfInjects("if true do\n        def f, do: 1\n      end", "def f, do: 1")

    fun testUseOfQuotedKeywordIfInjectsDefinitions() =
        assertUseOfQuotedIfInjects("if true, do: def(f, do: 1)", "def(f, do: 1)")

    fun testUseOfQuotedKeywordElseInjectsDefinitions() =
        assertUseOfQuotedIfInjects("if true, do: nil, else: def(f, do: 1)", "def(f, do: 1)")

    private fun assertUseOfQuotedIfInjects(block: String, definition: String) {
        myFixture.configureByText(
            "x.ex",
            "defmodule Injector do\n  defmacro __using__(_) do\n    quote do\n      $block\n    end\n  end\nend\n\n" +
                "defmodule User do\n  use Injector\n\n  def caller, do: f<caret>()\nend\n"
        )

        assertContainsElements(validTexts(), definition)
    }

    fun testImportFindsDefControl() = assertImportFindsDef(inIf = false)

    fun testImportFindsDefInIf() = assertImportFindsDef(inIf = true)

    fun testImportFindsDefInKeywordElse() {
        myFixture.configureByText(
            "x.ex",
            "defmodule Imported do\n  if true, do: nil, else: def(imported, do: :ok)\nend\n\n" +
                "defmodule Importer do\n  import Imported\n\n  def caller, do: imported<caret>()\nend\n"
        )

        assertContainsElements(validTexts(), "def(imported, do: :ok)")
    }

    fun testRelativeAliasFindsModuleControl() =
        assertEquals(listOf("defmodule Inner do"), relativeAliasTargets(inIf = false))

    /** Elixir's alias of a nested module ends with the block the module is defined in. */
    fun testRelativeAliasDoesNotFindModuleInIf() = assertEquals(emptyList<String>(), relativeAliasTargets(inIf = true))

    fun testQualifiedAliasFindsModuleInIf() {
        myFixture.configureByText(
            "x.ex",
            "defmodule Outer do\n  if true do\n    defmodule Inner do\n    end\n  end\nend\n\n" +
                "defmodule User do\n  def caller, do: Outer.Inner<caret>\nend\n"
        )

        assertEquals(listOf("defmodule Inner do"), aliasTargets(myFixture.file.findReferenceAt(myFixture.caretOffset - 1)))
    }

    fun testQualifiedAliasFindsModuleInKeywordElse() {
        myFixture.configureByText(
            "x.ex",
            "defmodule Outer do\n  if true, do: nil, else: (defmodule Inner do\n  end)\nend\n\n" +
                "defmodule User do\n  def caller, do: Outer.Inner<caret>\nend\n"
        )

        assertEquals(listOf("defmodule Inner do"), aliasTargets(myFixture.file.findReferenceAt(myFixture.caretOffset - 1)))
    }

    fun testImportInCaseDoesNotReachTheModule() =
        assertImportDoesNotReach("case :a do\n    :a -> import Imported\n  end\n\n  def caller, do: imported<caret>()")

    fun testAliasInModuleReachesIt() = assertEquals(listOf("def x, do: :ok"), aliasedCallTargets("alias Other.Target"))

    fun testAliasInCaseDoesNotReachTheModule() =
        assertEquals(emptyList<String>(), aliasedCallTargets("case :a do\n    :a -> alias Other.Target\n  end"))

    private fun aliasedCallTargets(alias: String): List<String> {
        myFixture.configureByText(
            "x.ex",
            "defmodule Other.Target do\n  def x, do: :ok\nend\n\n" +
                "defmodule Aliaser do\n  $alias\n\n  def caller, do: Target.x<caret>()\nend\n"
        )

        return validTexts()
    }

    fun testEExTemplateFindsFunctionFromFileControl() = assertEExTemplateFindsFunctionFromFile(inIf = false)

    fun testEExTemplateFindsFunctionFromFileInIf() = assertEExTemplateFindsFunctionFromFile(inIf = true)

    /** Each `from` resolving the other's scope walk would be a resolution cycle, reported as an error log. */
    fun testQueriesInTwoTestsResolveWithoutACycle() {
        myFixture.addFileToProject("lib/ecto/query.ex", "defmodule Ecto.Query do\n  defmacro from(expr, kw \\\\ []), do: nil\nend\n")
        myFixture.configureByText(
            "x_test.exs",
            "defmodule QueryTest do\n  import Ecto.Query\n\n" +
                "  test \"a\" do\n    from(p in Post, select: p)\n  end\n\n" +
                "  test \"b\" do\n    fr<caret>om(p in Post, select: p)\n  end\nend\n"
        )

        assertContainsElements(validTexts(), "defmacro from(expr, kw \\\\ []), do: nil")
    }

    fun testImportInOneTestDoesNotReachAnother() =
        assertImportDoesNotReach("test \"a\" do\n    import Imported\n  end\n\n  test \"b\" do\n    imported<caret>()\n  end")

    fun testUseInIfInjectsDefinitions() {
        myFixture.configureByText(
            "x.ex",
            "defmodule Injector do\n  defmacro __using__(_) do\n    quote do\n      def injected, do: :ok\n    end\n  end\nend\n\n" +
                "defmodule User do\n  if true do\n    use Injector\n  end\n\n  def caller, do: injected<caret>()\nend\n"
        )

        assertContainsElements(validTexts(), "def injected, do: :ok")
    }

    /** Every reader above takes a module's calls from here, so none of them sees an operand as a statement. */
    fun testModularChildCallsAreTheModulesStatements() {
        myFixture.configureByText(
            "x.ex",
            """
            defmodule M do
              @spec a() :: {:ok, t}
              def a, do: {:ok, helper(1)}
              @x [foo: bar()]
              if true do
                def b, do: 1
              else
                def c, do: 2
              end
              case :a do
                :a -> def d, do: 3
              end
              if true, do: def(e, do: 4)
              if true, do: [def(i, do: 8)]
              if true, "do": def(j, do: 9)
              if true, do: nil, else: def(k, do: 10)
              try do: :ok, after: def(l, do: 11)
              try do: :ok, rescue: (_ -> def(m, do: 12))
              try do: :ok, catch: (_ -> def(n, do: 13))
              if true, do: nil, "else": def(o, do: 14)
              if true, do: nil, else: [def(p, do: 15)]
              Some.fun(x, do: 1, else: def(q, do: 16))
              Keyword.keys(else: def(r, do: 17))
              if(true, [do: nil, else: def(s, do: 18)])
              (def g, do: 5)
              [def(h, do: 7)]
              describe "x" do
                test "y" do
                  run(z())
                end
              end
              Some.helper(if true do def f, do: 6 end)
            end
            """.trimIndent()
        )
        val module = PsiTreeUtil.findChildOfType(myFixture.file, Call::class.java)!!

        assertEquals(
            listOf(
                "@spec a() :: {:ok, t}",
                "def a, do: {:ok, helper(1)}",
                "@x [foo: bar()]",
                "if true do",
                "def b, do: 1",
                "def c, do: 2",
                "case :a do",
                "def d, do: 3",
                "if true, do: def(e, do: 4)",
                "def(e, do: 4)",
                "if true, do: [def(i, do: 8)]",
                "def(i, do: 8)",
                "if true, \"do\": def(j, do: 9)",
                "def(j, do: 9)",
                "if true, do: nil, else: def(k, do: 10)",
                "def(k, do: 10)",
                "try do: :ok, after: def(l, do: 11)",
                "def(l, do: 11)",
                "try do: :ok, rescue: (_ -> def(m, do: 12))",
                "def(m, do: 12)",
                "try do: :ok, catch: (_ -> def(n, do: 13))",
                "def(n, do: 13)",
                "if true, do: nil, \"else\": def(o, do: 14)",
                "def(o, do: 14)",
                "if true, do: nil, else: [def(p, do: 15)]",
                "def(p, do: 15)",
                "Some.fun(x, do: 1, else: def(q, do: 16))",
                "def(q, do: 16)",
                "Keyword.keys(else: def(r, do: 17))",
                "if(true, [do: nil, else: def(s, do: 18)])",
                "def g, do: 5",
                "def(h, do: 7)",
                "describe \"x\" do",
                "test \"y\" do",
                "run(z())",
                "Some.helper(if true do def f, do: 6 end)"
            ),
            CallDefinitionClause.modularChildCalls(module).map { it.text.lines().first().trim() }
        )
    }

    fun testEExFunctionFromControl() = assertEquals(listOf(EEX_DEFINER), eexFunctionFromTargets(inDescribe = false))

    fun testEExFunctionFromInDescribe() = assertEquals(listOf(EEX_DEFINER), eexFunctionFromTargets(inDescribe = true))

    private fun eexFunctionFromTargets(inDescribe: Boolean): List<String> =
        definerTargets(
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n",
            "require EEx",
            EEX_DEFINER,
            inDescribe,
            "render<caret>(1)"
        )

    fun testImportedEExFunctionFromInDescribe() =
        assertEquals(listOf(IMPORTED_DEFINER), importedFunctionFromTargets("EEx"))

    /** Only resolving a `function_from_*` to `EEx` makes it a definer; the same call imported from elsewhere is not. */
    fun testFunctionFromOfAnotherModuleInDescribeDefinesNothing() =
        assertEquals(emptyList<String>(), importedFunctionFromTargets("Other"))

    private fun importedFunctionFromTargets(module: String): List<String> =
        definerTargets(
            "defmodule $module do\n  defmacro function_from_string(kind, name, source, args) do\n" +
                "    {kind, name, source, args}\n  end\nend\n",
            "import $module",
            IMPORTED_DEFINER,
            inDescribe = true,
            "render<caret>(1)"
        )

    fun testEmbedTemplateControl() = assertEquals(listOf(EMBED_DEFINER), embedTemplateTargets(inDescribe = false))

    fun testEmbedTemplateInDescribe() = assertEquals(listOf(EMBED_DEFINER), embedTemplateTargets(inDescribe = true))

    private fun embedTemplateTargets(inDescribe: Boolean): List<String> =
        definerTargets(
            "defmodule Mix.Generator do\n  defmacro embed_template(name, contents) do\n    {name, contents}\n  end\nend\n",
            "import Mix.Generator",
            EMBED_DEFINER,
            inDescribe,
            "foo_template<caret>(1)"
        )

    /** [library] stands in for the SDK module that defines [definer]. */
    private fun definerTargets(
        library: String,
        directive: String,
        definer: String,
        inDescribe: Boolean,
        call: String
    ): List<String> {
        myFixture.addFileToProject("library.ex", library)
        val definition = inDescribeOrNot(inDescribe, definer)
        myFixture.configureByText(
            "x.ex",
            "defmodule M do\n  $directive\n\n  $definition\n\n  def caller, do: $call\nend\n"
        )

        return validTexts()
    }

    fun testVariableInFnIsNotAType() = assertNotAType("@x Enum.map([1], fn t -> t end)")

    fun testVariableInTestIsNotAType() = assertNotAType("test \"x\" do\n    t = 1\n    t\n  end")

    fun testVariableInIfIsNotAType() = assertNotAType("if true do\n    t\n  end")

    private fun assertNotAType(block: String) {
        myFixture.configureByText("x.ex", "defmodule M do\n  $block\n\n  @spec a() :: <caret>t\n  def a, do: 1\nend\n")
        val type = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), Call::class.java)!!

        assertEquals(
            emptyList<String>(),
            org.elixir_lang.psi.scope.type.MultiResolve.resolveResults("t", 0, false, type)
                .filter { it.isValidResult }
                .map { it.element!!.text }
        )
    }

    fun testUseInIfAfterTheCallInjectsDefinitions() {
        myFixture.configureByText(
            "x.ex",
            "defmodule Injector do\n  defmacro __using__(_) do\n    quote do\n      def injected, do: :ok\n    end\n  end\nend\n\n" +
                "defmodule User do\n  def caller, do: injected<caret>()\n\n  if true do\n    use Injector\n  end\nend\n"
        )

        assertContainsElements(validTexts(), "def injected, do: :ok")
    }

    /** ExUnit runs a `describe` block at module level, so what its `use` injects is the module's. */
    fun testUseInDescribeInjectsDefinitions() {
        myFixture.configureByText(
            "x.ex",
            "defmodule Injector do\n  defmacro __using__(_) do\n    quote do\n      def injected, do: :ok\n    end\n  end\nend\n\n" +
                "defmodule User do\n  ${inDescribeOrNot(true, "use Injector")}\n\n  def caller, do: injected<caret>()\nend\n"
        )

        assertContainsElements(validTexts(), "def injected, do: :ok")
    }

    fun testImportInIfDoesNotReachTheModule() =
        assertImportDoesNotReach("if true do\n    import Imported\n  end\n\n  def caller, do: imported<caret>()")

    fun testImportInElseDoesNotReachTheModule() =
        assertImportDoesNotReach("if false do\n    :ok\n  else\n    import Imported\n  end\n\n  def caller, do: imported<caret>()")

    fun testImportInUnlessDoesNotReachTheModule() =
        assertImportDoesNotReach("unless false do\n    import Imported\n  end\n\n  def caller, do: imported<caret>()")

    /** A caller before the block is reached through the module's calls rather than its previous siblings. */
    fun testImportInIfDoesNotReachAnEarlierCaller() =
        assertImportDoesNotReach("def caller, do: imported<caret>()\n\n  if true do\n    import Imported\n  end")

    fun testImportInIfReachesACallerInIt() {
        myFixture.configureByText(
            "x.ex",
            "defmodule Imported do\n  def imported, do: :ok\nend\n\n" +
                "defmodule Importer do\n  if true do\n    import Imported\n\n    def caller, do: imported<caret>()\n  end\nend\n"
        )

        assertContainsElements(validTexts(), "def imported, do: :ok")
    }

    fun testImportInIfInDefDoesNotReachALaterCall() =
        assertImportDoesNotReach("def caller do\n    if true do\n      import Imported\n    end\n\n    imported<caret>()\n  end")

    fun testAliasInIfDoesNotReachTheModule() =
        assertEquals(emptyList<String>(), aliasedCallTargets("if true do\n    alias Other.Target\n  end"))

    private fun assertImportDoesNotReach(body: String) {
        myFixture.configureByText(
            "x.ex",
            "defmodule Imported do\n  def imported, do: :ok\nend\n\ndefmodule Importer do\n  $body\nend\n"
        )

        assertEquals(emptyList<String>(), validTexts())
    }

    fun testTemplateAliasFindsModuleControl() =
        assertEquals(listOf("defmodule Inner do"), templateAliasTargets(inIf = false))

    fun testTemplateAliasDoesNotFindModuleInIf() = assertEquals(emptyList<String>(), templateAliasTargets(inIf = true))

    private fun templateAliasTargets(inIf: Boolean): List<String> {
        myFixture.addFileToProject(
            "lib/w/views/page_view.ex",
            "defmodule PageView do\n  ${inIfOrNot(inIf, "defmodule Inner do\n  end")}\nend\n"
        )
        val template = myFixture.addFileToProject("lib/w/templates/page/index.html.eex", "<%= Inner %>")
        myFixture.configureFromExistingVirtualFile(template.virtualFile)

        return aliasTargets(
            myFixture.file.viewProvider.findReferenceAt(template.text.indexOf("Inner") + 1, org.elixir_lang.ElixirLanguage)
        )
    }

    private fun assertImportFindsDef(inIf: Boolean) {
        myFixture.configureByText(
            "x.ex",
            "defmodule Imported do\n  ${inIfOrNot(inIf, "def imported, do: :ok")}\nend\n\n" +
                "defmodule Importer do\n  import Imported\n\n  def caller, do: imported<caret>()\nend\n"
        )

        assertContainsElements(validTexts(), "def imported, do: :ok")
    }

    private fun relativeAliasTargets(inIf: Boolean): List<String> {
        myFixture.configureByText(
            "x.ex",
            "defmodule Outer do\n  ${inIfOrNot(inIf, "defmodule Inner do\n  end")}\n\n  def caller, do: Inner<caret>\nend\n"
        )

        return aliasTargets(myFixture.file.findReferenceAt(myFixture.caretOffset - 1))
    }

    private fun aliasTargets(reference: PsiReference?): List<String> =
        (reference as PsiPolyVariantReference).multiResolve(false)
            .filter { it.isValidResult }
            .map { it.element!!.text.lines().first() }

    private fun assertEExTemplateFindsFunctionFromFile(inIf: Boolean) {
        myFixture.addFileToProject(
            "x.ex",
            "defmodule X do\n  require EEx\n\n  " +
                inIfOrNot(inIf, "EEx.function_from_file(:def, :render, Path.expand(\"t.eex\", __DIR__))") +
                "\nend\n"
        )
        myFixture.configureByText("t.eex", "<%= 1 %>")

        val elixirRoot = myFixture.file.viewProvider.getPsi(org.elixir_lang.ElixirLanguage) as ElixirFile

        assertEquals("x.ex", elixirRoot.viewFile()?.name)
    }

    private fun inIfOrNot(inIf: Boolean, code: String): String =
        if (inIf) "if true do\n    $code\n  end" else code

    private fun inDescribeOrNot(inDescribe: Boolean, code: String): String =
        if (inDescribe) "describe \"x\" do\n    $code\n  end" else code

    private fun assertCallFindsLaterDefp(block: String) {
        myFixture.configureByText("x.ex", "defmodule M do\n  $block\n\n  defp helper, do: 1\nend\n")

        assertEquals(listOf("defp helper, do: 1"), validTexts())
    }

    fun testStructureViewUsedFindsUsingInIf() {
        myFixture.configureByText(
            "x.ex",
            "defmodule IfUsed do\n  if true do\n    defmacro __using__(_o) do\n      quote do\n        def injected_by_use, do: :ok\n      end\n    end\n  end\nend\n\ndefmodule IfUser do\n  use IfUsed\nend\n"
        )
        val root = Model(myFixture.file as ElixirFile, null).root
        val user = root.children[1] as com.intellij.ide.util.treeView.smartTree.TreeElement

        val used = Used().provideNodes(user)

        assertFalse("nothing shown for the `use` of a module whose __using__ is in an if", used.isEmpty())
    }

    fun testStructureViewNestsDefInIf() = assertStructureViewNestsDef("if true do\n    def f, do: 1\n  end")

    fun testStructureViewNestsDefInCaseClause() = assertStructureViewNestsDef("case :a do\n    :a -> def f, do: 1\n  end")

    fun testStructureViewNestsDefInCondClause() = assertStructureViewNestsDef("cond do\n    true -> def f, do: 1\n  end")

    fun testStructureViewNestsDefInReceiveClause() =
        assertStructureViewNestsDef("receive do\n    :a -> def f, do: 1\n  end")

    fun testStructureViewNestsDefInElse() =
        assertStructureViewNestsDef("if true do\n    def f, do: 1\n  else\n    def g, do: 2\n  end", "def g, do: 2")

    fun testStructureViewNestsDefInRescueClause() =
        assertStructureViewNestsDef("try do\n    :ok\n  rescue\n    _ -> def h, do: 3\n  end", "def h, do: 3")

    fun testStructureViewNestsDefInKeywordElse() =
        assertStructureViewNestsDef("if true, do: nil, else: def(f, do: 1)", "def(f, do: 1)")

    fun testStructureViewNestsDefInKeywordRescueClause() =
        assertStructureViewNestsDef("try do: :ok, rescue: (_ -> def h, do: 3)", "def h, do: 3")

    fun testStructureViewNestsDefInKeywordElseList() =
        assertStructureViewNestsDef("if true, do: nil, else: [def(f, do: 1)]", "def(f, do: 1)")

    fun testStructureViewNestsDefInQuoteWithOptions() {
        myFixture.configureByText(
            "x.ex",
            "defmodule M do\n  defmacro m do\n    quote location: :keep, do: def(f, do: 1)\n  end\nend\n"
        )
        val module = Model(myFixture.file as ElixirFile, null).root.children.single()

        assertContainsElements(descendantTexts(module), "def(f, do: 1)")
    }

    /** The block keeps its own node, as written, with the definition under it. */
    private fun assertStructureViewNestsDef(block: String, definition: String = "def f, do: 1") {
        myFixture.configureByText("x.ex", "defmodule M do\n  $block\nend\n")
        val module = Model(myFixture.file as ElixirFile, null).root.children.single()
        val blockNode = module.children.single()

        assertContainsElements(descendantTexts(blockNode), definition)
    }

    private fun descendantTexts(node: com.intellij.ide.util.treeView.smartTree.TreeElement): List<String> =
        node.children.flatMap { child ->
            listOfNotNull(((child as? StructureViewTreeElement)?.value as? PsiElement)?.text) + descendantTexts(child)
        }

    fun testMixTestLoadFiltersInIf() {
        myFixture.tempDirFixture.createFile(
            "ifp/mix.exs",
            "defmodule Sample.MixProject do\n  use Mix.Project\n\n  if true do\n    def project do\n      [app: :ifp, test_load_filters: [&String.ends_with?(&1, \"_spec.exs\")]]\n    end\n  end\nend\n"
        )
        val spec = myFixture.tempDirFixture.createFile("ifp/test/foo_spec.exs", "")

        assertTrue(Test.isTestFile(PsiManager.getInstance(project).findFile(spec)!!))
    }

    fun testDepsDefinedInIf() =
        assertDeps("  def project do\n    [app: :s, deps: deps()]\n  end\n\n  if true do\n    defp deps do\n      [{:jason, \"~> 1.0\"}]\n    end\n  end\n")

    fun testDepsDefinedInKeywordElse() =
        assertDeps("  def project do\n    [app: :s, deps: deps()]\n  end\n\n  if true, do: nil, else: (defp deps do\n    [{:jason, \"~> 1.0\"}]\n  end)\n")

    fun testDepsProjectInIf() =
        assertDeps("  if true do\n    def project do\n      [app: :s, deps: deps()]\n    end\n  end\n\n  defp deps do\n    [{:jason, \"~> 1.0\"}]\n  end\n")

    fun testOtpAppProjectInIf() {
        val mixExs = myFixture.tempDirFixture.createFile(
            "dir_name/mix.exs",
            "defmodule Sample.MixProject do\n  use Mix.Project\n\n  if true do\n    def project do\n      [app: :app_name]\n    end\n  end\nend\n"
        )

        assertEquals("app_name", OtpApp(mixExs.parent, mixExs).name)
    }

    private fun assertUseWithArgument(inIf: Boolean) {
        val controller = "def controller do\n      quote do\n        def injected_by_controller(), do: :ok\n      end\n    end"
        val defined = if (inIf) "  if true do\n    $controller\n  end" else "  $controller"

        myFixture.configureByText(
            "x.ex",
            "defmodule IfWeb do\n$defined\n\n  defmacro __using__(which) do\n    apply(__MODULE__, which, [])\n  end\nend\n\ndefmodule IfCtl do\n  use IfWeb, :controller\n\n  def usage do\n    injected_by_controller<caret>\n  end\nend\n"
        )

        assertTrue(validTexts().toString(), validTexts().any { it.startsWith("def injected_by_controller") })
    }

    private fun assertDeps(body: String) {
        val file = myFixture.configureByText("mix.exs", "defmodule Sample.MixProject do\n  use Mix.Project\n\n$body\nend\n")
        val gatherer = DepGatherer()

        file.accept(gatherer)

        assertEquals(listOf("jason"), gatherer.depSet.map { it.application })
    }

    private fun markerTargetsAtCaret(provider: LineMarkerProvider): List<PsiElement> {
        val document = myFixture.editor.document
        val line = document.getLineNumber(myFixture.caretOffset)

        return PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
            .filter { document.getLineNumber(it.textOffset) == line }
            .mapNotNull { provider.getLineMarkerInfo(it) as? RelatedItemLineMarkerInfo<*> }
            .flatMap { it.createGotoRelatedItems() }
            .mapNotNull { it.element }
    }

    private fun assertTargetsInclude(targets: List<PsiElement>, text: String) {
        val expected = myFixture.file.text.indexOf(text)

        assertTrue("no target at \"$text\": ${targets.map { it.text }}", targets.any { it.textRange.startOffset == expected })
    }

    private fun validTexts(): List<String> {
        val call = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset - 1), Call::class.java)!!

        return (call.reference as PsiPolyVariantReference)
            .multiResolve(false)
            .filter { it.isValidResult }
            .map { it.element!!.text }
    }

    private companion object {
        const val EEX_DEFINER = "EEx.function_from_string(:def, :render, \"<%= a %>\", [:a])"
        const val EMBED_DEFINER = "embed_template(:foo, \"x\")"
        const val IMPORTED_DEFINER = "function_from_string(:def, :render, \"<%= a %>\", [:a])"
    }
}
