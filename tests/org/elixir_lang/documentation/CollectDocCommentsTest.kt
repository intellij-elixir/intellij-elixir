package org.elixir_lang.documentation

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call

private const val INNER = "defmodule Inner do\n  @moduledoc \"Inner\"\nend"

class CollectDocCommentsTest : PlatformTestCase() {
    fun testModuleAndFunctionDocumentationIsCollected() {
        myFixture.configureByText(
            "documented.ex",
            """
            defmodule Documented do
              @moduledoc "Module"

              @doc "Function"
              def f, do: 1
            end
            """.trimIndent()
        )

        assertEquals(listOf("@moduledoc \"Module\"", "@doc \"Function\""), collectDocCommentTexts())
    }

    /** A `@moduledoc` under an `if` in a module documents that module, as Elixir applies it. */
    fun testModuleDocumentationUnderAnIfIsOwnedByItsModule() {
        myFixture.configureByText("owned.ex", "defmodule Outer do\n  if true do\n    @moduledoc \"Doc\"\n  end\nend\n")

        val attribute = PsiTreeUtil.findChildrenOfType(myFixture.file, AtUnqualifiedNoParenthesesCall::class.java)
            .single { it.text.startsWith("@moduledoc") }
        val owner = Comment(attribute).owner as? Call

        assertEquals("defmodule Outer do", owner?.text?.lineSequence()?.first())
    }

    fun testHeredocHasNoDocumentation() {
        myFixture.configureByText("heredoc.ex", "\"\"\"\nbar\n\"\"\"")

        assertEmpty(collectDocCommentTexts())
    }

    fun testStructHasNoDocumentation() {
        myFixture.configureByText(
            "struct.ex",
            """
            %Schema{
              type: :object,
              properties: %{
                header: %Schema{type: :string}
              }
            }
            """.trimIndent()
        )
        assertShape(ElixirStructOperation::class.java)

        assertEmpty(collectDocCommentTexts())
    }

    fun testUnfinishedModuleAttributeIsSkipped() {
        myFixture.configureByText(
            "unfinished.ex",
            """
            @mo
            defmodule Identicon do
              @moduledoc false
            end
            """.trimIndent()
        )
        assertShape(ElixirUnmatchedAtOperation::class.java)

        assertEquals(listOf("@moduledoc false"), collectDocCommentTexts())
    }

    fun testModuleDocumentationIsCollectedInsideEveryShape() {
        val shapes = listOf(
            Triple("anonymous function", "fn -> $INNER end", ElixirAnonymousFunction::class.java),
            Triple("call argument", "foo($INNER)", ElixirUnmatchedUnqualifiedParenthesesCall::class.java),
            Triple("case clause", "case x do\n  _ -> $INNER\nend", ElixirStabOperation::class.java),
            Triple("if", "if true do\n  $INNER\nend", ElixirUnmatchedUnqualifiedNoParenthesesCall::class.java),
            Triple("interpolation", "\"#{$INNER}\"", ElixirInterpolation::class.java),
            Triple("list", "[$INNER]", ElixirList::class.java),
            Triple("map", "%{inner: $INNER}", ElixirMapOperation::class.java),
            Triple("match", "inner = $INNER", ElixirUnmatchedMatchOperation::class.java),
            Triple("parentheses", "($INNER)", ElixirParentheticalStab::class.java),
            Triple("struct", "%Wrapper{inner: $INNER}", ElixirStructOperation::class.java),
            Triple("tuple", "{$INNER}", ElixirTuple::class.java),
            Triple("unary operation", "!$INNER", ElixirUnmatchedUnaryOperation::class.java),
        )
        val placements = listOf<Pair<String, (String) -> String>>(
            "top level" to { it },
            "module body" to { "defmodule Outer do\n$it\nend" },
        )

        val failures = placements.flatMap { (placement, place) ->
            shapes.mapNotNull { (name, text, shape) ->
                val label = "$placement $name"
                myFixture.configureByText("inside.ex", place(text))

                val inner = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).singleOrNull { it.text == INNER }
                val enclosing = inner?.let { PsiTreeUtil.getParentOfType(it, shape, true) }

                if (enclosing == null || !text.contains(enclosing.text)) {
                    "$label: `$text` did not parse to ${shape.simpleName} around `Inner`"
                } else {
                    collectedFailure(label, listOf("@moduledoc \"Inner\""))
                }
            }
        }

        assertEmpty(failures)
    }

    fun testModuleDocumentationIsCollectedInsideFunctionBodies() {
        val definitions = listOf(
            "def f do\n$INNER\nend",
            "def f(x) when is_integer(x) do\n$INNER\nend",
            "def f, do: ($INNER)",
            "defp f do\n$INNER\nend",
            "defmacro f do\n$INNER\nend",
            "defmacrop f do\n$INNER\nend",
            "defmacro __using__(_) do\nquote do\ndef g do\n$INNER\nend\nend\nend",
        )

        val failures = definitions.mapNotNull { definition ->
            myFixture.configureByText("function.ex", "defmodule Outer do\n$definition\nend")

            collectedFailure(definition.replace("\n", " "), listOf("@moduledoc \"Inner\""))
        }

        assertEmpty(failures)
    }

    fun testDocumentationIsCollectedInsideAQuoteInAFunctionBody() {
        val quoted = "quote do\n@doc \"Injected\"\ndef injected, do: 1\nend"
        val definitions = listOf(
            "def f do\n$quoted\nend",
            "defp f do\n$quoted\nend",
            "defmacro __using__(_) do\n$quoted\nend",
            "defmacrop f do\n$quoted\nend",
            "defmacro __using__(opts) do\nquote location: :keep, bind_quoted: [opts: opts] do\n@doc \"Injected\"\ndef injected, do: 1\nend\nend",
            "defmacro __using__(_) do\nquote do\nif not Module.has_attribute?(__MODULE__, :doc) do\n@doc \"Injected\"\nend\ndef injected, do: 1\nend\nend",
            "defmacro __using__(_) do\nif true do\n$quoted\nend\nend",
        )

        val failures = definitions.mapNotNull { definition ->
            myFixture.configureByText("quoted.ex", "defmodule Outer do\n$definition\nend")

            collectedFailure(definition.replace("\n", " "), listOf("@doc \"Injected\""))
        }

        assertEmpty(failures)
    }

    fun testFunctionDocumentationIsCollectedInsideACallInAModuleBody() {
        myFixture.configureByText(
            "conditional.ex",
            """
            defmodule Outer do
              if Code.ensure_loaded?(Jason) do
                @doc "Function"
                def f, do: 1
              end
            end
            """.trimIndent()
        )

        assertEquals(listOf("@doc \"Function\""), collectDocCommentTexts())
    }

    fun testFunctionDocumentationIsCollectedInsideAnAnonymousFunctionInAModuleBody() {
        myFixture.configureByText(
            "generated.ex",
            """
            defmodule Generated do
              Enum.each([:a, :b], fn name ->
                @doc "Generated"
                def unquote(name)(), do: 1
              end)
            end
            """.trimIndent()
        )

        assertEquals(listOf("@doc \"Generated\""), collectDocCommentTexts())
    }

    private fun assertShape(shape: Class<out PsiElement>) {
        assertNotNull(
            "fixture did not parse to ${shape.simpleName}",
            PsiTreeUtil.findChildOfType(myFixture.file, shape)
        )
    }

    private fun collectedFailure(label: String, expected: List<String>): String? =
        try {
            collectDocCommentTexts().takeIf { it != expected }?.let { "$label: collected $it" }
        } catch (error: AssertionError) {
            "$label: ${error.message}"
        }

    private fun collectDocCommentTexts(): List<String> =
        collectDocComments().map { (it as Comment).moduleAttribute.text }

    /** Cancelling the indicator makes a walk that never returns throw `ProcessCanceledException` instead of hanging the run. */
    private fun collectDocComments(): List<PsiDocCommentBase> {
        val comments = mutableListOf<PsiDocCommentBase>()
        val indicator = EmptyProgressIndicator()
        val cancellation = AppExecutorUtil.getAppScheduledExecutorService().schedule(indicator::cancel, 10, TimeUnit.SECONDS)

        val (_, warning) = try {
            captureLoggedWarning(ElixirDocumentationProvider::class.java.name) {
                ProgressManager.getInstance().runProcess(
                    { ElixirDocumentationProvider().collectDocComments(myFixture.file) { comments.add(it) } },
                    indicator
                )
            }
        } finally {
            cancellation.cancel(false)
        }

        assertFalse("collectDocComments did not return within 10 seconds", indicator.isCanceled)
        assertNull("collectDocComments logged a warning: $warning", warning)

        return comments
    }
}
