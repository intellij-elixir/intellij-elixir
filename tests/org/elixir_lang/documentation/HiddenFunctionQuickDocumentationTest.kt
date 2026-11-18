package org.elixir_lang.documentation

import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
import java.io.File

/**
 * Elixir hides a function from its docs when its doc is `false`, or when it has none and `@impl` marks it a callback, or
 * its name starts with an underscore (`Module.compile_doc/6`, `Code.fetch_docs/1` gives `:hidden`). Quick Documentation
 * still shows a hidden function a call names, with its head and a line saying it isn't part of the public API, and
 * leaves it out of the listing of another module's functions.
 *
 * With Run the expander off a module that calls `use` shows its `@impl` and underscore functions with no line, as the
 * walk can't tell what a `use` leaves them: `Code.fetch_docs` on 1.20.4 hides them, and showing less is not wrong.
 */
class HiddenFunctionQuickDocumentationTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/every_arity"

    override fun setUp() {
        super.setUp()
        disableExpander(testRootDisposable)
    }

    private fun withTheExpanderOn(test: () -> Unit) {
        enableExpander(module, testRootDisposable)
        test()
    }

    // `@doc false`

    fun testADocFalseFunctionIsShownWithTheLineAndItsReason() = assertHiddenBy("@doc false", "N.<caret>a()", "a()")

    fun testADocFalseFunctionIsShownWithTheLineAndItsReasonWithTheExpanderOn() =
        withTheExpanderOn(::testADocFalseFunctionIsShownWithTheLineAndItsReason)

    fun testALocalCallOfAHiddenFunctionShowsTheLine() {
        val documentation = documentationOf(
            """
            defmodule Local do
              @doc false
              def hidden_here(), do: 1

              def run, do: hidden_<caret>here()
            end
            """.trimIndent()
        )

        assertTrue("Expected the head, got: $documentation", "hidden_here()" in documentation)
        assertTrue("Expected the line, got: $documentation", NOT_PUBLIC in documentation)
    }

    fun testALocalCallOfAHiddenFunctionShowsTheLineWithTheExpanderOn() =
        withTheExpanderOn(::testALocalCallOfAHiddenFunctionShowsTheLine)

    /** The line names the reason in the same markup as the rest of the popup. */
    fun testTheLineMarksTheReasonUp() {
        val documentation = documentationOf(caller("N.<caret>a()"), N)

        assertTrue("Expected the marked-up reason, got: $documentation", "Not part of the public API: <code>@doc false</code>." in documentation)
    }

    fun testTheLineMarksTheReasonUpWithTheExpanderOn() = withTheExpanderOn(::testTheLineMarksTheReasonUp)

    /** A macro is not "this function". */
    fun testAHiddenMacroIsNotCalledAFunction() {
        val documentation = documentationOf(caller("Mac.<caret>hidden_mac()"), "defmodule Mac do\n  @doc false\n  defmacro hidden_mac(), do: 1\nend\n")

        assertTrue("Expected the line, got: $documentation", NOT_PUBLIC in documentation)
        assertFalse("Expected the line not to call a macro a function, got: $documentation", "This function" in documentation)
    }

    fun testAHiddenMacroIsNotCalledAFunctionWithTheExpanderOn() = withTheExpanderOn(::testAHiddenMacroIsNotCalledAFunction)

    /** Elixir's docs hold public functions only, so a private function has no hidden status to report. */
    fun testAPrivateFunctionWithAnUnderscoreShowsNoLine() = assertLocalShowsNoLine("_<caret>helper()", "_helper()")

    fun testAPrivateFunctionWithAnUnderscoreShowsNoLineWithTheExpanderOn() = withTheExpanderOn(::testAPrivateFunctionWithAnUnderscoreShowsNoLine)

    fun testAPrivateFunctionWithDocFalseShowsNoLine() = assertLocalShowsNoLine("qu<caret>iet()", "quiet()")

    fun testAPrivateFunctionWithDocFalseShowsNoLineWithTheExpanderOn() = withTheExpanderOn(::testAPrivateFunctionWithDocFalseShowsNoLine)

    private fun assertLocalShowsNoLine(call: String, head: String) {
        val documentation = documentationOf(
            "defmodule Local do\n  defp _helper(), do: 1\n\n  @doc false\n  defp quiet(), do: 2\n\n  def run, do: $call\nend\n"
        )

        assertTrue("Expected the head $head, got: $documentation", head in documentation)
        assertFalse("Expected no line, got: $documentation", NOT_PUBLIC in documentation)
    }

    // `@impl`

    fun testAnImplTrueFunctionWithNoDocIsHidden() = assertHiddenBy("@impl", "N.<caret>cb()", "cb()")

    fun testAnImplTrueFunctionWithNoDocIsHiddenWithTheExpanderOn() = withTheExpanderOn(::testAnImplTrueFunctionWithNoDocIsHidden)

    fun testAnImplWithABehaviourFunctionWithNoDocIsHidden() = assertHiddenBy("@impl", "N.<caret>cb3()", "cb3()")

    fun testAnImplWithABehaviourFunctionWithNoDocIsHiddenWithTheExpanderOn() =
        withTheExpanderOn(::testAnImplWithABehaviourFunctionWithNoDocIsHidden)

    /** Every clause of a function is one function: `@impl` above its first clause hides it. */
    fun testAFunctionWhoseFirstClauseIsImplIsHidden() = assertHiddenBy("@impl", "N.<caret>cb6(1)", "cb6(")

    fun testAFunctionWhoseFirstClauseIsImplIsHiddenWithTheExpanderOn() = withTheExpanderOn(::testAFunctionWhoseFirstClauseIsImplIsHidden)

    /** `Module.compile_doc/6` runs for each clause, so a later one's `@impl` hides the function too. */
    fun testAFunctionWhoseLaterClauseIsImplIsHidden() = assertHiddenBy("@impl", "N.<caret>c1(1)", "c1(")

    fun testAFunctionWhoseLaterClauseIsImplIsHiddenWithTheExpanderOn() = withTheExpanderOn(::testAFunctionWhoseLaterClauseIsImplIsHidden)

    fun testAnImplFunctionWithADocIsDocumented() = assertShown("N.<caret>cb2()", N, "Cb2.", hidden = false)

    fun testAnImplFunctionWithADocIsDocumentedWithTheExpanderOn() = withTheExpanderOn(::testAnImplFunctionWithADocIsDocumented)

    fun testAnImplFalseFunctionIsNotHidden() = assertShown("N.<caret>plain()", N, "plain()", hidden = false)

    fun testAnImplFalseFunctionIsNotHiddenWithTheExpanderOn() = withTheExpanderOn(::testAnImplFalseFunctionIsNotHidden)

    /** The last `@impl` written stands: `@impl false` then `@impl true` marks a callback. */
    fun testTheLastImplWrittenStandsWhenItIsTrue() = assertHiddenBy("@impl", "N.<caret>stacked_true()", "stacked_true()")

    fun testTheLastImplWrittenStandsWhenItIsTrueWithTheExpanderOn() = withTheExpanderOn(::testTheLastImplWrittenStandsWhenItIsTrue)

    fun testTheLastImplWrittenStandsWhenItIsFalse() = assertShown("N.<caret>stacked_false()", N, "stacked_false()", hidden = false)

    fun testTheLastImplWrittenStandsWhenItIsFalseWithTheExpanderOn() = withTheExpanderOn(::testTheLastImplWrittenStandsWhenItIsFalse)

    /** An Erlang module is a behaviour too: `@impl :gen_server` marks a callback, as `@impl GenServer` does. */
    fun testAnImplWithAnErlangBehaviourFunctionWithNoDocIsHidden() = assertHiddenBy("@impl", "N.<caret>init(1)", "init(")

    fun testAnImplWithAnErlangBehaviourFunctionWithNoDocIsHiddenWithTheExpanderOn() =
        withTheExpanderOn(::testAnImplWithAnErlangBehaviourFunctionWithNoDocIsHidden)

    /** `@impl :gen_server` above one arity doesn't hide the arity of the name that has a doc. */
    fun testAnImplWithAnErlangBehaviourIsNotListedBesideAnotherAritysDoc() {
        val documentation = documentationOf(caller("N.<caret>init(1, 2, 3)"), N)

        assertTrue("Expected the documented arity, got: $documentation", "Handles." in documentation)
        assertFalse("Expected the hidden arity left out, got: $documentation", "init(arg, opts)" in documentation)
    }

    fun testAnImplWithAnErlangBehaviourIsNotListedBesideAnotherAritysDocWithTheExpanderOn() =
        withTheExpanderOn(::testAnImplWithAnErlangBehaviourIsNotListedBesideAnotherAritysDoc)

    /** `:false` is `false`: nothing is implemented. */
    fun testAnImplOfTheAtomFalseIsNotHidden() = assertShown("N.<caret>erlang_false()", N, "erlang_false()", hidden = false)

    fun testAnImplOfTheAtomFalseIsNotHiddenWithTheExpanderOn() = withTheExpanderOn(::testAnImplOfTheAtomFalseIsNotHidden)

    /** A hidden arity is left out of the listing of the arities of a name that another module's call names. */
    fun testAHiddenArityIsNotListedBesideAnotherAritysDoc() {
        val documentation = documentationOf("defmodule Caller do\n  def run, do: N.<caret>cb(1)\nend\n", N)

        assertTrue("Expected the documented arity, got: $documentation", "Cb one." in documentation)
        assertFalse("Expected no `cb()`, got: $documentation", "cb()" in documentation)
    }

    fun testAHiddenArityIsNotListedBesideAnotherAritysDocWithTheExpanderOn() =
        withTheExpanderOn(::testAHiddenArityIsNotListedBesideAnotherAritysDoc)

    // A leading underscore

    fun testAnUnderscoreFunctionWithNoDocIsHidden() = assertHiddenBy("underscore", "N.<caret>_under()", "_under()")

    fun testAnUnderscoreFunctionWithNoDocIsHiddenWithTheExpanderOn() = withTheExpanderOn(::testAnUnderscoreFunctionWithNoDocIsHidden)

    fun testADoubleUnderscoreFunctionWithNoDocIsHidden() = assertHiddenBy("underscore", "N.<caret>__dunder__()", "__dunder__()")

    fun testADoubleUnderscoreFunctionWithNoDocIsHiddenWithTheExpanderOn() =
        withTheExpanderOn(::testADoubleUnderscoreFunctionWithNoDocIsHidden)

    fun testAnUnderscoreFunctionWithADocIsDocumented() = assertShown("N.<caret>_under2()", N, "Under.", hidden = false)

    fun testAnUnderscoreFunctionWithADocIsDocumentedWithTheExpanderOn() = withTheExpanderOn(::testAnUnderscoreFunctionWithADocIsDocumented)

    // `defexception`

    fun testWhatDefexceptionDefinesIsHidden() = assertDefexceptionHidden("Plain.<caret>message(e)")

    fun testWhatDefexceptionDefinesIsHiddenWithTheExpanderOn() = withTheExpanderOn(::testWhatDefexceptionDefinesIsHidden)

    fun testTheExceptionFunctionDefexceptionDefinesIsHidden() = assertDefexceptionHidden("Plain.<caret>exception(m)")

    fun testTheExceptionFunctionDefexceptionDefinesIsHiddenWithTheExpanderOn() =
        withTheExpanderOn(::testTheExceptionFunctionDefexceptionDefinesIsHidden)

    fun testAnImplementedMessageOverDefexceptionIsHidden() = assertDefexceptionHidden("Oops.<caret>message(e)")

    fun testAnImplementedMessageOverDefexceptionIsHiddenWithTheExpanderOn() =
        withTheExpanderOn(::testAnImplementedMessageOverDefexceptionIsHidden)

    private fun assertDefexceptionHidden(call: String) = assertShown(call, EXCEPTIONS, null, hidden = true, parameters = "(e, m)")

    /** With the setting off the walk can't tell what `defexception` defined that the module's own `message/1` replaced. */
    fun testAnOverrideOfWhatDefexceptionDefinesShowsNoLineWithTheExpanderOff() =
        assertShown("Oops2.<caret>message(e)", EXCEPTIONS, "message(", hidden = false, parameters = "(e)")

    fun testAnOverrideOfWhatDefexceptionDefinesIsHiddenWithTheExpanderOn() = withTheExpanderOn {
        assertShown("Oops2.<caret>message(e)", EXCEPTIONS, null, hidden = true, parameters = "(e)")
    }

    // A module that calls `use`

    /** What `use` injects may document a function it defines, which a plain `def` over it keeps, so with no way to read it, nothing hides. */
    fun testAnImplFunctionOverAUsesDefaultIsNotHiddenWithTheExpanderOff() = assertShown("M.<caret>cb4()", M, "cb4()", hidden = false)

    fun testAnImplFunctionOverAUsesDocumentedDefaultKeepsItsDocWithTheExpanderOn() = withTheExpanderOn {
        assertShown("M.<caret>cb4()", M, "Orig.", hidden = false)
    }

    fun testAFunctionOverWhatAUseDefinesWithImplIsHiddenWithTheExpanderOn() = withTheExpanderOn {
        assertShown("M.<caret>o1()", M, null, hidden = true)
    }

    /** A `use` in a module nested in this one is the nested module's, so it leaves this module's own functions readable. */
    fun testAFunctionOfAModuleWhoseNestedModuleCallsUseIsHidden() = assertShown("Outer.<caret>cb()", NESTED, "cb()", hidden = true)

    fun testAFunctionOfAModuleWhoseNestedModuleCallsUseIsHiddenWithTheExpanderOn() =
        withTheExpanderOn(::testAFunctionOfAModuleWhoseNestedModuleCallsUseIsHidden)

    fun testAnUnderscoreFunctionOfAModuleWhoseNestedModuleCallsUseIsHidden() = assertShown("Outer.<caret>_under()", NESTED, "_under()", hidden = true)

    fun testAnUnderscoreFunctionOfAModuleWhoseNestedModuleCallsUseIsHiddenWithTheExpanderOn() =
        withTheExpanderOn(::testAnUnderscoreFunctionOfAModuleWhoseNestedModuleCallsUseIsHidden)

    /** The `@impl true` clause is written in a compiled quote, so the walk has no head for it. */
    fun testAGenServerCallbackShowsNoLine() {
        val documentation = documentationOf(
            """
            defmodule G do
              use GenServer

              @impl true
              def handle_call(r, f, s), do: {:reply, r, s}
            end

            defmodule Caller do
              def run(x), do: G.<caret>handle_call(x, x, x)
            end
            """.trimIndent()
        )

        assertTrue("Expected the head, got: $documentation", "handle_call(" in documentation)
        assertFalse("Expected no line, got: $documentation", NOT_PUBLIC in documentation)
    }

    fun testAGenServerCallbackShowsNoLineWithTheExpanderOn() = withTheExpanderOn(::testAGenServerCallbackShowsNoLine)

    // What a macro defines

    fun testAFunctionAMacroWritesAsDocFalseIsHiddenWithTheExpanderOn() = withTheExpanderOn {
        assertShown("Mine.<caret>hidden_one(1)", GEN, "hidden_one(", hidden = true)
    }

    fun testAFunctionAMacroNamesWithAnUnderscoreIsHiddenWithTheExpanderOn() = withTheExpanderOn {
        assertShown("Mine.<caret>_secret()", GEN, "_secret()", hidden = true)
    }

    fun testAFunctionAMacroNamesWithNoUnderscoreIsNotHiddenWithTheExpanderOn() = withTheExpanderOn {
        assertShown("Mine.<caret>open()", GEN, "open()", hidden = false)
    }

    /** A macro whose quote writes the head still takes the `@doc` above its call. */
    fun testADocAboveACallWhoseQuoteWritesAnUnderscoreHeadIsNotHiddenWithTheExpanderOn() = withTheExpanderOn {
        assertShown("Mine2.<caret>_helper()", WRITES_HEAD, "Public helper.", hidden = false)
    }

    fun testADocFalseAboveACallWhoseQuoteWritesAHeadIsHiddenWithTheExpanderOn() = withTheExpanderOn {
        assertShown("Mine2.<caret>quiet()", WRITES_HEAD, "quiet()", hidden = true)
    }

    fun testAnUnderscoreHeadAQuoteWritesWithNoDocIsHiddenWithTheExpanderOn() = withTheExpanderOn {
        assertShown("Mine2.<caret>_bare()", WRITES_HEAD, "_bare()", hidden = true)
    }

    /** A head a quote writes under its own `@doc false` is hidden for that reason, not for the call's. */
    fun testADocFalseAQuoteWritesNamesItAsTheReasonWithTheExpanderOn() = withTheExpanderOn {
        assertHiddenBy("@doc false", "Mine2.<caret>written_false()", "written_false()", WRITES_HEAD)
    }

    /** A head a quote writes under its own `@impl true` and no doc is hidden for that reason. */
    fun testAnImplTrueAQuoteWritesNamesItAsTheReasonWithTheExpanderOn() = withTheExpanderOn {
        assertHiddenBy("@impl", "Mine2.<caret>written_cb()", "written_cb()", WRITES_HEAD)
    }

    /** With the setting off, the `@doc` above the `use` that injects a head has no record to read, so its name's underscore does not hide it. */
    fun testADocAboveAUseWhoseQuoteWritesAnUnderscoreHeadIsNotHiddenWithTheExpanderOff() {
        assertShown("Mine3.<caret>_used()", WRITES_HEAD, "_used()", hidden = false)
    }

    /** A `@doc` whose value is another attribute can't be read, so it isn't read as no doc: an underscore name or an `@impl` doesn't hide it. */
    fun testADocTheReaderCannotReadIsNotNoDoc() {
        assertShown("Txt.<caret>_helper()", UNREADABLE_DOC, "_helper()", hidden = false)
        assertShown("Txt.<caret>txt_cb()", null, "txt_cb()", hidden = false)
    }

    fun testADocTheReaderCannotReadIsNotNoDocWithTheExpanderOn() = withTheExpanderOn(::testADocTheReaderCannotReadIsNotNoDoc)

    /** A compiled macro's definition that writes no doc keeps the `@doc false` of the one it replaced, as `Code.fetch_docs` says. */
    fun testACompiledMacrosDefinitionWithNoDocKeepsWhatItReplacedHiddenWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(UNQUOTED_NAME_MACRO_LIB, CompiledBuild("CompiledOverride"))

        assertShown("Over.<caret>f(1)", OVERRIDES_HIDDEN, "f/1", hidden = true)
    }

    // A compiled module's

    fun testACompiledHiddenArityIsShownWithTheLineWithDebugInfo() = assertCompiledHidden("EveryArityHidden")

    fun testACompiledHiddenArityIsShownWithTheLineFromTheDocsChunk() = assertCompiledHidden("EveryArityHiddenDocsOnly")

    private fun assertCompiledHidden(module: String) = assertShown("$module.pu<caret>sh(a, a)", null, "push(a, b)", hidden = true, parameters = "(a)")

    private fun assertHiddenBy(reason: String, call: String, head: String, definitions: String = N) {
        val documentation = documentationOf(caller(call), definitions)

        assertTrue("Expected the head $head, got: $documentation", head in documentation)
        assertTrue("Expected the line, got: $documentation", NOT_PUBLIC in documentation)
        assertTrue("Expected the reason $reason, got: $documentation", reason in documentation.substringAfter(NOT_PUBLIC))
        assertFalse("Expected no raw backtick, got: $documentation", '`' in documentation)
    }

    /**
     * The documentation of [call], written in a caller's `run` with [parameters], with [definitions], where given, in the
     * project: it shows [shows], where that is given, and the line exactly where [hidden].
     */
    private fun assertShown(call: String, definitions: String?, shows: String?, hidden: Boolean, parameters: String = "") {
        val documentation = documentationOf(caller(call, parameters), definitions)

        shows?.let { assertTrue("Expected $it, got: $documentation", it in documentation) }
        assertEquals("Expected the line exactly where hidden, got: $documentation", hidden, NOT_PUBLIC in documentation)
    }

    /** A module whose `run`, with [parameters], is [call]. */
    private fun caller(call: String, parameters: String = ""): String = "defmodule Caller do\n  def run$parameters, do: $call\nend\n"

    /** Quick Documentation at the caret in [caller], with [definitions] in the project. */
    private fun documentationOf(caller: String, definitions: String? = null): String {
        definitions?.let { myFixture.addFileToProject("definitions.ex", it) }
        myFixture.configureByText("caller.ex", caller)
        // Let the library's roots change and refresh finish before a gesture's background read waits on them.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        return myFixture.quickDocumentationAtCaret(project).orEmpty()
    }

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    private companion object {
        /** What the line says, before the reason it gives where it is known. */
        const val NOT_PUBLIC = "Not part of the public API"

        val N =
            """
            defmodule B do
              @callback cb() :: any
              @callback cb2() :: any
              @callback cb3() :: any
              @callback cb6(integer) :: any
              @callback c1(integer) :: any
              @callback plain() :: any
              @callback stacked_true() :: any
              @callback stacked_false() :: any
            end

            defmodule N do
              @behaviour B

              @doc false
              def a(), do: 1

              @impl true
              def cb(), do: 1

              @doc "Cb one."
              def cb(x), do: x

              @impl B
              def cb3(), do: 3

              @impl true
              def cb6(1), do: 1
              def cb6(2), do: 2

              def c1(1), do: 1
              @impl true
              def c1(2), do: 2

              def _under(), do: 1
              def __dunder__(), do: 1

              @impl true
              @doc "Cb2."
              def cb2(), do: 2

              @doc "Under."
              def _under2(), do: 2

              @impl false
              def plain(), do: 1

              @impl false
              @impl true
              def stacked_true(), do: 1

              @impl true
              @impl false
              def stacked_false(), do: 1

              @impl :gen_server
              def init(arg), do: {:ok, arg}

              @impl :gen_server
              def init(arg, opts), do: {:ok, {arg, opts}}

              @doc "Handles."
              def init(arg, opts, more), do: {:ok, {arg, opts, more}}

              @impl :false
              def erlang_false(), do: 1
            end
            """.trimIndent()

        val OVERRIDES_HIDDEN =
            """
            defmodule Used do
              defmacro __using__(_) do
                quote do
                  @doc false
                  def f(x), do: x

                  defoverridable f: 1
                end
              end
            end

            defmodule Over do
              use Used
              require CompiledOverride

              CompiledOverride."build f"()
            end
            """.trimIndent()

        val UNREADABLE_DOC =
            """
            defmodule TxtCb do
              @callback txt_cb() :: any
            end

            defmodule Txt do
              @behaviour TxtCb

              @text "Public helper."

              @doc @text
              def _helper(), do: :ok

              @impl true
              @doc @text
              def txt_cb(), do: :ok
            end
            """.trimIndent()

        val WRITES_HEAD =
            """
            defmodule Cb do
              @callback written_cb() :: any
            end

            defmodule Gen2 do
              defmacro written_false do
                quote do
                  @doc false
                  def written_false(), do: :ok
                end
              end

              defmacro written_impl do
                quote do
                  @impl true
                  def written_cb(), do: :ok
                end
              end

              defmacro helper do
                quote do
                  def _helper(), do: :ok
                end
              end

              defmacro hidden do
                quote do
                  def quiet(), do: :ok
                end
              end

              defmacro bare do
                quote do
                  def _bare(), do: :ok
                end
              end
            end

            defmodule Gen3 do
              defmacro __using__(_) do
                quote do
                  def _used(), do: :ok
                end
              end
            end

            defmodule Mine3 do
              @doc "Used."
              use Gen3
            end

            defmodule Mine2 do
              @behaviour Cb
              require Gen2

              Gen2.written_false()
              Gen2.written_impl()

              @doc "Public helper."
              Gen2.helper()

              @doc false
              Gen2.hidden()

              Gen2.bare()
            end
            """.trimIndent()

        val M =
            """
            defmodule B do
              @callback cb4() :: any
              @callback o1() :: any
            end

            defmodule U do
              defmacro __using__(_) do
                quote do
                  @doc "Orig."
                  def cb4(), do: :orig

                  defoverridable cb4: 0

                  @impl true
                  def o1(), do: :u

                  defoverridable o1: 0
                end
              end
            end

            defmodule M do
              use U
              @behaviour B

              @impl true
              def cb4(), do: :mine

              def o1(), do: :mine
            end
            """.trimIndent()

        val NESTED =
            """
            defmodule B do
              @callback cb() :: any
            end

            defmodule Outer do
              defmodule Inner do
                use GenServer

                def init(x), do: {:ok, x}
              end

              @behaviour B

              @impl true
              def cb(), do: 1

              def _under(), do: 1
            end
            """.trimIndent()

        val EXCEPTIONS =
            """
            defmodule Plain do
              defexception [:message]
            end

            defmodule Oops do
              defexception [:message]

              @impl true
              def message(e), do: e.message
            end

            defmodule Oops2 do
              defexception [:message]

              def message(e), do: e.message
            end
            """.trimIndent()

        val GEN =
            """
            defmodule Gen do
              defmacro hide(name) do
                quote do
                  @doc false
                  def unquote(name)(a), do: a
                end
              end

              defmacro under(name) do
                quote do
                  def unquote(name)(), do: 1
                end
              end
            end

            defmodule Mine do
              require Gen

              Gen.hide(:hidden_one)
              Gen.under(:_secret)
              Gen.under(:open)
            end
            """.trimIndent()
    }
}
