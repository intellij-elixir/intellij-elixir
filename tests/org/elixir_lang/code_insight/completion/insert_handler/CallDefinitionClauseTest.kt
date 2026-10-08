package org.elixir_lang.code_insight.completion.insert_handler

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.completeCandidateAtCaret
import org.elixir_lang.code_insight.completeSoleCandidateAtCaret

/**
 * Accepting a call-definition-clause completion whose target takes parameters must insert those
 * parameters as editable placeholders (`name(a, b)`, tabbable) instead of an empty `()` - the empty
 * form creates a call at an arity nothing defines, and every code-intelligence feature goes dark on
 * it. Zero-parameter targets are unaffected: they still get a bare `()`.
 *
 * Covers every site that attaches [CallDefinitionClause] (this insert handler): the two pre-existing
 * ones (remote/BEAM qualified completion, `defdelegate` from [org.elixir_lang.code_insight.completion.ModuleFunctionLookupElements])
 * and the seven local/unqualified ones in [org.elixir_lang.psi.scope.call_definition_clause.Variants]
 * that used to attach none at all.
 */
class CallDefinitionClauseTest : PlatformTestCase() {

    /* Zero-arity targets: unaffected by this change. */

    fun testZeroArityLocalCallStillInsertsBareParentheses() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def zero, do: :ok

                  def run do
                    ze<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected a bare `zero()`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("zero()")
        )
    }

    /* Source call-definition clause, local unqualified (Variants.executeOnCallDefinitionClause). */

    fun testMultiArityLocalCallInsertsParameterPlaceholders() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def add(augend, addend), do: augend + addend

                  def run do
                    ad<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `add(augend, addend)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("add(augend, addend)")
        )
    }

    /**
     * The inserted parameters must be a genuine live template - selectable and tabbable, per the
     * issue's own words - not literal text that merely looks right. Checked through the platform's own
     * [com.intellij.codeInsight.template.impl.TemplateState], the reliable way to observe this
     * headlessly: a finished or absent state would mean literal text, not a running template.
     *
     * Driving the session with simulated keystrokes ([org.elixir_lang.code_insight.completeCandidateAtCaret]'s
     * own `myFixture.type`) was tried and dropped - replacement across a `Tab` was unreliable in this
     * harness for reasons that did not point at the production code (the segment advances correctly per
     * [com.intellij.codeInsight.template.impl.TemplateState.getCurrentVariableNumber], only the typed
     * text's landing was inconsistent), so this test stops at what it can assert without flaking.
     */
    fun testInsertedParametersAreLiveTemplatePlaceholders() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)

        myFixture.configureByText(
            "template_test.ex",
            """
                defmodule Test do
                  def add(augend, addend), do: augend + addend

                  def run do
                    ad<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = TemplateManagerImpl.getTemplateState(myFixture.editor)
        assertNotNull("Expected a live template session for the inserted parameters", state)
        assertFalse("Template should still be running, stopped at the first parameter", state!!.isFinished)
        assertEquals("Expected to start at the first parameter", 0, state.currentVariableNumber)
    }

    /**
     * A default-valued parameter (`name \\ default`) must insert as the bare `name` - the head's own
     * `\\` default-value operation is only legal syntax in a definition head, and its raw text (what
     * [org.elixir_lang.code_insight.Signature] keeps unstripped for the Parameter Info hint) is an
     * invalid call-site expression if inserted verbatim.
     */
    fun testLocalCallWithDefaultValuedParameterStripsDefaultOnInsert() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def greet(name \\ "World"), do: name

                  def run do
                    gre<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `greet(name)`, not the raw default-value text; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("greet(name)")
        )
    }

    /* defdelegate, local unqualified (Variants.executeOnDelegation). */

    fun testLocalDelegateInsertsParameterPlaceholders() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  defdelegate double(x), to: SomeModule

                  def run do
                    dou<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `double(x)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("double(x)")
        )
    }

    fun testLocalDelegateWithDefaultValuedParameterStripsDefaultOnInsert() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  defdelegate values(map \\ %{}), to: SomeModule

                  def run do
                    val<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `values(map)`, not the raw default-value text; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("values(map)")
        )
    }

    fun testLocalZeroArityDelegateStillInsertsBareParentheses() {
        myFixture.configureByFiles("defdelegate.ex", "to.ex")

        myFixture.completeCandidateAtCaret("source")

        assertTrue(
            "Expected a bare `source()`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("source()")
        )
    }

    /* @callback/@macrocallback implementation scaffold, local (Variants.executeOnCallback). Mixes a
       typed parameter (`request :: term()`) with untyped ones, so the type annotation must be
       stripped down to the bare name. */

    fun testLocalCallbackInsertsParameterPlaceholdersStrippingTypeAnnotations() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule WithCallback do
                  @callback handle_call(request :: term(), from, state) :: term()

                  def run do
                    han<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `handle_call(request, from, state)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("handle_call(request, from, state)")
        )
    }

    /* EEx.function_from_file/string, local (Variants.executeOnEExFunctionFrom). The parameter names
       come from the macro's own `[:a, :b]` argument-name list, not from any PSI head. */

    fun testLocalEExFunctionFromInsertsDeclaredArgumentNamesAsPlaceholders() {
        myFixture.configureByFiles("eex_function.ex", "eex.ex")

        myFixture.completeCandidateAtCaret("function_from_file_sample")

        assertTrue(
            "Expected `function_from_file_sample(a, b)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("function_from_file_sample(a, b)")
        )
    }

    fun testLocalEExFunctionFromInsertsQuotedArgumentNamesWithoutTheirQuotes() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule EEx do
                  defmacro function_from_string(kind, name, source, args \\ [], options \\ []) do
                    quote do
                      unquote(kind)
                      unquote(name)
                    end
                  end
                end

                defmodule Test do
                  require EEx

                  EEx.function_from_string(:def, :quoted_sample, "<%= a %><%= b %>", [:"a", :"b"])

                  def run do
                    quoted_sa<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `quoted_sample(a, b)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("quoted_sample(a, b)")
        )
    }

    fun testLocalEExFunctionFromKeepsASlotForAnArgumentWhoseNameIsUnknown() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule EEx do
                  defmacro function_from_string(kind, name, source, args \\ [], options \\ []) do
                    quote do
                      unquote(kind)
                      unquote(name)
                    end
                  end
                end

                defmodule Test do
                  require EEx

                  EEx.function_from_string(:def, :unknown_sample, "<%= b %>", [:"a#{1}", :b])

                  def run do
                    unknown_sa<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `unknown_sample(arg, b)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("unknown_sample(arg, b)")
        )
    }

    /* defexception's exception/1 and message/1 hooks, local (Variants.executeOnException). Fixed,
       hardcoded parameter names - the same ones the completion renderer's tail text already shows. */

    fun testLocalExceptionHookInsertsFixedParameterName() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule MyException do
                  defexception [:message]

                  def extra do
                    exc<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `exception(message)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("exception(message)")
        )
    }

    fun testLocalExceptionMessageHookInsertsFixedParameterName() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule MyException do
                  defexception [:message]

                  def extra do
                    mes<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `message(exception)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("message(exception)")
        )
    }

    /* Mix.Generator's embed_template/embed_text, local (Variants.executeOnMixGeneratorEmbed).
       embed_template takes `assigns`; embed_text takes nothing, so it stays a bare `()`. */

    fun testLocalEmbedTemplateInsertsAssignsPlaceholder() {
        myFixture.configureByFiles("mix_generator_embed_template_usage.ex", "mix_generator.ex")

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected `log_template(assigns)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("log_template(assigns)")
        )
    }

    fun testLocalEmbedTextStillInsertsBareParentheses() {
        myFixture.configureByFiles("mix_generator_embed.ex", "mix_generator.ex")

        myFixture.completeCandidateAtCaret("error_text")

        assertTrue(
            "Expected a bare `error_text()`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("error_text()")
        )
    }

    /* Remote/BEAM qualified completion - the first pre-existing attachment site. Reuses the fixture
       [testPrefersBareFunctionHeadSignature] already pins for presentation. */

    fun testRemoteQualifiedCallInsertsParameterPlaceholders() {
        myFixture.configureByFiles("bare_head_preferred_usage.ex", "bare_head_preferred_declaration.ex")

        myFixture.completeCandidateAtCaret("map_every")

        assertTrue(
            "Expected `map_every(enumerable, nth, fun)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("map_every(enumerable, nth, fun)")
        )
    }

    /* A capture `&name/arity` must never gain parentheses - it names a function, it does not call
       one. [org.elixir_lang.reference.CaptureNameArityCompletionTest.testSoleCandidateAutoInserts]
       is the primary guard, but it only exercises a zero-arity target, which never reaches the
       placeholder path either way. This drives a nonzero-arity target through the same capture
       completion, so a regression that reattaches this handler there would show up as
       `&combine(a, b)/2` instead of the bare name. */

    fun testCaptureOfMultiArityFunctionStaysBare() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def combine(a, b), do: a + b

                  def run do
                    &com<caret>/2
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        assertTrue(
            "Expected a bare `&combine/2`, no parameters; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("&combine/2")
        )
    }

    /* Two parameters sharing a name (`_` is the common Elixir idiom for "ignored") must still be two
       independent tab stops. The live-template engine mirrors same-named variables - typing into one
       silently overwrites the other - unless each tab stop's internal identity is kept distinct from
       its displayed default text. Checked through the template's own segment count/text rather than
       simulated keystrokes, for the same flakiness reasons documented on
       [testInsertedParametersAreLiveTemplatePlaceholders]. */

    fun testDuplicateParameterNamesGetIndependentTabStops() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)

        myFixture.configureByText(
            "duplicate_parameter_names_test.ex",
            """
                defmodule Test do
                  def area(_, _), do: 0

                  def run do
                    are<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = TemplateManagerImpl.getTemplateState(myFixture.editor)
        assertNotNull("Expected a live template session for the inserted parameters", state)
        assertEquals(
            "Expected two independent tab stops even though both parameters are named `_`",
            2,
            state!!.template.variableCount
        )
        assertTrue(
            "Expected `area(_, _)`; got:\n${myFixture.file.text}",
            myFixture.file.text.contains("area(_, _)")
        )
    }

    /* An `import` that brings in only some arities of a definition with defaults. */

    fun testImportOnlyLowerArityDropsDefault() =
        assertImportedInsertion(
            "def snoc(q, x \\\\ nil), do: {q, x}",
            "only: [snoc: 1]",
            "sno",
            "snoc(q)"
        )

    fun testImportOnlyMiddleArityDropsLastDefault() =
        assertImportedInsertion(
            "def fill(a \\\\ 1, b \\\\ 2, c), do: {a, b, c}",
            "only: [fill: 2]",
            "fil",
            "fill(a, c)"
        )

    fun testImportOnlyZeroArityDropsEveryDefault() =
        assertImportedInsertion(
            "def snoc(q \\\\ [], x \\\\ nil), do: {q, x}",
            "only: [snoc: 0]",
            "sno",
            "snoc()"
        )

    fun testImportExceptFullArityDropsDefault() =
        assertImportedInsertion(
            "def snoc(q, x \\\\ nil), do: {q, x}",
            "except: [snoc: 2]",
            "sno",
            "snoc(q)"
        )

    fun testImportOnlyLowerArityOfBodilessHeadDropsDefault() =
        assertImportedInsertion(
            """
            def snoc(a \\ nil, b \\ nil)
            def snoc(a, b) when is_nil(a), do: {a, b}
            def snoc(a, b), do: {a, b}
            """.trimIndent(),
            "only: [snoc: 1]",
            "sno",
            "snoc(a)"
        )

    fun testImportOnlyLowerArityOfMacroDropsDefault() =
        assertImportedInsertion(
            "defmacro snoc(q, x \\\\ nil), do: quote(do: {unquote(q), unquote(x)})",
            "only: [snoc: 1]",
            "sno",
            "snoc(q)"
        )

    fun testImportOnlyLowerArityOfDelegateDropsDefault() =
        assertImportedInsertion(
            "defdelegate snoc(q, x \\\\ nil), to: Target",
            "only: [snoc: 1]",
            "sno",
            "snoc(q)"
        )

    fun testImportOnlySeveralLowerAritiesInsertsHighest() =
        assertImportedInsertion(
            "def fill(a \\\\ 1, b \\\\ 2, c), do: {a, b, c}",
            "only: [fill: 1, fill: 2]",
            "fil",
            "fill(a, c)"
        )

    fun testImportWholeOfDelegateInsertsFullHead() =
        assertImportedInsertion(
            "defdelegate snoc(q, x \\\\ nil), to: Target",
            null,
            "sno",
            "snoc(q, x)"
        )

    fun testFunctionImportNarrowsModuleImport() {
        val text = { use: String ->
            listOf(
                "defmodule Imported do",
                "  def snoc(q, x \\\\ nil), do: {q, x}",
                "end",
                "",
                "defmodule Caller do",
                "  import Imported",
                "",
                "  def run do",
                "    import Imported, only: [snoc: 1]",
                "",
                "    $use",
                "  end",
                "end"
            ).joinToString("\n")
        }

        myFixture.configureByText("imported_defaults.ex", text("sno<caret>"))

        myFixture.completeSoleCandidateAtCaret()

        assertEquals(text("snoc(q)"), myFixture.file.text)
    }

    fun testImportWholeInsertsFullHead() =
        assertImportedInsertion(
            "def snoc(q, x \\\\ nil), do: {q, x}",
            null,
            "sno",
            "snoc(q, x)"
        )

    private fun assertImportedInsertion(definition: String, options: String?, prefix: String, inserted: String) {
        val import = listOfNotNull("import Imported", options).joinToString(", ")
        val text = { use: String ->
            listOf(
                "defmodule Imported do",
                definition.prependIndent("  "),
                "end",
                "",
                "defmodule Caller do",
                "  $import",
                "",
                "  def run do",
                "    $use",
                "  end",
                "end"
            ).joinToString("\n")
        }

        myFixture.configureByText("imported_defaults.ex", text("$prefix<caret>"))

        myFixture.completeSoleCandidateAtCaret()

        assertEquals(text(inserted), myFixture.file.text)
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/code_insight/completion/contributor/call_definition_clause"
}
