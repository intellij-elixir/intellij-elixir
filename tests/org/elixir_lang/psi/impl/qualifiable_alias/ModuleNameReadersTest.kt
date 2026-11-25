package org.elixir_lang.psi.impl.qualifiable_alias

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.qualifiableAlias
import org.elixir_lang.psi.impl.MultipleAliasesMember
import org.elixir_lang.psi.impl.computeReference
import org.elixir_lang.psi.impl.multipleAliasesMember

/** Readers of an alias's module name, which is the module of the atom the alias quotes to. */
class ModuleNameReadersTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testNameOfAQualifiedAlias() = assertName("Foo.Bar", "Foo.Bar")

    fun testNameOfAQualifiedAliasSpacedAroundTheDot() = assertName("Foo . Bar", "Foo.Bar")

    fun testNameOfAnElixirPrefixedAlias() = assertName("Elixir.Foo", "Foo")

    /** The `B.C` of `Foo.{A, B.C}` is named as written: the module it names is `Foo.B.C`, but its name is under no qualifier. */
    fun testNameOfAQualifiedMemberOfAMultiAliasIsAsWritten() {
        myFixture.configureByText("alias.ex", "alias Foo.{A, B.C}\n")

        assertEquals("B.C", myFixture.qualifiableAlias("B.C").name)
    }

    fun testNameOfAModuleHeadedAlias() = assertName("__MODULE__.Bar", "__MODULE__.Bar")

    /** `x.Inner` has no value until run time, so its name stays its text, which no module is named. */
    fun testNameOfAVariableHeadedAlias() = assertName("x.Inner", "x.Inner")

    fun testAliasDirectiveReachesTheModule() = assertAliasReaches("Foo.Bar")

    fun testElixirPrefixedAliasDirectiveReachesTheModule() = assertAliasReaches("Elixir.Foo.Bar")

    fun testAliasDirectiveSpacedAroundTheDotReachesTheModule() = assertAliasReaches("Foo . Bar")

    /** `for: BitString` names the type of `<<...>>`, which no module defines, so it has no module reference. */
    fun testBitStringHasNoReference() = assertNull(reference("defimpl P, for: BitString do\nend\n", "BitString"))

    fun testElixirPrefixedBitStringHasNoReference() =
        assertNull(reference("defimpl P, for: Elixir.BitString do\nend\n", "Elixir.BitString"))

    /** `Foo.BitString` and `Foo.Elixir` are modules the compiler finds, since the member's whole name is read. */
    fun testBitStringMemberOfAMultiAliasHasAReference() =
        assertNotNull(reference("alias Foo.{BitString, Elixir}\n", "BitString"))

    fun testElixirMemberOfAMultiAliasHasAReference() =
        assertNotNull(reference("alias Foo.{BitString, Elixir}\n", "Elixir"))

    fun testMemberOfAMultiAliasHasTheQualifierAndItsOwnName() =
        assertMember("alias Foo.{A, B.C}\n", "A", qualifier = "Foo", name = "A")

    fun testQualifiedMemberOfAMultiAliasHasItsWholeOwnName() =
        assertMember("alias Foo.{A, B.C}\n", "B.C", qualifier = "Foo", name = "B.C")

    fun testQualifiedQualifierOfAMultiAliasKeepsItsWrittenForm() =
        assertMember("alias My.Foo.{A}\n", "A", qualifier = "My.Foo", name = "A")

    fun testQualifierOfAMultiAliasIsNoMember() = assertNoMember("alias Foo.{A}\n", "Foo")

    fun testAliasOfAnAliasDirectiveIsNoMember() = assertNoMember("alias Foo.Bar\n", "Foo.Bar")

    private fun assertMember(source: String, alias: String, qualifier: String, name: String) {
        val member = member(source, alias)

        assertNotNull("$alias is a member", member)
        assertEquals(qualifier, member!!.qualifier.text)
        assertEquals(name, member.name)
    }

    private fun assertNoMember(source: String, alias: String) = assertNull(member(source, alias))

    private fun member(source: String, alias: String): MultipleAliasesMember? {
        myFixture.configureByText("alias.ex", source)

        return myFixture.qualifiableAlias(alias).multipleAliasesMember()
    }

    private fun assertName(alias: String, expected: String) {
        myFixture.configureByText("alias.ex", "defmodule M do\n  def f(x), do: $alias\nend\n")

        assertEquals(expected, myFixture.qualifiableAlias(alias).name)
    }

    private fun assertAliasReaches(alias: String) {
        myFixture.configureByText(
            "alias_directive.ex",
            "defmodule Foo.Bar do\n  def f, do: 1\nend\n\ndefmodule Caller do\n  alias $alias\n\n  def g, do: Bar.f<caret>()\nend\n"
        )

        myFixture.assertGotoDeclarationLandsIn("f", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    private fun reference(source: String, alias: String): PsiPolyVariantReference? {
        myFixture.configureByText("alias.ex", source)

        return myFixture.qualifiableAlias(alias).computeReference()
    }
}
