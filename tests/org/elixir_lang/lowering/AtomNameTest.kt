package org.elixir_lang.lowering

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.psi.ElixirAdditionInfixOperator
import org.elixir_lang.psi.ElixirAtIdentifier
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirAtomKeyword
import org.elixir_lang.psi.ElixirIdentifier
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.ElixirRelativeIdentifier
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.psi.Operator
import java.lang.reflect.Modifier

/** [AtomName] gives each name the atom its full lowering builds, without lowering. */
class AtomNameTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testPlainAtom() = assertLoweringsName(":foo", ElixirAtom::class.java, "foo")
    fun testPlainAtomWithQuestionMark() = assertLoweringsName(":foo?", ElixirAtom::class.java, "foo?")
    fun testPlainAtomWithAt() = assertLoweringsName(":foo@bar", ElixirAtom::class.java, "foo@bar")
    fun testAliasAtom() = assertLoweringsName(":Foo", ElixirAtom::class.java, "Foo")
    fun testOperatorAtom() = assertLoweringsName(":+", ElixirAtom::class.java, "+")
    fun testBitStringOperatorAtom() = assertLoweringsName(":<<>>", ElixirAtom::class.java, "<<>>")
    fun testQuotedAtom() = assertLoweringsName(":\"foo\"", ElixirAtom::class.java, "foo")
    fun testEmptyQuotedAtom() = assertLoweringsName(":\"\"", ElixirAtom::class.java, "")
    fun testEscapedAtom() = assertLoweringsName(":\"fo\\x6f\"", ElixirAtom::class.java, "foo")
    fun testEscapedByteAtom() = assertLoweringsName(":\"caf\\xC3\\xA9\"", ElixirAtom::class.java, "café")
    fun testNonUtf8ByteAtom() = assertLoweringsName(":\"\\xFF\"", ElixirAtom::class.java, null)
    fun testNonUtf8ByteAmongTextAtom() = assertLoweringsName(":\"a\\xFFb\"", ElixirAtom::class.java, null)
    fun testTruncatedUtf8SequenceAtom() = assertLoweringsName(":\"\\xC3\"", ElixirAtom::class.java, null)
    fun testNonUtf8ByteKeywordKey() = assertLoweringsName("[\"\\xFF\": 1]", ElixirKeywordKey::class.java, null)
    fun testSurrogateEscapeAtom() = assertLoweringsName(":\"\\u{D800}\"", ElixirAtom::class.java, null)
    fun testInterpolatedAtom() = assertLoweringsName(":\"a#{x}\"", ElixirAtom::class.java, null)
    fun testNonAsciiAtom() = assertLoweringsName(":café", ElixirAtom::class.java, "café")
    fun testMicroSignAtom() = assertLoweringsName(":duration_µs", ElixirAtom::class.java, "duration_μs")
    fun testOverlongAtom() = assertLoweringsName(":" + "a".repeat(256), ElixirAtom::class.java, null)
    fun testOverlongQuotedAtom() = assertLoweringsName(":\"" + "a".repeat(256) + "\"", ElixirAtom::class.java, null)
    fun testPlainKeywordKey() = assertLoweringsName("[foo: 1]", ElixirKeywordKey::class.java, "foo")
    fun testQuotedKeywordKey() = assertLoweringsName("[\"foo\": 1]", ElixirKeywordKey::class.java, "foo")
    fun testEscapedKeywordKey() = assertLoweringsName("[\"fo\\x6f\": 1]", ElixirKeywordKey::class.java, "foo")
    fun testInterpolatedKeywordKey() = assertLoweringsName("[\"a#{x}\": 1]", ElixirKeywordKey::class.java, null)
    fun testPlainIdentifier() = assertLoweringsName("foo(1)", ElixirIdentifier::class.java, "foo")
    fun testNonAsciiIdentifier() = assertLoweringsName("café(1)", ElixirIdentifier::class.java, "café")
    fun testPlainRemoteCallName() = assertLoweringsName("M.foo(1)", ElixirRelativeIdentifier::class.java, "foo")
    fun testOperatorRemoteCallName() = assertLoweringsName("M.+(1, 2)", ElixirRelativeIdentifier::class.java, "+")
    fun testQuotedRemoteCallName() = assertLoweringsName("M.\"foo\"(1)", ElixirRelativeIdentifier::class.java, "foo")
    fun testEscapedRemoteCallName() = assertLoweringsName("M.\"fo\\x6f\"(1)", ElixirRelativeIdentifier::class.java, "foo")
    fun testInterpolatedRemoteCallName() = assertLoweringsName("M.\"a#{x}\"(1)", ElixirRelativeIdentifier::class.java, null)
    fun testEscapedRemoteCallNameBefore1_18() =
        assertLoweringsName("M.\"a\\x62\"(1)", ElixirRelativeIdentifier::class.java, "a\\x62", elixir("1.17.0"))

    fun testEscapedRemoteCallNameFrom1_18() =
        assertLoweringsName("M.\"a\\x62\"(1)", ElixirRelativeIdentifier::class.java, "ab", elixir("1.18.0"))

    fun testOperator() = assertLoweringsName("a + b", ElixirAdditionInfixOperator::class.java, "+")
    fun testOverlongKeywordKey() = assertLoweringsName("[" + "a".repeat(256) + ": 1]", ElixirKeywordKey::class.java, null)
    fun testOverlongQuotedKeywordKey() =
        assertLoweringsName("[\"" + "a".repeat(256) + "\": 1]", ElixirKeywordKey::class.java, null)

    fun testAtomKeywordRemoteCallName() = assertLoweringsName("M.true(1)", ElixirRelativeIdentifier::class.java, "true")
    fun testAtomKeyword() = assertLoweringsName("true", ElixirAtomKeyword::class.java, "true")

    /*
     * Elixir 1.20.4's `Code.string_to_quoted` accepts a 255-character variable, call name and attribute name, and
     * rejects 256 with "atom length must be less than system limit: " (`elixir_tokenizer.erl`).
     */
    fun testLongestVariable() = assertLoweringsName("a".repeat(255) + " = 1", ElixirIdentifier::class.java, "a".repeat(255))
    fun testOverlongVariable() = assertLoweringsName("a".repeat(256) + " = 1", ElixirIdentifier::class.java, null)
    fun testOverlongIdentifier() = assertLoweringsName("a".repeat(256) + "(1)", ElixirIdentifier::class.java, null)

    /** An attribute name lowers to no atom of its own, so only its name is pinned. */
    fun testAttributeName() {
        assertName("@foo 1", ElixirAtIdentifier::class.java, "foo")
    }

    fun testLongestAttributeName() {
        assertName("@" + "a".repeat(255) + " 1", ElixirAtIdentifier::class.java, "a".repeat(255))
    }

    fun testOverlongAttributeName() {
        assertName("@" + "a".repeat(256) + " 1", ElixirAtIdentifier::class.java, null)
    }

    /** A new operator rule fails here until [AtomName] names it. */
    fun testEveryOperatorElementTypeIsNamed() {
        val operatorTypes = ElixirTypes::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && IElementType::class.java.isAssignableFrom(it.type) }
            .map { it.get(null) as IElementType }
            .filter { type ->
                try {
                    ElixirTypes.Factory.createElement(CompositeElement(type)) is Operator
                } catch (_: AssertionError) {
                    false
                }
            }

        assertFalse("no operator element type found", operatorTypes.isEmpty())
        assertEquals(
            "operator element types AtomName does not name",
            emptyList<IElementType>(),
            operatorTypes.filterNot { AtomName.NAMED.contains(it) }
        )
    }

    /** `%x{}` names its struct with a variable, which is a name like any other. */
    fun testStructVariableName() {
        myFixture.configureByText("atom_name.ex", "%x{}")
        val element = PsiTreeUtil.findChildOfType(myFixture.file, ElixirVariable::class.java)

        assertNotNull("no ElixirVariable in `%x{}`", element)
        assertEquals("AtomName of `%x{}`", "x", AtomName.of(element!!))
    }

    private fun assertLoweringsName(
        text: String,
        elementClass: Class<out PsiElement>,
        expected: String?,
        languageLevel: ElixirLanguageLevel? = null
    ) {
        val element = assertName(text, elementClass, expected, languageLevel)

        assertEquals("the full lowering of `$text`", expected, (ElementLowering.lower(element) as? ElixirAst.Literal.Atom)?.name)
    }

    private fun assertName(
        text: String,
        elementClass: Class<out PsiElement>,
        expected: String?,
        languageLevel: ElixirLanguageLevel? = null
    ): PsiElement {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        myFixture.configureByText("atom_name.ex", text)
        val element = PsiTreeUtil.findChildOfType(myFixture.file, elementClass)
        assertNotNull("no ${elementClass.simpleName} in `$text`", element)

        LoweringCounters.reset()
        LoweringCounters.counting = true
        val name = try {
            AtomName.of(element!!)
        } finally {
            LoweringCounters.counting = false
        }

        assertEquals("AtomName of `$text`", expected, name)
        assertEquals("lowering requests for `$text`", 0L, LoweringCounters.requests.sum())

        return element
    }
}
