package org.elixir_lang.psi.scope

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallableTable
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub.isModular

/**
 * Ecto.Schema and ExUnit.Case decide "does this call belong to the library" from
 * [org.elixir_lang.psi.CallableTable] now, not by resolving the candidate call's own reference
 * ([org.elixir_lang.resolvesToModularName]) - these pin the behaviour that change must preserve, plus one
 * thing it changes on purpose: Ecto.Query's macros stop appearing in a module's own declaration scan (they
 * never declared a callable, only bound query variables). `psi/scope/Module.kt`'s own nested-scope-descent
 * widening (`Case.isChild` to `hasDoBlockOrKeyword`, cutting `Issue3405Test`'s reentrancy hinge) is covered
 * by that test and by `structure_view/ex_unit/CaseNodeTest` rather than here - a direct regression test for
 * the widening itself would need to isolate `scope/Module.kt`'s relative-alias resolution from ordinary
 * qualified-call resolution, which turned out not to be the same code path a plain `Inner.foo()` reference
 * exercises even before this change (confirmed empirically), so it is not attempted here.
 */
class EctoExUnitMembershipTest : PlatformTestCase() {
    fun testUseExUnitCaseWithAnOnlyFilteredUsingResolvesTestAndDescribe() {
        myFixture.configureByText(
            "case.ex",
            """
            defmodule ExUnit.Case do
              defmacro __using__(_opts) do
                quote do
                  import ExUnit.Case, only: [describe: 2, test: 1, test: 2]
                end
              end

              defmacro describe(message, do: block) do
                quote do
                  unquote(message)
                  unquote(block)
                end
              end

              defmacro test(message, do: block) do
                quote do
                  unquote(message)
                  unquote(block)
                end
              end

              defmacro test(message) do
                quote do
                  unquote(message)
                end
              end
            end

            defmodule MyTest do
              use ExUnit.Case

              describe "a group" do
                test "one thing" do
                  :ok
                end
              end

              test "a pending one"
            end
            """.trimIndent()
        )

        val body = myFixture.file.text.indexOf("defmodule MyTest")
        val describeCall = onlyCallNamed("describe", afterOffset = body)
        val testWithBlockCall = onlyCallNamed("test", arity = 2, afterOffset = body)
        val testPendingCall = onlyCallNamed("test", arity = 1, afterOffset = body)

        assertTrue("`describe/2` reached only through an `only:`-filtered import must still match",
            org.elixir_lang.psi.ex_unit.Case.isDescribe(describeCall, com.intellij.psi.ResolveState.initial()))
        assertTrue("`test/2` reached only through an `only:`-filtered import must still match",
            org.elixir_lang.psi.ex_unit.Case.isTest(testWithBlockCall, com.intellij.psi.ResolveState.initial()))
        assertTrue("a bodyless `test/1` must still match",
            org.elixir_lang.psi.ex_unit.Case.isTest(testPendingCall, com.intellij.psi.ResolveState.initial()))
    }

    fun testSchemaMacroBodyStillReachesUsingTreeWalkUp() {
        myFixture.configureByText(
            "schema.ex",
            """
            defmodule Ecto.Schema do
              defmacro __using__(_opts) do
                quote do
                  import Ecto.Schema, only: [schema: 2, embedded_schema: 1]
                end
              end

              defmacro schema(source, do: _block) do
                quote do
                  def __schema__(:source), do: unquote(source)
                end
              end

              defmacro embedded_schema(do: _block) do
                quote do
                  def __schema__(:source), do: nil
                end
              end
            end

            defmodule MyApp.User do
              use Ecto.Schema

              schema "users" do
              end

              def usage do
                __schema__(:source)
              end
            end
            """.trimIndent()
        )

        val text = myFixture.file.text
        val offset = text.indexOf("__schema__(:source)", text.indexOf("def usage"))
        assertTrue("`__schema__(:source)` not found in `usage/0`'s body", offset >= 0)
        val leaf = myFixture.file.findElementAt(offset)!!
        val reference = generateSequence(leaf) { it.parent }.mapNotNull { it.reference }.first()

        assertInstanceOf(reference, PsiPolyVariantReference::class.java)
        val resolved = (reference as PsiPolyVariantReference).multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element?.text }

        assertEquals(
            "the `schema \"users\" do ... end` call must reach `Using.treeWalkUp` and declare `__schema__/1`, " +
                "the same way it did resolving through `call.reference.multiResolve()` before #4123",
            listOf("def __schema__(:source), do: unquote(source)"),
            resolved
        )
    }

    /**
     * A top-level `from(...)` never declared a callable - `Query.isChild`'s `NameArityRangeWalker`s only
     * `keepProcessing` on binding elements the `Variable` processor reads, which `CallDefinitionClause`
     * ignores. Dropping `from`/`select`/etc. from [org.elixir_lang.psi.CallableTable]'s `LIVE_FUNCTION_NAMES`
     * must not turn it into a spurious [CallableTable.Entry] or [CallableTable.Candidate] either.
     */
    fun testATopLevelFromCallIsNeitherAnEntryNorALiveCandidate() {
        myFixture.configureByText(
            "query.ex",
            """
            defmodule MyApp.Repo do
              import Ecto.Query

              from(u in User)

              def usage, do: :ok
            end
            """.trimIndent()
        )

        val modular = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { isModular(it) }
        val table = CallableTable.of(modular)
        val fromCallText = "from(u in User)"

        assertTrue(
            "a top-level `from(...)` with no `do` block must not become an entry",
            table.entries.none { it.call.text == fromCallText }
        )
        assertTrue(
            "a top-level `from(...)` with no `do` block must not become a live candidate either",
            table.liveCandidates.none { it.call.text == fromCallText }
        )
    }

    private fun onlyCallNamed(name: String, arity: Int? = null, afterOffset: Int = 0): Call =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .filter {
                it.functionName() == name && it.textOffset >= afterOffset &&
                    (arity == null || it.resolvedFinalArity() == arity)
            }
            .single()

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/scope/ecto_ex_unit_membership"
}
