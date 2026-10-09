package org.elixir_lang.model.psi.words

import com.intellij.find.usages.api.PsiUsage
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.lang.injection.InjectedLanguageManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.settings.ElixirExperimentalSettings

/**
 * Find Usages finds a name however it is spelled: an atom written with an escape (`:"fo\x6f"` is `:foo`), an operator,
 * or an identifier written decomposed (`cafe\u0301` is `café`). Each spelling has a plain-spelling control.
 */
class SpellingFindUsagesTest : PlatformTestCase() {
    private var originalEnableHtmlInjection = false

    override fun setUp() {
        super.setUp()
        originalEnableHtmlInjection = ElixirExperimentalSettings.instance.state.enableHtmlInjection
        ElixirExperimentalSettings.instance.state.enableHtmlInjection = true
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(ElixirSigilInjector(), testRootDisposable)
    }

    override fun tearDown() {
        try {
            ElixirExperimentalSettings.instance.state.enableHtmlInjection = originalEnableHtmlInjection
        } finally {
            super.tearDown()
        }
    }

    private val callback = "defmodule B do\n  @callback <caret>run(term) :: term\nend\n"

    fun testCallbackImplementationPlain() =
        assertFound(callback, "defmodule I do\n  @behaviour B\n  def run(x), do: x\nend\n", "def run(x), do: x")

    fun testDefoverridableKeyPlain() =
        assertFound(callback, "defmodule I do\n  @behaviour B\n  def run(x), do: x\n  defoverridable [run: 1]\nend\n", "defoverridable [run: 1]")

    fun testDefoverridableKeyEscaped() =
        assertFound(
            callback,
            "defmodule I do\n  @behaviour B\n  def run(x), do: x\n  defoverridable [\"r\\x75n\": 1]\nend\n",
            "defoverridable [\"r\\x75n\": 1]"
        )

    private val protocol = "defprotocol P do\n  def <caret>size(x)\nend\n"

    fun testProtocolCallPlain() = assertFound(protocol, "defmodule C do\n  def c(x), do: P.size(x)\nend\n", "def c(x), do: P.size(x)")

    fun testProtocolCallEscaped() =
        assertFound(protocol, "defmodule C do\n  def c(x), do: P.\"si\\x7ae\"(x)\nend\n", "def c(x), do: P.\"si\\x7ae\"(x)")

    fun testProtocolImplementationPlain() =
        assertFound(protocol, "defimpl P, for: Integer do\n  def size(_x), do: 0\nend\n", "def size(_x), do: 0")

    private val function = "defmodule M do\n  def <caret>foo(x), do: x\nend\n"

    fun testQualifiedCallPlain() = assertFound(function, "defmodule C do\n  def c, do: M.foo(1)\nend\n", "def c, do: M.foo(1)")

    fun testQualifiedCallEscaped() =
        assertFound(function, "defmodule C do\n  def c, do: M.\"fo\\x6f\"(1)\nend\n", "def c, do: M.\"fo\\x6f\"(1)")

    fun testCapturePlain() = assertFound(function, "defmodule C do\n  def c, do: &M.foo/1\nend\n", "def c, do: &M.foo/1")

    fun testCaptureEscaped() =
        assertFound(function, "defmodule C do\n  def c, do: &M.\"fo\\x6f\"/1\nend\n", "def c, do: &M.\"fo\\x6f\"/1")

    fun testMfaPlain() = assertFound(function, "defmodule C do\n  def c, do: {M, :foo, 1}\nend\n", "def c, do: {M, :foo, 1}")

    fun testMfaEscaped() =
        assertFound(function, "defmodule C do\n  def c, do: {M, :\"fo\\x6f\", 1}\nend\n", "def c, do: {M, :\"fo\\x6f\", 1}")

    fun testApplyPlain() = assertFound(function, "defmodule C do\n  def c, do: apply(M, :foo, [1])\nend\n", "def c, do: apply(M, :foo, [1])")

    fun testApplyEscaped() =
        assertFound(function, "defmodule C do\n  def c, do: apply(M, :\"fo\\x6f\", [1])\nend\n", "def c, do: apply(M, :\"fo\\x6f\", [1])")

    /** Rename replaces the name an atom spells, not a part of the atom's syntax. */
    fun testMfaPlainRenames() = assertRenamed("{M, :foo, 1}", "{M, :renamed, 1}")

    fun testMfaEscapedRenames() = assertRenamed("{M, :\"fo\\x6f\", 1}", "{M, :\"renamed\", 1}")

    fun testApplyEscapedRenames() = assertRenamed("apply(M, :\"fo\\x6f\", [1])", "apply(M, :\"renamed\", [1])")

    fun testQualifiedCallEscapedRenames() = assertRenamed("M.\"fo\\x6f\"(1)", "M.renamed(1)")

    fun testCaptureEscapedRenames() = assertRenamed("&M.\"fo\\x6f\"/1", "&M.renamed/1")

    fun testTypeEscapedRenames() =
        assertRenamedInPlace(
            "defmodule T do\n  @type <caret>t :: integer\n  @type u :: T.\"\\x74\"()\nend\n",
            "defmodule T do\n  @type renamed :: integer\n  @type u :: T.renamed()\nend\n"
        )

    fun testVariableNfdReadRenames() =
        assertRenamedInPlace(
            "defmodule V do\n  def f do\n    <caret>caf\u00e9 = 1\n    cafe\u0301 + 1\n  end\nend\n",
            "defmodule V do\n  def f do\n    renamed = 1\n    renamed + 1\n  end\nend\n"
        )

    fun testAttributeNfdReadRenames() =
        assertRenamedInPlace(
            "defmodule A do\n  @<caret>caf\u00e9 1\n  def r, do: @cafe\u0301\nend\n",
            "defmodule A do\n  @renamed 1\n  def r, do: @renamed\nend\n"
        )

    fun testImportOnlyKeyPlain() = assertFound(function, "defmodule C do\n  import M, only: [foo: 1]\nend\n", "import M, only: [foo: 1]")

    fun testImportOnlyKeyEscaped() =
        assertFound(function, "defmodule C do\n  import M, only: [\"fo\\x6f\": 1]\nend\n", "import M, only: [\"fo\\x6f\": 1]")

    fun testOperatorCall() =
        assertFound(
            "defmodule M do\n  def a <<caret>~> b, do: {a, b}\nend\n",
            "defmodule C do\n  import M\n  def c, do: 1 <~> 2\nend\n",
            "def c, do: 1 <~> 2"
        )

    fun testFamilyClausePlain() =
        assertFound("defmodule M do\n  def <caret>foo(0), do: 0\n  def foo(n), do: n\nend\n", null, "def foo(n), do: n")

    fun testTypePlain() = assertFound("defmodule T do\n  @type <caret>t :: integer\n  @type u :: T.t()\nend\n", null, "@type u :: T.t()")

    fun testTypeEscaped() =
        assertFound("defmodule T do\n  @type <caret>t :: integer\n  @type u :: T.\"\\x74\"()\nend\n", null, "@type u :: T.\"\\x74\"()")

    fun testVariableNfcRead() =
        assertFound("defmodule V do\n  def f do\n    <caret>caf\u00e9 = 1\n    caf\u00e9 + 1\n  end\nend\n", null, "caf\u00e9 + 1")

    fun testVariableNfdRead() =
        assertFound("defmodule V do\n  def f do\n    <caret>caf\u00e9 = 1\n    cafe\u0301 + 1\n  end\nend\n", null, "cafe\u0301 + 1")

    fun testAttributeNfcRead() =
        assertFound("defmodule A do\n  @<caret>caf\u00e9 1\n  def r, do: @caf\u00e9\nend\n", null, "def r, do: @caf\u00e9")

    fun testAttributeNfdRead() =
        assertFound("defmodule A do\n  @<caret>caf\u00e9 1\n  def r, do: @cafe\u0301\nend\n", null, "def r, do: @cafe\u0301")

    fun testFunctionNfdCall() =
        assertFound("defmodule M do\n  def <caret>caf\u00e9(x), do: x\nend\n", "defmodule C do\n  def c, do: M.cafe\u0301(1)\nend\n", "def c, do: M.cafe\u0301(1)")

    fun testQuotedRemoteNameOfOneLetter() =
        assertFound("defmodule M do\n  def <caret>x, do: 1\nend\n", "defmodule C do\n  def c, do: M.\"\\x78\"()\nend\n", "def c, do: M.\"\\x78\"()")

    /** The global literal search never finds a name over 100 characters, so only the decoded half lists it. */
    fun testLongFunctionNameFoundOnce() {
        val name = "a".repeat(101)

        assertFoundOnce(
            "defmodule M do\n  def <caret>$name(x), do: x\nend\n",
            "defmodule C do\n  def c, do: M.$name(1)\nend\n",
            "def c, do: M.$name(1)"
        )
    }

    /** A local search scans text, so it finds a long name literally and the decoded half must not list it again. */
    fun testLongVariableFoundOnce() {
        val name = "a".repeat(101)

        assertFoundOnce(
            "defmodule V do\n  def f do\n    <caret>$name = 1\n    $name + 1\n  end\nend\n",
            null,
            "$name + 1"
        )
    }

    fun testOperatorInPlainSigil() =
        assertFound(
            "defmodule M do\n  def a <<caret>~> b, do: {a, b}\nend\n",
            "defmodule C do\n  import M\n  def render(assigns) do\n    ~H\"\"\"\n    <p>{1 <~> 2}</p>\n    \"\"\"\n  end\nend\n",
            "<p>{1 <~> 2}</p>"
        )

    /** The same escaped call in each kind of file that holds Elixir. */
    fun testTemplates() {
        val call = """M."fo\x6f"(1)"""
        val rows = mapOf(
            "using.eex" to "<p><%= $call %></p>\n",
            "using.html.eex" to "<p><%= $call %></p>\n",
            "using.html.leex" to "<p><%= $call %></p>\n",
            "using.html.heex" to "<p><%= $call %></p>\n",
            "using.heex" to "<p>{$call}</p>\n",
            "using.ex" to sigilModule("H", "<p>{$call}</p>"),
            "using_e.ex" to sigilModule("E", "<p><%= $call %></p>"),
            "using_l.ex" to sigilModule("L", "<p><%= $call %></p>")
        )
        val missed = rows.filter { (name, text) ->
            val lines = usageLines("defmodule M do\n  def <caret>foo(x), do: x\nend\n", name, text)

            lines.none { call in it }
        }.keys

        assertTrue("Find Usages lists `$call` in every row; it missed $missed", missed.isEmpty())
    }

    /** With HTML injection off a `~H`, `~E` or `~L` is not a host, so neither half lists a use inside it. */
    fun testTemplatesWithInjectionOff() = assertNotListedWithInjectionOff("H", "<p>{CALL}</p>")

    fun testEexSigilWithInjectionOff() = assertNotListedWithInjectionOff("E", "<p><%= CALL %></p>")

    fun testLiveViewEexSigilWithInjectionOff() = assertNotListedWithInjectionOff("L", "<p><%= CALL %></p>")

    private fun assertNotListedWithInjectionOff(sigil: String, body: String) {
        ElixirExperimentalSettings.instance.state.enableHtmlInjection = false

        val call = """M."fo\x6f"(1)"""
        val lines = usageLines(
            "defmodule M do\n  def <caret>foo(x), do: x\nend\n",
            "using.ex",
            sigilModule(sigil, body.replace("CALL", call))
        )

        assertTrue("with injection off Find Usages lists `$call` inside ~$sigil: $lines", lines.none { call in it })
    }

    /** An operator, which no words index holds, inside an EEx sigil. */
    fun testOperatorInEexSigil() =
        assertFound(
            "defmodule M do\n  def a <<caret>~> b, do: {a, b}\nend\n",
            sigilModule("L", "<p><%= 1 <~> 2 %></p>"),
            "<p><%= 1 <~> 2 %></p>"
        )

    /** A call spelled with a micro sign inside a `~H` is listed once, by the decoded half, not again by the tag search. */
    fun testMicroSignCallInsideHSigilIsFoundOnce() =
        assertFoundOnce(
            "defmodule M do\n  def <caret>src_µ(x), do: x\nend\n",
            sigilModule("H", "<p>{M.src_µ(1)}</p>"),
            "<p>{M.src_µ(1)}</p>"
        )

    /** A call in a documentation code block, spelled with a decomposed `é`, is listed and renamed. */
    fun testDecomposedCallInDocumentationCodeBlock() {
        val doctest = "iex> M.cafe\u0301(1)"

        assertFound(
            "defmodule M do\n  def <caret>caf\u00e9(x), do: x\nend\n",
            documentedModule(doctest),
            doctest
        )
    }

    fun testPlainCallInDocumentationCodeBlock() =
        assertFound(
            "defmodule M do\n  def <caret>caf\u00e9(x), do: x\nend\n",
            documentedModule("iex> M.caf\u00e9(1)"),
            "iex> M.caf\u00e9(1)"
        )

    fun testDecomposedCallInDocumentationCodeBlockRenames() {
        val using = myFixture.addFileToProject("using.ex", documentedModule("iex> M.cafe\u0301(1)"))
        myFixture.configureByText("declaring.ex", "defmodule M do\n  def <caret>caf\u00e9(x), do: x\nend\n")
        myFixture.renameTargetAtCaret("renamed")

        assertEquals(documentedModule("iex> M.renamed(1)"), using.text)
    }

    private fun documentedModule(doctest: String) =
        "defmodule C do\n  @doc \"\"\"\n      $doctest\n  \"\"\"\n  def c, do: 1\nend\n"

    private fun sigilModule(name: String, body: String) =
        "defmodule C do\n  import M\n  def render(assigns) do\n    ~$name\"\"\"\n    $body\n    \"\"\"\n  end\nend\n"

    /** Renaming `foo` from its definition rewrites [use], a line of another file, to [renamed]. */
    private fun assertRenamed(use: String, renamed: String) {
        val using = myFixture.addFileToProject("using.ex", "defmodule C do\n  def c, do: $use\nend\n")
        myFixture.configureByText("declaring.ex", function)
        myFixture.renameTargetAtCaret("renamed")

        assertEquals("defmodule C do\n  def c, do: $renamed\nend\n", using.text)
    }

    /** Renaming at the caret of [source] gives [expected]. */
    private fun assertRenamedInPlace(source: String, expected: String) {
        myFixture.configureByText("declaring.ex", source)
        myFixture.renameTargetAtCaret("renamed")

        assertEquals(expected, myFixture.file.text)
    }

    /** Find Usages at the caret in [declaring] lists [line], a line of [using] (or of [declaring] when it is `null`). */
    private fun assertFound(declaring: String, using: String?, line: String) {
        val lines = usageLines(declaring, using?.let { "using.ex" }, using)

        assertTrue("Find Usages lists `$line`; it gave $lines", line in lines)
    }

    private fun assertFoundOnce(declaring: String, using: String?, line: String) {
        val lines = usageLines(declaring, using?.let { "using.ex" }, using)

        assertEquals("Find Usages lists `$line` once; it gave $lines", 1, lines.count { it == line })
    }

    /** The trimmed host line of each usage in the file Find Usages is run to find, not counting its declaration. */
    @Suppress("UnstableApiUsage")
    private fun usageLines(declaring: String, usingName: String?, using: String?): List<String> {
        if (usingName != null && using != null) myFixture.addFileToProject(usingName, using)
        myFixture.configureByText("declaring.ex", declaring)
        val injected = InjectedLanguageManager.getInstance(project)

        return myFixture.psiUsagesAtCaret(project)
            .filterNot { it.declaration && using != null }
            .map { usage -> hostLine(injected, usage) }
            .filter { (name, _) -> if (using == null) name == "declaring.ex" else name == usingName }
            .map { it.second }
    }

    @Suppress("UnstableApiUsage")
    private fun hostLine(injected: InjectedLanguageManager, usage: PsiUsage): Pair<String, String> {
        val inFragment = injected.isInjectedFragment(usage.file)
        val file = if (inFragment) injected.getInjectionHost(usage.file)!!.containingFile else usage.file
        val range = if (inFragment) injected.injectedToHost(usage.file, usage.range) else usage.range
        val document = file.viewProvider.document!!
        val number = document.getLineNumber(range.startOffset)
        val line = document.charsSequence.subSequence(document.getLineStartOffset(number), document.getLineEndOffset(number))

        return file.name to line.toString().trim()
    }
}
