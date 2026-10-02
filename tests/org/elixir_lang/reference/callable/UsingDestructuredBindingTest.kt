package org.elixir_lang.reference.callable

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import org.elixir_lang.PlatformTestCase

/**
 * The two places `Using` reads a `__using__` body's value and used to stop at a container: a fragment in the list a
 * `__using__` returns, and the value of a match that is the body's last statement.
 */
class UsingDestructuredBindingTest : PlatformTestCase() {
    /** A fragment resolving to a name bound by destructuring, rather than by a bare `name = value`. */
    fun testFragmentBoundByDestructuringResolves() = assertResolves(
        "{imports, _} = {$QUOTE, :other}\n\n    [imports]"
    )

    /** The list of fragments a `__using__` already returns, so the test above is read as being about the binding. */
    fun testFragmentBoundDirectlyResolves() = assertResolves("imports = $QUOTE\n\n    [imports]")

    /** The body's last statement is a match whose value is the list of fragments, so the list has to be entered. */
    fun testLastStatementMatchingAListResolves() = assertResolves("[x] = [$QUOTE]")

    /**
     * A fragment reached through the list a match binds resolves the same way as one reached through the list the body
     * ends with - the parity the two branches claim.
     */
    fun testFragmentBoundInsideAMatchedListResolves() =
        assertResolves("imports = $QUOTE\n\n    fragments = [imports]")

    /**
     * A two-element tuple is a quoted literal, so the compiler expands both elements and splices what they define,
     * exactly as it does for a list. `{_x, _} = {quote ... end, :other}` really does define `injected/0` in the caller.
     */
    fun testLastStatementMatchingATwoTupleResolves() = assertResolves("{_x, _} = {$QUOTE, :other}")

    /**
     * Three elements is a call node, `{name, meta, args}`, and `:other` where the metadata belongs is not a valid
     * quoted expression, so the caller gets a compile error rather than a definition.
     */
    fun testLastStatementMatchingAThreeTupleDoesNotResolve() =
        assertDoesNotResolve("{_x, _, _} = {$QUOTE, :other, :another}")

    /**
     * Rebinding a name to a list holding itself is ordinary Elixir, and the returned `[[quote ... end]]` splices. This
     * does not exercise the visited guard - the inner read resolves to the *earlier* binding, so the container is
     * never re-entered - and is kept as a guard against that resolver rule changing, not as evidence for the walk.
     */
    fun testAListBoundToItselfTerminates() =
        assertResolves("imports = $QUOTE\n\n    imports = [imports]\n\n    [imports]")

    /**
     * A nested container is an `accessExpression`, which `unmatchedExpressionList` omits, so reading the returned list
     * any other way than a list reached as a value breaks the parity
     * [testFragmentBoundInsideAMatchedListResolves] claims.
     */
    fun testNestedListOfFragmentsResolves() = assertResolves("[[$QUOTE]]")

    /**
     * The same tuple returned bare rather than through a match. Without a branch of its own it falls to the dispatch's
     * `else`, which - the statements being in reverse - reads an earlier statement as the return value.
     */
    fun testLastStatementBareTwoTupleResolves() = assertResolves("{$QUOTE, :other}")

    /** A quote is opaque here, so it is answered whole to any pattern, including ones Elixir would raise on. */
    fun testATuplePatternAgainstAQuoteResolves() = assertResolves("{_name, _meta, _args} = $QUOTE")

    /** The pattern gained an element the value has not, a state a body is genuinely left in mid-edit. */
    fun testPatternWithMoreElementsThanTheListDoesNotResolve() =
        assertDoesNotResolve("[imports, _helpers] = [$QUOTE]")

    /** The value changed from a map to a list, the pattern not yet updated. */
    fun testMapPatternAgainstAListValueDoesNotResolve() =
        assertDoesNotResolve("%{imports: imports} = [$QUOTE]")

    /** A trailing keyword pair is matched by key, so an agreeing one leaves the list spliceable. */
    fun testTrailingKeywordPairWithTheSameKeyResolves() =
        assertResolves("[imports, a: _x] = [$QUOTE, a: 1]")

    /** And a differing key raises, so the list is not spliced. */
    fun testTrailingKeywordPairWithAnotherKeyDoesNotResolve() =
        assertDoesNotResolve("[imports, a: _x] = [$QUOTE, b: 1]")

    /** A keyword pair is a two-element tuple, so either side may be spelled either way and the match still holds. */
    fun testKeywordPairSpelledAsATupleResolves() =
        assertResolves("[imports, {:mode, _m}] = [$QUOTE, mode: :strict]")

    /** And the mirror spelling. */
    fun testTupleSpelledAsAKeywordPairResolves() =
        assertResolves("[imports, mode: _m] = [$QUOTE, {:mode, :strict}]")

    /** An escape sequence in a key spells its atom: `"\x61":` is `a:`. */
    fun testEscapedKeyResolves() = assertResolves("""[imports, a: _x] = [$QUOTE, "\x61": 1]""")

    /** So an escaped key that spells another atom raises, and the list is not spliced. */
    fun testEscapedKeyOfAnotherAtomDoesNotResolve() = assertDoesNotResolve("""[imports, b: _x] = [$QUOTE, "\x61": 1]""")

    fun testInterpolatedKeyResolves() = assertResolves("""[imports, a: _x] = [$QUOTE, "#{:a}": 1]""")

    /**
     * A refusal stops the one match, not the walk, so an alias injected by an earlier `use` still resolves. Only an
     * alias catches this: `CallDefinitionClause` drops the walk's answer, so no function resolve can see it.
     */
    fun testARefusalDoesNotStopTheWalk() {
        myFixture.configureByText(
            "injectors.ex",
            "defmodule Some.Deep.Thing do\n  def hello, do: :ok\nend\n\n" +
                "defmodule Aliaser do\n  defmacro __using__(_opts) do\n    quote do\n" +
                "      alias Some.Deep.Thing\n    end\n  end\nend\n\n" +
                "defmodule Refuser do\n  defmacro __using__(_opts) do\n    [_a, _b] = [$QUOTE]\n  end\nend\n"
        )
        // the modular walk runs backwards, so the refusing `use` has to sit nearer the caret than the aliasing one
        myFixture.configureByText(
            "user.ex",
            "defmodule User do\n  use Aliaser\n  use Refuser\n  def call, do: <caret>Thing\nend\n"
        )

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)!! as PsiPolyVariantReference
        val (resolveResults, errors) = captureLoggedErrors { reference.multiResolve(false) }

        assertEmpty(errors)
        assertNotEmpty(resolveResults.toList())
    }

    private fun assertResolves(body: String) = assertNotEmpty(resolveInjected(body).toList())

    private fun assertDoesNotResolve(body: String) = assertEmpty(resolveInjected(body).toList())

    /** Resolves `injected()` in a module that `use`s an injector whose `__using__` has [body]. */
    private fun resolveInjected(body: String): Array<out ResolveResult> {
        myFixture.configureByText(
            "injector.ex",
            "defmodule Injector do\n  defmacro __using__(_opts) do\n    $body\n  end\nend\n"
        )
        myFixture.configureByText(
            "user.ex",
            "defmodule User do\n  use Injector\n  def call, do: <caret>injected()\nend\n"
        )
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)!! as PsiPolyVariantReference

        val (resolveResults, errors) = captureLoggedErrors { reference.multiResolve(false) }

        assertEmpty(errors)

        return resolveResults
    }

    companion object {
        private const val QUOTE = "quote do\n      def injected(), do: :ok\n    end"
    }
}
