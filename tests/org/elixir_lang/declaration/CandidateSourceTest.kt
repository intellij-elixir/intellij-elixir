package org.elixir_lang.declaration

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm

/** Each candidate written `line name=applicability`, `line` being where its element starts. */
class CandidateSourceTest : PlatformTestCase() {
    fun testPrefixMatchIsNoCandidate() {
        configure("applicability.ex")

        assertReaches("fo(1)", false, 20)
        assertCandidates("fo(1)", false)
    }

    fun testDelegationTargetThroughPrefixMatchedHeadIsNoCandidate() {
        configure("applicability.ex")

        assertReaches("fo(1)", true, 2)
        assertCandidates("fo(1)", true)
    }

    fun testAsTargetThroughPrefixMatchedHeadIsNoCandidate() {
        configure("applicability.ex")

        assertReaches("ren(1)", true, 4)
        assertCandidates("ren(1)", true)
    }

    fun testChainedDelegationNearMissIsWrongArity() {
        configure("applicability.ex")

        assertCandidates(
            "chained(1, 2)",
            true,
            "18 chained=WRONG_ARITY",
            "9 size=WRONG_ARITY",
            "4 bar=WRONG_ARITY"
        )
    }

    fun testNamedExceptionUseReachesOnlyItsName() {
        configure("applicability.ex")

        assertCandidates("exception(1)", false, "19 exception=VALID")
    }

    fun testNamelessUseReachesBothExceptionDeclarations() {
        configure("applicability.ex")

        assertContainsCandidates("Uses.unquote(f)(1)", false, "19 exception=OPAQUE", "19 message=OPAQUE")
    }

    fun testNamelessUseIsOpaqueWhereTheArityFits() {
        configure("applicability.ex")

        assertContainsCandidates("Uses.unquote(f)(1)", false, "16 foo=OPAQUE", "20 fo_local=OPAQUE")
    }

    fun testNamelessUseIsWrongArityWhereTheArityDoesNotFit() {
        configure("applicability.ex")

        assertContainsCandidates("Uses.unquote(f)(1)", true, "24 uses=WRONG_ARITY")
    }

    fun testQuotedEExNameNearMissIsWrongArity() {
        configure("applicability.ex")
        val eex = call("EEx.function_from_string(:def, :\"quoted_eex\"")
        val state = ResolveState.initial()
        val form = DeclaringForm.syntacticForm(eex) ?: DeclaringForm.resolvingForm(eex, state)
        val declaration = Declarations.of(form!!, eex, state).single().declaration!!
        val use = Use.of(call("quoted_eex(1, 2)")) as Use.Named

        assertEquals(Applicability.WRONG_ARITY, Applicability.of(declaration, use.name, false))
    }

    /** `embed_template` defines `page_template/1` only; the walk resolving `page_template()` is kept. */
    fun testEmbedTemplateWithNoArgumentsIsValid() {
        configure("applicability.ex")

        assertCandidates("page_template()", false, "22 page_template=VALID")
    }

    fun testUndecidedEExKindLocalWrongArityNamesItsArity() {
        configure("eex_kind.ex")

        assertRejectedNaming("dyn(1, 2)", "dyn/[1]")
    }

    fun testUndecidedEExKindRemoteWrongArityNamesItsArity() {
        configure("eex_kind.ex")

        assertRejectedNaming("EExKind.dyn(1, 2)", "dyn/[1]")
    }

    fun testUndecidedEExKindBesideAClauseNamesBoth() {
        configure("eex_kind.ex")

        assertRejectedNaming("other(1, 2)", "other/[3]", "other/[1]")
    }

    /** The import and the `defdelegate` both reach `target/1`: each candidate keeps its own route's reach and paths. */
    fun testEachRouteToADeclarationKeepsItsOwnReachAndPaths() {
        configure("two_routes.ex")

        assertEquals(
            setOf(
                "2 target DELEGATION_TARGET via [7]",
                "2 target IMPORT via [6]",
                "7 target OWN via []"
            ),
            candidates("target(1)", false).map { found ->
                "${lineOf(found.element)} ${found.candidate.declaration.name} ${found.candidate.reach} " +
                    "via ${found.via.map(::lineOf)}"
            }.toSet()
        )
    }

    /** Through `NestedA`'s `defdelegate`, each route `NestedB` reaches `t/1` by keeps its own reach and paths. */
    fun testEachRouteThroughADelegationKeepsItsOwnReachAndPaths() {
        configure("nested_routes.ex")

        assertEquals(
            listOf(
                "11 t OWN via []",
                "2 t DELEGATION_TARGET via [11, 7]",
                "2 t UNHELD_DELEGATION_TARGET via [6, 11]",
                "7 t DELEGATION_TARGET via [11]"
            ),
            candidates("t(1)", false).map { found ->
                "${lineOf(found.element)} ${found.candidate.declaration.name} ${found.candidate.reach} " +
                    "via ${found.via.map(::lineOf)}"
            }.sorted()
        )
    }

    /** A `defdelegate` reaches only the target it names, so a prefix match behind it is no candidate. */
    fun testNamelessUseReachesNoPrefixMatchBehindADelegation() {
        configure("applicability.ex")

        val actual = candidates("Uses.unquote(f)(1)", true).map(::describe)

        assertTrue("2 foo=OPAQUE not in $actual", "2 foo=OPAQUE" in actual)
        assertEquals(emptyList<String>(), actual.filter { it.startsWith("3 ") || it.startsWith("5 ") })
    }

    /** A piped value is one more argument: `:x |> Left.unquote(name)(1)` calls with two. */
    fun testAPipedArgumentCountsInANamelessUsesArity() {
        configure("unquote_arity.ex")

        assertEquals(
            listOf("Arities.unquote(piped) [3 one=OPAQUE]", "Arities.unquote(piped)(1) [4 two=OPAQUE]"),
            listOf("Arities.unquote(piped)", "Arities.unquote(piped)(1)").map { text ->
                "$text ${candidates(text, false).filter { lineOf(it.element) in 2..4 }.map(::describe).sorted()}"
            }
        )
    }

    /**
     * A `do` block is one more argument to the argument list it follows: `Left.unquote(name) do ... end` is a call to
     * `unquote/2`, which Elixir does not unquote, and `Left.unquote(name)(1) do ... end` calls with two.
     */
    fun testADoBlockCountsInTheArgumentListItFollows() {
        configure("unquote_arity.ex")

        assertEquals(
            listOf("Arities.unquote(block) []", "Arities.unquote(block)(1) [4 two=OPAQUE]"),
            listOf("Arities.unquote(block)", "Arities.unquote(block)(1)").map { text ->
                "$text ${candidates(text, false).filter { lineOf(it.element) in 2..4 }.map(::describe).sorted()}"
            }
        )
    }

    /** `Left.unquote(name)(args)` calls with `args`, so `unquote`'s own argument is not counted. */
    fun testANamelessUsesArityIsItsSecondArgumentList() {
        configure("unquote_arity.ex")

        assertEquals(
            listOf(
                "Arities.unquote(name) ic=false [2 zero=OPAQUE]",
                "Arities.unquote(name) ic=true [2 zero=OPAQUE, 3 one=WRONG_ARITY, 4 two=WRONG_ARITY]",
                "Arities.unquote(name)() ic=false [2 zero=OPAQUE]",
                "Arities.unquote(name)() ic=true [2 zero=OPAQUE, 3 one=WRONG_ARITY, 4 two=WRONG_ARITY]",
                "Arities.unquote(name)(1, 2) ic=false [4 two=OPAQUE]",
                "Arities.unquote(name)(1, 2) ic=true [2 zero=WRONG_ARITY, 3 one=WRONG_ARITY, 4 two=OPAQUE]"
            ),
            listOf("Arities.unquote(name)", "Arities.unquote(name)()", "Arities.unquote(name)(1, 2)").flatMap { text ->
                listOf(false, true).map { incompleteCode ->
                    val found = candidates(text, incompleteCode).filter { lineOf(it.element) in 2..4 }.map(::describe).sorted()

                    "$text ic=$incompleteCode $found"
                }
            }
        )
    }

    /** Each `defdelegate` head a nameless use crosses must take the arity reaching it, and passes on its largest. */
    fun testANamelessUseFitsADelegationThroughTheHeadsCrossed() {
        configure("applicability.ex")

        assertEquals(
            listOf(
                "FitDelegator.unquote(name)(1) [42 f=OPAQUE via [47], 42 f=WRONG_ARITY via [48], 43 h=WRONG_ARITY via [49]]",
                "FitChain.unquote(name)(1) [42 f=OPAQUE via [59, 47]]"
            ),
            listOf("FitDelegator.unquote(name)(1)", "FitChain.unquote(name)(1)").map { text ->
                val targets = candidates(text, true)
                    .filter { lineOf(it.element) in 42..43 }
                    .map { "${describe(it)} via ${it.via.map(::lineOf)}" }
                    .sorted()

                "$text $targets"
            }
        )
    }

    /** A head with no largest arity passes on an arity that is not known, so its target's fit is not decidable. */
    fun testANamedUseThroughAHeadWithNoLargestArityIsOpaque() {
        configure("applicability.ex")

        assertEquals(
            listOf("69 open=OPAQUE via [74]", "70 open=OPAQUE via [74]"),
            candidates("OpenDelegator.open(1, 2)", false)
                .filter { lineOf(it.element) in 69..70 }
                .map { "${describe(it)} via ${it.via.map(::lineOf)}" }
                .sorted()
        )
    }

    /** An `import` that leaves out the use's arity leaves the head, and what it delegates to, a wrong arity. */
    fun testANamedUseAnImportLeavesOutIsAWrongArityThroughADelegation() {
        configure("applicability.ex")

        assertEquals(
            listOf("80 pair=WRONG_ARITY via [88, 84]", "84 pair=WRONG_ARITY via [88]"),
            candidates("pair(1)", true)
                .filter { lineOf(it.element) in 80..84 }
                .map { "${describe(it)} via ${it.via.map(::lineOf)}" }
                .sorted()
        )
    }

    /** An `import` that leaves out the use's arity rejects what a head with no largest arity delegates to as well. */
    fun testANamedUseAnImportLeavesOutIsAWrongArityThroughAHeadWithNoLargestArity() {
        configure("applicability.ex")

        assertEquals(
            listOf("69 open=WRONG_ARITY via [94, 74]", "70 open=WRONG_ARITY via [94, 74]"),
            candidates("open(1)", true)
                .filter { lineOf(it.element) in 69..70 }
                .map { "${describe(it)} via ${it.via.map(::lineOf)}" }
                .sorted()
        )
    }

    private fun configure(file: String) {
        myFixture.copyDirectoryToProject("", "")
        myFixture.configureFromTempProjectFile(file)
    }

    private fun assertCandidates(text: String, incompleteCode: Boolean, vararg expected: String) =
        assertEquals(expected.toList(), candidates(text, incompleteCode).map(::describe))

    private fun assertContainsCandidates(text: String, incompleteCode: Boolean, vararg expected: String) {
        val actual = candidates(text, incompleteCode).map(::describe)

        for (candidate in expected) {
            assertTrue("$candidate not in $actual", candidate in actual)
        }
    }

    /** That the walk reaches an element at [line], so that it being no candidate is not vacuous. */
    private fun assertReaches(text: String, incompleteCode: Boolean, line: Int) {
        val reached = (call(text).reference as PsiPolyVariantReference).multiResolve(incompleteCode).map { lineOf(it.element!!) }

        assertTrue("nothing at line $line in $reached", line in reached)
    }

    private fun assertRejectedNaming(text: String, vararg expected: String) {
        val use = call(text)
        val named = RejectedCall.named(candidates(text, false), Found::candidate, use is Qualified) { _, _ -> true }

        assertEquals(
            expected.toList(),
            named?.map { "${it.candidate.candidate.declaration.name}/${it.arities}" }
        )
    }

    private fun candidates(text: String, incompleteCode: Boolean): List<Found> =
        sourceFor(Feature.SYMBOL_REFERENCES).candidates(Use.of(call(text))!!, incompleteCode)

    private fun describe(found: Found): String =
        "${lineOf(found.element)} ${found.candidate.declaration.name}=${found.candidate.applicability}"

    private fun call(text: String): Call {
        val offset = myFixture.file.text.indexOf(text).also { assertTrue("`$text` not in the file", it >= 0) }

        return PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .first { it.textRange.startOffset == offset && it.text.startsWith(text) }
    }

    private fun lineOf(element: PsiElement): Int {
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)!!

        return document.getLineNumber(element.textRange.startOffset) + 1
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/declaration/candidate_source"
}
