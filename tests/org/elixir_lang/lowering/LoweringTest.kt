package org.elixir_lang.lowering

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.DummyBlockType
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.impl.source.tree.PsiErrorElementImpl
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.junit.logs.expectErrors
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirEndOfExpression
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirStabNoParenthesesSignature
import org.elixir_lang.psi.ElixirStabParenthesesSignature
import org.elixir_lang.psi.impl.ElixirMatchedQualifiedMultipleAliasesImpl
import org.elixir_lang.psi.impl.ElixirMultipleAliasesImpl
import org.elixir_lang.psi.impl.ElixirParentheticalStabImpl
import org.elixir_lang.psi.impl.ElixirStabBodyImpl
import org.elixir_lang.psi.impl.ElixirStabImpl

class LoweringTest : LoweringTestCase() {
    fun testAnErrorElementIsAnError() =
        assertEquals(Lowering.Bucket.ERROR, Lowering.classifier.classify(PsiErrorElementImpl::class.java))

    fun testADummyBlockIsAnError() =
        assertEquals(Lowering.Bucket.ERROR, Lowering.classifier.classify(DummyBlockType.DummyBlock::class.java))

    fun testAShapeNoRowNamesIsUnknownNotNoNode() =
        assertEquals(Lowering.Bucket.UNKNOWN, Lowering.classifier.classify(LeafPsiElement::class.java))

    fun testQualifiedMultipleAliasesAndTheirAliasListAreCalls() = assertEquals(
        listOf(Lowering.Bucket.CALL, Lowering.Bucket.CALL),
        listOf(ElixirMatchedQualifiedMultipleAliasesImpl::class.java, ElixirMultipleAliasesImpl::class.java)
            .map { Lowering.classifier.classify(it) }
    )

    fun testStabsTheirBodiesAndParenthesesAreBlocks() = assertEquals(
        listOf(Lowering.Bucket.BLOCK, Lowering.Bucket.BLOCK, Lowering.Bucket.BLOCK),
        listOf(ElixirStabImpl::class.java, ElixirStabBodyImpl::class.java, ElixirParentheticalStabImpl::class.java)
            .map { Lowering.classifier.classify(it) }
    )

    fun testAShapeNoRowNamesIsUnlowered() {
        val file = createPsiFile(getTestName(false), "1") as ElixirFile
        val leaf = PsiTreeUtil.getDeepestFirst(file)

        val errors = expectErrors(Lowering::class.java, Regex("has no lowering")) {
            val lowered = ReadAction.computeBlocking<ElixirAst, Throwable> {
                Lowering.of(file, ElixirLanguageLevel.FALLBACK).lower(leaf)
            }

            assertEquals(leaf.javaClass, ((lowered as ElixirAst.Placeholder).reason as ElixirAst.Placeholder.Reason.Unlowered).shape)
        }

        assertEquals(1, errors.size)
    }

    fun testALiteralLowers() = assertLowers("1", "1")

    fun testParenthesesLowerAsABlock() = assertLowers("(1)", "1")

    fun testAShapeItsParentReadsFailsOnItsOwn() =
        assertFailsOnItsOwn("\"#{1}\"", ElixirInterpolation::class.java)

    fun testASignatureFailsOnItsOwn() {
        assertFailsOnItsOwn("fn 1 -> 2 end", ElixirStabNoParenthesesSignature::class.java)
        assertFailsOnItsOwn("fn (1) -> 2 end", ElixirStabParenthesesSignature::class.java)
    }

    fun testAShapeWithNoNodeFailsOnItsOwn() = assertFailsOnItsOwn("1\n2", ElixirEndOfExpression::class.java)

    private fun <T : PsiElement> assertFailsOnItsOwn(code: String, shape: Class<T>) {
        val file = createPsiFile(getTestName(false), code) as ElixirFile
        val element = PsiTreeUtil.findChildOfType(file, shape)!!

        val errors = expectErrors(Lowering::class.java, Regex(".*${shape.simpleName}.*")) {
            val lowered = ReadAction.computeBlocking<ElixirAst, Throwable> {
                Lowering.of(file, ElixirLanguageLevel.FALLBACK).lower(element)
            }

            assertInstanceOf((lowered as ElixirAst.Placeholder).reason, ElixirAst.Placeholder.Reason.Unlowered::class.java)
        }

        assertEquals(1, errors.size)
    }
}
