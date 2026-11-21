package org.elixir_lang.code_insight.completion.contributor

import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.ExpanderWarmUp
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.expander.PhoenixCorpus
import org.elixir_lang.expander.PhoenixRouterFixture
import org.elixir_lang.expander.psiLetGo
import org.elixir_lang.expander.utf8
import org.elixir_lang.model.psi.config.caret
import java.io.File

/**
 * Route paths offered inside `~p`, from the router the caret's module names in `@router`, in the generated Phoenix
 * project [PhoenixRouterFixture.demo]; on a leg other than its lock's these tests assert nothing. `DemoWeb.RouteShapesRouter`,
 * in its `routers/`, writes one route of each shape.
 */
class VerifiedRouteCompletionTest : LegSdkFixtureTestCase() {
    /** Every route of the generated router is offered at a `~p` of only `/`, each path once, as Elixir compiled them. */
    fun testTheGeneratedRoutersRoutesAreOffered() {
        val compiled = demo("defmodule Caret do\n  use DemoWeb, :verified_routes\n\n  def root, do: ~p\"/\"\nend\n") ?: return
        val root = ROWS.first()
        warm(root)

        val expected = PhoenixRouterFixture.routesOf(compiled.routers, "DemoWeb.Router")
            .map { utf8((it as OtpErlangTuple).elementAt(1)) }
            .distinct()
            .sorted()

        assertEquals("root: offers ${expected.joinToString(" ")}", row(root))
    }

    /** What each place in and around a `~p` offers, and what accepting an offer leaves. */
    fun testEachPlaceOffersTheRoutesUnderItsPathSoFar() {
        demo() ?: return
        myFixture.addFileToProject(HEEX, HEEX_CALLER)
        warm(ROWS.first())

        val expected = """
            root: offers / /assets/css-:md5 /files/*path /mailbox /users/:id /users/:id/edit /users/:id/posts/:post_id /users/register
            under a segment: offers /users/:id /users/:id/edit /users/:id/posts/:post_id /users/register
            in a segment: inserted   def in_a_segment, do: ~p"/users/register<caret>"
            after an interpolation: offers /edit /posts/:post_id
            after an interpolation, static: accepted /edit   def after_interpolation(id), do: ~p"/users/#{id}/edit<caret>"
            after an interpolation, dynamic: accepted /posts/:post_id   def after_interpolation(id), do: ~p"/users/#{id}/posts/#{<caret>}"
            in a segment after an interpolation: inserted   def in_a_segment_after_interpolation(id), do: ~p"/users/#{id}/edit<caret>"
            inside an interpolation: inserted   def in_an_interpolation(user), do: ~p"/users/#{user<caret>}/"
            right after an interpolation: offers /edit /posts/:post_id
            a dynamic segment: accepted /users/:id   def under_a_segment, do: ~p"/users/#{<caret>}"
            a dynamic part of a segment: inserted   def dynamic_part, do: ~p"/assets/css-#{<caret>}"
            a glob: inserted   def glob, do: ~p"/files/#{<caret>}"
            a glob's whole route typed: none
            a forward: inserted   def forward, do: ~p"/mailbox<caret>"
            after a query: none
            single quotes: inserted   def single_quotes, do: ~p'/users/register<caret>'
            parentheses: inserted   def parentheses, do: ~p(/users/register<caret>)
            bars: inserted   def bars, do: ~p|/users/register<caret>|
            slashes, escaped: inserted   def slashes, do: ~p/\/users\/register<caret>/
            slashes, dynamic: accepted /posts/:post_id   def slashes_after_interpolation(id), do: ~p/\/users\/#{id}\/posts\/#{<caret>}/
            an escape of another delimiter's terminator: none
            after the sigil: none
            heredoc: inserted     /users/register<caret>
            heredoc, indented past its terminator: none
            another sigil: none
            a string: none
            in ~H: none
            in .heex, a template not reached from its module, left to template support: none
        """.trimIndent()
        val actual = ROWS.joinToString("\n", transform = ::row)

        assertEquals(expected, actual)
    }

    /**
     * A completion before the router's expansion is held offers nothing, so it runs no expansion of the router itself:
     * it warms the router in a read of its own, and the completion after that offers the routes.
     */
    fun testACompletionBeforeTheRouterIsHeldOffersNothingAndTheNextOffersItsRoutes() {
        demo() ?: return
        val row = ROWS[1]

        val cold = warm(row)
        val warm = row(row)

        assertEquals(
            "under a segment: none\nunder a segment: offers /users/:id /users/:id/edit /users/:id/posts/:post_id /users/register",
            "$cold\n$warm",
        )
    }

    /** A router's routes, once warmed, are still offered after its PSI, which no editor shows, is collected. */
    fun testAWarmedRoutersRoutesAreOfferedAfterAGarbageCollection() {
        val compiled = demo() ?: return
        val row = ROWS[1]
        warm(row)
        val router = File(compiled.project, "routers/route_shapes_router.ex")

        assertTrue("the router's PSI is let go", psiLetGo(project, LocalFileSystem.getInstance().findFileByIoFile(router)!!))
        assertEquals("under a segment: offers /users/:id /users/:id/edit /users/:id/posts/:post_id /users/register", row(row))
    }

    /** With the expander off nothing is offered, as the route list is the expander's. */
    fun testWithTheExpanderOffNothingIsOffered() {
        demo(expander = false) ?: return

        assertEquals("root: none", row(ROWS.first()))
    }

    /** What [row] offers before the router is held, once the router's expansion that this completion starts has finished. */
    private fun warm(row: Row): String {
        val warmUp = ExpanderWarmUp.getInstance(project)

        return warmUp.asynchronous { row(row) }
            .also { PlatformTestUtil.waitForPromise(warmUp.pending ?: error("no completion warmed a router")) }
    }

    /**
     * The demo's files as the module's, with `routers/` a source root too, so its routers are found from source, [text]
     * as the [CALLER], and the expander on or off as [expander] says; `null` on a leg other than its lock's.
     */
    private fun demo(text: String = SHAPES_CALLER, expander: Boolean = true): PhoenixRouterFixture.Compiled? {
        val compiled = PhoenixRouterFixture.demo.getOrThrow() ?: return null
        PhoenixCorpus(project, myFixture.testRootDisposable, compiled.project).setUp(module)
        val routers = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(compiled.project, "routers"))!!
        PsiTestUtil.addSourceRoot(module, routers)
        if (expander) enableExpander(myFixture.testRootDisposable) else disableExpander(myFixture.testRootDisposable)
        caller(text)

        return compiled
    }

    /**
     * A place in the [CALLER], marked by [Row.marked]: what completion offers there, sorted, or the line it leaves once it
     * inserted its one offer or [Row.accept], with the caret marked. The file is put back, and saved, after.
     */
    private fun row(row: Row): String {
        myFixture.caret(row.path, row.marked)
        val document = myFixture.editor.document
        val original = document.text

        try {
            val items = myFixture.completeBasic()
            val accepted = items?.firstOrNull { it.lookupString == row.accept }

            return "${row.name}: " + when {
                items == null -> "inserted ${caretLine()}"
                accepted != null -> {
                    myFixture.lookup.currentItem = accepted
                    myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
                    "accepted ${row.accept} ${caretLine()}"
                }
                items.isEmpty() -> "none"
                else -> "offers ${items.map { it.lookupString }.sorted().joinToString(" ")}"
            }
        } finally {
            LookupManager.hideActiveLookup(project)
            WriteCommandAction.runWriteCommandAction(project) { document.setText(original) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    /** The caret's line, with `<caret>` where the caret is. */
    private fun caretLine(): String {
        val document = myFixture.editor.document
        val offset = myFixture.editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val text = document.getText(TextRange(start, document.getLineEndOffset(line)))

        return text.substring(0, offset - start) + "<caret>" + text.substring(offset - start)
    }

    /**
     * @property accept the offer to accept, `null` to show what is offered
     * @property path the file [marked] is in
     */
    private class Row(val name: String, val marked: String, val accept: String? = null, val path: String = CALLER)

    private companion object {
        const val HEEX = "lib/caller.html.heex"
        const val HEEX_CALLER = "<.link href={~p\"/users/h\"}>x</.link>\n"

        val SHAPES_CALLER = """
            defmodule Caret do
              use Phoenix.VerifiedRoutes, router: DemoWeb.RouteShapesRouter
              import Phoenix.Component

              def root, do: ~p"/"
              def under_a_segment, do: ~p"/users/"
              def in_a_segment, do: ~p"/users/re"
              def after_interpolation(id), do: ~p"/users/#{id}/"
              def in_a_segment_after_interpolation(id), do: ~p"/users/#{id}/ed"
              def in_an_interpolation(user), do: ~p"/users/#{user}/"
              def right_after_interpolation(id), do: ~p"/users/#{id}"
              def dynamic_part, do: ~p"/assets/c"
              def glob, do: ~p"/fi"
              def whole_glob(path), do: ~p"/files/#{path}"
              def forward, do: ~p"/ma"
              def after_a_query, do: ~p"/users?"
              def single_quotes, do: ~p'/users/re'
              def parentheses, do: ~p(/users/re)
              def bars, do: ~p|/users/re|
              def slashes, do: ~p/\/users\/re/
              def slashes_after_interpolation(id), do: ~p/\/users\/#{id}\//
              def escaped_slash, do: ~p"/users\/re"
              def after_the_sigil, do: ~p"/users/re"

              def heredoc do
                ~p""${'"'}
                /users/re
                ""${'"'}
              end

              def heredoc_indented do
                ~p""${'"'}
                  /users/re
                ""${'"'}
              end

              def another_sigil, do: ~s"/users/"
              def a_string, do: "/users/x"

              def in_h(assigns) do
                ~H""${'"'}
                <.link href={~p"/users/h"}>x</.link>
                ""${'"'}
              end
            end
        """

        val ROWS = listOf(
            Row("root", "root, do: ~p\"/<caret>\""),
            Row("under a segment", "under_a_segment, do: ~p\"/users/<caret>\""),
            Row("in a segment", "in_a_segment, do: ~p\"/users/re<caret>\""),
            Row("after an interpolation", "after_interpolation(id), do: ~p\"/users/#{id}/<caret>\""),
            Row("after an interpolation, static", "after_interpolation(id), do: ~p\"/users/#{id}/<caret>\"", "/edit"),
            Row("after an interpolation, dynamic", "after_interpolation(id), do: ~p\"/users/#{id}/<caret>\"", "/posts/:post_id"),
            Row("in a segment after an interpolation", "do: ~p\"/users/#{id}/ed<caret>\""),
            Row("inside an interpolation", "~p\"/users/#{user<caret>}/\""),
            Row("right after an interpolation", "~p\"/users/#{id}<caret>\""),
            Row("a dynamic segment", "under_a_segment, do: ~p\"/users/<caret>\"", "/users/:id"),
            Row("a dynamic part of a segment", "~p\"/assets/c<caret>\""),
            Row("a glob", "~p\"/fi<caret>\""),
            Row("a glob's whole route typed", "~p\"/files/#{path}<caret>\""),
            Row("a forward", "~p\"/ma<caret>\""),
            Row("after a query", "~p\"/users?<caret>\""),
            Row("single quotes", "~p'/users/re<caret>'"),
            Row("parentheses", "~p(/users/re<caret>)"),
            Row("bars", "~p|/users/re<caret>|"),
            Row("slashes, escaped", "~p/\\/users\\/re<caret>/"),
            Row("slashes, dynamic", "~p/\\/users\\/#{id}\\/<caret>/", "/posts/:post_id"),
            Row("an escape of another delimiter's terminator", "~p\"/users\\/re<caret>\""),
            Row("after the sigil", "after_the_sigil, do: ~p\"/users/re\"<caret>"),
            Row("heredoc", "\n    /users/re<caret>\n"),
            Row("heredoc, indented past its terminator", "      /users/re<caret>\n"),
            Row("another sigil", "~s\"/users/<caret>\""),
            Row("a string", "\"/users/<caret>x\""),
            Row("in ~H", "~p\"/users/<caret>h\""),
            Row("in .heex, a template not reached from its module, left to template support", "~p\"/users/<caret>h\"", path = HEEX),
        )
    }
}
