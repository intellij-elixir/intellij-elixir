package org.elixir_lang.leex.reference.resolver

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.psi.AtOperation

/**
 * An `@assign` in a LiveView layout resolves by walking the Phoenix functions that set it, naming
 * the expression shapes an assignment can hide in. A shape the walk does not name assigns nothing,
 * which is what a stray literal or an operation in such a function means, and is not an error.
 *
 * A template under `layout/` is used because that is the branch the resolver can reach without a
 * view module as the template's context. The resolver searches libraries only, as Phoenix is a
 * dependency, so the stub Phoenix module is registered as a library. The walk reads a `do` block's
 * body last expression first and stops once the assign resolves, so an unmodelled shape sits after
 * the assignment or replaces it.
 */
class AssignTest : PlatformTestCase() {
    fun testLiveActionResolvesToTheAtomAssignedByAssignAction() {
        addPhoenixFunction(
            """
            def assign_action(socket, action) do
              assign(socket, :live_action, action)
            end
            """.trimIndent()
        )

        val results = resolveSilently("live_action")

        assertTrue("@live_action should resolve to the `:live_action` assigned", results.any(ResolveResult::isValidResult))
    }

    fun testLiteralExpressionInAssigningFunctionAssignsNothing() {
        addPhoenixFunction(
            """
            def assign_action(socket, action) do
              assign(socket, :live_action, action)
              1
            end
            """.trimIndent()
        )

        resolveSilently("live_action")
    }

    fun testNonAtomAssignKeyAssignsNothing() {
        addPhoenixFunction(
            """
            def assign_action(socket, action) do
              assign(socket, "live_action", action)
            end
            """.trimIndent()
        )

        resolveSilently("live_action")
    }

    fun testLiteralInToRenderedAssignsNothing() {
        addPhoenixFunction(
            """
            def to_rendered(content, view) do
              1
            end
            """.trimIndent()
        )

        resolveSilently("inner_content")
    }

    fun testOperationInRenderPendingComponentsAssignsNothing() {
        addPhoenixFunction(
            """
            def render_pending_components(socket) do
              a + b
            end
            """.trimIndent()
        )

        resolveSilently("myself")
    }

    fun testLiteralInRenderPendingComponentsAssignsNothing() {
        addPhoenixFunction(
            """
            def render_pending_components(socket) do
              1
            end
            """.trimIndent()
        )

        resolveSilently("myself")
    }

    fun testKeywordKeyResolves() = assertAssigns("assign(socket, count: 1)", "count")
    fun testQuotedKeywordKeyIsTheAssignedName() = assertAssigns("assign(socket, \"count\": 1)", "count")
    fun testQuotedAtomIsTheAssignedName() = assertAssigns("assign(socket, :\"count\", 1)", "count")
    fun testEscapedQuotedAtomIsTheAssignedName() = assertAssigns("assign(socket, :\"cou\\x6et\", 1)", "count")

    fun testMicroSignKeywordKeyFrom1_14() = assertAssigns("assign(socket, µ: 1)", "µ", elixir("1.14.0"))
    fun testMicroSignKeywordKeyBefore1_14() = assertAssigns("assign(socket, µ: 1)", "µ", elixir("1.13.0"))
    fun testMicroSignAtomFrom1_14() = assertAssigns("assign(socket, :µ, 1)", "µ", elixir("1.14.0"))
    fun testMicroSignAtomBefore1_14() = assertAssigns("assign(socket, :µ, 1)", "µ", elixir("1.13.0"))

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun assertAssigns(assignment: String, assign: String, languageLevel: ElixirLanguageLevel? = null) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        myFixture.addFileToProject(
            "lib/app_web/live/count_live.ex",
            "defmodule CountLive do\n  def mount(_params, _session, socket) do\n    $assignment\n  end\nend\n"
        )
        val template = myFixture.addFileToProject("lib/app_web/live/count_live.html.leex", "<%= @$assign %>")
        myFixture.configureFromExistingVirtualFile(template.virtualFile)
        val elixirRoot = myFixture.file.viewProvider.allFiles.first { it.language.id == "Elixir" }
        val atOperation = PsiTreeUtil.findChildOfType(elixirRoot, AtOperation::class.java)!!
        val reference = atOperation.reference as PsiPolyVariantReference

        assertTrue(
            "@$assign should resolve to the name assigned by `$assignment`",
            reference.multiResolve(false).any(ResolveResult::isValidResult)
        )
    }

    private fun addPhoenixFunction(definition: String) {
        myFixture.addFileToProject(
            "deps/phoenix_live_view/lib/utils.ex",
            "defmodule Phoenix.LiveView.Utils do\n" + definition.prependIndent("  ") + "\nend\n"
        )
        val dependency = myFixture.tempDirFixture.findOrCreateDir("deps/phoenix_live_view")
        BeamLibraryFixture.addLibrary(project, myFixture.module, LIBRARY, listOf(dependency))
    }

    private fun resolveSilently(assign: String): Array<ResolveResult> {
        val template = myFixture.addFileToProject("lib/app_web/templates/layout/app.html.leex", "<%= @$assign %>")
        myFixture.configureFromExistingVirtualFile(template.virtualFile)
        val atOperation = PsiTreeUtil.findChildOfType(
            myFixture.file.viewProvider.allFiles.first { it.language.id == "Elixir" },
            AtOperation::class.java
        )
        assertNotNull("template did not parse to an assign", atOperation)
        val elixirRoot = atOperation!!.containingFile
        assertNull("a layout template has no view module as its context", elixirRoot.context)
        assertEquals("the template must sit under layout/", "layout", elixirRoot.containingDirectory?.name)
        val reference = atOperation.reference
        assertInstanceOf(reference, org.elixir_lang.leex.reference.Assign::class.java)
        reference as PsiPolyVariantReference

        val (results, errors) = captureLoggedErrors { reference.multiResolve(false) }

        assertEmpty("an expression the resolver does not assign from is not an error", errors)

        return results
    }

    private companion object {
        const val LIBRARY = "phoenix_live_view"
    }
}
