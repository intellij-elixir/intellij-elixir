package org.elixir_lang.reference.callable

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import org.elixir_lang.PlatformTestCase

/**
 * A quote bound by destructuring, `{x, _} = {quote ... end, :other}`, is reached through the container and then paired
 * with the value at its own position, so the definitions it carries are injected at the `unquote` site.
 */
class UnquoteDestructuredBindingTest : PlatformTestCase() {
    fun testQuoteBoundThroughATupleResolves() = assertResolves("{x, _} = {$QUOTE, :other}")

    fun testQuoteBoundThroughAListResolves() = assertResolves("[x] = [$QUOTE]")

    fun testQuoteBoundThroughAKeywordMapResolves() = assertResolves("%{a: x} = %{a: $QUOTE}")

    /** `:a => value` and `a: value` name the same key, so a pattern may use either spelling. */
    fun testQuoteBoundThroughAnAssociationMapResolves() = assertResolves("%{:a => x} = %{a: $QUOTE}")

    /** Pairing is per level, so nesting needs no separate handling. */
    fun testQuoteBoundThroughANestedContainerResolves() = assertResolves("{[_, x], _} = {[:first, $QUOTE], :other}")

    /**
     * The discriminating case. Reaching the match out of a container is not enough: without pairing, every name in the
     * pattern claims the whole right-hand side, so this resolves too and every test above passes for the wrong reason.
     */
    fun testNameBoundBesideTheQuoteDoesNotResolve() = assertDoesNotResolve("{_, x} = {$QUOTE, :other}")

    /** The same, one level of indirection away: `x` is `:other` whichever way the tuple is reached. */
    fun testNameBoundBesideTheQuoteThroughAVariableDoesNotResolve() =
        assertDoesNotResolve("pair = {$QUOTE, :other}\n    {_, x} = pair")

    /** And the position that does hold the quote still finds it through the same indirection. */
    fun testQuoteBoundThroughAVariableResolves() = assertResolves("pair = {$QUOTE, :other}\n    {x, _} = pair")

    /** A rebound name reads as its latest binding, so the quote in that one is found. */
    fun testQuoteInTheLatestBindingResolves() =
        assertResolves("pair = {:first, :other}\n    pair = {$QUOTE, :other}\n    {x, _} = pair")

    /** And a binding the rebinding shadows is not followed, so its quote is not claimed by the later read. */
    fun testQuoteOnlyInAShadowedBindingDoesNotResolve() =
        assertDoesNotResolve("pair = {$QUOTE, :other}\n    pair = {:second, :other}\n    {x, _} = pair")

    /** Sides that cannot match bind nothing, rather than falling back to the value as a whole. */
    fun testTupleOfADifferentLengthDoesNotResolve() = assertDoesNotResolve("{x, _, _} = {$QUOTE, :other}")

    /** `[h | t]` matches any list at least as long as its heads, so a head pairs with the value at its own index. */
    fun testQuoteBoundThroughAConsPatternResolves() = assertResolves("[x | _rest] = [$QUOTE, :other]")

    /**
     * A known limit, recorded in [org.elixir_lang.psi.Destructure.valuesAt]: the tail of `[h | t]` binds nothing,
     * because no node stands for the sub-list it takes. Here that is also the right answer - `x` is `[:other]` - so
     * the case below is the one that says why the limit is kept.
     */
    fun testNameBoundToAConsTailDoesNotClaimTheHead() = assertDoesNotResolve("[_h | x] = [$QUOTE, :other]")

    /**
     * Why the tail is not answered element by element: `x` is `:last` here, and answering the tail's elements
     * separately would pair `[_p, x]` against one of them and resolve the quote that `_p` takes.
     */
    fun testConsTailDestructuredAgainDoesNotClaimTheWrongElement() =
        assertDoesNotResolve("[_h | t] = [:first, $QUOTE, :last]\n    [_p, x] = t")

    /** A keyword list is a list of pairs, so its pairs line up by position and then agree on the key. */
    fun testQuoteInAKeywordListPatternResolves() = assertResolves("[a: x] = [a: $QUOTE]")

    /** A cons whose tail is still being typed must not read as a fixed-length list, which a whole one does not. */
    fun testHalfTypedConsOnTheValueSideDoesNotResolve() = assertDoesNotResolve("[x] = [$QUOTE | ]")

    /** The same on the pattern side, against a one-element value so length alone does not answer it. */
    fun testHalfTypedConsOnThePatternSideDoesNotResolve() = assertDoesNotResolve("[x | ] = [$QUOTE]")

    /** The completed form of that, which does bind: `[x | rest] = [:only]` gives `rest == []`. */
    fun testCompleteConsAgainstAOneElementListResolves() = assertResolves("[x | _rest] = [$QUOTE]")

    fun testHalfTypedKeywordPairDoesNotResolve() = assertDoesNotResolve("[a: x] = [a:")

    fun testHalfTypedMatchDoesNotResolve() = assertDoesNotResolve("[a: x] =")

    /** Lining up is not enough - a pair whose key differs matches nothing. */
    fun testKeywordListPatternWithAnotherKeyDoesNotResolve() = assertDoesNotResolve("[b: x] = [a: $QUOTE]")

    /** More heads than the value has elements is a match that raises. */
    fun testConsPatternLongerThanTheValueDoesNotResolve() = assertDoesNotResolve("[_a, x | _rest] = [$QUOTE]")

    /** A trailing keyword list is one PSI child but one element per pair, and Elixir counts the pairs. */
    fun testQuoteBesideAKeywordTailResolves() = assertResolves("[x, _y, _z] = [$QUOTE, k: 1, j: 2]")

    /** A tuple counts the same keyword tail as one element, so `{q, k: 1, j: 2}` has two, not three. */
    fun testQuoteBesideAKeywordTailInATupleResolves() = assertResolves("{x, _opts} = {$QUOTE, k: 1, j: 2}")

    /** The other side of that: three positions against that two-element tuple is a match that raises. */
    fun testTupleCountingAKeywordTailAsSeparateElementsDoesNotResolve() =
        assertDoesNotResolve("{x, _y, _z} = {$QUOTE, k: 1, j: 2}")

    /** A list pattern against a tuple value raises, so the value must not be answered whole and spliced. */
    fun testContainersOfDifferentKindsDoNotResolve() = assertDoesNotResolve("[x] = {$QUOTE, :other}")

    /** A duplicate key takes its last value, so the later entry is the one that binds. */
    fun testDuplicateMapKeyTakesTheLastValueResolves() =
        assertResolves("%{a: x} = %{:a => :first, a: $QUOTE}")

    /** A key the value does not carry binds nothing, for the same reason. */
    fun testMapKeyMissingFromTheValueDoesNotResolve() = assertDoesNotResolve("%{b: x} = %{a: $QUOTE}")

    /** The binary `"a"` and the atom `:a` are different keys, so spelling one as the other must not pair them. */
    fun testMapKeyOfADifferentTypeDoesNotResolve() = assertDoesNotResolve("%{\"a\" => x} = %{a: $QUOTE}")

    /** A quoted keyword key is the same atom as the bare one - Elixir warns that the quotes are not required. */
    fun testQuotedKeywordKeyResolves() = assertResolves("%{\"a\": x} = %{a: $QUOTE}")

    /** The same on the atom side: `:"a"` is `:a`. */
    fun testQuotedAtomKeyResolves() = assertResolves("%{:\"a\" => x} = %{a: $QUOTE}")

    /**
     * An interpolated key names an atom only known once the code runs, so it pairs with nothing. Elixir does bind
     * here - `"#{:a}":` is the atom `a:` - so this records a deliberate under-approximation, not the language.
     */
    fun testInterpolatedKeyDoesNotResolve() = assertDoesNotResolve("%{a: x} = %{\"#{:a}\": $QUOTE}")

    /** Two keys neither side can read stay unequal, rather than pairing with each other for being equally unknown. */
    fun testUnreadableMapKeysDoNotResolve() = assertDoesNotResolve("""%{"#{:a}": x} = %{"#{:b}": $QUOTE}""")

    /** The same on the keyword list path, which pairs its keys by position rather than by lookup. */
    fun testUnreadableKeywordKeysDoNotResolve() = assertDoesNotResolve("""["#{:a}": x] = ["#{:b}": $QUOTE]""")

    /** `true =>` is the atom `true:` is. */
    fun testAtomKeywordAssociationKeyIsTheBareKey() = assertResolves("%{true: x} = %{true => $QUOTE}")

    /** An escape sequence in a key spells its atom: `"\x61":` is `a:`. */
    fun testEscapedKeywordKeyResolves() = assertResolves("""["\x61": x] = [a: $QUOTE]""")

    /** The same through a map's keys. */
    fun testEscapedMapKeyResolves() = assertResolves("""%{"\x61": x} = %{a: $QUOTE}""")

    /** And through an association's atom: `:"\x61"` is `:a`. */
    fun testEscapedAtomKeyResolves() = assertResolves("""%{:"\x61" => x} = %{a: $QUOTE}""")

    /** Two escaped keys that spell different atoms stay apart. */
    fun testEscapedKeysOfDifferentAtomsDoNotResolve() = assertDoesNotResolve("""["\x61": x] = ["\x62": $QUOTE]""")

    /**
     * From Elixir 1.14 a bare key is normalized as an identifier and a quoted one is not, so a decomposed `café:` is
     * the atom a precomposed `"café":` spells.
     */
    fun testDecomposedBareKeyIsThePrecomposedQuotedKey() =
        assertResolves("[$DECOMPOSED_CAFE: x] = [\"$PRECOMPOSED_CAFE\": $QUOTE]")

    /** The reverse spelling stays two atoms: the quoted key keeps its decomposed form. */
    fun testPrecomposedBareKeyIsNotTheDecomposedQuotedKey() =
        assertDoesNotResolve("[$PRECOMPOSED_CAFE: x] = [\"$DECOMPOSED_CAFE\": $QUOTE]")

    /** A map update names its own keys, so one it names pairs exactly as a construction's does. */
    fun testQuoteBoundThroughAMapUpdateResolves() =
        assertResolves("base = %{a: nil}\n    %{a: x} = %{base | a: $QUOTE}")

    /** A key the update does not name comes from `base`, which is unknown here, so it pairs with nothing. */
    fun testMapKeyInheritedByAnUpdateDoesNotResolve() =
        assertDoesNotResolve("base = %{a: nil, b: nil}\n    %{a: x} = %{base | b: $QUOTE}")

    /**
     * `unquote(x)` splices whatever `x` holds, and the compiler expands a list literal's elements, so a list of
     * fragments defines each of them at the splice site.
     */
    fun testQuoteBoundThroughAContainerOfFragmentsResolves() = assertResolves("{x, _} = {[$QUOTE], :other}")

    /** The tuple counterpart of the list above: a two-element tuple is a quoted literal and splices both elements. */
    fun testQuoteBoundThroughATupleOfFragmentsResolves() =
        assertResolves("{x, _} = {{$QUOTE, :other}, :ignored}")

    /**
     * `:a` where a call node's metadata belongs is not a valid quoted expression, so this shape defines nothing. A
     * fragment in the *name* position is fine - `Macro.validate({{:def, [], []}, [], []})` is `:ok` - and it is the
     * shape, not the arity, that decides: `{:__block__, [], [fragment]}` is three elements and does splice.
     */
    fun testQuoteBoundThroughAThreeTupleOfFragmentsDoesNotResolve() =
        assertDoesNotResolve("{x, _} = {{$QUOTE, :a, :b}, :other}")

    /** The undestructured form the issue compares against, so a regression there is not read as one here. */
    fun testQuoteBoundDirectlyResolves() = assertResolves("x = $QUOTE")

    /**
     * A value the pairing cannot take apart is followed whole, which is all that reaches a quote returned by a
     * call. Pairing narrows what a destructured name claims; it must not narrow it to nothing.
     */
    fun testQuoteBoundThroughAnOpaqueValueResolves() = assertResolves("[x] = fragments()", FRAGMENTS)

    private fun assertResolves(binding: String, definitions: String = "") =
        assertNotEmpty(resolveInjected(binding, definitions).toList())

    private fun assertDoesNotResolve(binding: String, definitions: String = "") =
        assertEmpty(resolveInjected(binding, definitions).toList())

    /**
     * Resolves `injected()` in a module that `use`s an injector whose `__using__` binds a quote with [binding] and
     * unquotes the name it bound. [definitions] are extra members of the injector module, for a binding whose value
     * is a call.
     */
    private fun resolveInjected(binding: String, definitions: String): Array<out ResolveResult> {
        myFixture.configureByText(
            "injector.ex",
            "defmodule Injector do\n  defmacro __using__(_opts) do\n    $binding\n    quote do\n      unquote(x)\n" +
                "    end\n  end\n$definitions\nend\n"
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

        private const val DECOMPOSED_CAFE = "cafe\u0301"
        private const val PRECOMPOSED_CAFE = "caf\u00e9"

        /** A helper returning the fragments to splice, so a binding's value can be a call rather than a container. */
        private const val FRAGMENTS = "  defp fragments do\n    [$QUOTE]\n  end"
    }
}
