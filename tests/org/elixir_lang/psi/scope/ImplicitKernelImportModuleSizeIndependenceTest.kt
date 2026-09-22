package org.elixir_lang.psi.scope

import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.Name
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.putAncestorUnquote
import org.elixir_lang.psi.putInitialVisitedElement

/**
 * #4123's own follow-on gap, found profiling `elixir_parser.beam` live in a sandbox after Option A's three
 * fixes landed: a call that falls through to the *implicit* `import Kernel` (nothing in the local module
 * declares it) still walks every one of Kernel's own declarations one at a time
 * ([org.elixir_lang.psi.Modular.callDefinitionClauseCallWhile], classifying each with
 * [org.elixir_lang.psi.CallDefinitionClause.is]/`isMacro`) even though [CallDefinitionClause.targetName] is
 * known by then - the same O(module size) cost #4123 itself removed from the *local* module, just
 * relocated onto Kernel's own declaration list. `implicitImports`/`implicitImport` (`CallDefinitionClause.kt`)
 * is the call site, and the fix is to let it reach Kernel's declarations through the very
 * [org.elixir_lang.psi.CallableTable] #4123 built for the entrance's own module -
 * [org.elixir_lang.psi.CallableTable.declaring] is a map lookup against a table cached per modular - rather
 * than enumerating and classifying every declaration Kernel has.
 *
 * A synthetic source module literally named `Kernel` (real source, not the compiled stdlib) makes
 * [org.elixir_lang.psi.scope.CallDefinitionClause.implicitImports] find and walk it exactly the way it
 * found the real one live - reproducing the profiled shape without the sandbox or the real ~270-file
 * stdlib. The throw-away `ElixirParserBeamHighlightingProfileTest` used earlier in this investigation
 * could not reproduce this cost: its fixture only had Kernel's *compiled* form available, which routes
 * through a different, cheap path - this test is what that one was missing.
 */
class ImplicitKernelImportModuleSizeIndependenceTest : PlatformTestCase() {
    fun testResolvingOneNameThroughTenUnrelatedKernelDeclarationsNeverVisitsThem() {
        assertNoUnrelatedKernelDeclarationVisited(unrelatedKernelDefCount = 10)
    }

    fun testResolvingOneNameThroughAThousandUnrelatedKernelDeclarationsNeverVisitsThem() {
        assertNoUnrelatedKernelDeclarationVisited(unrelatedKernelDefCount = 1000)
    }

    /**
     * `import Kernel` re-exports what `Kernel` itself declares, never what `Kernel` merely `import`ed -
     * `import` is not transitive in Elixir. Making the implicit import read
     * [org.elixir_lang.psi.CallableTable] (which descends through `import`/`use`/`for`/`quote`/`try`
     * collecting whatever a module can *call*, a strictly larger set than what it *exports*) is what puts
     * that distinction at risk; the walk this replaced only ever saw `macroChildCalls()`, so it could not
     * reach an `import`ed declaration to begin with.
     *
     * Nothing in today's `lib/elixir/lib/kernel.ex` has a module-level `import`, so this pins the rule
     * rather than a reproduction - the point is that a future `Kernel` gaining one must not start leaking
     * the imported module's functions into every file's unqualified scope.
     */
    fun testImplicitKernelImportDoesNotReExportWhatKernelItselfImported() {
        myFixture.addFileToProject(
            "kernel_fixture.ex",
            """
            defmodule Helper do
              def helper_fun, do: :ok
            end

            defmodule Kernel do
              import Helper

              def target, do: :ok
            end
            """.trimIndent()
        )

        // `Caller` imports nothing, so the implicit `import Kernel` is the only path by which `helper_fun`
        // could ever be reached from here - a visit to it is necessarily a transitive-import leak.
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def go, do: helper_fun()
            end
            """.trimIndent()
        )

        val entrance = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { it.text == "helper_fun()" }

        val collector = ClauseVisitCollector("helper_fun")

        PsiTreeUtil.treeWalkUp(
            collector,
            entrance,
            maxScope(entrance),
            ResolveState.initial()
                .put(ENTRANCE, entrance)
                .putInitialVisitedElement(entrance)
                .putAncestorUnquote(entrance)
        )

        assertTrue(
            "`import Kernel` is not transitive, so `Helper.helper_fun/0` must not be reachable as an " +
                "unqualified call just because `Kernel` imports `Helper` - visited: ${collector.visitedTexts}",
            collector.visitedTexts.none { it.contains("def helper_fun") }
        )
    }

    /**
     * `target` is the *last* declaration in the synthetic `Kernel` module, so an unfiltered walk that
     * doesn't stop until it reaches `target`'s own entry is forced to visit every `kernel_unrelated_*` one
     * first - the worst case the live, unfiltered walk actually pays.
     */
    /**
     * The other side of [testImplicitKernelImportDoesNotReExportWhatKernelItselfImported]: the filter that
     * drops `import`ed entries must not take these with it. `elixir` compiles both - an `import` of a
     * `use`-injected `def` and of an `if`-guarded `def` - so both are genuinely re-exported, which is what
     * rules out the two narrower predicates. `Entry.declaringModuleName == moduleName` would drop the
     * `use`-injected one, whose `def` sits physically in the *using* module's `quote` and so names that
     * module; `Entry.path.wrappers.isEmpty()` would drop both.
     */
    fun testImplicitKernelImportReExportsAUseInjectedDeclaration() {
        assertReachableThroughImplicitKernelImport(
            kernelBody = """
              use Injector
            """.trimIndent(),
            extraModules = """
            defmodule Injector do
              defmacro __using__(_) do
                quote do
                  def injected_fun, do: :ok
                end
              end
            end
            """.trimIndent(),
            calledName = "injected_fun",
            expectedDeclarationText = "def injected_fun"
        )
    }

    fun testImplicitKernelImportReExportsAConditionallyDefinedDeclaration() {
        assertReachableThroughImplicitKernelImport(
            kernelBody = """
              if true do
                def conditional_fun, do: :ok
              end
            """.trimIndent(),
            extraModules = "",
            calledName = "conditional_fun",
            expectedDeclarationText = "def conditional_fun"
        )
    }

    private fun assertReachableThroughImplicitKernelImport(
        kernelBody: String,
        extraModules: String,
        calledName: String,
        expectedDeclarationText: String
    ) {
        myFixture.addFileToProject(
            "kernel_fixture.ex",
            """
            $extraModules

            defmodule Kernel do
            $kernelBody
            end
            """.trimIndent()
        )

        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def go, do: $calledName()
            end
            """.trimIndent()
        )

        val entrance = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { it.text == "$calledName()" }

        val collector = ClauseVisitCollector(calledName)

        PsiTreeUtil.treeWalkUp(
            collector,
            entrance,
            maxScope(entrance),
            ResolveState.initial()
                .put(ENTRANCE, entrance)
                .putInitialVisitedElement(entrance)
                .putAncestorUnquote(entrance)
        )

        assertTrue(
            "`$calledName/0` is `Kernel`'s own declaration and must stay reachable through the implicit " +
                "`import Kernel` - visited: ${collector.visitedTexts}",
            collector.visitedTexts.any { it.contains(expectedDeclarationText) }
        )
    }

    private fun assertNoUnrelatedKernelDeclarationVisited(unrelatedKernelDefCount: Int) {
        val unrelatedDefs =
            (0 until unrelatedKernelDefCount).joinToString("\n") { "  def kernel_unrelated_$it, do: :ok" }

        myFixture.addFileToProject(
            "kernel_fixture.ex",
            """
            defmodule Kernel do
            $unrelatedDefs

              def target, do: :ok
            end
            """.trimIndent()
        )

        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def go, do: target()
            end
            """.trimIndent()
        )

        val entrance = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { it.functionName() == "target" && it.text == "target()" }

        val collector = ClauseVisitCollector("target")
        val walkScope = maxScope(entrance)
        val state = ResolveState.initial()
            .put(ENTRANCE, entrance)
            .putInitialVisitedElement(entrance)
            .putAncestorUnquote(entrance)

        PsiTreeUtil.treeWalkUp(collector, entrance, walkScope, state)

        assertTrue(
            "resolving `target/0` through the implicit Kernel import must find its own declaration - " +
                "visited: ${collector.visitedTexts}",
            collector.visitedTexts.any { it.contains("def target") }
        )
        assertTrue(
            "resolving `target/0` through the implicit Kernel import must not visit any of Kernel's " +
                "$unrelatedKernelDefCount unrelated declarations - visited: ${collector.visitedTexts}",
            collector.visitedTexts.none { it.contains("kernel_unrelated") }
        )
    }

    /** Records the text of every clause `implicitImports`' Kernel walk (or the local modular branch, or
     *  the direct-ancestor path to `go` itself) calls `executeOnCallDefinitionClause` on - the rest of the
     *  abstract surface is a no-op, since this test only cares which clauses get visited.
     *  `element.functionName()` is the *outer* macro's name (`"def"` for every entry here, since a clause
     *  is itself a `def(...)` call), not the declared head - `.text` is what actually distinguishes them. */
    private class ClauseVisitCollector(private val name: Name) : CallDefinitionClause() {
        val visitedTexts = mutableListOf<String>()

        override fun targetName(): Name = name

        override fun executeOnCallDefinitionClause(element: Call, state: ResolveState): Boolean {
            visitedTexts.add(element.text)
            return true
        }

        override fun execute(element: BeamCallDefinition, state: ResolveState): Boolean = true
        override fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean = true
        override fun executeOnDelegation(element: Call, state: ResolveState): Boolean = true
        override fun executeOnException(element: Call, state: ResolveState): Boolean = true
        override fun executeOnEExFunctionFrom(element: Call, state: ResolveState): Boolean = true
        override fun executeOnMixGeneratorEmbed(element: Call, state: ResolveState): Boolean = true
        override fun keepProcessing(): Boolean = true
    }
}
