package org.elixir_lang.leex.reference.resolver

import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ReferenceRange
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import java.io.File

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

    fun testInnerContentPutIntoAssigns() = assertInnerContentPut("assigns[:inner_content]")
    fun testInnerContentPutIntoAssignsAsAQuotedAtom() = assertInnerContentPut("assigns[:\"inner_content\"]")
    fun testInnerContentPutIntoAssignsAsAnEscapedAtom() = assertInnerContentPut("assigns[:\"inner_cont\\x65nt\"]")
    fun testInnerContentPutIntoAssignsWithSpaces() = assertInnerContentPut("assigns[ :inner_content ]")

    fun testAnotherAssignPutIntoAssignsIsNotInnerContent() {
        addPhoenixFunction(
            """
            def to_rendered(content, view) do
              put_in(assigns[:"other"], 1)
            end
            """.trimIndent()
        )

        assertFalse(resolveSilently("inner_content").any(ResolveResult::isValidResult))
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

    private fun assertInnerContentPut(path: String) {
        addPhoenixFunction(
            """
            def to_rendered(content, view) do
              put_in($path, content)
            end
            """.trimIndent()
        )

        assertTrue(
            "@inner_content should resolve to the `put_in($path, ...)` that sets it",
            resolveSilently("inner_content").any(ResolveResult::isValidResult)
        )
    }

    fun testAUsersOwnLiveComponentIsNotFollowed() {
        assertNotFollowed(resolveInComponentTemplate(OWN_LIVE_COMPONENT, "live_component @socket, Widget, title: \"x\"", "Widget"))
    }

    fun testAUsersOwnLiveComponentWithParenthesesIsNotFollowed() {
        assertNotFollowed(resolveInComponentTemplate(OWN_LIVE_COMPONENT, "live_component(@socket, Widget, title: \"x\")", "Widget"))
    }

    fun testAUsersOwnLiveComponentOfAQualifiedModuleIsNotFollowed() {
        assertNotFollowed(resolveInComponentTemplate(OWN_LIVE_COMPONENT, "live_component @socket, AppWeb.Widget, title: \"x\""))
    }

    fun testAnImportOfAUsersLiveComponentBeforeOneOfLiveViewsIsNotFollowed() {
        myFixture.addFileToProject(
            "lib/app_web/live/own.ex",
            "defmodule AppWeb.Own do\n  def live_component(_socket, _component, _assigns), do: nil\nend\n"
        )

        assertNotFollowed(
            resolveInComponentTemplate(
                "defmodule AppWeb.Page do\n  import AppWeb.Own\n  import Phoenix.LiveView.Helpers\nend",
                "live_component @socket, Widget, title: \"x\"",
                "Widget",
            )
        )
    }

    fun testLiveViewsLiveComponentThroughAnAliasIsFollowed() {
        assertFollowed(resolveInComponentTemplate(ALIASED_HELPERS, "H.live_component(@socket, AppWeb.Widget, title: \"x\")"))
    }

    fun testLiveViewsLiveComponentThroughAnAliasOfASingleSegmentModuleIsFollowed() {
        assertFollowed(resolveInComponentTemplate(ALIASED_HELPERS, "H.live_component(@socket, Widget, title: \"x\")", "Widget"))
    }

    fun testLiveViewsLiveComponentThroughAnAliasWithoutParenthesesIsFollowed() {
        assertFollowed(resolveInComponentTemplate(ALIASED_HELPERS, "H.live_component @socket, Widget, title: \"x\"", "Widget"))
    }

    fun testAnImportedLiveComponentIsFollowed() {
        assertFollowed(resolveInComponentTemplate(IMPORTED_HELPERS, "live_component @socket, Widget, title: \"x\"", "Widget"))
    }

    fun testAnImportedLiveComponentWithParenthesesIsFollowed() {
        assertFollowed(resolveInComponentTemplate(IMPORTED_HELPERS, "live_component(@socket, Widget, title: \"x\")", "Widget"))
    }

    fun testAnImportedLiveComponentOfAQualifiedModuleIsFollowed() {
        assertFollowed(resolveInComponentTemplate(IMPORTED_HELPERS, "live_component @socket, AppWeb.Widget, title: \"x\""))
    }

    fun testAnImportedLiveComponentWithParenthesesOfAQualifiedModuleIsFollowed() {
        assertFollowed(resolveInComponentTemplate(IMPORTED_HELPERS, "live_component(@socket, AppWeb.Widget, title: \"x\")"))
    }

    /** Why the search anchors on the last segment: the whole name is found where a qualified alias's reference isn't. */
    fun testAWholeNameSearchMissesAQualifiedModulesReference() {
        resolveInComponentTemplate(IMPORTED_HELPERS, "live_component @socket, AppWeb.Widget, title: \"x\"")

        val viewModule = myFixture.file.viewProvider.allFiles.first { it.language.id == "Elixir" }.context!!
        val page = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("lib/app_web/live/page.html.leex"))!!
        val alias = PsiTreeUtil.findChildrenOfType(
            page.viewProvider.allFiles.first { it.language.id == "Elixir" },
            QualifiableAlias::class.java
        ).first { it.text == "AppWeb.Widget" }
        val reference = alias.reference!!

        assertTrue(reference.isReferenceTo(viewModule))
        assertFalse(ReferenceRange.containsOffsetInElement(reference, 0))
        assertTrue(ReferenceRange.containsOffsetInElement(reference, "AppWeb.".length))
        assertEmpty(ReferencesSearch.search(viewModule, GlobalSearchScope.projectScope(project)).findAll())
    }

    fun testALiveComponentOfAnAliasedModuleIsFollowed() {
        assertFollowed(
            resolveInComponentTemplate(
                "defmodule AppWeb.Page do\n  import Phoenix.LiveView.Helpers\n  alias AppWeb.Widget\nend",
                "live_component @socket, Widget, title: \"x\"",
            )
        )
    }

    fun testALiveComponentOfASingleSegmentModuleThatResolvesToNothingIsFollowed() {
        assertFollowed(resolveInComponentTemplate("defmodule AppWeb.Page do\nend", "live_component @socket, Widget, title: \"x\"", "Widget"))
    }

    fun testALiveModalThatResolvesToNothingIsFollowed() {
        assertFollowed(resolveInComponentTemplate("defmodule AppWeb.Page do\nend", "live_modal @socket, Widget, title: \"x\"", "Widget"))
    }

    fun testALiveComponentThatResolvesToNothingIsFollowed() {
        assertFollowed(
            resolveInComponentTemplate("defmodule AppWeb.Page do\nend", "live_component @socket, AppWeb.Widget, title: \"x\"")
        )
    }

    fun testALiveComponentOfAnotherModuleIsNotFollowed() {
        myFixture.addFileToProject("lib/app_web/live/other.ex", "defmodule AppWeb.Other do\nend\n")

        assertNotFollowed(resolveInComponentTemplate(IMPORTED_HELPERS, "live_component @socket, AppWeb.Other, title: \"x\""))
    }

    fun testALiveComponentOfAQualifiedModuleNestedUnderTheComponentIsNotFollowed() {
        myFixture.addFileToProject("lib/app_web/live/item.ex", "defmodule AppWeb.Widget.Item do\nend\n")

        assertNotFollowed(resolveInComponentTemplate(IMPORTED_HELPERS, "live_component @socket, AppWeb.Widget.Item, title: \"x\""))
    }

    fun testALiveComponentOfASingleSegmentModuleNestedUnderTheComponentIsNotFollowed() {
        myFixture.addFileToProject("lib/app_web/live/item.ex", "defmodule Widget.Item do\nend\n")

        assertNotFollowed(resolveInComponentTemplate(IMPORTED_HELPERS, "live_component @socket, Widget.Item, title: \"x\"", "Widget"))
    }

    fun testAnImportedLiveComponentFromABeamIsFollowed() {
        val results = resolveInComponentTemplate(IMPORTED_HELPERS, "live_component @socket, Widget, title: \"x\"", "Widget", compiledHelpers = true)

        assertTheCallResolvesToABeam()
        assertFollowed(results)
    }

    fun testLiveViewsLiveComponentThroughAnAliasFromABeamIsFollowed() {
        val results = resolveInComponentTemplate(ALIASED_HELPERS, "H.live_component(@socket, AppWeb.Widget, title: \"x\")", compiledHelpers = true)

        assertTheCallResolvesToABeam()
        assertFollowed(results)
    }

    fun testAProjectsLiveModalIsFollowed() {
        myFixture.addFileToProject("lib/app_web/live/live_helpers.ex", LIVE_HELPERS)

        assertFollowed(resolveInComponentTemplate(IMPORTED_LIVE_HELPERS, "live_modal @socket, AppWeb.Widget, title: \"x\""))
    }

    fun testAProjectsLiveModalOfASingleSegmentModuleIsFollowed() {
        myFixture.addFileToProject("lib/app_web/live/live_helpers.ex", LIVE_HELPERS)

        assertFollowed(resolveInComponentTemplate(IMPORTED_LIVE_HELPERS, "live_modal @socket, Widget, title: \"x\"", "Widget"))
    }

    fun testALiveModalInALibraryIsNotFollowed() {
        myFixture.addFileToProject(
            "deps/phoenix_live_view/lib/modal.ex",
            "defmodule Library.Modal do\n  def live_modal(_socket, _component, _assigns), do: nil\nend\n"
        )

        val deps = myFixture.tempDirFixture.findOrCreateDir("deps")

        PsiTestUtil.addExcludedRoot(module, deps)

        try {
            assertNotFollowed(
                resolveInComponentTemplate(
                    "defmodule AppWeb.Page do\n  import Library.Modal\nend",
                    "live_modal @socket, Widget, title: \"x\"",
                    "Widget",
                )
            )
        } finally {
            PsiTestUtil.removeExcludedRoot(module, deps)
        }
    }

    private fun assertTheCallResolvesToABeam() {
        val page = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("lib/app_web/live/page.html.leex"))!!
        val call = PsiTreeUtil.findChildrenOfType(page.viewProvider.allFiles.first { it.language.id == "Elixir" }, Call::class.java)
            .first { it.functionName() == "live_component" }
        val target = Resolved.target(call.reference as PsiPolyVariantReference)

        assertEquals("the call should resolve into the `.beam`", COMPILED_HELPERS, target?.containingFile?.virtualFile?.name)
    }

    private fun assertFollowed(results: Array<ResolveResult>) {
        assertTrue("@title should resolve to the `title:` given to the call", results.any(ResolveResult::isValidResult))
    }

    private fun assertNotFollowed(results: Array<ResolveResult>) {
        assertFalse("@title should not resolve through the call", results.any(ResolveResult::isValidResult))
    }

    /**
     * `@title` in the template of `AppWeb.Widget`, whose assigns come from the `live_component/3` call in [call], made in
     * the template of the view module [page], when the component's module is named [widget]. `Phoenix.LiveView.Helpers` is
     * a library's source or, with [compiledHelpers], its `.beam` alone, as a built dependency's is loaded.
     */
    private fun resolveInComponentTemplate(
        page: String,
        call: String,
        widget: String = "AppWeb.Widget",
        compiledHelpers: Boolean = false,
    ): Array<ResolveResult> {
        if (compiledHelpers) {
            myFixture.copyFileToProject(File(COMPILED_HELPERS_DIRECTORY, COMPILED_HELPERS).absolutePath, "deps/phoenix_live_view/ebin/$COMPILED_HELPERS")
        } else {
            myFixture.addFileToProject(
                "deps/phoenix_live_view/lib/helpers.ex",
                """
                defmodule Phoenix.LiveView.Helpers do
                  defmacro live_component(socket, component, assigns \\ [], do_block \\ []), do: {socket, component, assigns, do_block}
                end
                """.trimIndent()
            )
        }
        BeamLibraryFixture.addLibrary(project, myFixture.module, LIBRARY, listOf(myFixture.tempDirFixture.findOrCreateDir("deps/phoenix_live_view")))
        myFixture.addFileToProject("lib/app_web/live/widget.ex", "defmodule $widget do\nend\n")
        myFixture.addFileToProject("lib/app_web/live/page.ex", page)
        myFixture.addFileToProject("lib/app_web/live/page.html.leex", "<%= $call %>")

        val template = myFixture.addFileToProject("lib/app_web/live/widget.html.leex", "<%= @title %>")
        myFixture.configureFromExistingVirtualFile(template.virtualFile)
        val atOperation = PsiTreeUtil.findChildOfType(
            myFixture.file.viewProvider.allFiles.first { it.language.id == "Elixir" },
            AtOperation::class.java
        )!!
        assertNotNull("a component's template has its module as its context", atOperation.containingFile.context)
        return (atOperation.reference as PsiPolyVariantReference).multiResolve(false)
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
        const val COMPILED_HELPERS_DIRECTORY = "testData/org/elixir_lang/leex/reference/resolver/assign"
        const val COMPILED_HELPERS = "Elixir.Phoenix.LiveView.Helpers.beam"
        const val OWN_LIVE_COMPONENT = "defmodule AppWeb.Page do\n  def live_component(_socket, _component, _assigns), do: nil\nend"
        const val ALIASED_HELPERS = "defmodule AppWeb.Page do\n  alias Phoenix.LiveView.Helpers, as: H\nend"
        const val IMPORTED_HELPERS = "defmodule AppWeb.Page do\n  import Phoenix.LiveView.Helpers\nend"
        const val IMPORTED_LIVE_HELPERS = "defmodule AppWeb.Page do\n  import AppWeb.LiveHelpers\nend"
        const val LIVE_HELPERS = "defmodule AppWeb.LiveHelpers do\n  def live_modal(_socket, _component, _assigns), do: nil\nend\n"
    }
}
