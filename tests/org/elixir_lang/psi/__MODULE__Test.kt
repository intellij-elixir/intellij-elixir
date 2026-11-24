package org.elixir_lang.psi

import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.declaration.Use as DeclarationUse
import org.elixir_lang.expander.ExpanderSourceRows
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.__module__.Reference

/**
 * Tests for [org.elixir_lang.psi.__MODULE__.reference], whose `useCall` parameter selects which
 * `use` call's injected scope `__MODULE__` resolves through.
 */
@Suppress("ClassName") // named for the `__MODULE__` special form, matching `org.elixir_lang.psi.__MODULE__`
class __MODULE__Test : PlatformTestCase() {

    private fun configureModuleUsingWeb() {
        myFixture.configureByText(
            "page_controller.ex",
            """
            defmodule MyApp.PageController do
              use MyApp.Web

              def index(conn, _params) do
                __MODULE__.render(conn)
              end
            end
            """.trimIndent()
        )
    }

    private fun callWithText(text: String, root: PsiElement = myFixture.file): Call =
        PsiTreeUtil.findChildrenOfType(root, Call::class.java).single { it.text == text }

    /**
     * `platform.random.idempotence.check.rate` makes the platform re-run the provider on every cache
     * hit instead of once in a while, which is the recipe `IdempotenceChecker`'s own javadoc gives for
     * turning this from a race into something a test can assert on.
     */
    private fun assertResolvingTwiceIsIdempotent(secondUseCall: Call?, firstUseCall: Call) {
        Registry.get("platform.random.idempotence.check.rate").setValue(1, testRootDisposable)

        val __MODULE__Call = callWithText("__MODULE__")
        val (_, loggedErrors) = captureLoggedErrors {
            __MODULE__.reference(__MODULE__Call, firstUseCall)
            __MODULE__.reference(__MODULE__Call, secondUseCall)
        }

        assertEmpty(
            "resolving __MODULE__ twice must not report a non-idempotent computation",
            loggedErrors.filter { error ->
                listOfNotNull(error.message, error.title).any { it.contains("Non-idempotent computation") }
            }
        )
    }

    /**
     * `reference` caches under `KEY` on the `__MODULE__` call itself, and a `ParameterizedCachedValue`
     * holds one value per (data holder, key) - the parameter reaches the provider but never the
     * lookup.  So a reference computed for one `useCall` is handed back to every later caller that
     * asks for a different one, while the file is unchanged.
     */
    fun testReferenceIsNotSharedAcrossUseCalls() {
        configureModuleUsingWeb()

        val __MODULE__Call = callWithText("__MODULE__")
        val useCall = callWithText("use MyApp.Web")

        val forUseCall = __MODULE__.reference(__MODULE__Call, useCall) as Reference
        assertSame(useCall, forUseCall.useCall)

        val forNoUseCall = __MODULE__.reference(__MODULE__Call) as Reference
        assertNull(
            "__MODULE__.reference() must not hand back the reference built for `use MyApp.Web` to a " +
                "caller that passed no useCall",
            forNoUseCall.useCall
        )
    }

    /**
     * The same slot in the other direction: the no-`useCall` reference is the one
     * `Call.computeCallableReference` asks for, so it is usually cached first and then returned to
     * `Call.maybeModularNameToModulars`, which loses the `use` scope it passed in.
     */
    fun testReferenceForNoUseCallIsNotReusedForAUseCall() {
        configureModuleUsingWeb()

        val __MODULE__Call = callWithText("__MODULE__")
        val useCall = callWithText("use MyApp.Web")

        assertNull((__MODULE__.reference(__MODULE__Call) as Reference).useCall)

        assertSame(
            "__MODULE__.reference(useCall = <use MyApp.Web>) must not hand back the reference built " +
                "with no useCall",
            useCall,
            (__MODULE__.reference(__MODULE__Call, useCall) as Reference).useCall
        )
    }

    /**
     * The shared slot's user-visible face: the provider hands the platform `arrayOf(call, useCall)` or
     * `arrayOf(call)` depending on the parameter, so two computations of the one slot disagree on how
     * many dependencies it has and `IdempotenceChecker` reports the value as non-idempotent.
     */
    fun testResolvingWithAndWithoutAUseCallIsIdempotent() {
        configureModuleUsingWeb()

        assertResolvingTwiceIsIdempotent(
            firstUseCall = callWithText("use MyApp.Web"),
            secondUseCall = null
        )
    }

    /**
     * The shape the reports on the tracker carry, where both dependency arrays name both calls and
     * only `PSI_MOD_COUNT_OPTIMIZATION` differs.  `PsiCachedValue.normalizeDependencies` prepends that
     * sentinel only when every dependency is "very physical", so the same two-element array is
     * observed as three elements for a `use` call in the edited file and as two for the same call in
     * the non-physical copy code completion resolves against - which is the case
     * `PsiCachedValue.isVeryPhysical`'s own comment calls out.
     */
    fun testResolvingAgainstANonPhysicalCopyIsIdempotent() {
        configureModuleUsingWeb()

        val copy = myFixture.file.copy() as PsiFile
        assertFalse("a PSI file copy must be non-physical for this to be the reported case", copy.isPhysical)

        assertResolvingTwiceIsIdempotent(
            firstUseCall = callWithText("use MyApp.Web"),
            secondUseCall = callWithText("use MyApp.Web", copy)
        )
    }

    /** `use __MODULE__` in a `quote` reads its argument where the `use` is written, not through that `use` again. */
    fun testAUseOfModuleInAQuoteResolvesTheQuotesCalls() =
        assertEquals(listOf("OWN:L.f/0"), useOfModuleCandidates("m"))

    fun testAUseOfModuleInAUsingQuoteResolvesTheQuotesCalls() =
        assertEquals(
            listOf("OWN:L.f/0"),
            useOfModuleCandidates("__using__(_)", caller = "\n\ndefmodule U do\n  use L\nend")
        )

    /** The walk's candidates for `f()` after `use __MODULE__` in `defmacro [macroHead]`'s `quote`, then [caller]. */
    private fun useOfModuleCandidates(macroHead: String, caller: String = ""): List<String> =
        walkCandidatesAtCaret(
            """
            defmodule L do
              def f, do: 1

              defmacro $macroHead do
                quote do
                  use __MODULE__
                  def run, do: <caret>f()
                end
              end
            end
            """.trimIndent() + caller
        )

    private fun walkCandidatesAtCaret(text: String): List<String> {
        myFixture.configureByText("l.ex", text)

        return LegacyWalkSource.candidates(DeclarationUse.of(myFixture.enclosingCallAtCaret()!!)!!, false).map { found ->
            "${found.candidate.reach}:${found.head.module}.${found.head.name}/${ExpanderSourceRows.arity(found.head.arity)}"
        }
    }
}
