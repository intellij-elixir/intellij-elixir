package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Definer
import org.elixir_lang.declaration.Form
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.enclosingMacroCall
import org.elixir_lang.psi.scope.CallDefinitionClause as CallDefinitionClauseScope
import org.elixir_lang.psi.scope.call_definition_clause.Declarations

/** What a clause or a `defdelegate` defines, from the one site every reader of that question goes through. */
class DefinitionDeclarationTest : PlatformTestCase() {
    fun testEachDefinerGivesItsCapabilities() {
        for (definer in Definer.entries) {
            val call = call("defmodule M do\n  ${written(definer)}\nend", definer.keyword)
            val declaration = DefinitionDeclaration.of(call, ResolveState.initial())

            assertNotNull(definer.keyword, declaration)
            assertEquals(definer.keyword, definer.capabilities, declaration!!.capabilities)
            assertEquals(definer.keyword, "f", declaration.name)
            assertEquals(definer.keyword, ArityKnowledge.Exact(1), declaration.arity)
            assertEquals(definer.keyword, Form.CLAUSE, (declaration.declared as Declared.Source).form)
            assertTrue(definer.keyword, DefinitionDeclaration.`is`(call))
        }
    }

    fun testDelegationIsAPublicRuntimeFunction() {
        val call = call("defmodule M do\n  defdelegate id(socket), to: Ids, as: :other\nend", "defdelegate")
        val declaration = DefinitionDeclaration.of(call, ResolveState.initial())!!

        assertEquals("id", declaration.name)
        assertEquals(ArityKnowledge.Exact(1), declaration.arity)
        assertEquals(Definer.DEF.capabilities, declaration.capabilities)
        assertTrue(declaration.capabilities.remoteCallable)
        assertEquals(Form.DELEGATION, (declaration.declared as Declared.Source).form)
        assertEquals(CallDefinitionClause.sourceOrigin(call), (declaration.declared as Declared.Source).origin)
        assertTrue(DefinitionDeclaration.`is`(call))
    }

    fun testDelegationWithDefaultsDeclaresEachArity() {
        val call = call("defmodule M do\n  defdelegate size(atom, opts \\\\ []), to: Sizes\nend", "defdelegate")

        assertEquals(
            ArityKnowledge.Range(1, 2),
            DefinitionDeclaration.of(call, ResolveState.initial())!!.arity
        )
    }

    fun testListOfHeadsDelegationDeclaresNothing() {
        val call = call("defmodule M do\n  defdelegate [a(x), b(x)], to: Sizes\nend", "defdelegate")

        assertNull(DefinitionDeclaration.of(call, ResolveState.initial()))
    }

    fun testAnythingElseDefinesNothing() {
        val call = call("defmodule M do\n  call(inner(x))\nend", "call")

        assertFalse(DefinitionDeclaration.`is`(call))
        assertNull(DefinitionDeclaration.of(call, ResolveState.initial()))
        assertNull(DefinitionDeclaration.nameIdentifier(call))
    }

    fun testNameIdentifierIsTheHeadsName() {
        val file = myFixture.configureByText(
            "definition.ex",
            "defmodule M do\n  def clause(x), do: x\n  defdelegate delegated(x), to: Ids\nend\n"
        )
        val calls = PsiTreeUtil.findChildrenOfType(file, Call::class.java)

        assertEquals("clause", DefinitionDeclaration.nameIdentifier(calls.first { it.functionName() == "def" })!!.text)
        assertEquals(
            "delegated",
            DefinitionDeclaration.nameIdentifier(calls.first { it.functionName() == "defdelegate" })!!.text
        )
    }

    /** What the walk declared for a delegation before the one site existed, spelled out. */
    fun testWalkDelegationDeclarationIsUnchanged() {
        val call = call("defmodule M do\n  defdelegate del(a, b \\\\ 1), to: Sizes\nend", "defdelegate")

        assertEquals(
            Declaration(
                "del",
                ArityKnowledge.Range(1, 2),
                Definer.DEF.capabilities,
                Declared.Source(Form.DELEGATION, CallDefinitionClause.sourceOrigin(call))
            ),
            Declarations.named(Form.DELEGATION, call, "del", ResolveState.initial())
        )
        assertEquals(
            Declarations.named(Form.DELEGATION, call, "del", ResolveState.initial()),
            DefinitionDeclaration.of(call, ResolveState.initial())
        )
    }

    fun testWalkDelegationDeclarationFollowsTheStateTheWalkAdjusts() {
        val call = call("defmodule M do\n  defdelegate over(a), to: Sizes\nend", "defdelegate")
        val adjusted = ResolveState.initial().put(CallDefinitionClauseScope.MODULAR_CANONICAL_NAME, WINDOW_API)

        assertEquals(ArityKnowledge.Exact(1), DefinitionDeclaration.of(call, ResolveState.initial())!!.arity)
        assertEquals(ArityKnowledge.Range(1, 2), DefinitionDeclaration.of(call, adjusted)!!.arity)
        assertEquals(
            Declarations.named(Form.DELEGATION, call, "over", adjusted),
            DefinitionDeclaration.of(call, adjusted)
        )
    }

    fun testDelegationSymbolIsAFunction() {
        val call = call("defmodule M do\n  defdelegate del(a, b \\\\ 1), to: Sizes\nend", "defdelegate")

        assertEquals(
            listOf("FunctionSymbol(M.del/1, macro=false)", "FunctionSymbol(M.del/2, macro=false)"),
            FunctionSymbol.fromDelegation(call).map { it.toString() }
        )
    }

    fun testImportOfOnlyFunctionsBringsInADelegation() {
        assertEquals(listOf("defdelegate"), importedFunctionNames("only: :functions"))
    }

    fun testImportOfOnlyMacrosLeavesOutADelegation() {
        assertEquals(listOf("defmacro"), importedFunctionNames("only: :macros"))
    }

    /** An `import` in the walk carries only its `FILTER`, but `Import.export` passes on whatever state it is given. */
    fun testImportForwardsItsStateToADelegation() {
        val importer = myFixture.configureByText(
            "importer.ex",
            "defmodule Importer do\n  import Imported, only: [over: 2]\n" +
                "end\ndefmodule Imported do\n  defdelegate over(a), to: Sizes\nend\n"
        )
        val calls = PsiTreeUtil.findChildrenOfType(importer, Call::class.java)
        val importCall = calls.single { it.functionName() == "import" }
        val delegation = calls.single { it.functionName() == "defdelegate" }
        val filter = Import.Filter.of(importCall, ElixirLanguageLevel.of("1.20.4"), null)
        val state = ResolveState.initial()
            .put(Import.FILTER, filter)
            .put(CallDefinitionClauseScope.MODULAR_CANONICAL_NAME, WINDOW_API)

        assertEquals(setOf(2), Import.importedArities(delegation, state))
    }

    private fun importedFunctionNames(options: String): List<String> {
        myFixture.addFileToProject(
            "lib/imported.ex",
            "defmodule Imported do\n  defdelegate del(x), to: Ids\n  defmacro mac(x), do: x\nend\n"
        )
        val importer = myFixture.configureByText(
            "importer.ex",
            "defmodule Importer do\n  import Imported, $options\nend\n"
        )
        val importCall = PsiTreeUtil.findChildrenOfType(importer, Call::class.java).single { it.functionName() == "import" }
        val state = ResolveState.initial().put(ENTRANCE, importCall.enclosingMacroCall()).putInitialVisitedElement(importCall)
        val imported = mutableListOf<String>()

        Import.treeWalkUp(importCall, state) { element: PsiElement, _ ->
            imported += (element as Call).functionName()!!
            true
        }

        return imported
    }

    private fun written(definer: Definer): String =
        when (definer) {
            Definer.DEFGUARD, Definer.DEFGUARDP -> "${definer.keyword} f(x) when is_atom(x)"
            else -> "${definer.keyword} f(x), do: x"
        }

    private fun call(code: String, functionName: String): Call {
        val file = myFixture.configureByText("definition.ex", "$code\n")

        return PsiTreeUtil.findChildrenOfType(file, Call::class.java).first { it.functionName() == functionName }
    }

    private companion object {
        const val WINDOW_API = "Ecto.Query.WindowAPI"
    }
}
