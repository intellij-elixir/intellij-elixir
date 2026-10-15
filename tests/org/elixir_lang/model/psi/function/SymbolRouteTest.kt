package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.model.psi.protocol.ProtocolFunction

/**
 * The Symbols a call, a capture and a `@spec` head resolve to, each as `Symbol file:line "label"`, and where Go To
 * lands from them, as `file:line: text of that line`.
 */
@Suppress("UnstableApiUsage")
class SymbolRouteTest : PlatformTestCase() {
    fun testDefIsTheDeclarationsSymbol() =
        assertUseIsTheDeclarationsSymbol("def f(a), do: a", "FunctionSymbol(M.f/1, macro=false)")

    fun testDefmacroIsTheDeclarationsSymbol() =
        assertUseIsTheDeclarationsSymbol("defmacro f(a), do: a", "FunctionSymbol(M.f/1, macro=true)", require = true)

    /** A guard is expanded at compile time, so it is presented as a macro. */
    fun testDefguardIsAMacro() =
        assertUseIsTheDeclarationsSymbol(
            "defguard f(a) when is_integer(a)",
            "FunctionSymbol(M.f/1, macro=true)",
            require = true
        )

    fun testUnresolvedDelegationIsTheDelegationsSymbol() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate f(a), to: Nowhere\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: M.<caret>f(1)\nend\n")

        assertEquals(listOf("FunctionSymbol(M.f/1, macro=false) m.ex:2 \"defdelegate f(a)\""), callSymbols())
        assertEquals(listOf("m.ex:2: defdelegate f(a), to: Nowhere"), goToAtCaret())
    }

    /** A delegation's label is its head as written, defaults included. */
    fun testUnresolvedDelegationWithDefaults() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate f(a, b \\\\ 1), to: Nowhere\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: M.<caret>f(1)\nend\n")

        assertEquals(listOf("FunctionSymbol(M.f/1, macro=false) m.ex:2 \"defdelegate f(a, b \\\\ 1)\""), callSymbols())
    }

    fun testDelegationWithTarget() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate x(a), to: T\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: M.<caret>x(1)\nend\n")

        assertEquals(listOf("FunctionSymbol(T.x/1, macro=false) t.ex:2 \"def x(a)\""), callSymbols())
        assertEquals(listOf("t.ex:2: def x(a), do: a"), goToAtCaret())
    }

    /** `M.x(1)` calls `T.x(1, 1)`. */
    fun testDelegationWithDefaultsCallsTheTargetWithEveryArgument() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a, b), do: {a, b}\nend\n")
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate x(a, b \\\\ 1), to: T\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: M.<caret>x(1)\nend\n")

        assertEquals(listOf("FunctionSymbol(T.x/2, macro=false) t.ex:2 \"def x(a, b)\""), callSymbols())
        assertEquals(listOf("t.ex:2: def x(a, b), do: {a, b}"), goToAtCaret())
    }

    fun testGeneratedException() {
        myFixture.addFileToProject("e.ex", "defmodule E do\n  defexception [:message]\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: E.<caret>exception(\"m\")\nend\n")

        assertEquals(listOf("FunctionSymbol(E.exception/1, macro=false) e.ex:2 \"E.exception/1\""), callSymbols())
        assertEquals(listOf("e.ex:2: defexception [:message]"), goToAtCaret())
    }

    fun testUserExceptionOverGenerated() {
        myFixture.addFileToProject(
            "e.ex",
            "defmodule E do\n  defexception [:message]\n\n  def exception(msg), do: %E{message: msg}\nend\n"
        )
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: E.<caret>exception(\"m\")\nend\n")

        assertEquals(
            listOf("FunctionSymbol(E.exception/1, macro=false) e.ex:4 \"def exception(msg)\""),
            callSymbols()
        )
        assertEquals(listOf("e.ex:4: def exception(msg), do: %E{message: msg}"), goToAtCaret())
    }

    /** A call is an EEx function's declaration only when it calls `EEx`'s macro. */
    fun testEExFunction() {
        myFixture.addFileToProject("t.ex", eexFunction)
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: T.<caret>hello(1)\nend\n")

        assertEquals(emptyList<String>(), callSymbols())
    }

    fun testEExFunctionWithEExModule() {
        addEEx()
        myFixture.addFileToProject("t.ex", eexFunction)
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: T.<caret>hello(1)\nend\n")

        assertEquals(listOf("FunctionSymbol(T.hello/1, macro=false) t.ex:3 \"T.hello/1\""), callSymbols())
        assertEquals(listOf("t.ex:3: EEx.function_from_string(:def, :hello, \"<%= a %>\", [:a])"), goToAtCaret())
    }

    fun testEmbeddedTemplate() {
        addMixGenerator()
        myFixture.addFileToProject("t.ex", embedded)
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: T.<caret>log_template(a: 1)\nend\n")

        assertEquals(listOf("FunctionSymbol(T.log_template/1, macro=false) t.ex:4 \"T.log_template/1\""), callSymbols())
    }

    fun testEmbeddedText() {
        addMixGenerator()
        myFixture.addFileToProject("t.ex", embedded)
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: T.<caret>error_text()\nend\n")

        assertEquals(listOf("FunctionSymbol(T.error_text/0, macro=false) t.ex:5 \"T.error_text/0\""), callSymbols())
    }

    fun testProtocolFunction() {
        myFixture.addFileToProject("p.ex", "defprotocol P do\n  def f(a)\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: P.<caret>f(1)\nend\n")

        assertEquals(listOf("ProtocolFunction(P.f/1) p.ex:2"), callSymbols())
    }

    /** A qualified `unquote` can reach any function of the module, so it names none. */
    fun testQualifiedUnquoteNamesNothing() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  def f(a), do: a\nend\n")
        myFixture.configureByText(
            "use.ex",
            "defmodule U do\n  defmacro run(name) do\n    quote do: M.<caret>unquote(name)(1)\n  end\nend\n"
        )

        assertEquals(emptyList<String>(), callSymbols())
    }

    fun testANameWithNoAtomValueNamesNothing() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  def f(a), do: a\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: M.<caret>\"f#{1}\"(1)\nend\n")

        assertEquals(emptyList<String>(), callSymbols())
    }

    fun testQualifiedCaptureNamesTheCapturesArity() {
        myFixture.addFileToProject("m.ex", twoArities)
        myFixture.configureByText("capture.ex", "defmodule C do\n  def run, do: &M.<caret>f/2\nend\n")

        assertEquals(listOf("FunctionSymbol(M.f/2, macro=false) m.ex:3 \"def f(a, b)\""), captureSymbols())
        assertEquals(listOf("m.ex:3: def f(a, b), do: a + b"), goToAtCaret())
    }

    fun testLocalCaptureNamesTheCapturesArity() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def f(), do: 0\n  def f(a, b), do: a + b\n\n  def run, do: &<caret>f/2\nend\n"
        )

        assertEquals(listOf("FunctionSymbol(M.f/2, macro=false) m.ex:3 \"def f(a, b)\""), captureSymbols())
        assertEquals(listOf("m.ex:3: def f(a, b), do: a + b"), goToAtCaret())
    }

    fun testCaptureOfAnEExFunction() {
        addEEx()
        myFixture.addFileToProject("t.ex", eexFunction)
        myFixture.configureByText("capture.ex", "defmodule C do\n  def run, do: &T.<caret>hello/1\nend\n")

        assertEquals(listOf("FunctionSymbol(T.hello/1, macro=false) t.ex:3 \"T.hello/1\""), captureSymbols())
    }

    fun testCaptureOfADelegationWithDefaultsNamesTheTarget() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a, b), do: {a, b}\nend\n")
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate x(a, b \\\\ 1), to: T\nend\n")
        myFixture.configureByText("capture.ex", "defmodule C do\n  def run, do: &M.<caret>x/1\nend\n")

        assertEquals(listOf("FunctionSymbol(T.x/2, macro=false) t.ex:2 \"def x(a, b)\""), captureSymbols())
    }

    fun testSpecNamesItsFunction() {
        myFixture.configureByText(
            "s.ex",
            "defmodule S do\n  @spec <caret>map(t) :: t\n  def map(x), do: x\n  def map(x, y), do: {x, y}\nend\n"
        )

        assertEquals(listOf("FunctionSymbol(S.map/1, macro=false) s.ex:3 \"def map(x)\""), specSymbols())
    }

    /** A `@spec` specifies the delegation itself, not what it delegates to. */
    fun testSpecNamesADelegation() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
        myFixture.configureByText("s.ex", "defmodule S do\n  @spec <caret>x(t) :: t\n  defdelegate x(a), to: T\nend\n")

        assertEquals(listOf("FunctionSymbol(S.x/1, macro=false) s.ex:3 \"defdelegate x(a)\""), specSymbols())
    }

    fun testSpecNamesAnEExFunction() {
        addEEx()
        myFixture.configureByText(
            "s.ex",
            "defmodule S do\n  require EEx\n  @spec <caret>hello(term) :: String.t()\n" +
                "  EEx.function_from_string(:def, :hello, \"<%= a %>\", [:a])\nend\n"
        )

        assertEquals(listOf("FunctionSymbol(S.hello/1, macro=false) s.ex:4 \"S.hello/1\""), specSymbols())
    }

    /** A `@spec` of an embedded template names the one Symbol its head declares. */
    fun testSpecNamesAnEmbeddedFunction() {
        addMixGenerator()
        myFixture.configureByText(
            "s.ex",
            "defmodule S do\n  require Mix.Generator\n  @spec <caret>log_template(keyword) :: String.t()\n" +
                "  Mix.Generator.embed_template(:log, \"Log\")\nend\n"
        )
        val specified = symbolsAtCaret<SpecFunctionReference>()

        assertEquals(1, specified.size)
        assertEquals("the head's Symbol", declaredSymbols(myFixture.file, myFixture.file.text.indexOf(":log") + 1), specified)
        assertEquals(listOf("s.ex:4: Mix.Generator.embed_template(:log, \"Log\")"), goToAtCaret())
    }

    fun testSpecDoesNotNameAnImportedFunction() {
        myFixture.addFileToProject("enum.ex", "defmodule Enum do\n  def map(e, f), do: f.(e)\nend\n")
        myFixture.configureByText(
            "s.ex",
            "defmodule S do\n  import Enum\n\n  @spec <caret>map(t, f) :: t\n  def other(x), do: x\nend\n"
        )

        assertEquals(emptyList<String>(), specSymbols())
    }

    private val eexFunction = "defmodule T do\n  require EEx\n  EEx.function_from_string(:def, :hello, \"<%= a %>\", [:a])\nend\n"

    private val embedded =
        "defmodule T do\n  require Mix.Generator\n\n  Mix.Generator.embed_template(:log, \"Log\")\n" +
            "  Mix.Generator.embed_text(:error, \"Error\")\nend\n"

    private fun addEEx() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
    }

    private fun addMixGenerator() {
        myFixture.addFileToProject(
            "mix_generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_template(name, contents), do: {name, contents}\n" +
                "  defmacro embed_text(name, contents), do: {name, contents}\nend\n"
        )
    }

    private val twoArities = "defmodule M do\n  def f(), do: 0\n  def f(a, b), do: a + b\nend\n"

    /** The use's Symbol is the one its declaration declares. */
    private fun assertUseIsTheDeclarationsSymbol(definition: String, expected: String, require: Boolean = false) {
        val declaring = "defmodule M do\n  $definition\nend\n"
        myFixture.addFileToProject("m.ex", declaring)
        val prefix = if (require) "  require M\n\n" else ""
        myFixture.configureByText("use.ex", "defmodule U do\n$prefix  def run, do: M.<caret>f(1)\nend\n")

        val used = symbolsAtCaret<FunctionCallReference>()
        val file = psiManager.findFile(myFixture.findFileInTempDir("m.ex"))!!

        assertEquals(listOf(expected), used.map(Symbol::toString))
        assertEquals("the declaration's Symbol", used, declaredSymbols(file, declaring.indexOf(" f(") + 1))
    }

    private fun declaredSymbols(file: PsiFile, offset: Int): List<Symbol> =
        generateSequence(file.findElementAt(offset)) { it.parent }
            .takeWhile { it !is PsiFile }
            .map { element -> FunctionSymbolDeclarationProvider().getDeclarations(element, offset - element.textRange.startOffset) }
            .first { it.isNotEmpty() }
            .map { it.symbol }

    private fun callSymbols(): List<String> = symbolsAtCaret<FunctionCallReference>().map(::render)

    private fun captureSymbols(): List<String> = symbolsAtCaret<CaptureFunctionReference>().map(::render)

    private fun specSymbols(): List<String> = symbolsAtCaret<SpecFunctionReference>().map(::render)

    /** What the one [R] on the innermost element at the caret that has one resolves to. */
    private inline fun <reified R : PsiSymbolReference> symbolsAtCaret(): List<Symbol> =
        generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .map { element -> PsiSymbolReferenceService.getService().getReferences(element).filterIsInstance<R>() }
            .first { it.isNotEmpty() }
            .single()
            .resolveReference()
            .toList()

    private fun goToAtCaret(): List<String> =
        myFixture.gotoDeclarationTargetsAtCaret().orEmpty().mapNotNull { it.destination?.let(::describe) }.sorted()

    private fun render(symbol: Symbol): String =
        when (symbol) {
            is FunctionSymbol ->
                "$symbol ${line(symbol.file, symbol.range.startOffset)} \"${symbol.presentation().presentableText}\""
            is ProtocolFunction ->
                "ProtocolFunction(${symbol.protocolName}.${symbol.name}/${symbol.arity}) " +
                    line(symbol.file, symbol.range.startOffset)
            else -> symbol.toString()
        }

    private fun line(file: PsiFile, offset: Int): String =
        "${file.name}:${file.text.substring(0, offset).count { it == '\n' } + 1}"

    /** [element] as `file:line: text of that line`. */
    private fun describe(element: PsiElement): String {
        val file = element.containingFile
        val text = file.text
        val offset = element.textOffset
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it == -1) text.length else it }

        return "${line(file, offset)}: ${text.substring(lineStart, lineEnd).trim()}"
    }
}
