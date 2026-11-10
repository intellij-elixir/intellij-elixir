package org.elixir_lang.annotator

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.Disposer
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.IGNORED_VARIABLE
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.PARAMETER
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.PREDEFINED_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.TYPE
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.VARIABLE
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.declaration.DistinctKeyScheme
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk
import java.io.File

/** What the editor colours a call by, whatever the called thing is: a variable, a macro, a type, a `Kernel` function. */
class CallableHighlightingTest : BeamLibraryTestCase() {
    private lateinit var scheme: DistinctKeyScheme
    private lateinit var text: String

    override fun getTestDataPath(): String = EBIN.path

    override val ebinDirectory: File
        get() = EBIN

    override fun setUp() {
        super.setUp()
        scheme = DistinctKeyScheme(testRootDisposable)
    }

    fun testAVariableAnIgnoredVariableAndAParameterAtAUse() {
        configure(CODE)
        assertEquals(
            mapOf(
                "parameter" to setOf(PARAMETER),
                "variable" to setOf(VARIABLE),
                "ignored" to setOf(IGNORED_VARIABLE)
            ),
            mapOf(
                "parameter" to keysAt("variable = parameter", "parameter"),
                "variable" to keysAt("    variable\n", "variable"),
                "ignored" to keysAt("    _\n", "_")
            )
        )
    }

    fun testAnUnresolvedCallWithADoBlockIsAMacroCall() {
        configure(CODE)
        assertEquals(setOf(MACRO_CALL), keysAt("unresolved_macro do", "unresolved_macro"))
    }

    fun testABitStringOptionIsAType() {
        configure(CODE)
        assertEquals(setOf(TYPE), keysAt("1::integer", "integer"))
    }

    fun testACallToKernelIsPredefinedFromSource() {
        configure(SOURCE_KERNEL_CODE)

        assertEquals(setOf(FUNCTION_CALL, PREDEFINED_CALL), keysAt("Kernel.source_only(", "source_only"))
    }

    fun testACallToKernelIsPredefinedFromABeam() {
        configure(CODE)

        assertEquals(setOf(FUNCTION_CALL, PREDEFINED_CALL), keysAt("Kernel.abs(", "abs"))
    }

    fun testACallToAModuleDefinedInSourceIsNotPredefined() {
        configure(CODE)

        assertEquals(setOf(FUNCTION_CALL), keysAt("Target.one(", "one"))
    }

    fun testACallToAModuleDefinedInABeamIsNotPredefined() {
        configure(CODE)

        assertEquals(setOf(FUNCTION_CALL), keysAt(":elixir_interpolation.unescape_string(", "unescape_string"))
    }

    fun testACallToKernelSpecialFormsIsPredefinedFromSource() {
        configure(
            """
            defmodule Kernel.SpecialForms do
              def special_only(x), do: x
            end

            defmodule Source do
              def calls(x), do: Kernel.SpecialForms.special_only(x)
            end
            """.trimIndent() + "\n"
        )

        assertEquals(setOf(FUNCTION_CALL, PREDEFINED_CALL), keysAt("SpecialForms.special_only(", "special_only"))
    }

    fun testACallInAModuleWhoseNameEndsInKernelIsNotPredefined() {
        configure(
            """
            defmodule MyKernel do
              def f, do: :f
              def g, do: f()
            end

            defmodule Foo.Kernel do
              def h, do: :h
              def i, do: h()
            end
            """.trimIndent() + "\n"
        )

        assertEquals(
            mapOf("f" to setOf(FUNCTION_CALL), "h" to setOf(FUNCTION_CALL)),
            mapOf("f" to keysAt("g, do: f()", "f"), "h" to keysAt("i, do: h()", "h"))
        )
    }

    fun testTheNameInACaptureOfASourceFunctionIsAFunctionCall() {
        configure(
            """
            defmodule Capturing do
              def f(x), do: x

              def g do
                {&f/1, &Capturing.f/1}
              end
            end
            """.trimIndent() + "\n"
        )

        assertEquals(
            mapOf("local" to setOf(FUNCTION_CALL), "qualified" to setOf(FUNCTION_CALL)),
            mapOf("local" to keysAt("&f/1", "f"), "qualified" to keysAt("&Capturing.f/1", "f"))
        )
    }

    fun testTheNameInACaptureOfABeamFunctionIsAFunctionCall() {
        configure(
            """
            defmodule Capturing do
              def g do
                {&Kernel.abs/1, &abs/1, &:elixir_interpolation.unescape_string/1}
              end
            end
            """.trimIndent() + "\n"
        )

        assertEquals(
            mapOf(
                "qualified Kernel" to setOf(FUNCTION_CALL, PREDEFINED_CALL),
                "imported Kernel" to setOf(FUNCTION_CALL, PREDEFINED_CALL),
                "other module" to setOf(FUNCTION_CALL)
            ),
            mapOf(
                "qualified Kernel" to keysAt("&Kernel.abs/1", "abs"),
                "imported Kernel" to keysAt("&abs/1", "abs"),
                "other module" to keysAt("&:elixir_interpolation.unescape_string/1", "unescape_string")
            )
        )
    }

    fun testADelegationUseWithATargetTakesTheTargetsKey() {
        configure(CODE)

        assertEquals(setOf(FUNCTION_CALL), keysAt("    forwarded(", "forwarded"))
    }

    fun testAVariableNamedLikeAFunctionIsOnlyAVariable() {
        configure(CODE)

        assertEquals(setOf(PARAMETER), keysAt("fn snoc -> snoc", "snoc"))
    }

    fun testACallIntoAModuleWithTwoCopiesTakesTheirKeyWithTheExpanderOn() {
        expanderOn()
        myFixture.addFileToProject("lib/first.ex", "defmodule Twice do\n  def g(a), do: a\nend\n")
        myFixture.addFileToProject("lib/second.ex", "defmodule Twice do\n  def g(a), do: a\nend\n")
        configure("defmodule Caller do\n  def f, do: Twice.g(1)\nend\n")

        assertEquals(setOf(FUNCTION_CALL), keysAt("Twice.g(1)", "g"))
    }

    fun testACallPastAStopToANameOnlyTheIndexHoldsTakesItsKeyWithTheExpanderOn() {
        expanderOn()
        configure(
            """
            defmodule Stopped do
              1.f()
              def after_stop, do: later(1)
              def later(a), do: a
            end
            """.trimIndent() + "\n"
        )

        assertEquals(setOf(FUNCTION_CALL), keysAt("do: later(1)", "later"))
    }

    fun testAParameterNamedLikeAnotherModulesFunctionIsOnlyAParameter() {
        assertEquals(setOf(PARAMETER), keysAtParameterNamedLikeAFunction(""))
    }

    fun testAParameterNamedLikeAnotherModulesFunctionIsOnlyAParameterWithTheExpanderOnAndNoSdk() {
        enableExpander(testRootDisposable)

        assertEquals(setOf(PARAMETER), keysAtParameterNamedLikeAFunction(""))
    }

    fun testAParameterNamedLikeAnotherModulesFunctionIsOnlyAParameterPastAStopWithTheExpanderOn() {
        expanderOn()

        assertEquals(setOf(PARAMETER), keysAtParameterNamedLikeAFunction("  1.stop()\n"))
    }

    fun testACallAfterAnElixirRootedAliasTakesItsKeyWithTheExpanderOnAndNoSdk() {
        enableExpander(testRootDisposable)
        myFixture.addFileToProject("lib/target.ex", "defmodule Target do\n  def f(a), do: a\nend\n")
        configure("defmodule Caller do\n  def go, do: Elixir.Target.f(1)\nend\n")

        assertEquals(setOf(FUNCTION_CALL), keysAt("Elixir.Target.f(1)", "f"))
    }

    /** The keys on `f` where `def g(f), do: f` uses its parameter, in a module that starts with [start]. */
    private fun keysAtParameterNamedLikeAFunction(start: String): Set<TextAttributesKey> {
        myFixture.addFileToProject("lib/other.ex", "defmodule Other do\n  def f, do: 1\nend\n")
        configure("defmodule User do\n${start}  def g(f), do: f\nend\n")

        return keysAt("do: f", "f")
    }

    private fun expanderOn() {
        LegSdk.setUp(module, testRootDisposable)
        Disposer.register(testRootDisposable) { LegSdk.tearDown(module) }
        enableExpander(testRootDisposable)
    }

    private fun configure(code: String) {
        text = code
        myFixture.configureByText("highlighting.ex", code)
    }

    /** The keys on the last [name] in the first [marker]. */
    private fun keysAt(marker: String, name: String): Set<TextAttributesKey> {
        val offset = text.indexOf(marker) + marker.lastIndexOf(name)

        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributes != null && it.startOffset == offset && it.endOffset == offset + name.length }
            .flatMap { scheme.keys(it.forcedTextAttributes) }
            .toSet()
    }

    companion object {
        private val EBIN = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

        private val SOURCE_KERNEL_CODE = """
            defmodule Kernel do
              def source_only(x), do: x
            end

            defmodule Source do
              def calls(x), do: Kernel.source_only(x)
            end
        """.trimIndent() + "\n"

        private val CODE = """
            defmodule Target do
              def one(x), do: x
            end

            defmodule Source do
              defdelegate forwarded(x), to: Target, as: :one

              def snoc(q, x), do: {q, x}

              def uses(parameter) do
                variable = parameter
                _ = variable
                variable
                _
              end

              def calls(x) do
                unresolved_macro do
                  <<1::integer>>
                end

                Kernel.abs(x)
                Target.one(x)
                :elixir_interpolation.unescape_string(x)
                forwarded(x)
                fn snoc -> snoc end
              end
            end
        """.trimIndent() + "\n"
    }
}
