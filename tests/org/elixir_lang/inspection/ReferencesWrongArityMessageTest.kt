package org.elixir_lang.inspection

import org.elixir_lang.PlatformTestCase

/**
 * `References`'s wrong-arity message ("Only resolves to invalid results") on a call whose name
 * resolves but whose arity does not - it must name every declared arity as `name/arity`, the way the
 * compiler's own "Did you mean" does. Asserts message *content*, never exact wording, since a later
 * phrasing change should not redden this: [org.elixir_lang.inspection.References] had zero tests
 * before this, so this is the inspection's first regression coverage, not an extension of existing
 * coverage.
 */
class ReferencesWrongArityMessageTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
    }

    fun testWrongArityNamesTheDeclaredArity() {
        myFixture.configureByFiles("single_arity_mismatch.ex")
        val descriptions = errorDescriptions()

        assertTrue(
            "Wrong-arity diagnostic should name the declared arity 'snoc/2': $descriptions",
            descriptions.any { it.contains("snoc/2") }
        )
    }

    /** One `def` with a default argument declares an arity *interval*, not a single arity. */
    fun testWrongArityNamesEveryArityInADefaultArgumentInterval() {
        myFixture.configureByFiles("default_argument_interval.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'snoc/2': $message", message.contains("snoc/2"))
        assertTrue("Should also name 'snoc/3': $message", message.contains("snoc/3"))
    }

    fun testWrongArityNamesEveryDeclaredArityAcrossSeparateClauses() {
        myFixture.configureByFiles("multiple_clauses.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'snoc/2': $message", message.contains("snoc/2"))
        assertTrue("Should also name 'snoc/4': $message", message.contains("snoc/4"))
    }

    /**
     * `multiResolve` name-matches by `startsWith` (so a partially-typed call can still complete), so a
     * wrong-arity `snoc()` must not also list the unrelated `snoc_all/3`.
     */
    fun testWrongArityExcludesNamesThatOnlyShareAPrefix() {
        myFixture.configureByFiles("exact_name_match.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'snoc/2': $message", message.contains("snoc/2"))
        assertFalse("Should not name the unrelated 'snoc_all': $message", message.contains("snoc_all"))
    }

    /**
     * The exact-name filter above must normalize both sides before comparing: the call site here is
     * precomposed (`ć`, U+0107) and the declaration is decomposed (`c` + U+0301 COMBINING ACUTE ACCENT) -
     * the same pair [org.elixir_lang.inspection.ReferencesNoFalsePositiveTest] already proves *resolves*,
     * but resolving and being named by this filter are two different comparisons.
     */
    fun testWrongArityNamesADecomposedDeclarationFromAPrecomposedCall() {
        myFixture.configureByFiles("decomposed_wrong_arity.ex")
        val message = wrongArityDescription()
        val decomposedNameSlashArity = "sno" + "c" + "́" + "/2"

        assertTrue("Should name '$decomposedNameSlashArity': $message", message.contains(decomposedNameSlashArity))
    }

    /**
     * A special form's written parameters are not its arities: `defmacro alias(module, opts)` in
     * `Kernel.SpecialForms` is really `alias/1` and `alias/2`. The message must name the arities the
     * call was actually checked against.
     */
    fun testWrongArityNamesASpecialFormsRealArities() {
        myFixture.configureByFiles("special_forms_alias_wrong_arity.ex", "kernel_special_forms.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'alias/1': $message", message.contains("alias/1"))
        assertTrue("Should also name 'alias/2': $message", message.contains("alias/2"))
    }

    /**
     * `defdelegate foo(a)` declares only `foo/1` here, whatever other arities the target module defines,
     * so the message must not offer the target's `foo/3`.
     */
    fun testWrongArityThroughADelegationNamesOnlyTheDelegatedArity() {
        myFixture.configureByFiles("delegate_other_arity.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'foo/1': $message", message.contains("foo/1"))
        assertFalse("Should not name the target's undelegated 'foo/3': $message", message.contains("foo/3"))
    }

    /** A target's default argument does not widen what `defdelegate foo(x)` forwards. */
    fun testWrongArityThroughADelegationIgnoresTheTargetsDefaultArguments() {
        myFixture.configureByFiles("delegate_default_arguments.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'foo/1': $message", message.contains("foo/1"))
        assertFalse("Should not name the undelegated 'foo/2': $message", message.contains("foo/2"))
    }

    /** `unquote_splicing` leaves the arity open, so any arity from the minimum up is valid. */
    fun testWrongArityNamesAnOpenIntervalAsOpen() {
        myFixture.configureByFiles("open_interval.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'foo/1+': $message", message.contains("foo/1+"))
    }

    /** Two clauses spelled differently but the same identifier to the compiler are one function. */
    fun testWrongArityNamesTwoSpellingsOfOneFunctionOnce() {
        myFixture.configureByFiles("two_spellings.ex")
        val message = wrongArityDescription()

        assertEquals("Should name the arity once: $message", 1, Regex("/2").findAll(message).count())
    }

    /** A name nothing declares has no arities to name, so this message must stay untouched. */
    fun testUndeclaredNameMessageStaysGeneric() {
        myFixture.configureByFiles("undeclared_name.ex")
        val descriptions = errorDescriptions()

        assertTrue(
            "An undeclared name should still report the generic message, unchanged: $descriptions",
            descriptions.any { it == "Does not resolve to anything" }
        )
    }

    private fun errorDescriptions(): List<String> = myFixture.doHighlighting().mapNotNull { it.description }

    private fun wrongArityDescription(): String {
        val descriptions = errorDescriptions()
        return descriptions.firstOrNull { it.startsWith("Only resolves to invalid results") }
            ?: throw AssertionError("No 'Only resolves to invalid results' diagnostic found among: $descriptions")
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/inspection/references_wrong_arity_message"
}
