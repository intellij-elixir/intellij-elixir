package org.elixir_lang.reference.callable

import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import org.elixir_lang.beam.compiledDefinition
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.psi.scope.WalkTestSupport
import java.io.File

/** Whether a call resolves to what an `import`'s options bring in from `m.ex`, the module the import oracle uses. */
class ImportOptionsTest : ExpanderSettingTestCase() {
    fun testNoOptionsBringsInEverything() {
        assertResolves("import M", "g(1)")
        assertResolves("import M", "f(1, 2)")
        assertResolves("import M", "mac(1)")
        assertResolves("import M", "is_small(1)")
        assertResolves("import M", "sigil_x(\"a\", [])")
    }

    fun testOnlyFunctionsBringsInFunctions() {
        assertResolves("import M, only: :functions", "g(1)")
        assertDoesNotResolve("import M, only: :functions", "mac(1)")
    }

    fun testOnlyFunctionsBringsInADefaultedFunction() {
        assertResolves("import M, only: :functions", "f(1)")
        assertResolves("import M, only: :functions", "f(1, 2)")
    }

    fun testOnlyMacrosBringsInMacros() {
        assertResolves("import M, only: :macros", "mac(1)")
        assertDoesNotResolve("import M, only: :macros", "g(1)")
    }

    fun testOnlyMacrosBringsInGuards() {
        assertResolves("import M, only: :macros", "is_small(1)")
        assertDoesNotResolve("import M, only: :functions", "is_small(1)")
    }

    fun testOnlySigilsBringsInSigils() {
        assertResolves("import M, only: :sigils", "sigil_x(\"a\", [])")
        assertDoesNotResolve("import M, only: :sigils", "g(1)")
    }

    fun testOnlyOneArityOfADefaultedFunctionBringsInTheDefinition() {
        assertResolves("import M, only: [f: 2]", "f(1, 2)")
    }

    fun testExceptOneArityOfADefaultedFunctionLeavesOutThatArity() {
        assertDoesNotResolve("import M, except: [f: 1]", "f(1)")
    }

    fun testExceptOneArityOfADefaultedFunctionBringsInItsOtherArity() {
        assertResolves("import M, except: [f: 1]", "f(1, 2)")
    }

    fun testAnArityOnlyLeavesOutReachesTheDefinitionAsAWrongArity() {
        assertWrongArity("import M, only: [f: 1]", "f(1, 2)")
    }

    fun testAnArityExceptLeavesOutReachesTheDefinitionAsAWrongArity() {
        assertWrongArity("import M, except: [f: 1]", "f(1)")
    }

    fun testADefinitionOnlyLeavesOutAtEveryArityIsNotReached() {
        assertEmpty(definitionResults("import M, only: [g: 1]", "f(1)"))
    }

    fun testAnArityOnlyLeavesOutReachesTheCompiledDefinitionAsAWrongArity() =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "import_options_logger", LOGGER) {
            val import = "import Logger, only: [debug: 1]"
            val use = "debug(1, [])"
            myFixture.configureByText("u.ex", user(import, "<caret>$use"))
            val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
            val results = reference.multiResolve(false).filter { compiledDefinition(it.element) != null }

            assertTrue("`$use` under `$import` reaches no compiled definition", results.isNotEmpty())
            assertTrue("`$use` under `$import` resolves to a compiled definition", results.none { it.isValidResult })
        }

    fun testReimportWithExceptNarrowsTheEarlierImport() {
        val imports = "import M, only: [f: 1, g: 1]\nimport M, except: [f: 1]"

        assertResolves(imports, "g(1)")
        assertResolves(imports, "mac(1)")
        assertDoesNotResolve(imports, "f(1)")
        assertDoesNotResolve(imports, "f(1, 2)")
    }

    fun testReimportWithoutExceptReplacesTheEarlierImport() {
        val imports = "import M, only: [f: 1]\nimport M, only: [g: 1]"

        assertResolves(imports, "g(1)")
        assertDoesNotResolve(imports, "f(1)")
    }

    fun testReimportWithExceptOfAKindTheEarlierImportLeftEmptyBringsInThatKind() {
        val imports = "import M, only: [mac: 1]\nimport M, except: [f: 1]"

        assertResolves(imports, "g(1)")
        assertResolves(imports, "f(1, 2)")
        assertResolves(imports, "mac(1)")
        assertDoesNotResolve(imports, "is_small(1)")
    }

    fun testADefinitionBetweenTwoImportsSeesOnlyTheEarlierOne() {
        assertTrue(
            "`f(1)` between `import M, only: [f: 1]` and `import M, only: [g: 1]` resolves to no definition",
            resolvesToDefinition(
                """
                defmodule U do
                  import M, only: [f: 1]

                  def v do
                    <caret>f(1)
                  end

                  import M, only: [g: 1]
                end
                """.trimIndent()
            )
        )
    }

    fun testABlockUnquotedBetweenTwoImportsInAQuoteSeesTheEarlierOne() {
        assertTrue(
            "`f(1)` in a block unquoted between `import M, only: [f: 1]` and `import M, only: [g: 1]` resolves to no definition",
            resolvesToDefinition(
                """
                defmodule Blocks do
                  defmacro my_block(do: block) do
                    quote do
                      import M, only: [f: 1]
                      unquote(block)
                      import M, only: [g: 1]
                    end
                  end
                end

                defmodule U do
                  require Blocks

                  def u do
                    Blocks.my_block do
                      <caret>f(1)
                    end
                  end
                end
                """.trimIndent()
            )
        )
    }

    fun testExceptOfAnAttributeInAFunctionKeepsOtherNames() {
        assertTrue(
            "`g(1)` under `import M, except: @excluded` in a function resolves to no definition",
            resolvesToDefinition(
                """
                defmodule U do
                  @excluded [f: 1]

                  def u do
                    import M, except: @excluded
                    <caret>g(1)
                  end
                end
                """.trimIndent()
            )
        )
    }

    fun testUnquotedExceptInAQuoteIsNotRead() {
        assertTrue(
            "`f(1)` under `import M, except: unquote(excluded)` in a quote resolves to no definition",
            resolvesToDefinition(
                """
                defmodule U do
                  defmacro __using__(excluded) do
                    quote do
                      import M, except: unquote(excluded)

                      def u do
                        <caret>f(1)
                      end
                    end
                  end
                end
                """.trimIndent()
            )
        )
    }

    fun testFirstRepeatedOnlyWins() {
        assertResolves("import M, only: [g: 1], only: [f: 1]", "g(1)")
        assertDoesNotResolve("import M, only: [g: 1], only: [f: 1]", "f(1)")
    }

    fun testFirstRepeatedExceptWins() {
        assertResolves("import M, except: [g: 1], except: [f: 1]", "f(1)")
        assertDoesNotResolve("import M, except: [g: 1], except: [f: 1]", "g(1)")
    }

    fun testNoOptionsLeavesOutPrivateDefinitions() {
        assertDoesNotResolve("import M", "private(1)")
        assertDoesNotResolve("import M", "private_mac(1)")
    }

    fun testOnlyFunctionsLeavesOutPrivateFunctions() {
        assertDoesNotResolve("import M, only: :functions", "private(1)")
    }

    fun testOnlyMacrosLeavesOutPrivateMacros() {
        assertDoesNotResolve("import M, only: :macros", "private_mac(1)")
    }

    fun testExceptLeavesOutPrivateDefinitions() {
        assertDoesNotResolve("import M, except: [f: 1]", "private(1)")
    }

    fun testExceptOneArityOfADelegatedFunctionIsNotReachedThroughAnotherArity() {
        val delegations = """
            defmodule Target do
              def snoc(q), do: q
              def snoc(q, x), do: [x | q]
            end

            defmodule D do
              defdelegate snoc(q), to: Target
              defdelegate snoc(q, x), to: Target
            end
        """.trimIndent()

        assertEmpty(validTargets(delegations, "import D, except: [snoc: 1]", "snoc(1)"))
        assertContainsElements(
            validTargets(delegations, "import D, except: [snoc: 1]", "snoc(1, 2)"),
            "def snoc(q, x), do: [x | q]"
        )
    }

    fun testADelegationWithDefaultsReachesTheTargetsFullArity() {
        val delegations = """
            defmodule Target do
              def f(a, b), do: {a, b}
            end

            defmodule D do
              defdelegate f(a, b \\ 1), to: Target
            end
        """.trimIndent()

        assertContainsElements(validTargets(delegations, "import D", "f(1)"), "def f(a, b), do: {a, b}")
    }

    fun testExceptOneArityOfADelegationWithDefaultsLeavesOutOnlyThatArity() {
        val delegations = """
            defmodule Target do
              def snoc(q, x), do: [x | q]
            end

            defmodule D do
              defdelegate snoc(q, x \\ nil), to: Target
            end
        """.trimIndent()

        assertEmpty(validTargets(delegations, "import D, except: [snoc: 1]", "snoc(1)"))
        assertContainsElements(
            validTargets(delegations, "import D, except: [snoc: 1]", "snoc(1, 2)"),
            """defdelegate snoc(q, x \\ nil), to: Target"""
        )
    }

    fun testAnArityExceptLeavesOutReachesTheDelegationAsAWrongArity() {
        val delegations = """
            defmodule Target do
              def snoc(q, x), do: [x | q]
            end

            defmodule D do
              defdelegate snoc(q, x \\ nil), to: Target
            end
        """.trimIndent()

        assertContainsElements(
            invalidTargets(delegations, "import D, except: [snoc: 1]", "snoc(1)"),
            """defdelegate snoc(q, x \\ nil), to: Target"""
        )
    }

    fun testADelegationOfAnotherArityDoesNotHideTheTarget() {
        val delegations = """
            defmodule Target do
              def get(m, k, d \\ nil), do: {m, k, d}
            end

            defmodule D do
              defdelegate get(m, k, d), to: Target
              defdelegate get(m, k), to: Target
            end
        """.trimIndent()

        assertContainsElements(
            validTargets(delegations, "import D", "get(1, 2)"),
            """def get(m, k, d \\ nil), do: {m, k, d}"""
        )
    }

    /** The first line of each valid result for [use] under [import], after the modules [delegations] declares. */
    private fun validTargets(delegations: String, import: String, use: String): List<String> =
        firstLines(results(delegations, import, use).filter { it.isValidResult })

    private fun invalidTargets(delegations: String, import: String, use: String): List<String> =
        firstLines(results(delegations, import, use).filterNot { it.isValidResult })

    private fun firstLines(results: List<ResolveResult>): List<String> =
        results.mapNotNull { it.element?.text?.lines()?.first() }

    private fun results(delegations: String, import: String, use: String): List<ResolveResult> {
        val user = """
            defmodule U do
              $import

              def u do
                <caret>$use
              end
            end
        """.trimIndent()
        myFixture.configureByText("u.ex", "$delegations\n\n$user")
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

        return reference.multiResolve(false).toList()
    }

    fun testUnderscoredNamesAreNotImported() {
        assertDoesNotResolve("import M", "_hidden(1)")
        assertDoesNotResolve("import M, except: [f: 1]", "_hidden(1)")
    }

    fun testUnderscoredNamesAreImportedWhenOnlyNamesThem() {
        assertResolves("import M, only: [_hidden: 1]", "_hidden(1)")
    }

    fun testOnlyWithExceptBringsInNothing() {
        assertDoesNotResolve("import M, only: [g: 1], except: [f: 1]", "g(1)")
    }

    fun testBracketedOptionsAreRead() {
        assertResolves("import M, [only: [g: 1]]", "g(1)")
        assertDoesNotResolve("import M, [only: [g: 1]]", "f(1)")
    }

    fun testOnlyMacrosOfACompiledModuleBringsInItsMacros() =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "import_options_logger", LOGGER) {
            assertEquals(listOf("Elixir.Logger.beam.decompiled.ex debug/1"), compiledDefinitions("import Logger, only: :macros", "debug(1)"))
            assertEmpty(compiledDefinitions("import Logger, only: :macros", "level()"))
        }

    fun testOnlyNamingInfoOfACompiledModuleDoesNotBringItIn() =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "import_options_logger", LOGGER) {
            assertEquals(listOf("Elixir.Logger.beam.decompiled.ex level/0"), compiledDefinitions("import Logger, only: [level: 0, __info__: 1]", "level()"))
            assertEmpty(compiledDefinitions("import Logger, only: [level: 0, __info__: 1]", "__info__(:functions)"))
        }

    fun testACompiledModulesImportLeavesOutModuleInfo() =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "import_options_queue", QUEUE) {
            assertEquals(listOf("queue.beam.decompiled.ex new/0"), compiledDefinitions("import :queue", "new()"))
            assertEmpty(compiledDefinitions("import :queue", "module_info()"))
            assertEmpty(compiledDefinitions("import :queue", "module_info(:exports)"))
            assertNotOffered("import :queue", "module_", "module_info")
        }

    fun testACompiledKernelsImplicitImportLeavesOutModuleInfoAndInfo() = withCompiledKernel {
        assertEmpty(compiledDefinitions("", "__info__(:functions)"))
        assertEmpty(compiledDefinitions("", "module_info()"))
        assertEmpty(compiledDefinitions("", "module_info(:exports)"))
    }

    fun testACompiledKernelsImplicitImportLeavesOutPrivateDefinitions() = withCompiledKernel {
        assertEquals(listOf("Elixir.Kernel.beam.decompiled.ex is_atom/1"), compiledDefinitions("", "is_atom(1)"))
        assertEmpty(compiledDefinitions("", "assert_module_scope(1, 2, 3)"))
    }

    /** [block] with `Kernel`'s `.beam` alone as a library, so the implicit import reads it and no source `Kernel`. */
    private fun withCompiledKernel(block: () -> Unit) {
        val directory = FileUtil.createTempDirectory("compiled_kernel", null)
        File(WalkTestSupport.DOCS_KERNEL, "Elixir.Kernel.beam").copyTo(File(directory, "Elixir.Kernel.beam"))

        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "import_options_kernel", directory) { block() }
    }

    /** The compiled definitions [use] under [import] resolves to, each its file, name and arity. */
    private fun compiledDefinitions(import: String, use: String): List<String> {
        myFixture.configureByText("u.ex", user(import, "<caret>$use"))
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

        return reference.multiResolve(false).filter { it.isValidResult }.mapNotNull { compiledDefinition(it.element) }.distinct()
    }

    /** One lookup is inserted without a list, so nothing offered must also leave the document as it was. */
    private fun assertNotOffered(import: String, prefix: String, name: String) {
        myFixture.configureByText("u.ex", user(import, "$prefix<caret>"))
        val before = myFixture.editor.document.text
        val offered = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

        assertEquals("completing `$prefix` under `$import` inserted a lookup", before, myFixture.editor.document.text)
        assertDoesntContain(offered, name)
    }

    private fun user(import: String, use: String): String =
        """
        defmodule U do
          $import

          def u do
            $use
          end
        end
        """.trimIndent()

    private fun assertResolves(import: String, use: String) {
        assertTrue("`$use` under `$import` resolves to no definition", resolvesToDefinition(import, use))
    }

    private fun assertDoesNotResolve(import: String, use: String) {
        assertFalse("`$use` under `$import` resolves to a definition", resolvesToDefinition(import, use))
    }

    private fun resolvesToDefinition(import: String, use: String): Boolean =
        resolvesToDefinition(
            """
            defmodule U do
              $import

              def u do
                <caret>$use
              end
            end
            """.trimIndent()
        )

    private fun resolvesToDefinition(text: String): Boolean = definitionResults(text).any { it.isValidResult }

    private fun assertWrongArity(import: String, use: String) {
        val results = definitionResults(import, use)

        assertTrue("`$use` under `$import` reaches no definition", results.isNotEmpty())
        assertTrue("`$use` under `$import` resolves to a definition", results.none { it.isValidResult })
    }

    private fun definitionResults(import: String, use: String): List<ResolveResult> =
        definitionResults(user(import, "<caret>$use"))

    private fun definitionResults(text: String): List<ResolveResult> {
        myFixture.configureByText("u.ex", text)
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

        // The `import` line is a result of its own, so only a definition in `m.ex` counts.
        return reference.multiResolve(false).filter { it.element?.containingFile?.name == "m.ex" }
    }

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("m.ex", File("testData/org/elixir_lang/psi/import/oracle/m.ex").readText())
    }

    private companion object {
        val LOGGER = File("testData/org/elixir_lang/mockSdk-1.0.4/lib/logger/ebin")
        val QUEUE = File("testData/org/elixir_lang/psi/scope/callable_table/library_roots/lib")
    }
}
