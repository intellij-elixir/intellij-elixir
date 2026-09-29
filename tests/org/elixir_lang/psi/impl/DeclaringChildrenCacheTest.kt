package org.elixir_lang.psi.impl

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirStabBody

/**
 * #4123's fifth cause, found profiling `elixir_parser.beam` live after the first four fixes landed:
 * resolving a call inside a module walks every previous sibling expression
 * ([ProcessDeclarationsImpl.processDeclarationsInPreviousSibling]) and asks
 * [org.elixir_lang.psi.impl.declarations.UseScopeImpl.selector] to classify each one from scratch - six
 * `isCalling` checks, then `CallDefinitionClause.is`, `isModular` and `hasDoBlockOrKeyword` - only to
 * conclude `SELF` and filter it straight back out. A decompiled `.beam` is one [ElixirStabBody] holding
 * thousands of `def` siblings, so that is O(N) per resolve and O(N^2) per file: the cost #4123 removed from
 * `macroChildCalls` and from the implicit `import Kernel`, in a third place. It was caught in 244 of 300
 * thread dumps.
 *
 * The clauses were already being filtered out correctly; only the re-deciding was wasted. So the fix is
 * memoisation and [ProcessDeclarationsImpl.declaringChildren] is what these tests pin - a *visit*-counting
 * [com.intellij.psi.scope.PsiScopeProcessor] double (what
 * [org.elixir_lang.psi.scope.CallDefinitionClauseModuleSizeIndependenceTest] and
 * [org.elixir_lang.psi.scope.ImplicitKernelImportModuleSizeIndependenceTest] use) cannot see this
 * regression at all, because the expensive siblings are dropped before any processor is handed them.
 */
class DeclaringChildrenCacheTest : PlatformTestCase() {
    fun testTheSameScopeAnswersFromCacheRatherThanReclassifying() {
        val stabBody = moduleBodyOf(
            """
            defmodule Caller do
              import Helper

              def one, do: :ok
              def two, do: :ok
            end
            """.trimIndent()
        )

        val first = ProcessDeclarationsImpl.declaringChildren(stabBody)
        val second = ProcessDeclarationsImpl.declaringChildren(stabBody)

        assertSame(
            "a scope's classification must be computed once and reused - recomputing it per resolve is the " +
                "O(N^2) cost this exists to remove",
            first,
            second
        )
    }

    /** The `def`s are what make the walk expensive and are exactly what must not survive; the `import` is
     *  what makes it load-bearing - an `import`/`alias`/`require` is reachable *only* through this walk, so
     *  dropping one would make its names silently unresolvable. */
    fun testClausesAreExcludedAndStatementsThatDeclareThroughAWalkAreKept() {
        val stabBody = moduleBodyOf(
            """
            defmodule Caller do
              import Helper
              alias Helper.Nested
              @attr :value

              def one, do: :ok
              defp two, do: :ok
              defmacro three, do: :ok
              defguard four(a) when a > 0
            end
            """.trimIndent()
        )

        val texts = ProcessDeclarationsImpl.declaringChildren(stabBody).declaring.map { it.text }

        assertContainsElements(texts, "import Helper", "alias Helper.Nested", "@attr :value")
        assertEmpty(
            "a call-definition clause creates its own scope, so it must not be offered as a declaring " +
                "sibling - kept: $texts",
            texts.filter { it.startsWith("def") }
        )
    }

    fun testAnEditToTheScopeIsPickedUp() {
        val stabBody = moduleBodyOf(
            """
            defmodule Caller do
              import Helper

              def one, do: :ok
            end
            """.trimIndent()
        )

        val before = ProcessDeclarationsImpl.declaringChildren(stabBody).declaring.map { it.text }
        assertContainsElements(before, "import Helper")

        WriteCommandAction.runWriteCommandAction(project) {
            // `setText`, not `text =` - IntelliJ's own inspection suggests property syntax here, but
            // `Document.getText` has no matching setter for Kotlin, so the property form does not compile.
            myFixture.editor.document.setText(
                """
                defmodule Caller do
                  import Other

                  def one, do: :ok
                end
                """.trimIndent()
            )
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        val editedStabBody = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirStabBody::class.java).first()
        val after = ProcessDeclarationsImpl.declaringChildren(editedStabBody).declaring.map { it.text }

        assertContainsElements(after, "import Other")
        assertDoesntContain(after, "import Helper")
    }

    private fun moduleBodyOf(text: String): ElixirStabBody {
        myFixture.configureByText("caller.ex", text)

        return PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirStabBody::class.java).first()
    }
}
