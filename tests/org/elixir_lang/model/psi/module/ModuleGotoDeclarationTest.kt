package org.elixir_lang.model.psi.module

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.underAnAliasOfNsFoo

class ModuleGotoDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/module"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testCtrlClickOnModuleAliasChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationNavigatesToDefmoduleName() {
        myFixture.configureByFiles("goto_declaration.ex")
        myFixture.assertGotoDeclarationLandsIn("Target", "a defmodule declaration") { Module.`is`(it) }
    }

    /** A written `Elixir.Foo` is the module it says, so Go to Declaration on it after `alias NS.Foo` lands in `Foo`. */
    fun testAnElixirRootedNameAfterAnAliasGoesToTheModuleItSays() {
        myFixture.configureByText("alias.ex", underAnAliasOfNsFoo("\ndef g, do: Elixir.<caret>Foo.f()"))

        val declaration = myFixture.assertGotoDeclarationLandsIn("Foo", "a defmodule declaration") { Module.`is`(it) }

        assertEquals("Foo", ModuleSymbol.moduleNameText(declaration))
    }

    /** The `for:` of a `defimpl` is read as written: `Elixir.Foo` after `alias NS.Foo` goes to `Foo`. */
    fun testAnElixirRootedImplementationTargetAfterAnAliasGoesToTheModuleItSays() {
        myFixture.configureByText(
            "alias.ex",
            underAnAliasOfNsFoo("\ndefprotocol P do\n  def f(x)\nend\n\ndefimpl P, for: Elixir.<caret>Foo do\n  def f(x), do: x\nend")
        )

        val declaration = myFixture.assertGotoDeclarationLandsIn("Foo", "a defmodule declaration") { Module.`is`(it) }

        assertEquals("Foo", ModuleSymbol.moduleNameText(declaration))
    }

    /** The member of `alias Elixir.Foo.{Bar}` is `Foo.Bar` whatever `alias NS.Foo` is in scope: Go to Declaration on it lands in `Foo.Bar`. */
    fun testAnElixirRootedMultipleAliasMemberAfterAnAliasGoesToTheModuleItSays() {
        myFixture.configureByText(
            "alias.ex",
            underAnAliasOfNsFoo("\nalias Elixir.Foo.{<caret>Bar}", defines = "Foo.Bar")
        )

        val declaration = myFixture.assertGotoDeclarationLandsIn("Foo", "a defmodule declaration") { Module.`is`(it) }

        assertEquals("Foo.Bar", ModuleSymbol.moduleNameText(declaration))
    }

    /** The name `alias Elixir.Foo.{Bar}` introduces is `Foo.Bar` whatever `alias NS.Foo` is in scope: a later `Bar` reaches it. */
    fun testAnElixirRootedMultipleAliasMemberIntroducesTheModuleItSays() {
        myFixture.configureByText(
            "alias.ex",
            underAnAliasOfNsFoo("\nalias Elixir.Foo.{Bar}\n\ndef g, do: <caret>Bar.f()", defines = "Foo.Bar")
        )

        val declaration = myFixture.assertGotoDeclarationLandsIn("Foo", "a defmodule declaration") { Module.`is`(it) }

        assertEquals("Foo.Bar", ModuleSymbol.moduleNameText(declaration))
    }

    /** `alias Elixir.{Foo}` is rooted as `Elixir.Foo` is: its member reaches `Foo` after `alias NS.Foo`. */
    fun testAMultipleAliasMemberUnderElixirAfterAnAliasGoesToTheModuleItSays() {
        myFixture.configureByText("alias.ex", underAnAliasOfNsFoo("\nalias Elixir.{<caret>Foo}"))

        val declaration = myFixture.assertGotoDeclarationLandsIn("Foo", "a defmodule declaration") { Module.`is`(it) }

        assertEquals("Foo", ModuleSymbol.moduleNameText(declaration))
    }

    fun testCtrlClickOnQualifiedAliasQualifierChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration_qualified.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromQualifiedAliasQualifierNavigatesToDefmoduleName() {
        myFixture.configureByFiles("goto_declaration_qualified.ex")
        val declaration = myFixture.assertGotoDeclarationLandsIn("MyApp", "a defmodule declaration") { Module.`is`(it) }
        assertEquals("MyApp", ModuleSymbol.moduleNameText(declaration))
    }

    fun testCtrlClickOnQualifiedAliasLastSegmentChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration_qualified_last_segment.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromQualifiedAliasLastSegmentNavigatesToFullModule() {
        myFixture.configureByFiles("goto_declaration_qualified_last_segment.ex")
        val declaration = myFixture.assertGotoDeclarationLandsIn("MyApp", "a defmodule declaration") { Module.`is`(it) }
        assertEquals("MyApp.Module", ModuleSymbol.moduleNameText(declaration))
    }

    fun testCtrlClickOnQualifiedAliasMiddleSegmentChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration_qualified_middle_segment.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromQualifiedAliasMiddleSegmentNavigatesToPrefixModule() {
        myFixture.configureByFiles("goto_declaration_qualified_middle_segment.ex")
        val declaration = myFixture.assertGotoDeclarationLandsIn("MyApp", "a defmodule declaration") { Module.`is`(it) }
        assertEquals("MyApp.Module", ModuleSymbol.moduleNameText(declaration))
    }

    fun testCtrlClickOnBareAliasedNameChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration_bare_aliased.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromBareAliasedNameNavigatesDirectlyToDefmodule() {
        myFixture.configureByFiles("goto_declaration_bare_aliased.ex")
        // assertGotoDeclarationLandsIn requires a SINGLE target: this is the regression assertion for
        // the legacy chooser that offered the `alias` line alongside the defmodule.
        val declaration = myFixture.assertGotoDeclarationLandsIn("MyApp", "a defmodule declaration") { Module.`is`(it) }
        assertEquals("MyApp.Module", ModuleSymbol.moduleNameText(declaration))
    }

    fun testCtrlClickOnBareQualifiedNameChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration_bare_qualified.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromBareQualifiedNameNavigatesToDefmodule() {
        myFixture.configureByFiles("goto_declaration_bare_qualified.ex")
        val declaration = myFixture.assertGotoDeclarationLandsIn("MyApp", "a defmodule declaration") { Module.`is`(it) }
        assertEquals("MyApp.Module", ModuleSymbol.moduleNameText(declaration))
    }

    fun testCtrlClickOnAliasNamingAPrefixOfTheEnclosingModuleChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration_prefix_sibling.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromAliasNamingAPrefixOfTheEnclosingModuleNavigatesToTheSibling() {
        myFixture.configureByFiles("goto_declaration_prefix_sibling.ex")
        // In scope the walk finds only the enclosing MyModule.InternalTest, an invalid match because a
        // prefix is not an exact name, so resolution has to fall through to the module index to reach
        // the sibling - taking a non-empty in-scope set regardless of validity lets the enclosing
        // module win instead. Both assertions carry weight: both candidates begin with the leaf text
        // MyModule, so only the full-name comparison below says which one was reached.
        val declaration = myFixture.assertGotoDeclarationLandsIn("MyModule", "a defmodule declaration") { Module.`is`(it) }
        assertEquals("MyModule.Internal", ModuleSymbol.moduleNameText(declaration))
    }

    fun testCtrlClickOnMultiAliasBraceMemberChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration_multi_alias.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromMultiAliasBraceMemberNavigatesToDefmodule() {
        myFixture.configureByFiles("goto_declaration_multi_alias.ex")
        val declaration = myFixture.assertGotoDeclarationLandsIn("MyApp", "a defmodule declaration") { Module.`is`(it) }
        assertEquals("MyApp.B", ModuleSymbol.moduleNameText(declaration))
    }

    fun testGoToDeclarationFromAliasedQualifierOfACrossFileCallNavigatesToDefmodule() {
        myFixture.configureByFiles(
            "goto_declaration_alias_qualified_call.ex",
            "goto_declaration_alias_qualified_call_target.ex"
        )
        // An `alias` in scope plus the aliased name used as a remote-call qualifier, with the defmodule
        // in another file. assertGotoDeclarationLandsIn requires a SINGLE target, which is the assertion
        // that matters: the legacy PsiReference layer deliberately reports two results here - the
        // defmodule and the `alias` line, per AsTest1.testReference - and only hosting the module
        // reference on the alias keeps the gesture from offering that chooser.
        val declaration = myFixture.assertGotoDeclarationLandsIn("MyApp", "a defmodule declaration") { Module.`is`(it) }
        assertEquals("MyApp.Module", ModuleSymbol.moduleNameText(declaration))
    }

}
