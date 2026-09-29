package org.elixir_lang.navigation

import com.intellij.navigation.ChooseByNameRegistry
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.CallDefinitionClause
import org.elixir_lang.structure_view.element.CallDefinitionHead

class GotoSymbolContributorTest : PlatformTestCase() {
    private fun gotoSymbolContributor(): GotoSymbolContributor {
        val symbolModelContributors = ChooseByNameRegistry
                .getInstance()
                .symbolModelContributors

        var gotoSymbolContributor: GotoSymbolContributor? = null

        for (symbolModelContributor in symbolModelContributors) {
            if (symbolModelContributor is GotoSymbolContributor) {
                gotoSymbolContributor = symbolModelContributor
            }
        }

        assertNotNull(gotoSymbolContributor)

        return gotoSymbolContributor!!
    }

    override fun getTestDataPath(): String {
        return "testData/org/elixir_lang/navigation/goto_symbol_contributor"
    }

    /*
     * Tests
     */

    /**
     * A `def`, `@spec` or `@callback` outside any module is legal in a script, but there is no module
     * to list it under, so Go to Symbol has no entry for it. That is not an error.
     */
    fun testDefinitionsOutsideAModuleHaveNoEntry() {
        myFixture.configureByFile("top_level_definitions.exs")
        val gotoSymbolContributor = gotoSymbolContributor()

        val (items, errors) = captureLoggedErrors {
            listOf("foo", "f", "g").flatMap { name ->
                gotoSymbolContributor.getItemsByName(name, name, myFixture.project, false).toList()
            }
        }

        assertEmpty("a definition with no enclosing module is not an error", errors)
        assertEmpty("a definition with no enclosing module has nowhere to be listed", items)
    }

    /**
     * A `defmacro` still missing its head has no name to construct a clause from, so a `def` in the
     * `quote` inside it has no enclosing modular yet. That is a macro being typed, not an error.
     */
    fun testDefinitionInsideHeadlessMacroQuoteHasNoEntry() {
        myFixture.configureByFile("headless_macro_quote.ex")
        val gotoSymbolContributor = gotoSymbolContributor()

        val (items, errors) = captureLoggedErrors {
            gotoSymbolContributor.getItemsByName("foo", "foo", myFixture.project, false).toList()
        }

        assertEmpty("a headless macro is not an error", errors)
        assertEmpty("a definition quoted in a headless macro has nowhere to be listed", items)
    }

    /** A `defguard` is expanded at compile time, as Elixir defines it with `defmacro`, so Go To Symbol lists it so. */
    fun testADefguardIsListedAsCompileTime() {
        myFixture.configureByText(
            "guards.ex",
            """
            defmodule Guards do
              defguard is_small(x) when x < 10
            end
            """.trimIndent()
        )

        val items = gotoSymbolContributor().getItemsByName("is_small", "is_small", myFixture.project, false)

        assertEquals(
            listOf(org.elixir_lang.structure_view.element.Timed.Time.COMPILE),
            items.filterIsInstance<CallDefinitionClause>().map { it.callDefinition.time() }
        )
    }

    fun testIssue472() {
        myFixture.configureByFile("issue_472.ex")
        val gotoSymbolContributor = gotoSymbolContributor()
        val itemsByName = gotoSymbolContributor.getItemsByName(
                "decode_auth_type",
                "decode_a",
                myFixture.project,
                false
        )

        assertEquals(1, itemsByName.size)

        assertInstanceOf(itemsByName[0], CallDefinitionClause::class.java)
        val callDefinitionClause = itemsByName[0] as CallDefinitionClause
        assertEquals("decode_auth_type", callDefinitionClause.name)
    }

    @RequiresReadLock
    fun testIssue705BeforeCompile() {
        myFixture.configureByFile("issue_705__before_compile__.ex")
        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)

        val call = elementAtCaret!!.parent.parent.parent.parent as Call

        val enclosingModularMacroCall = enclosingModularMacroCall(call)
        assertNotNull(enclosingModularMacroCall)

        val modular = CallDefinitionClause.enclosingModular(call)
        assertNotNull(modular)
    }

    @RequiresReadLock
    fun testIssue705TypeFunctions() {
        myFixture.configureByFile("issue_705_type_functions.ex")
        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)

        val call = elementAtCaret!!.parent.parent.parent as Call

        val enclosingModularMacroCall = enclosingModularMacroCall(call)
        assertNotNull(enclosingModularMacroCall)

        val modular = CallDefinitionClause.enclosingModular(call)
        assertNotNull(modular)
    }

    @RequiresReadLock
    fun testIssue1141() {
        myFixture.configureByFile("issue_1141.ex")
        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)

        val call = elementAtCaret!!.parent.parent.parent.parent as Call

        val enclosingModularMacroCall = enclosingModularMacroCall(call)
        assertNotNull(enclosingModularMacroCall)

        val modular = CallDefinitionClause.enclosingModular(call)
        assertNotNull(modular)
    }

    /**
     * A second head for the same module/name/arity is what sends the list through
     * `Any.isDecompiled()`, whose `else` branch throws `NotImplementedError` for any type it has no
     * case for. Nothing else pins the `CallDefinitionHead` case.
     */
    fun testIssue1603() {
        myFixture.configureByFile("issue_1603.ex")
        val gotoSymbolContributor = gotoSymbolContributor()
        val itemsByName = gotoSymbolContributor.getItemsByName(
                "collide",
                "coll",
                myFixture.project,
                false
        )

        assertEquals(2, itemsByName.filterIsInstance<CallDefinitionHead>().size)
    }

    @RequiresReadLock
    fun testIssue1228() {
        myFixture.configureByFile("issue_1228.ex")
        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)

        val call = elementAtCaret!!.parent.parent.parent.parent as Call

        val enclosingModularMacroCall = enclosingModularMacroCall(call)
        assertNotNull(enclosingModularMacroCall)

        val modular = CallDefinitionClause.enclosingModular(call)
        assertNotNull(modular)
    }
}
