package org.elixir_lang.psi.walk

import com.intellij.psi.DummyBlockType
import com.intellij.psi.impl.source.tree.PsiErrorElementImpl
import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.psi.Destructure
import org.elixir_lang.psi.ElixirAnonymousFunction
import org.elixir_lang.psi.ElixirBlockItem
import org.elixir_lang.psi.ElixirBlockList
import org.elixir_lang.psi.ElixirDoBlock
import org.elixir_lang.psi.ElixirEex
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirKeywordPair
import org.elixir_lang.psi.ElixirMapUpdateArguments
import org.elixir_lang.psi.ElixirMatchedParenthesesArguments
import org.elixir_lang.psi.ElixirNoParenthesesKeywordPair
import org.elixir_lang.psi.ElixirNoParenthesesManyStrictNoParenthesesExpression
import org.elixir_lang.psi.ElixirNoParenthesesStrict
import org.elixir_lang.psi.ElixirMatchedExpression
import org.elixir_lang.psi.ElixirUnmatchedExpression
import org.elixir_lang.psi.ElixirUnmatchedGreaterThanOrEqualToOnePointSixCaptureNonNumericOperation
import org.elixir_lang.psi.ElixirUnmatchedLessThanOnePointSixCaptureNonNumericOperation
import org.elixir_lang.psi.impl.ElixirTupleImpl
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.annotator.ParameterWalk
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.UnquotedVariableWalk
import org.elixir_lang.psi.scope.TypeAscent
import org.elixir_lang.psi.scope.TypeDescent
import org.elixir_lang.psi.scope.VariableDescent
import org.elixir_lang.reference.VariableUseScopeWalk
import org.elixir_lang.reference.VariableWalk

/** Holds each walk's [Classifier] against the grammar, so a new rule fails here until it is given a bucket. */
class ShapeCoverageTest : UnitTestCase() {
    fun testTheVisitorEnumeratesTheGeneratedSurface() {
        val shapes = GrammarShapes.CONCRETE

        assertTrue(ElixirTupleImpl::class.java in shapes)
        /* The visitor also has an overload per hand-written marker interface and for the platform's. Pinning them by
           name means a generated interface the prefix no longer matches shows up here rather than vanishing.

           SigilHeredocLiteral left when the interpolating sigil heredoc gained Interpolated: dispatch goes to the
           alphabetically first interface, so the overload only ever fired for half of them. SigilLine likewise. */
        assertEquals(
            listOf(
                "Arguments", "AssociationOperation", "Atomable", "Body", "Digits", "EscapeSequence",
                "EscapedHexadecimalDigits", "HeredocLineable", "HeredocLiteral", "Interpolated", "Literal",
                "MaybeModuleName", "Named", "NamedElement", "NavigatablePsiElement", "Operator", "PsiElement",
                "Quotable", "QuotableArguments", "QuotableKeywordList", "QuotableKeywordPair",
                "Unquoted", "WholeNumber"
            ),
            GrammarShapes.SKIPPED.map { it.simpleName }
        )
        /* The two `extends` bases have abstract implementations, and the two version-gated capture rules leave an
           interface behind with no implementation, no element type and no rule; nothing else is left out. */
        assertEquals(
            listOf(
                ElixirMatchedExpression::class.java,
                ElixirUnmatchedExpression::class.java,
                ElixirUnmatchedGreaterThanOrEqualToOnePointSixCaptureNonNumericOperation::class.java,
                ElixirUnmatchedLessThanOnePointSixCaptureNonNumericOperation::class.java
            ),
            GrammarShapes.DROPPED
        )
    }

    fun testVariableWalkNamesEveryShape() = assertCovers(VariableWalk.classifier)

    fun testVariableUseScopeWalkNamesEveryShape() = assertCovers(VariableUseScopeWalk.classifier)

    fun testParameterWalkNamesEveryShape() = assertCovers(ParameterWalk.classifier)

    fun testUnquotedVariableWalkNamesEveryShape() = assertCovers(UnquotedVariableWalk.classifier)

    fun testVariableDescentNamesEveryShape() = assertCovers(VariableDescent.classifier)

    fun testDestructureNamesEveryShape() = assertCovers(Destructure.classifier)

    fun testTypeDescentNamesEveryShape() = assertCovers(
        TypeDescent.classifier,
        // the decompiled module, type and function the type resolver reads beside the generated shapes
        listOf(
            org.elixir_lang.beam.psi.impl.ModuleImpl::class.java,
            org.elixir_lang.beam.psi.impl.TypeDefinitionImpl::class.java,
            org.elixir_lang.beam.psi.impl.CallDefinitionImpl::class.java
        )
    )

    fun testTypeAscentNamesEveryShape() = assertCovers(TypeAscent.classifier)

    // an error element is met wherever the parser recovered, and a dummy block wraps what a `recoverWhile` skipped
    @Suppress("UnstableApiUsage")
    fun testLoweringNamesEveryShape() = assertCovers(
        Lowering.classifier,
        listOf(PsiErrorElementImpl::class.java, DummyBlockType.DummyBlock::class.java)
    )

    /** What the type descent reads through, the type ascent must climb, or a type variable inside loses its spec. */
    fun testTypeAscentClimbsWhatTheTypeDescentReads() {
        val exceptions = listOf<Class<*>>()
        val shapes = GrammarShapes.CONCRETE.filter { TypeDescent.classifier.classify(it) in typeReadThrough }
        val (excepted, checked) = shapes.partition { shape -> exceptions.any { it.isAssignableFrom(shape) } }

        val disagreeing = checked.filter { TypeAscent.classifier.classify(it) != TypeAscent.Bucket.PARENT }
            .map { GrammarShapes.name(it) }
        assertTrue(
            "the type descent reads through these but the type ascent stops:\n  ${disagreeing.joinToString("\n  ")}",
            disagreeing.isEmpty()
        )

        val stale = excepted.filter { TypeAscent.classifier.classify(it) == TypeAscent.Bucket.PARENT }
            .map { GrammarShapes.name(it) }
        assertTrue("no longer exceptions:\n  ${stale.joinToString("\n  ")}", stale.isEmpty())
    }

    /** Each arm casts to its bucket's type, so a shape filed in the wrong bucket must fail here, not at resolve. */
    fun testEveryVariableWalkBucketReceivesWhatItsArmCastsTo() =
        assertBucketsReceiveWhatTheirArmsCastTo(VariableWalk.classifier) { it.reads }

    fun testEveryVariableUseScopeWalkBucketReceivesWhatItsArmCastsTo() =
        assertBucketsReceiveWhatTheirArmsCastTo(VariableUseScopeWalk.classifier) { it.reads }

    fun testEveryParameterWalkBucketReceivesWhatItsArmCastsTo() =
        assertBucketsReceiveWhatTheirArmsCastTo(ParameterWalk.classifier) { it.reads }

    fun testEveryDescentBucketReceivesWhatItsArmCastsTo() =
        assertBucketsReceiveWhatTheirArmsCastTo(VariableDescent.classifier) { it.reads }

    fun testEveryTypeDescentBucketReceivesWhatItsArmCastsTo() =
        assertBucketsReceiveWhatTheirArmsCastTo(TypeDescent.classifier) { it.reads }

    private fun <B : Enum<B>> assertBucketsReceiveWhatTheirArmsCastTo(
        classifier: Classifier<B>,
        reads: (B) -> Class<*>
    ) {
        val miscast = GrammarShapes.CONCRETE
            .filter { !reads(classifier.classify(it)).isAssignableFrom(it) }
            .map { "${GrammarShapes.name(it)} in ${classifier.classify(it)}" }

        assertTrue(
            "these shapes are not what their bucket's arm casts to:\n  ${miscast.joinToString("\n  ")}",
            miscast.isEmpty()
        )
    }

    /** A declaration the resolver finds inside a shape is visible outside it, so a use inside must look outside too. */
    fun testIsVariableLooksThroughWhatTheDescentEnters() {
        val disagreeing = GrammarShapes.CONCRETE.filter {
            VariableDescent.classifier.classify(it) !in passedOver &&
                VariableWalk.classifier.classify(it) !in setOf(
                    VariableWalk.Bucket.TRANSPARENT, VariableWalk.Bucket.DECLARES, VariableWalk.Bucket.CALL
                )
        }.map { GrammarShapes.name(it) }

        assertTrue(
            "the descent enters these but isVariable stops at them:\n  ${disagreeing.joinToString("\n  ")}",
            disagreeing.isEmpty()
        )
    }

    /**
     * A shape `isVariable` looks through should be one the resolver descends into, or the variable resolves and
     * nothing finds it.
     */
    fun testTheDescentEntersWhatIsVariableLooksThrough() {
        val exceptions = listOf<Class<*>>(
            // a declaration inside a function body or a `do` block is not visible outside; a use inside looks outside
            ElixirAnonymousFunction::class.java,
            ElixirBlockItem::class.java,
            ElixirBlockList::class.java,
            ElixirDoBlock::class.java,
            // a template's tags are walked by its own declarations pass
            ElixirEex::class.java,
            // read through their keyword list, map arguments or call rather than met directly
            ElixirKeywordPair::class.java,
            ElixirNoParenthesesKeywordPair::class.java,
            ElixirMapUpdateArguments::class.java,
            ElixirMatchedParenthesesArguments::class.java,
            // syntax errors met while typing; `isVariable` searches above them, the resolver does not enter them
            ElixirNoParenthesesManyStrictNoParenthesesExpression::class.java,
            ElixirNoParenthesesStrict::class.java
        )
        val shapes = GrammarShapes.CONCRETE.filter {
            VariableWalk.classifier.classify(it) == VariableWalk.Bucket.TRANSPARENT
        }
        val (excepted, checked) = shapes.partition { shape -> exceptions.any { it.isAssignableFrom(shape) } }

        val disagreeing = checked.filter { VariableDescent.classifier.classify(it) in passedOver }
            .map { GrammarShapes.name(it) }
        assertTrue(
            "isVariable looks through these but the descent passes over them:\n  ${disagreeing.joinToString("\n  ")}",
            disagreeing.isEmpty()
        )

        val stale = excepted.filter { VariableDescent.classifier.classify(it) !in passedOver }
            .map { GrammarShapes.name(it) }
        assertTrue("no longer exceptions:\n  ${stale.joinToString("\n  ")}", stale.isEmpty())
    }

    /** A shape `isVariable` looks through must carry the use scope walk too, or Find Usages loses the variable. */
    fun testUseScopeWalksThroughWhatIsVariableLooksThrough() = assertAgree(
        VariableWalk.Bucket.TRANSPARENT,
        setOf(VariableUseScopeWalk.Bucket.PARENT),
        listOf(
            // an element `isVariable` starts from, never an ancestor
            ElixirVariable::class.java
        )
    )

    /** A shape that makes an identifier a variable must give it a scope. */
    fun testUseScopeScopesWhatIsVariableDeclares() = assertAgree(
        VariableWalk.Bucket.DECLARES,
        VariableUseScopeWalk.Bucket.entries.toSet() -
            setOf(VariableUseScopeWalk.Bucket.EMPTY, VariableUseScopeWalk.Bucket.LEAF),
        emptyList()
    )

    /** An unquote container left unfollowed is only open if a variable can be declared through it. */
    fun testUnfollowedUnquoteContainersCanDeclareAVariable() {
        val closed = GrammarShapes.CONCRETE.filter {
            UnquotedVariableWalk.classifier.classify(it) == UnquotedVariableWalk.Bucket.UNFOLLOWED &&
                VariableWalk.classifier.classify(it) !in lookedThrough
        }.map { GrammarShapes.name(it) }

        assertTrue("stops, not unfollowed:\n  ${closed.joinToString("\n  ")}", closed.isEmpty())
    }

    private fun assertAgree(
        variable: VariableWalk.Bucket,
        useScope: Set<VariableUseScopeWalk.Bucket>,
        exceptions: List<Class<*>>
    ) {
        val shapes = GrammarShapes.CONCRETE.filter { VariableWalk.classifier.classify(it) == variable }
        val (excepted, checked) = shapes.partition { shape -> exceptions.any { it.isAssignableFrom(shape) } }

        val disagreeing = checked.filter { VariableUseScopeWalk.classifier.classify(it) !in useScope }
            .map { GrammarShapes.name(it) }
        val expected = useScope.joinToString("/")
        assertTrue(
            "$variable in isVariable but not $expected in variableUseScope:\n  ${disagreeing.joinToString("\n  ")}",
            disagreeing.isEmpty()
        )

        val stale = excepted.filter { VariableUseScopeWalk.classifier.classify(it) in useScope }
            .map { GrammarShapes.name(it) }
        assertTrue("no longer exceptions:\n  ${stale.joinToString("\n  ")}", stale.isEmpty())
    }

    private val lookedThrough = setOf(VariableWalk.Bucket.TRANSPARENT, VariableWalk.Bucket.DECLARES)

    private val passedOver =
        setOf(VariableDescent.Bucket.STOP, VariableDescent.Bucket.LEAF, VariableDescent.Bucket.FILE)

    private val typeReadThrough = setOf(
        TypeDescent.Bucket.CHILDREN,
        TypeDescent.Bucket.STAB_PARENTHESES_SIGNATURE,
        TypeDescent.Bucket.STAB_NO_PARENTHESES_SIGNATURE
    )

    private fun <B : Enum<B>> assertCovers(classifier: Classifier<B>, alsoMet: List<Class<*>> = emptyList()) {
        val shapes = buildList {
            addAll(GrammarShapes.CONCRETE)
            add(ElixirFile::class.java)
            addAll(alsoMet)
        }

        // first assignable wins, so the winner must be narrower than every other entry the shape matches, or the
        // order between two unrelated entries is what decided
        val byOrder = shapes.flatMap { shape ->
            val winner = classifier.winner(shape) ?: return@flatMap emptyList()
            classifier.entries
                .filter { it.shape.isAssignableFrom(shape) && !it.shape.isAssignableFrom(winner.shape) }
                .map { "${GrammarShapes.name(shape)}: ${winner.shape.simpleName} over ${it.shape.simpleName}" }
        }
        assertTrue("decided by order between unrelated entries:\n  ${byOrder.joinToString("\n  ")}", byOrder.isEmpty())

        val unnamed = shapes.filter { classifier.winner(it) == null }.map { GrammarShapes.name(it) }
        assertTrue("no bucket names:\n  ${unnamed.joinToString("\n  ")}", unnamed.isEmpty())

        val winners = shapes.mapNotNull { classifier.winner(it) }.toSet()
        val dead = classifier.entries.filter { it !in winners }.map { "${GrammarShapes.name(it.shape)} (${it.bucket})" }
        assertTrue("no shape reaches these entries first:\n  ${dead.joinToString("\n  ")}", dead.isEmpty())
    }
}
