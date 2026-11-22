package org.elixir_lang.declaration

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.parameterInfoSignaturesAtCaret
import org.elixir_lang.expander.ExpanderSourceRows.lineOf
import org.elixir_lang.model.psi.function.FunctionSymbol

/**
 * `defoverridable` makes overridable only what is already defined when it runs, so a later definition of that name and
 * arity is the user's, and a call names it alone. With Run the expander on, Parameter Info names the parameters of the
 * clause the overriding one replaces, as Elixir keeps them.
 */
@Suppress("UnstableApiUsage")
class OverridableTest : ExpanderSettingTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testADefinitionThatDefoverridableFollowsIsReplacedByALaterOne() = assertADefinitionThatDefoverridableFollowsIsReplacedByALaterOne("user")

    fun testADefinitionThatDefoverridableFollowsIsReplacedByALaterOneWithTheExpanderOn() = withTheExpanderOn { assertADefinitionThatDefoverridableFollowsIsReplacedByALaterOne("original") }

    private fun assertADefinitionThatDefoverridableFollowsIsReplacedByALaterOne(parameters: String) {
        configure(
            """
            defmodule M do
              def f(original), do: original

              defoverridable [f: 1]

              def f(user), do: user

              def run(y), do: f(y<caret>)
            end
            """
        )

        assertEquals(listOf(parameters), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("6 -> []")
        atName()
        assertEquals(listOf("preferred.ex:6"), goTo())
        assertEquals(listOf("def f(user)"), labels())
    }

    fun testWhatAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition() = assertWhatAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition("user")

    fun testWhatAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinitionWithTheExpanderOn() = withTheExpanderOn { assertWhatAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition("x") }

    private fun assertWhatAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition(parameters: String) {
        myFixture.addFileToProject(
            "b.ex",
            """
            defmodule B do
              defmacro __using__(_) do
                quote do
                  def g(x), do: x

                  defoverridable g: 1
                end
              end
            end
            """.trimIndent()
        )
        configure(
            """
            defmodule M do
              use B

              def g(user), do: user

              def run(y), do: g(y<caret>)
            end
            """
        )

        assertEquals(listOf(parameters), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("4 -> []")
        atName()
        assertEquals(listOf("preferred.ex:4"), goTo())
        assertEquals(listOf("def g(user)"), labels())
    }

    fun testADelegationThatDefoverridableFollowsIsReplacedByALaterDefinition() = assertADelegationThatDefoverridableFollowsIsReplacedByALaterDefinition("user")

    fun testADelegationThatDefoverridableFollowsIsReplacedByALaterDefinitionWithTheExpanderOn() = withTheExpanderOn { assertADelegationThatDefoverridableFollowsIsReplacedByALaterDefinition("s") }

    private fun assertADelegationThatDefoverridableFollowsIsReplacedByALaterDefinition(parameters: String) {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def id(s), do: s\nend\n")
        configure(
            """
            defmodule M do
              defdelegate id(s), to: T

              defoverridable id: 1

              def id(user), do: user

              def run(y), do: id(y<caret>)
            end
            """
        )

        assertEquals(listOf(parameters), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("6 -> []")
        atName()
        assertEquals(listOf("preferred.ex:6"), goTo())
        assertEquals(listOf("def id(user)"), labels())
    }

    fun testADelegationAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition() = assertADelegationAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition("user")

    fun testADelegationAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinitionWithTheExpanderOn() = withTheExpanderOn { assertADelegationAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition("s") }

    private fun assertADelegationAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition(parameters: String) {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def id(s), do: s\nend\n")
        myFixture.addFileToProject(
            "b.ex",
            """
            defmodule B do
              defmacro __using__(_) do
                quote do
                  defdelegate id(s), to: T

                  defoverridable id: 1
                end
              end
            end
            """.trimIndent()
        )
        configure(
            """
            defmodule M do
              use B

              def id(user), do: user

              def run(y), do: id(y<caret>)
            end
            """
        )

        assertEquals(listOf(parameters), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("4 -> []")
        atName()
        assertEquals(listOf("preferred.ex:4"), goTo())
        assertEquals(listOf("def id(user)"), labels())
    }

    /** What a `use` injects is defined where the `use` is, so a `defoverridable` after the `use` takes it. */
    fun testWhatAUseInjectsIsMadeOverridableByADefoverridableAfterTheUse() = assertWhatAUseInjectsIsMadeOverridableByADefoverridableAfterTheUse("user")

    fun testWhatAUseInjectsIsMadeOverridableByADefoverridableAfterTheUseWithTheExpanderOn() = withTheExpanderOn { assertWhatAUseInjectsIsMadeOverridableByADefoverridableAfterTheUse("x") }

    private fun assertWhatAUseInjectsIsMadeOverridableByADefoverridableAfterTheUse(parameters: String) {
        myFixture.addFileToProject(
            "b.ex",
            """
            defmodule B do
              defmacro __using__(_) do
                quote do
                  def g(x), do: x
                end
              end
            end
            """.trimIndent()
        )
        configure(
            """
            defmodule M do
              use B

              defoverridable g: 1

              def g(user), do: user

              def run(y), do: g(y<caret>)
            end
            """
        )

        assertEquals(listOf(parameters), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("6 -> []")
        atName()
        assertEquals(listOf("preferred.ex:6"), goTo())
        assertEquals(listOf("def g(user)"), labels())
    }

    /** A `use` inside a `use`'s `quote` injects where that `quote` is, so a `defoverridable` after it there takes it. */
    fun testWhatANestedUseInjectsIsMadeOverridableByADefoverridableAfterIt() = assertWhatANestedUseInjectsIsMadeOverridableByADefoverridableAfterIt("user")

    fun testWhatANestedUseInjectsIsMadeOverridableByADefoverridableAfterItWithTheExpanderOn() = withTheExpanderOn { assertWhatANestedUseInjectsIsMadeOverridableByADefoverridableAfterIt("x") }

    private fun assertWhatANestedUseInjectsIsMadeOverridableByADefoverridableAfterIt(parameters: String) {
        myFixture.addFileToProject(
            "b.ex",
            """
            defmodule B do
              defmacro __using__(_) do
                quote do
                  def g(x), do: x
                end
              end
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "a.ex",
            """
            defmodule A do
              defmacro __using__(_) do
                quote do
                  use B

                  defoverridable g: 1
                end
              end
            end
            """.trimIndent()
        )
        configure(
            """
            defmodule M do
              use A

              def g(user), do: user

              def run(y), do: g(y<caret>)
            end
            """
        )

        assertEquals(listOf(parameters), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("4 -> []")
        atName()
        assertEquals(listOf("preferred.ex:4"), goTo())
        assertEquals(listOf("def g(user)"), labels())
    }

    fun testDefoverridableOfAnotherArityLeavesTheDefinitionTheUsers() {
        configure(
            """
            defmodule M do
              def f(original), do: original
              def f(a, b), do: {a, b}

              defoverridable [f: 2]

              def f(user), do: user

              def run(y), do: f(y<caret>)
            end
            """
        )

        assertEquals(listOf("2 -> []", "7 -> []"), kept(incompleteCode = false))
        atName()
        assertEquals(listOf("preferred.ex:2", "preferred.ex:7"), goTo())
        assertEquals(listOf("def f(original)", "def f(user)"), labels())
    }

    /** A head with a default declares `f/1` and `f/2`; replacing `f/2` leaves `f/1`, which Elixir keeps, to the head. */
    fun testDefoverridableOfOneArityOfADefaultHeadLeavesItTheOther() = assertTheHeadKeepsTheOtherArity()

    /** The override and the head's `f/1` are one function, as Elixir records it, so Parameter Info shows its signature once. */
    fun testDefoverridableOfOneArityOfADefaultHeadLeavesItTheOtherWithTheExpanderOn() =
        withTheExpanderOn(::assertTheHeadKeepsTheOtherArity)

    /** At the overridden arity, Parameter Info shows the one signature Elixir records for the function. */
    fun testACallAtTheOverriddenArityOfADefaultHeadShowsTheFunctionOnce() = assertTheOverriddenArityShowsTheFunctionOnce()

    fun testACallAtTheOverriddenArityOfADefaultHeadShowsTheFunctionOnceWithTheExpanderOn() =
        withTheExpanderOn(::assertTheOverriddenArityShowsTheFunctionOnce)

    /**
     * With Run the expander on, Go To from a call at the overridden arity lands on the redefinition alone, and from a
     * call at the arity the head keeps, on the head.
     */
    fun testWithTheExpanderOnGoToFromEachArityOfAnOverriddenDefaultHeadLandsOnWhatDefinesIt() = withTheExpanderOn {
        assertEquals(
            listOf("f(y, 2)" to listOf("preferred.ex:6"), "f(y)" to listOf("preferred.ex:2")),
            listOf("f(y, 2)", "f(y)").map { call ->
                configure(overriddenDefaultHead(call.replaceFirst("(", "(<caret>")))
                atName()

                call to goTo()
            }
        )
    }

    private fun assertTheOverriddenArityShowsTheFunctionOnce() {
        configure(overriddenDefaultHead("f(y, 2<caret>)"))

        assertEquals(listOf("a, b \\\\ 1"), myFixture.parameterInfoSignaturesAtCaret())
    }

    /** `M`, whose `f(a, b \\ 1)` has its `f/2` overridden, and whose `run(y)` calls [call]. */
    private fun overriddenDefaultHead(call: String): String =
        """
        defmodule M do
          def f(a, b \\ 1), do: {a, b}

          defoverridable f: 2

          def f(a, b), do: {b, a}

          def run(y), do: $call
        end
        """

    private fun assertTheHeadKeepsTheOtherArity() {
        configure(overriddenDefaultHead("f(y<caret>)"))

        assertEquals(listOf("a, b \\\\ 1"), myFixture.parameterInfoSignaturesAtCaret())
        assertEquals(listOf("2 -> []", "6 -> []"), kept(incompleteCode = true))
        assertEquals(listOf("2 -> []"), kept(incompleteCode = false))
        atName()
        assertEquals(listOf("preferred.ex:2"), goTo())
    }

    fun testAWrongArityCallOffersADefaultHeadBesideTheOverrideOfOneOfItsArities() {
        configure(
            """
            defmodule M do
              def f(a, b \\ 1), do: {a, b}

              defoverridable f: 2

              def f(a, b), do: {b, a}

              def run, do: f(<caret>)
            end
            """
        )

        assertEquals(listOf("2 -> []", "6 -> []"), kept(incompleteCode = false))
        atName()
        assertEquals(listOf("preferred.ex:2", "preferred.ex:6"), goTo())
    }

    /** Replacing every arity a default head declares replaces the head. */
    fun testDefoverridableOfEveryArityOfADefaultHeadReplacesIt() {
        configure(
            """
            defmodule M do
              def f(a, b \\ 1), do: {a, b}

              defoverridable f: 1, f: 2

              def f(a), do: a
              def f(a, b), do: {b, a}

              def run(y), do: f(y<caret>)
            end
            """
        )

        assertEquals(listOf("6 -> []", "7 -> []"), kept(incompleteCode = true))
    }

    /** A `defoverridable` written after a definition makes it overridable once the file changes. */
    fun testADefoverridableAddedAfterADefinitionMakesItOverridable() {
        configure(
            """
            defmodule M do
              def f(original), do: original
              def f(user), do: user

              def run(y), do: f(y<caret>)
            end
            """
        )
        assertEquals(listOf("2 -> []", "3 -> []"), kept(incompleteCode = false))

        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.getLineStartOffset(2), "  defoverridable f: 1\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(listOf("4 -> []"), kept(incompleteCode = false))
    }

    /** A `defoverridable` in another module's body, later in the file, doesn't follow the definition. */
    fun testADefoverridableInALaterModuleLeavesTheDefinitionTheUsers() {
        configure(
            """
            defmodule M do
              def f(original), do: original

              defoverridable f: 1

              def f(user), do: user

              def run(y), do: f(y<caret>)
            end

            defmodule N do
              def f(x), do: x

              defoverridable f: 1
            end
            """
        )

        assertEquals(listOf("6 -> []"), kept(incompleteCode = false))
    }

    /**
     * With Run the expander on, a definition in a module-body `if` is made overridable by a `defoverridable` after the
     * block, as `defexception`'s `exception/1` is, so Go To lands on the redefinition alone.
     */
    fun testWithTheExpanderOnADefinitionInAnIfIsReplacedByALaterOne() =
        withTheExpanderOn { assertADefinitionInABlockIsReplacedByALaterOne("if true") }

    fun testWithTheExpanderOnADefinitionInAnUnlessIsReplacedByALaterOne() =
        withTheExpanderOn { assertADefinitionInABlockIsReplacedByALaterOne("unless false") }

    private fun assertADefinitionInABlockIsReplacedByALaterOne(block: String) {
        configure(
            """
            defmodule M do
              $block do
                def f(original), do: original
              end

              defoverridable f: 1

              def f(user), do: user

              def run(y), do: f(y<caret>)
            end
            """
        )
        atName()

        assertEquals(listOf("preferred.ex:8"), goTo())
    }

    /** With Run the expander on, a definition in a module-body `if` that nothing redefines is stored back, and found. */
    fun testWithTheExpanderOnADefinitionInAnIfThatNothingRedefinesIsTheDefinition() = withTheExpanderOn {
        configure(
            """
            defmodule M do
              if true do
                def f(original), do: original
              end

              defoverridable f: 1

              def run(y), do: f(y<caret>)
            end
            """
        )
        atName()

        assertEquals(listOf("preferred.ex:3"), goTo())
    }

    /**
     * With Run the expander on, a definition made overridable, redefined and made overridable again keeps only the
     * redefinition, so Go To lands on it alone.
     */
    fun testWithTheExpanderOnAChainOfOverridableDefinitionsKeepsTheLast() = withTheExpanderOn {
        configure(
            """
            defmodule M do
              def f(a), do: a
              defoverridable f: 1
              def f(b), do: b
              defoverridable f: 1

              def run(y), do: f(y<caret>)
            end
            """
        )
        atName()

        assertEquals(listOf("preferred.ex:4"), goTo())
    }

    private fun configure(text: String) {
        myFixture.configureByText("preferred.ex", text.trimIndent())
    }

    /** The step keeps [expected] alone, whether or not code is complete. */
    private fun assertStepKeeps(expected: String) {
        for (incompleteCode in listOf(true, false)) {
            assertEquals("incompleteCode $incompleteCode", listOf(expected), kept(incompleteCode))
        }
    }

    /** What the step keeps at the call, as `line -> [lines of what it delegates to]`. */
    private fun kept(incompleteCode: Boolean): List<String> {
        val use = Use.of(myFixture.callAtCaret()) as Use.Named

        return preferred(use, sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, true), incompleteCode).entryLines()
    }

    /** Moves the caret from the arguments onto the call's name. */
    private fun atName() {
        myFixture.editor.caretModel.moveToOffset(myFixture.callAtCaret().functionNameElement()!!.textOffset)
    }

    private fun goTo(): List<String> =
        myFixture.gotoDeclarationTargetsAtCaret().orEmpty()
            .mapNotNull { it.destination }
            .map { "${it.containingFile.name}:${lineOf(it)}" }

    /** The chooser row of each function Symbol the call resolves to. */
    private fun labels(): List<String> =
        generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .map { PsiSymbolReferenceService.getService().getReferences(it) }
            .first { it.isNotEmpty() }
            .flatMap { it.resolveReference() }
            .filterIsInstance<FunctionSymbol>()
            .map { it.presentation().presentableText }
}
