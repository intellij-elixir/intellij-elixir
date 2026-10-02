package org.elixir_lang.psi

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.call.Call

/** [Import.Filter.of] over an `import` call's PSI, for options the compiler's expansion can't be read from here. */
class ImportFilterPsiTest : PlatformTestCase() {
    fun testAnArityThatIsACallIsSkipped() = assertImports("import M, only: [g: 1, f: @a]", "g/1 |")

    fun testAnArityThatIsAVariableIsInvalid() =
        assertImports("x = 1\nimport M, only: [g: 1, f: x]", "error: invalid_option only")

    fun testAnExceptThatIsACallIsStillGiven() = assertImports("import M, only: [g: 1], except: @x", "error: only_and_except_given")

    fun testAnOptionKeyThatIsACallIsUnsupported() =
        assertImports("import M, \"#{:only}\": [g: 1]", "error: unsupported_option")

    fun testOptionsInAVariableAreNotRead() = assertImports("opts = []\nimport M, opts", "f/1 f/2 g/1 | mac/1")

    fun testOptionsAsTuplesAreNotRead() = assertImports("import M, [{:only, [g: 1]}]", "f/1 f/2 g/1 | mac/1")

    private fun assertImports(code: String, expected: String) {
        val file = myFixture.configureByText("case.ex", code)
        val importCall = PsiTreeUtil.findChildrenOfType(file, Call::class.java).single { it.functionName() == "import" }
        val actual = when (val filter = Import.Filter.of(importCall, LEVEL, null)) {
            is Import.Filter.Invalid -> "error: ${filter.error.kind}"
            else -> render(filter.imports(EXPORTS))
        }

        assertEquals(expected, actual)
    }

    private fun render(imports: Import.Imports): String =
        listOf(imports.functions, imports.macros).joinToString(" | ") { nameArities ->
            nameArities.sortedWith(compareBy({ it.name }, { it.arity })).joinToString(" ") { "${it.name}/${it.arity}" }
        }.trim()

    private companion object {
        val LEVEL: ElixirLanguageLevel = ElixirLanguageLevel.of("1.20.4")
        val EXPORTS = Import.Imports(
            setOf(NameArity("f", 1), NameArity("f", 2), NameArity("g", 1), NameArity("_h", 1)),
            setOf(NameArity("mac", 1)),
        )
    }
}
