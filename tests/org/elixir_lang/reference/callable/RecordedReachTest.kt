package org.elixir_lang.reference.callable

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.Reach
import org.elixir_lang.declaration.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import java.io.File

/** How the callable walk records it reached each result, one path per test. */
class RecordedReachTest : PlatformTestCase() {
    override fun getTestDataPath(): String = EBIN.path

    fun testOwnModule() {
        assertReaches(
            """
            defmodule Own do
              def f(x), do: x
              def g, do: <caret>f(1)
            end
            """,
            "def f(x), do: x" to Reach.OWN
        )
    }

    fun testEnclosingModulesFunctionIsNotReached() {
        assertReaches(
            """
            defmodule Outer do
              def f(x), do: x

              defmodule Inner do
                def g, do: <caret>f(1)
              end
            end
            """
        )
    }

    fun testUse() {
        myFixture.addFileToProject("using.ex", USING)

        assertReaches(
            """
            defmodule User do
              use Using

              def g, do: <caret>f(1)
            end
            """,
            "def f(x), do: x" to Reach.USE
        )
    }

    fun testUseInEnclosingModule() {
        myFixture.addFileToProject("using.ex", USING)

        assertReaches(
            """
            defmodule Outer do
              use Using

              defmodule Inner do
                def g, do: <caret>f(1)
              end
            end
            """,
            "def f(x), do: x" to Reach.OUTER
        )
    }

    fun testImportInjectedByUse() {
        myFixture.addFileToProject("imported.ex", IMPORTED)
        myFixture.addFileToProject(
            "using.ex",
            """
            defmodule Using do
              defmacro __using__(_) do
                quote do
                  import Imported
                end
              end
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule User do
              use Using

              def g, do: <caret>f(1)
            end
            """,
            "def f(x), do: x" to Reach.IMPORT
        )
    }

    fun testImport() {
        myFixture.addFileToProject("imported.ex", IMPORTED)

        assertReaches(
            """
            defmodule Importer do
              import Imported

              def g, do: <caret>f(1)
            end
            """,
            "def f(x), do: x" to Reach.IMPORT
        )
    }

    fun testImportInEnclosingModule() {
        myFixture.addFileToProject("imported.ex", IMPORTED)

        assertReaches(
            """
            defmodule Outer do
              import Imported

              defmodule Inner do
                def g, do: <caret>f(1)
              end
            end
            """,
            "def f(x), do: x" to Reach.IMPORT
        )
    }

    fun testImplicitKernelImport() {
        myFixture.addFileToProject(
            "kernel.ex",
            """
            defmodule Kernel do
              def kernel_only(x), do: x
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Caller do
              def g, do: <caret>kernel_only(1)
            end
            """,
            "def kernel_only(x), do: x" to Reach.IMPLICIT_IMPORT
        )
    }

    fun testImplicitKernelSpecialFormsImport() {
        myFixture.addFileToProject(
            "special_forms.ex",
            """
            defmodule Kernel.SpecialForms do
              defmacro special_only(x), do: x
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Caller do
              def g, do: <caret>special_only(1)
            end
            """,
            "defmacro special_only(x), do: x" to Reach.IMPLICIT_IMPORT
        )
    }

    fun testImplicitCompiledKernelImport() {
        myFixture.copyFileToProject("Elixir.Kernel.beam", "ebin/Elixir.Kernel.beam")

        assertReaches(
            """
            defmodule Caller do
              def g, do: <caret>abs(1)
            end
            """,
            "compiled abs" to Reach.IMPLICIT_IMPORT
        )
    }

    fun testQualifiedCompiled() {
        myFixture.copyFileToProject("Elixir.Kernel.beam", "ebin/Elixir.Kernel.beam")

        assertReaches(
            """
            defmodule Caller do
              def g, do: Kernel.<caret>abs(1)
            end
            """,
            "compiled abs" to Reach.OWN
        )
    }

    fun testQualified() {
        myFixture.addFileToProject("target.ex", TARGET)

        assertReaches(
            """
            defmodule Caller do
              def g, do: Target.<caret>f(1)
            end
            """,
            "def f(x), do: x" to Reach.OWN
        )
    }

    fun testHeldDelegation() {
        myFixture.addFileToProject("target.ex", TARGET)

        assertReaches(
            """
            defmodule Delegator do
              defdelegate f(x), to: Target

              def g, do: <caret>f(1)
            end
            """,
            "defdelegate f(x), to: Target" to Reach.OWN,
            "def f(x), do: x" to Reach.DELEGATION_TARGET
        )
    }

    fun testQualifiedDelegation() {
        myFixture.addFileToProject("target.ex", TARGET)
        myFixture.addFileToProject("delegator.ex", DELEGATOR)

        assertReaches(
            """
            defmodule Caller do
              def g, do: Delegator.<caret>f(1)
            end
            """,
            "defdelegate f(x), to: Target" to Reach.OWN,
            "def f(x), do: x" to Reach.DELEGATION_TARGET
        )
    }

    fun testImportedDelegation() {
        myFixture.addFileToProject("target.ex", TARGET)
        myFixture.addFileToProject("delegator.ex", DELEGATOR)

        assertReaches(
            """
            defmodule Importer do
              import Delegator

              def g, do: <caret>f(1)
            end
            """,
            "defdelegate f(x), to: Target" to Reach.IMPORT,
            "def f(x), do: x" to Reach.UNHELD_DELEGATION_TARGET
        )
    }

    fun testDelegationInEnclosingModule() {
        myFixture.addFileToProject("target.ex", TARGET)

        assertReaches(
            """
            defmodule Outer do
              defdelegate f(x), to: Target

              defmodule Inner do
                def g, do: <caret>f(1)
              end
            end
            """,
            "defdelegate f(x), to: Target" to Reach.OUTER,
            "def f(x), do: x" to Reach.UNHELD_DELEGATION_TARGET
        )
    }

    fun testDelegationInjectedByUse() {
        myFixture.addFileToProject("target.ex", TARGET)
        myFixture.addFileToProject(
            "using.ex",
            """
            defmodule Using do
              defmacro __using__(_) do
                quote do
                  defdelegate f(x), to: Target
                end
              end
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule User do
              use Using

              def g, do: <caret>f(1)
            end
            """,
            "defdelegate f(x), to: Target" to Reach.USE,
            "def f(x), do: x" to Reach.DELEGATION_TARGET
        )
    }

    fun testUnknownMacroInjection() {
        myFixture.addFileToProject(
            "macros.ex",
            """
            defmodule Macros do
              defmacro inject(do: block) do
                quote do
                  def f(x), do: x
                  unquote(block)
                end
              end
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule User do
              require Macros

              Macros.inject do
                def g, do: <caret>f(1)
              end
            end
            """,
            "def f(x), do: x" to Reach.USE
        )
    }

    fun testImportInjectedByUseInEnclosingModule() {
        myFixture.addFileToProject("imported.ex", IMPORTED)
        myFixture.addFileToProject(
            "using.ex",
            """
            defmodule Using do
              defmacro __using__(_) do
                quote do
                  import Imported
                end
              end
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Outer do
              use Using

              defmodule Inner do
                def g, do: <caret>f(1)
              end
            end
            """,
            "def f(x), do: x" to Reach.IMPORT
        )
    }

    fun testDelegationTargetTheTargetModuleOnlyImplicitlyImports() {
        myFixture.addFileToProject(
            "kernel.ex",
            """
            defmodule Kernel do
              def kernel_only(x), do: x
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "target.ex",
            """
            defmodule Target do
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Delegator do
              defdelegate kernel_only(x), to: Target

              def g, do: <caret>kernel_only(1)
            end
            """,
            "defdelegate kernel_only(x), to: Target" to Reach.OWN,
            "def kernel_only(x), do: x" to Reach.UNHELD_DELEGATION_TARGET
        )
    }

    fun testDelegationChain() {
        myFixture.addFileToProject("target.ex", TARGET)
        myFixture.addFileToProject("delegator.ex", DELEGATOR)

        assertReaches(
            """
            defmodule Chain do
              defdelegate f(x), to: Delegator

              def g, do: <caret>f(1)
            end
            """,
            "defdelegate f(x), to: Delegator" to Reach.OWN,
            "defdelegate f(x), to: Target" to Reach.DELEGATION_TARGET,
            "def f(x), do: x" to Reach.DELEGATION_TARGET
        )
    }

    fun testDelegationChainToATargetItsModuleOnlyImports() {
        myFixture.addFileToProject("imported.ex", IMPORTED)
        myFixture.addFileToProject(
            "importer.ex",
            """
            defmodule Importer do
              import Imported
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "delegator.ex",
            """
            defmodule Delegator do
              defdelegate f(x), to: Importer
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Chain do
              defdelegate f(x), to: Delegator

              def g, do: <caret>f(1)
            end
            """,
            "defdelegate f(x), to: Delegator" to Reach.OWN,
            "defdelegate f(x), to: Importer" to Reach.DELEGATION_TARGET,
            "def f(x), do: x" to Reach.UNHELD_DELEGATION_TARGET
        )
    }

    fun testEctoQueryApi() {
        myFixture.addFileToProject("lib/ecto/query.ex", ECTO_QUERY)
        myFixture.addFileToProject(
            "lib/ecto/query/api.ex",
            """
            defmodule Ecto.Query.API do
              def avg(value), do: value
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Caller do
              import Ecto.Query

              def g, do: from(p in Post, select: <caret>avg(p.x))
            end
            """,
            "def avg(value), do: value" to Reach.IMPORT
        )
    }

    fun testEctoQueryApiDefInCase() {
        myFixture.addFileToProject("lib/ecto/query.ex", ECTO_QUERY)
        myFixture.addFileToProject(
            "lib/ecto/query/api.ex",
            """
            defmodule Ecto.Query.API do
              case :a do
                :a -> def avg(value), do: value
              end
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Caller do
              import Ecto.Query

              def g, do: from(p in Post, select: <caret>avg(p.x))
            end
            """,
            "def avg(value), do: value" to Reach.IMPORT
        )
    }

    fun testEctoQueryWindowApi() {
        myFixture.addFileToProject("lib/ecto/query.ex", ECTO_QUERY)
        myFixture.addFileToProject(
            "lib/ecto/query/window_api.ex",
            """
            defmodule Ecto.Query.WindowAPI do
              def rank, do: nil
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Caller do
              import Ecto.Query

              def g, do: from(p in Post, select: <caret>rank())
            end
            """,
            "def rank, do: nil" to Reach.IMPORT
        )
    }

    fun testEctoSchema() {
        myFixture.addFileToProject(
            "lib/ecto/schema.ex",
            """
            defmodule Ecto.Schema do
              defmacro schema(source, do: block) do
                quote do
                  unquote(block)
                  def f(x), do: x
                end
              end
            end
            """.trimIndent()
        )

        assertReaches(
            """
            defmodule Post do
              import Ecto.Schema

              schema "posts" do
                <caret>f(1)
              end
            end
            """,
            "def f(x), do: x" to Reach.USE
        )
    }

    private fun assertReaches(caller: String, vararg expected: Pair<String, Reach>) {
        myFixture.configureByText("caller.ex", caller.trimIndent())
        val call = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), Call::class.java)!!

        val found = LegacyWalkSource.candidates(Use.of(call)!!, false)

        assertEquals(expected.toList(), found.map { describe(it.element) to it.candidate.reach })
    }

    private fun describe(element: PsiElement?): String =
        when (element) {
            is BeamCallDefinition -> "compiled ${element.nameArityInterval.name}"
            else -> element!!.text.lines().first()
        }

    private companion object {
        val EBIN = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

        val IMPORTED =
            """
            defmodule Imported do
              def f(x), do: x
            end
            """.trimIndent()

        val USING =
            """
            defmodule Using do
              defmacro __using__(_) do
                quote do
                  def f(x), do: x
                end
              end
            end
            """.trimIndent()

        val ECTO_QUERY =
            """
            defmodule Ecto.Query do
              defmacro from(expr, kw \\ []), do: nil
            end
            """.trimIndent()

        val TARGET =
            """
            defmodule Target do
              def f(x), do: x
            end
            """.trimIndent()

        val DELEGATOR =
            """
            defmodule Delegator do
              defdelegate f(x), to: Target
            end
            """.trimIndent()
    }
}
