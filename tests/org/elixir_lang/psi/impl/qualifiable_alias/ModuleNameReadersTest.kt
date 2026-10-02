package org.elixir_lang.psi.impl.qualifiable_alias

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.QualifiedAlias
import org.elixir_lang.psi.impl.computeReference

/** Readers of an alias's module name, which is the module of the atom the alias quotes to. */
class ModuleNameReadersTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testNameOfAQualifiedAlias() = assertName("Foo.Bar", "Foo.Bar")

    fun testNameOfAQualifiedAliasSpacedAroundTheDot() = assertName("Foo . Bar", "Foo.Bar")

    fun testNameOfAnElixirPrefixedAlias() = assertName("Elixir.Foo", "Foo")

    fun testNameOfAModuleHeadedAlias() = assertName("__MODULE__.Bar", "__MODULE__.Bar")

    /** `x.Inner` has no value until run time, so its name stays its text, which no module is named. */
    fun testNameOfAVariableHeadedAlias() = assertName("x.Inner", "x.Inner")

    fun testAliasDirectiveReachesTheModule() = assertAliasReaches("Foo.Bar")

    fun testElixirPrefixedAliasDirectiveReachesTheModule() = assertAliasReaches("Elixir.Foo.Bar")

    fun testAliasDirectiveSpacedAroundTheDotReachesTheModule() = assertAliasReaches("Foo . Bar")

    /** `for: BitString` names the type of `<<...>>`, which no module defines, so it has no module reference. */
    fun testBitStringHasNoReference() = assertNoReference("defimpl P, for: BitString do\nend\n", "BitString")

    fun testElixirPrefixedBitStringHasNoReference() =
        assertNoReference("defimpl P, for: Elixir.BitString do\nend\n", "Elixir.BitString")

    private fun assertName(alias: String, expected: String) {
        myFixture.configureByText("alias.ex", "defmodule M do\n  def f(x), do: $alias\nend\n")

        assertEquals(expected, qualifiableAlias(alias).name)
    }

    private fun assertAliasReaches(alias: String) {
        myFixture.configureByText(
            "alias_directive.ex",
            "defmodule Foo.Bar do\n  def f, do: 1\nend\n\ndefmodule Caller do\n  alias $alias\n\n  def g, do: Bar.f<caret>()\nend\n"
        )

        myFixture.assertGotoDeclarationLandsIn("f", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    private fun assertNoReference(source: String, alias: String) {
        myFixture.configureByText("alias.ex", source)

        assertNull(qualifiableAlias(alias).computeReference())
    }

    private fun qualifiableAlias(text: String): QualifiableAlias =
        PsiTreeUtil.findChildrenOfType(myFixture.file, QualifiableAlias::class.java).single {
            it.text == text && (it is QualifiedAlias || text.none { c -> c == '.' })
        }
}
