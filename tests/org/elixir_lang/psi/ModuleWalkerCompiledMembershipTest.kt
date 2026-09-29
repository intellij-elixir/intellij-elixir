package org.elixir_lang.psi

import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.psi.call.Call

/**
 * `ModuleWalker.matches`/`isChild` recognized a library macro only from a source declaration -
 * [CallableTable.definersIn] never searched [CallableTable.beamCallDefinitions], so a DSL macro (like
 * `Ecto.Schema.schema/2`) shipped only as a compiled dependency was never recognized as its own library's,
 * even though the table already collects it while walking a compiled `import` target. `matchesCompiledIn`
 * closes that gap for recognition; `Using.treeWalkUp`'s own `BeamCallDefinition -> TODO()` (a compiled
 * `__using__` has no decompiled body to walk into for injected fields) is untouched and still out of scope.
 */
class ModuleWalkerCompiledMembershipTest : BeamLibraryTestCase() {
    fun testMatchesRecognizesAMacroDeclaredOnlyByACompiledDependency() {
        myFixture.configureByText(
            "host.ex",
            """
            defmodule Host do
              import TestDsl

              dsl_block do
                :ok
              end
            end
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { it.functionName() == "dsl_block" }

        assertTrue(
            "`dsl_block do ... end` must be recognized as TestDsl's own macro purely from the compiled " +
                "`Elixir.TestDsl.beam` - there is no source declaration anywhere in this fixture",
            TestWalker.isChild(call, ResolveState.initial())
        )
    }

    private object TestWalker : ModuleWalker("TestDsl", NameArityRangeWalker("dsl_block", 1))

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/compiled_dsl"
}
