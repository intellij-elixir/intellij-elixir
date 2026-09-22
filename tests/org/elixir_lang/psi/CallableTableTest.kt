package org.elixir_lang.psi

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub.isModular

/**
 * `CallableTable.of` builds once per module scope and reuses the same instance across resolves, and its
 * entries carry enough of the walker's own state (`Path.visitedElements`/`wrappers`) to reproduce the
 * entrance-relative guards `Import`/`Use`/`if`/`unless` apply live, instead of losing the resolve path a
 * `use`-sourced declaration needs or over-offering a declaration an entrance already sits inside.
 */
class CallableTableTest : PlatformTestCase() {
    fun testTableIsTheSameInstanceAcrossResolves() {
        myFixture.configureByText(
            "cached.ex",
            """
            defmodule Cached do
              def one, do: 1
              def two, do: 2
            end
            """.trimIndent()
        )

        val modular = modularCall("Cached")

        assertSame(CallableTable.of(modular), CallableTable.of(modular))
    }

    fun testUseSourcedEntryCarriesTheUseCallInItsWrappers() {
        myFixture.configureByFiles("through_use.ex")

        val modular = modularCall("ThroughUse")
        val entry = CallableTable.of(modular).declaring("clause").single()
        val useCall = PsiTreeUtil.findChildrenOfType(modular, Call::class.java).single { Use.`is`(it) }

        assertTrue(
            "the `use Using` call that declared `clause/1` must be recorded in its entry's wrappers",
            entry.path.wrappers.any { it.isEquivalentTo(useCall) }
        )
        assertTrue(
            "resolving from outside the `use` must still offer the entry",
            entry.reachableFrom(modular, followsImports = true)
        )
    }

    /**
     * A module's scope looks through an `if`, so a definition behind one is an entry with no wrapper, and resolves
     * from a function body once, as any other does.
     */
    fun testADefinitionBehindAnIfIsAnUnwrappedEntryThatResolvesFromOutsideIt() {
        myFixture.configureByText(
            "if_wrapped.ex",
            """
            defmodule IfWrapped do
              if true do
                def conditional, do: :ok
              end

              def caller, do: conditional()
            end
            """.trimIndent()
        )

        val modular = modularCall("IfWrapped")
        val entry = CallableTable.of(modular).declaring("conditional").single()

        assertTrue("the `if` is looked through, not wrapped", entry.path.wrappers.isEmpty())

        val use = "def caller, do: conditional()"
        val leaf = myFixture.file.findElementAt(myFixture.file.text.indexOf(use) + use.indexOf("conditional"))!!
        val reference = generateSequence(leaf) { it.parent }.mapNotNull { it.reference }.first()
        val resolved = (reference as com.intellij.psi.PsiPolyVariantReference).multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element?.text }

        assertEquals(listOf("def conditional, do: :ok"), resolved)
    }

    /** A use of a name may reach a declaration whose name it only starts, as a candidate: what starts with it, and only that. */
    fun testDeclaringStartingWithNamesWhatAPrefixStartsInTheOrderTheyAreFirstDeclared() {
        myFixture.configureByText(
            "prefixed.ex",
            """
            defmodule Prefixed do
              def snoc, do: 1
              def snow, do: 2
              def snoc!, do: 3
              def other, do: 4
              def snoc, do: 5
            end
            """.trimIndent()
        )

        val table = CallableTable.of(modularCall("Prefixed"))

        assertEquals(
            listOf("def snoc, do: 1", "def snoc, do: 5", "def snow, do: 2", "def snoc!, do: 3"),
            table.declaringStartingWith("sno").map { it.call.text }
        )
        assertEquals(
            listOf("def snoc, do: 1", "def snoc, do: 5", "def snoc!, do: 3"),
            table.declaringStartingWith("snoc").map { it.call.text }
        )
        assertEquals(emptyList<String>(), table.declaringStartingWith("x").map { it.call.text })
    }

    private fun modularCall(name: String): Call =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { isModular(it) && it.text.startsWith("defmodule $name ") }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"
}
