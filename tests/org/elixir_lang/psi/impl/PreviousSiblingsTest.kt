package org.elixir_lang.psi.impl

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.WalkTestSupport.location

/** What a call sees of the statements before it in its block, as the block is after an edit. */
class PreviousSiblingsTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "helpers.ex",
            """
            defmodule Helper do
              def helper_fun, do: :ok
            end

            defmodule Helper.Nested do
              def nested_fun, do: :ok
            end

            defmodule Other do
              def helper_fun, do: :other
            end

            defmodule Late do
              def late_fun, do: :ok
            end
            """.trimIndent()
        )
    }

    /** Only the previous-sibling walk reaches an `import` in a function's own body. */
    fun testImportInTheFunctionBody() {
        configure(
            """
            defmodule Caller do
              def go do
                import Helper
                :ok
                helper_fun()
              end
            end
            """
        )

        assertResolves("helper_fun()", "helpers.ex:2:3")
    }

    fun testImportAfterTheCallInTheFunctionBodyIsNotSeen() {
        configure(
            """
            defmodule Caller do
              def go do
                :ok
                helper_fun()
                import Helper
              end
            end
            """
        )

        assertResolves("helper_fun()")
    }

    fun testAnEditToTheImportInTheFunctionBodyIsSeen() {
        configure(
            """
            defmodule Caller do
              def go do
                import Helper
                :ok
                helper_fun()
              end
            end
            """
        )
        assertResolves("helper_fun()", "helpers.ex:2:3")

        edit { it.replace("import Helper", "import Other") }

        assertResolves("helper_fun()", "helpers.ex:10:3")
    }

    fun testAnImportInsertedInTheFunctionBodyIsSeen() {
        configure(
            """
            defmodule Caller do
              def go do
                :ok
                helper_fun()
              end
            end
            """
        )
        assertResolves("helper_fun()")

        edit { it.replace("    :ok\n", "    :ok\n    import Helper\n") }

        assertResolves("helper_fun()", "helpers.ex:2:3")
    }

    fun testAliasBeforeTheDefinition() {
        configure(
            """
            defmodule Caller do
              alias Helper.Nested

              def one, do: :ok
              def go, do: Nested.nested_fun()
            end
            """
        )

        assertResolves("Nested.nested_fun()", "helpers.ex:6:3")
    }

    private fun configure(source: String) {
        myFixture.configureByText("caller.ex", source.trimIndent())
    }

    private fun edit(change: (String) -> String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.setText(change(document.text))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    private fun assertResolves(text: String, vararg locations: String) {
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == text }
        val valid = (call.reference as PsiPolyVariantReference).multiResolve(false).filter { it.isValidResult }

        assertEquals(text, locations.toList(), valid.map { location(it.element) })
    }
}
