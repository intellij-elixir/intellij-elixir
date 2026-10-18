package org.elixir_lang.reference.mfa_tuple

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.UsageOptions
import com.intellij.find.usages.impl.AllSearchOptions
import com.intellij.find.usages.impl.buildQuery
import com.intellij.find.usages.impl.searchTargets
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.model.psi.atom.AtomReference
import org.elixir_lang.model.psi.atom.GeneralAtomReference
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.atom.Variants
import org.elixir_lang.reference.Module
import java.util.concurrent.Callable

@Suppress("UnstableApiUsage")
class MfaTupleReferenceTest : PlatformTestCase() {
    fun testFunctionResolvesIntegerArity() {
        val reference = atomReferenceAtCaret("basic_mfa.ex")

        val resolveResults = reference.multiResolve(false)
        val resolvedName = (resolveResults.single().element as? PsiNamedElement)?.name

        assertEquals(1, resolveResults.size)
        assertTrue(resolveResults.single().isValidResult)
        assertEquals("map", resolvedName)
    }

    fun testResolveReturnsSingleElement() {
        val reference = atomReferenceAtCaret("basic_mfa.ex")

        val resolved = reference.resolve()
        assertNotNull(resolved)
        assertEquals("map", (resolved as? PsiNamedElement)?.name)
    }

    fun testResolveHandlesMultiClause() {
        val reference = atomReferenceAtCaret("multi_clause.ex")

        // multiResolve returns one result per clause
        val resolveResults = reference.multiResolve(false)
        assertTrue("Expected multiple clauses", resolveResults.size > 1)
        assertTrue(resolveResults.all { it.isValidResult })
        assertTrue(resolveResults.all { (it.element as? PsiNamedElement)?.name == "process" })

        // resolve() must return non-null even with multiple clauses - preferred target
        val resolved = reference.resolve()
        assertNotNull("resolve() must not return null for multi-clause functions", resolved)
        assertEquals("process", (resolved as? PsiNamedElement)?.name)
    }

    fun testGetVariantsOffersModuleFunctions() {
        val reference = atomReferenceAtCaret("basic_mfa.ex")

        val variantStrings = reference.variants.map { (it as LookupElement).lookupString }
        assertContainsElements(variantStrings, "map")
    }

    fun testBareAtomUsesGeneralReferenceWithLegacyVariants() {
        myFixture.configureByFile("bare_atom.ex")
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
        assertInstanceOf(reference, GeneralAtomReference::class.java)

        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)
            ?: error("No element at caret in bare_atom.ex")
        val atom = PsiTreeUtil.getParentOfType(elementAtCaret, ElixirAtom::class.java, false)
            ?: error("No ElixirAtom at caret in bare_atom.ex")
        val expectedVariants = Variants.lookupElementList(atom)

        assertEquals(
            "General atom reference should expose legacy atom variants",
            expectedVariants.size,
            reference.variants.size
        )
    }

    fun testMfaAtomUsesMfaReferenceWithModuleFunctionVariants() {
        myFixture.configureByFile("basic_mfa.ex")
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion()

        assertInstanceOf(reference, AtomReference::class.java)
        val variantStrings = reference.variants.map { (it as LookupElement).lookupString }
        assertContainsElements(variantStrings, "map")
    }

    fun testFunctionResolvesArgsListArity() {
        val reference = atomReferenceAtCaret("supervisor_child_spec.ex")

        val resolveResults = reference.multiResolve(false)
        val resolvedName = (resolveResults.single().element as? PsiNamedElement)?.name

        assertEquals(1, resolveResults.size)
        assertTrue(resolveResults.single().isValidResult)
        assertEquals("start_link", resolvedName)
    }

    fun testApplyThreeResolvesFunctionAtom() {
        val reference = atomReferenceAtCaret("apply_source.ex")

        val resolveResults = reference.multiResolve(false)
        val resolvedName = (resolveResults.single().element as? PsiNamedElement)?.name

        assertEquals(1, resolveResults.size)
        assertTrue(resolveResults.single().isValidResult)
        assertEquals("reverse", resolvedName)
    }

    fun testTupleEnumMapResolvesExactly() {
        myFixture.configureByFile("enum_map_tuple.ex")
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as AtomReference

        val resolveResults = reference.multiResolve(false)
        val resolvedNames = resolveResults.mapNotNull { it.element as? PsiNamedElement }.mapNotNull { it.name }.toSet()

        assertTrue(resolveResults.isNotEmpty())
        assertTrue(resolveResults.all { it.isValidResult })
        assertEquals(setOf("map"), resolvedNames)
    }

    fun testApplyEnumMapResolvesExactly() {
        myFixture.configureByFile("enum_map_apply.ex")
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as AtomReference

        val resolveResults = reference.multiResolve(false)
        val resolvedNames = resolveResults.mapNotNull { it.element as? PsiNamedElement }.mapNotNull { it.name }.toSet()

        assertTrue(resolveResults.isNotEmpty())
        assertTrue(resolveResults.all { it.isValidResult })
        assertEquals(setOf("map"), resolvedNames)
    }

    fun testFunctionResolvesEmptyArgsListArity() {
        val reference = atomReferenceAtCaret("empty_args_list.ex")

        val resolveResults = reference.multiResolve(false)
        val resolvedName = (resolveResults.single().element as? PsiNamedElement)?.name

        assertEquals(1, resolveResults.size)
        assertTrue(resolveResults.single().isValidResult)
        assertEquals("run", resolvedName)
    }

    fun testFunctionResolvesInInlineDoBody() {
        val reference = atomReferenceAtCaret("inline_do_body.ex")

        val resolveResults = reference.multiResolve(false)
        val resolvedName = (resolveResults.single().element as? PsiNamedElement)?.name

        assertEquals(1, resolveResults.size)
        assertTrue(resolveResults.single().isValidResult)
        assertEquals("map", resolvedName)
    }

    fun testFunctionResolvesInBlockDoBody() {
        val reference = atomReferenceAtCaret("block_do_body.ex")

        val resolveResults = reference.multiResolve(false)
        val resolvedName = (resolveResults.single().element as? PsiNamedElement)?.name

        assertEquals(1, resolveResults.size)
        assertTrue(resolveResults.single().isValidResult)
        assertEquals("map", resolvedName)
    }

    fun testModuleAliasStillResolves() {
        myFixture.configureByFile("module_reference.ex")

        val reference = myFixture.getReferenceAtCaretPositionWithAssertion()

        assertInstanceOf(reference, Module::class.java)
        assertNotNull(reference.resolve())
    }

    fun testArityMismatchResolvesWithInvalidResult() {
        val reference = atomReferenceAtCaret("arity_mismatch.ex")

        val resolveResults = reference.multiResolve(false)
        assertEquals(1, resolveResults.size)
        assertFalse(
            "Arity mismatch should resolve with isValidResult=false for navigation, not as a valid result",
            resolveResults.single().isValidResult
        )
    }

    fun testNonMfaTupleHasNoMfaReference() {
        myFixture.configureByFile("non_mfa_tuple.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testQuotedAtomResolves() {
        assertResolvesValidlyTo("quoted_atom.ex", "map")
    }

    fun testInterpolatedAtomResolvesToNothing() {
        val reference = atomReferenceAtCaret("interpolated_atom.ex")

        assertEmpty(reference.multiResolve(false))
    }

    fun testApplyThreeResolvesQuotedFunctionAtom() {
        assertResolvesValidlyTo("apply_quoted_atom.ex", "reverse")
    }

    fun testApplyThreeResolvesEscapedFunctionAtomByItsValue() {
        assertResolvesValidlyTo("apply_escaped_atom.ex", "reverse")
    }

    fun testAtomNestedInApplyThreeFunctionArgumentHasNoMfaReference() {
        myFixture.configureByFile("apply_nested_atom.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testQuotedAtomNestedInApplyThreeFunctionArgumentHasNoMfaReference() {
        myFixture.configureByFile("apply_nested_quoted_atom.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testApplyThreeResolvesParenthesisedFunctionAtom() {
        assertResolvesValidlyTo("apply_parenthesised_atom.ex", "reverse")
    }

    fun testAtomNestedInParenthesisedApplyThreeFunctionArgumentHasNoMfaReference() {
        myFixture.configureByFile("apply_parenthesised_nested_atom.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testInterpolatedModuleAtomHasNoMfaReference() {
        myFixture.configureByFile("interpolated_module_atom.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testApplyThreeWithInterpolatedModuleAtomHasNoMfaReference() {
        myFixture.configureByFile("apply_interpolated_module_atom.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testQuotedModuleAtomResolves() {
        assertResolvesValidlyTo("quoted_module_atom.ex", "map")
    }

    fun testApplyThreeWithQuotedModuleAtomResolves() {
        assertResolvesValidlyTo("apply_quoted_module_atom.ex", "reverse")
    }

    fun testVariableModuleHasNoMfaReference() {
        myFixture.configureByFile("variable_module.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testBareAtomHasNoMfaReference() {
        myFixture.configureByFile("bare_atom.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testAtomInListHasNoMfaReference() {
        myFixture.configureByFile("atom_in_list.ex")

        assertNull(atomReferenceAtCaretOrNull())
    }

    /** Elixir does not check an arity that is not a literal, so the one function of the name is the atom's target. */
    fun testVariableArityResolvesTheLoneFunction() {
        assertEquals("map", loneTarget("variable_arity.ex"))
    }

    fun testDynamicThirdElementResolvesTheLoneFunction() {
        assertEquals("by_id", loneTarget("dynamic_third_element.ex"))
    }

    fun testWildcardThirdElementResolvesTheLoneFunction() {
        assertEquals("start_link", loneTarget("wildcard_third_element.ex"))
    }

    /** The name of the atom's target in [fileName], once its one result is checked to be valid. */
    private fun loneTarget(fileName: String): String? {
        val reference = atomReferenceAtCaret(fileName)

        assertTrue("one valid result", reference.multiResolve(false).single().isValidResult)

        return (reference.resolve() as? PsiNamedElement)?.name
    }

    fun testTupleWithAnUnresolvedModuleReadsItsMiddleAtomAsAnOrdinaryAtom() {
        myFixture.configureByFile("unresolvable_module.ex")

        val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
        assertInstanceOf(reference, GeneralAtomReference::class.java)
        assertNull(atomReferenceAtCaretOrNull())
        assertEmpty((reference as GeneralAtomReference).multiResolve(false))
    }

    fun testTaggedValueTupleMiddleAtomIsAnOrdinaryAtom() {
        myFixture.configureByFile("tagged_value_tuple.ex")

        assertInstanceOf(myFixture.getReferenceAtCaretPositionWithAssertion(), GeneralAtomReference::class.java)
        assertNull(atomReferenceAtCaretOrNull())
    }

    fun testTaggedTupleMiddleAtomNavigatesToTheModule() {
        assertMiddleAtomNavigatesToTheModule("tag_middle_module.ex")
    }

    fun testTupleWithAnUnresolvedAliasFirstNavigatesItsMiddleAtomToTheModule() {
        assertMiddleAtomNavigatesToTheModule("tag_middle_module_unresolved_alias.ex")
    }

    fun testModuleAtomFirstWithAnArgumentsListResolvesTheFunction() {
        assertResolvesValidlyTo("tag_module_first_args_list.ex", "f")
    }

    fun testModuleAtomFirstWithAnIntegerArityResolvesTheFunction() {
        assertResolvesValidlyTo("tag_module_first_integer_arity.ex", "f")
    }

    fun testMiddleAtomBecomesAFunctionNameOnceAnotherFileDefinesTheModule() {
        myFixture.configureByText("usage.ex", "defmodule Usage do\n  def run, do: {Later, :<caret>f, 1}\nend\n")
        assertInstanceOf(myFixture.getReferenceAtCaretPositionWithAssertion(), GeneralAtomReference::class.java)

        myFixture.addFileToProject("later.ex", "defmodule Later do\n  def f(a), do: a\nend\n")

        val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
        assertInstanceOf(reference, AtomReference::class.java)
        assertEquals("later.ex", reference.resolve()?.containingFile?.name)
    }

    fun testApplyKeepsItsMfaReferenceWhenItsFirstArgumentIsNoModule() {
        myFixture.configureByFile("tag_apply_non_module_first.ex")

        assertInstanceOf(myFixture.getReferenceAtCaretPositionWithAssertion(), AtomReference::class.java)
        assertNotNull(atomReferenceAtCaretOrNull())
    }

    fun testApplyThreeWithErlangModuleIsStillAnUnresolvedGap() {
        val reference = atomReferenceAtCaret("erlang_apply.ex")

        val resolveResults = reference.multiResolve(false)
        assertTrue(
            "The Erlang-style apply/3 form should stay soft until beam-backed function atoms are modeled",
            resolveResults.isEmpty()
        )
    }

    fun testKernelMacroIsNotAcceptedAsMfaFunctionTarget() {
        val reference = atomReferenceAtCaret("kernel_macro.ex")

        val resolveResults = reference.multiResolve(false)
        assertTrue(
            "MFA function atoms must not resolve compile-time macros",
            resolveResults.isEmpty()
        )
    }

    /** `apply/3` can't call a private function, so the atom names only the public one, at an arity it doesn't take. */
    fun testPrivateFunctionIsNotAnMfaFunctionTarget() {
        val resolveResults = atomReferenceAtCaret("private_function.ex").multiResolve(false)

        assertEquals(listOf("def f(a, b), do: {a, b}"), resolveResults.map { it.element?.text })
        assertFalse("at an arity it doesn't take", resolveResults.single().isValidResult)
    }

    /** A guard is a macro, which `apply/3` can't call, so its atom is no use of the guard at any gesture. */
    fun testGuardIsNoTargetOfAnMfaAtom() = assertNoTargetOfAnMfaAtom(
        "defmodule M do\n  defguard is_g(x) when is_integer(x)\nend\n\ndefmodule U do\n  def run(x), do: apply(M, :is_g, [x])\nend\n",
        "is_g"
    )

    /** `apply/3` can't call a private function, so its atom is no use of the function at any gesture. */
    fun testPrivateFunctionIsNoTargetOfAnMfaAtom() = assertNoTargetOfAnMfaAtom(
        "defmodule M do\n  defp f(a), do: a\n  def g(a), do: f(a)\nend\n\ndefmodule U do\n  def run, do: apply(M, :f, [1])\nend\n",
        "f"
    )

    /** Go To at an MFA atom naming a function of two clauses lands on each, as at a call. */
    fun testGoToAtAnMfaAtomLandsOnEachClause() = assertMfaAtomLandsOn(
        "defmodule M do\n  def f(q, x) when is_list(q), do: {q, x}\n  def f(queue, item), do: {queue, item}\nend\n",
        "apply(M, :f, [[], 1])",
        "def f(q, x) when is_list(q), do: {q, x}",
        "def f(queue, item), do: {queue, item}"
    )

    /** Go To at an MFA atom naming a head with defaults lands on it once, though the head covers two arities. */
    fun testGoToAtAnMfaAtomLandsOnAHeadWithDefaultsOnce() = assertMfaAtomLandsOn(
        "defmodule M do\n  def f(q, x \\\\ nil), do: {q, x}\nend\n",
        "apply(M, :f, [1, 2])",
        "def f(q, x \\\\ nil), do: {q, x}"
    )

    /** Go To at an MFA atom naming a delegation with defaults lands on its target once, at either arity. */
    fun testGoToAtAnMfaAtomLandsOnADelegationsTargetOnce() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def f(q, x), do: {q, x}\nend\n")
        val module = "defmodule M do\n  defdelegate f(q, x \\\\ nil), to: T\nend\n"

        assertMfaAtomLandsOn(module, "apply(M, :f, [1, 2])", "def f(q, x), do: {q, x}")
        assertMfaAtomLandsOn(module, "{M, :f, 1}", "def f(q, x), do: {q, x}")
    }

    /** Find Usages from an MFA atom naming a head with defaults finds the calls at each arity the head takes. */
    fun testFindUsagesFromAnMfaAtomAtAHeadWithDefaultsFindsEveryArity() =
        assertMfaAtomFindsEveryArity("defmodule M do\n  def f(q, x \\\\ nil), do: {q, x}\nend\n")

    /** Find Usages from an MFA atom naming a delegation with defaults finds the calls at each arity it takes. */
    fun testFindUsagesFromAnMfaAtomAtADelegationFindsEveryArity() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def f(q, x), do: {q, x}\nend\n")

        assertMfaAtomFindsEveryArity("defmodule M do\n  defdelegate f(q, x \\\\ nil), to: T\nend\n")
    }

    fun testFindUsagesIncludesMfaTupleReference() {
        val reference = atomReferenceAtCaret("find_usages.ex")

        // Resolve the MFA reference to the function definition
        val resolveResults = reference.multiResolve(false)
        assertTrue("Expected MFA reference to resolve", resolveResults.isNotEmpty())
        assertTrue(resolveResults.first().isValidResult)
        val functionDefinition = resolveResults.first().element!!

        // Find usages through SearchTarget routing (Symbol API path).
        val usages = findUsages(functionDefinition)

        // The MFA tuple atom should be among them
        val usageElements = usages.mapNotNull { usage -> usage.file.findElementAt(usage.range.startOffset) }
        val hasMfaTupleAtomUsage = usages.any { usage ->
            if (usage.file.name != "find_usages.ex") return@any false
            val start = usage.range.startOffset
            if (start <= 0) return@any false
            usage.file.text.getOrNull(start - 1) == ':'
        }
        assertTrue(
            "Expected MFA tuple reference in Find Usages results, got: ${usageElements.map { it.text }}",
            hasMfaTupleAtomUsage
        )
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple"

    /** At an arity Elixir does not check, the one function of the name is the atom's target. */
    fun testNonLiteralArityResolvesALoneClause() {
        assertEquals("def f(a), do: a", nonLiteralArityTarget("defmodule M do\n  def f(a), do: a\nend\n"))
    }

    fun testNonLiteralArityResolvesNothingAmongTwoFunctions() {
        assertNull(nonLiteralArityTarget("defmodule M do\n  def f(a), do: a\n  def f(a, b), do: {a, b}\nend\n"))
    }

    fun testNonLiteralArityResolvesNothingAmongTwoClauses() {
        assertNull(nonLiteralArityTarget("defmodule M do\n  def f(0), do: 0\n  def f(n), do: n\nend\n"))
    }

    fun testNonLiteralArityResolvesNothingAtADelegationAndItsTarget() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def f(a), do: a\nend\n")

        assertNull(nonLiteralArityTarget("defmodule M do\n  defdelegate f(a), to: T\nend\n"))
    }

    /**
     * In [text], Go To at `apply`'s `:`[name] atom goes nowhere, and from the definition [name] Find Usages doesn't
     * list the atom and rename leaves it.
     */
    @Suppress("UnstableApiUsage")
    private fun assertNoTargetOfAnMfaAtom(text: String, name: String) {
        myFixture.configureByText("m.ex", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(":$name,") + 1)

        assertNull("Go To at the atom", myFixture.gotoDeclarationTargetsAtCaret())

        myFixture.editor.caretModel.moveToOffset(text.indexOf(" $name(") + 1)
        val lines = myFixture.singleTargetPsiUsagesAtCaret(project).map { usage ->
            usage.file.text.lines()[usage.file.text.take(usage.range.startOffset).count { it == '\n' }].trim()
        }

        assertFalse("Find Usages lists the atom: $lines", lines.any { "apply(" in it })

        myFixture.renameTargetAtCaret("${name}2")

        assertEquals(text.replace("$name(", "${name}2("), myFixture.editor.document.text)
    }

    /** With [module] as `m.ex`, Go To at `:f` in [mfa] lands on the lines [expected], each once. */
    private fun assertMfaAtomLandsOn(module: String, mfa: String, vararg expected: String) {
        myFixture.configureByText("m.ex", "${module}\ndefmodule U do\n  def run, do: $mfa\nend\n")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(":f") + 1)

        val landed = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { target ->
            val destination = target.destination!!
            val document = myFixture.getDocument(destination.containingFile)
            val line = document.getLineNumber(destination.textOffset)

            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        }

        assertEquals(mfa, expected.sorted(), landed.sorted())
    }

    /** With [module] as `m.ex`, Find Usages from `apply`'s `:f` lists `M.f` called with one argument and with two. */
    private fun assertMfaAtomFindsEveryArity(module: String) {
        val text = "${module}\ndefmodule U do\n  def run, do: apply(M, :f, [1, 2])\n  def one, do: M.f(1)\n  def two, do: M.f(1, 2)\nend\n"
        myFixture.configureByText("m.ex", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(":f") + 1)

        val lines = myFixture.singleTargetPsiUsagesAtCaret(project).filterNot { it.declaration }.map { usage ->
            usage.file.text.lines()[usage.file.text.take(usage.range.startOffset).count { it == '\n' }].trim()
        }

        assertEquals(
            listOf("def one, do: M.f(1)", "def run, do: apply(M, :f, [1, 2])", "def two, do: M.f(1, 2)"),
            lines.sorted()
        )
    }

    /** The text of what `apply(M, :f, args)`'s atom resolves to, with [module] as `m.ex`. */
    private fun nonLiteralArityTarget(module: String): String? {
        myFixture.addFileToProject("m.ex", module)
        myFixture.configureByText("u.ex", "defmodule U do\n  def run(args), do: apply(M, :<caret>f, args)\nend\n")

        return atomReferenceAtCaretOrNull()!!.resolve()?.text
    }

    private fun atomReferenceAtCaret(fileName: String): AtomReference {
        myFixture.configureByFile(fileName)

        return atomReferenceAtCaretOrNull() ?: error("Expected AtomReference at caret")
    }

    private fun assertMiddleAtomNavigatesToTheModule(fileName: String) {
        myFixture.configureByFile(fileName)

        val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
        assertInstanceOf(reference, GeneralAtomReference::class.java)
        assertNull(atomReferenceAtCaretOrNull())

        val destination = myFixture.gotoDeclarationDestinationAtCaret()!!
        val fileText = destination.containingFile.text
        assertTrue(
            "Go To on the middle atom must land in `defmodule :tag_target`, landed at: " +
                    fileText.substring(destination.textRange.startOffset).lineSequence().first(),
            destination.textRange.startOffset < fileText.indexOf("defmodule Usage")
        )
    }

    private fun assertResolvesValidlyTo(fileName: String, name: String) {
        val resolveResults = atomReferenceAtCaret(fileName).multiResolve(false)

        assertEquals(1, resolveResults.size)
        assertTrue(resolveResults.single().isValidResult)
        assertEquals(name, (resolveResults.single().element as? PsiNamedElement)?.name)
    }

    private fun atomReferenceAtCaretOrNull(): AtomReference? {
        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
        val atom = PsiTreeUtil.getParentOfType(elementAtCaret, ElixirAtom::class.java, false) ?: return null

        return PsiSymbolReferenceService.getService().getReferences(atom)
            .filterIsInstance<AtomReference>()
            .singleOrNull()
    }

    private fun findUsages(element: PsiElement): List<PsiUsage> {
        val file = element.containingFile
        val offset = (element as? Call)
            ?.takeIf { CallDefinitionClause.`is`(it) }
            ?.let { CallDefinitionClause.nameIdentifier(it)?.textRange?.startOffset }
            ?: element.textRange.startOffset
        val allOptions = AllSearchOptions(
            UsageOptions.createOptions(GlobalSearchScope.allScope(project)),
            textSearch = false
        )

        return onPooledThread<List<PsiUsage>> {
            ReadAction.nonBlocking(Callable<List<PsiUsage>> {
                val targets = searchTargets(file, offset)
                assertTrue("Expected at least one search target at offset $offset", targets.isNotEmpty())
                targets
                    .flatMap { buildQuery(project, it, allOptions).findAll() }
                    .filterIsInstance<PsiUsage>()
            }).executeSynchronously()
        }
    }
}
