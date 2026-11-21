package org.elixir_lang.psi.scope.callable_table

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.validDefinitions
import org.elixir_lang.psi.scope.WalkProbe
import org.elixir_lang.psi.scope.WalkProbe.Counter
import org.elixir_lang.psi.scope.WalkTestSupport
import java.io.File

/** A library added after a use first resolves is found by the next resolve, through an `import` the table recorded. */
class LibraryRootsTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    override fun tearDown() {
        try {
            WalkProbe.reset()
        } finally {
            super.tearDown()
        }
    }

    fun testAnAddedLibraryIsFound() {
        val file = myFixture.configureByText(
            "user.ex",
            """
            defmodule LibraryRoots.User do
              def go, do: new()

              import :queue
            end
            """.trimIndent()
        )
        val reference = file.viewProvider.findReferenceAt(file.text.indexOf("new()"), ElixirLanguage) as PsiPolyVariantReference

        WalkProbe.reset()
        val before = WalkProbe.counting { reference.validDefinitions() }
        val buildsBefore = WalkProbe.snapshot().getValue(Counter.TABLE_BUILD)
        assertFalse("before the library: $before", before.any { it.startsWith("queue.beam") })

        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "library_roots", LIBRARY) {
            val after = WalkProbe.counting { reference.validDefinitions() }
            val buildsAfter = WalkProbe.snapshot().getValue(Counter.TABLE_BUILD)

            assertContainsElements(after, "queue.beam.decompiled.ex new/0")
            assertTrue("tables built: $buildsBefore, then $buildsAfter", buildsBefore > 0 && buildsAfter > buildsBefore)
        }
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"

    private companion object {
        val LIBRARY = File("testData/org/elixir_lang/psi/scope/callable_table/library_roots/lib")
    }
}
