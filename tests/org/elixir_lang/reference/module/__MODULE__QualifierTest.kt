package org.elixir_lang.reference.module

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.expander.ExpanderSourceRows
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.QualifiedAlias
import org.elixir_lang.psi.__MODULE__
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.call.maybeModularNameToModulars
import org.elixir_lang.psi.stub.index.ModularName
import org.elixir_lang.psi.stub.type.call.Stub

/**
 * Tests for qualified aliases whose qualifier is a `__MODULE__` call, e.g.
 * `__MODULE__.Endpoint.config_change(changed, removed)` inside `defmodule MyApp`.
 *
 * The `Module` reference for `__MODULE__.Endpoint` lives on the whole [QualifiedAlias] node, and
 * resolution starts from [QualifiedAlias.fullyQualifiedName].  `getName()` for a [QualifiedAlias]
 * is the raw source text, which for an Alias-only chain happens to equal the resolvable module
 * name - but for a call qualifier the text (`__MODULE__.Endpoint`) matches nothing in the module
 * name index.  The qualifier must be resolved to the enclosing module so the fully-qualified name
 * becomes `MyApp.Endpoint`.
 */
@Suppress("ClassName") // named for the `__MODULE__` special form, matching `org.elixir_lang.psi.__MODULE__`
class __MODULE__QualifierTest : BeamLibraryTestCase() {
    override val ebinDirectory = ERLANG_STDLIB_EBIN

    private fun qualifiedAliasWithText(text: String): QualifiedAlias =
        PsiTreeUtil.findChildrenOfType(myFixture.file, QualifiedAlias::class.java)
            .single { it.text == text }

    private fun callWithText(text: String = "__MODULE__"): Call =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == text }

    private fun resolvedModulars(): List<Call> = resolvedCalls(__MODULE__.reference(callWithText()))

    private fun resolvedCalls(reference: PsiReference?): List<Call> =
        (reference as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element as? Call }

    private fun resolvedCallsAtCaret(): List<Call> = resolvedCalls(myFixture.file.findReferenceAt(myFixture.caretOffset))

    private fun resolvedModularTexts(): List<String> = firstLines(resolvedModulars())

    private fun firstLines(elements: Iterable<PsiElement>): List<String> = elements.map { it.text.lineSequence().first() }

    private fun resolvedModularTexts(text: String): List<String> {
        myFixture.configureByText("outer.ex", text.trimIndent())

        return resolvedModularTexts()
    }

    /**
     * The stub names a module by expanding `__MODULE__` in its text; the reference resolves `__MODULE__` through PSI.
     * The module the reference resolves to must be the one the stub's canonical name for [written] expanded it to.
     */
    private fun assertStubNameAgreesWithReference(written: String) {
        val named = generateSequence(callWithText().parent) { it.parent }
            .filterIsInstance<Call>()
            .first { Stub.isModular(it) }
        val expanded = written.replace("__MODULE__", SyntacticCall.of(resolvedModulars().single()).canonicalName()!!)

        assertTrue(
            "the stub index does not name `${named.text.lineSequence().first()}` `$expanded`",
            StubIndex.getElements(
                ModularName.KEY,
                expanded,
                project,
                GlobalSearchScope.fileScope(myFixture.file),
                NamedElement::class.java
            ).any { it == named }
        )
    }

    fun testFullyQualifiedNameExpandsModuleQualifier() {
        myFixture.configureByText(
            "my_app.ex",
            """
            defmodule MyApp do
              def config_change(changed, removed) do
                __MODULE__.Endpoint.config_change(changed, removed)
              end
            end
            """.trimIndent()
        )

        assertEquals(
            "MyApp.Endpoint",
            qualifiedAliasWithText("__MODULE__.Endpoint").fullyQualifiedName()
        )
    }

    /**
     * The qualifier expansion must recurse: in `__MODULE__.Foo.Bar` the outer qualified alias's
     * qualifier is itself a qualified alias (`__MODULE__.Foo`) whose own qualifier is the call.
     */
    fun testFullyQualifiedNameExpandsNestedModuleQualifier() {
        myFixture.configureByText(
            "my_app.ex",
            """
            defmodule MyApp do
              def child_spec(arg) do
                __MODULE__.Foo.Bar.child_spec(arg)
              end
            end
            """.trimIndent()
        )

        assertEquals(
            "MyApp.Foo.Bar",
            qualifiedAliasWithText("__MODULE__.Foo.Bar").fullyQualifiedName()
        )
    }

    fun testReferenceResolvesToModuleDefinition() {
        myFixture.addFileToProject(
            "endpoint.ex",
            """
            defmodule MyApp.Endpoint do
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "my_app.ex",
            """
            defmodule MyApp do
              def config_change(changed, removed) do
                __MODULE__.Endpoint.config_change(changed, removed)
              end
            end
            """.trimIndent()
        )

        val reference = qualifiedAliasWithText("__MODULE__.Endpoint").reference as PsiPolyVariantReference?
        assertNotNull("__MODULE__.Endpoint must have a Module reference", reference)

        val resolvedTexts = reference!!
            .multiResolve(false)
            .mapNotNull { it.element?.text }
        assertTrue(
            "__MODULE__.Endpoint inside defmodule MyApp must resolve to " +
                "`defmodule MyApp.Endpoint`. Resolved: $resolvedTexts",
            resolvedTexts.any { it.startsWith("defmodule MyApp.Endpoint") }
        )
    }

    /**
     * Inside `defmodule Outer`, Elixir evaluates `__MODULE__` in `defimpl __MODULE__.P, for: X` as
     * `Outer`: the `defimpl` call's own arguments are not inside its `do` block.
     */
    fun testDefimplProtocolArgumentModuleQualifierIsEnclosingModule() {
        myFixture.addFileToProject(
            "outer_p.ex",
            """
            defmodule Outer do
              defprotocol P do
                def f(t)
              end
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "outer.ex",
            """
            defmodule Outer do
              defimpl __MODULE__.<caret>P, for: X do
                def f(_), do: :ok
              end
            end
            """.trimIndent()
        )

        val targets = firstLines(GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset).asList())

        assertEquals(
            "__MODULE__ resolves to; __MODULE__.P is; Go to Declaration on P reaches",
            listOf(listOf("defmodule Outer do"), "Outer.P", listOf("defprotocol P do")),
            listOf(
                resolvedModularTexts(),
                qualifiedAliasWithText("__MODULE__.P").fullyQualifiedName(),
                targets
            )
        )

        assertStubNameAgreesWithReference("__MODULE__.P.X")
    }

    /** `for: __MODULE__` in a `defimpl` inside `defmodule Outer` is `Outer`. */
    fun testDefimplForArgumentModuleIsEnclosingModule() {
        assertEquals(
            listOf("defmodule Outer do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  defprotocol P do
                    def f(t)
                  end

                  defimpl Outer.P, for: __MODULE__ do
                    def f(_), do: :ok
                  end
                end
                """
            )
        )

        assertStubNameAgreesWithReference("Outer.P.__MODULE__")
    }

    fun testDefimplKeywordBodyProtocolArgumentModuleIsEnclosingModule() {
        assertEquals(
            listOf("defmodule Outer do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  defimpl __MODULE__.P, for: X, do: (def f(_), do: :ok)
                end
                """
            )
        )

        assertStubNameAgreesWithReference("__MODULE__.P.X")
    }

    fun testDefmoduleNameArgumentModuleIsEnclosingModule() {
        assertEquals(
            listOf("defmodule Outer do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  defmodule __MODULE__.Inner do
                  end
                end
                """
            )
        )

        assertStubNameAgreesWithReference("__MODULE__.Inner")
    }

    fun testDefprotocolNameArgumentModuleIsEnclosingModule() {
        assertEquals(
            listOf("defmodule Outer do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  defprotocol __MODULE__.P do
                    def f(t)
                  end
                end
                """
            )
        )

        assertStubNameAgreesWithReference("__MODULE__.P")
    }

    fun testModuleCreateNameArgumentModuleIsEnclosingModule() {
        assertEquals(
            listOf("defmodule Outer do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  Module.create(__MODULE__.X, quote(do: nil), __ENV__)
                end
                """
            )
        )
    }

    fun testDefmoduleInACallsArgumentsNameArgumentModuleIsEnclosingModule() {
        assertEquals(
            listOf("defmodule Outer do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  IO.inspect(defmodule __MODULE__.Sub do
                  end)
                end
                """
            )
        )

        assertStubNameAgreesWithReference("__MODULE__.Sub")
    }

    fun testDefmoduleInModuleCreatesContentsNameArgumentModuleIsEnclosingModule() {
        assertEquals(
            listOf("defmodule Outer do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  Module.create(Inner, (defmodule __MODULE__.Child do
                  end; nil), __ENV__)
                end
                """
            )
        )

        assertStubNameAgreesWithReference("__MODULE__.Child")
    }

    fun testDefmoduleInAnUnquoteInAModuleInAQuoteNameArgumentModuleIsTheQuotesModule() {
        assertEquals(
            listOf("defmodule U do"),
            resolvedModularTexts(
                """
                defmodule U do
                  defmacro __using__(_) do
                    quote do
                      defmodule Inner do
                        unquote(defmodule __MODULE__.X do
                        end)
                      end
                    end
                  end
                end
                """
            )
        )

        assertStubNameAgreesWithReference("__MODULE__.X")
    }

    fun testDefmoduleInAModuleInAnUnquoteNameArgumentModuleIsThatModule() {
        assertEquals(
            listOf("defmodule Inner do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  quote(do: unquote((defmodule Inner do
                    defmodule __MODULE__.Y do
                    end
                  end; :ok)))
                end
                """
            )
        )

        assertStubNameAgreesWithReference("__MODULE__.Y")
    }

    fun testAsNameOfAModuleQualifiedAliasResolvesToTheModule() =
        assertEquals(
            listOf("defmodule Repro.Socket do"),
            resolvedAfterAliasIn("alias __MODULE__.Socket, as: Sock\n\n  def new, do: %<caret>Sock{}")
        )

    fun testAModuleQualifiedAliasResolvesToTheModule() =
        assertEquals(
            listOf("defmodule Repro.Socket do"),
            resolvedAfterAliasIn("alias __MODULE__.Socket\n\n  def new, do: %<caret>Socket{}")
        )

    /** The modules the caret's reference resolves to in `defmodule Repro`, whose body is [body], with `Repro.Socket` in its own file. */
    private fun resolvedAfterAliasIn(body: String): List<String> {
        myFixture.addFileToProject("socket.ex", "defmodule Repro.Socket do\n  defstruct []\nend\n")
        myFixture.configureByText("repro.ex", "defmodule Repro do\n  $body\nend\n")

        return resolvedCallsAtCaret()
            .filter { Stub.isModular(it) }
            .let(::firstLines)
    }

    fun testQuoteArgumentModuleIsEnclosingModule() {
        assertEquals(
            listOf("defmodule Outer do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  defmacro __using__(_) do
                    quote bind_quoted: [module: __MODULE__] do
                      def f, do: module
                    end
                  end
                end
                """
            )
        )
    }

    fun testDefimplDoBlockModuleIsImplementation() {
        assertEquals(
            listOf("defimpl P, for: X do"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  defimpl P, for: X do
                    def f(_), do: __MODULE__
                  end
                end
                """
            )
        )
    }

    fun testDefimplDoKeywordModuleIsImplementation() {
        assertEquals(
            listOf("defimpl P, for: X, do: (def f(_), do: __MODULE__)"),
            resolvedModularTexts(
                """
                defmodule Outer do
                  defimpl P, for: X, do: (def f(_), do: __MODULE__)
                end
                """
            )
        )
    }

    fun testABareModuleInAQuoteWithNoUseNamesNoModule() =
        assertEquals(
            emptyList<String>(),
            lookupTexts(
                """
                defmodule L do
                  defmacro m do
                    quote do
                      def run, do: __MODULE__
                    end
                  end
                end
                """,
                "__MODULE__"
            )
        )

    fun testAnUnquotedModuleInAQuoteNamesTheQuotingModule() =
        assertEquals(
            listOf("defmodule L do"),
            lookupTexts(
                """
                defmodule L do
                  def helper(x), do: x

                  defmacro m do
                    quote do
                      def run, do: unquote(__MODULE__).helper(1)
                    end
                  end
                end
                """,
                "unquote(__MODULE__)"
            )
        )

    fun testAnUnquotedModuleInAModuleAQuoteWritesNamesTheQuotingModule() =
        assertEquals(
            listOf("defmodule L do"),
            lookupTexts(
                """
                defmodule L do
                  def helper(x), do: x

                  defmacro w do
                    quote do
                      defmodule Inner do
                        def run, do: unquote(__MODULE__).helper(1)
                      end
                    end
                  end
                end
                """,
                "unquote(__MODULE__)"
            )
        )

    fun testAnAliasAfterAliasingModuleInAQuoteResolves() =
        assertEquals(listOf("defmodule L do"), resolvedAfterAliasingModuleInAQuote("m"))

    fun testAnAliasAfterAliasingModuleInAUsingQuoteResolves() =
        assertEquals(
            listOf("defmodule L do"),
            resolvedAfterAliasingModuleInAQuote("__using__(_)", caller = "\n\ndefmodule U do\n  use L\nend")
        )

    /** What `L.f()` after `alias __MODULE__` in `defmacro [macroHead]`'s `quote` resolves to, then [caller]. */
    private fun resolvedAfterAliasingModuleInAQuote(macroHead: String, caller: String = ""): List<String> =
        resolvedAtCaretWithSpecialForms(
            """
            defmodule L do
              def f, do: 1

              defmacro $macroHead do
                quote do
                  alias __MODULE__
                  def run, do: <caret>L.f()
                end
              end
            end
            """.trimIndent() + caller
        )

    fun testAnAliasAfterAliasingModuleInAnAtomNamedModuleResolves() =
        assertEquals(
            listOf("defmodule Bar do"),
            resolvedAtCaretWithSpecialForms(
                """
                defmodule Bar do
                  def x, do: 1
                end

                defmodule :foo do
                  alias __MODULE__
                  def run, do: <caret>Bar.x()
                end
                """
            )
        )

    fun testAnAliasAfterAliasingModuleInAModuleNamedByUnquoteResolves() =
        assertEquals(
            listOf("defmodule Bar do"),
            resolvedAtCaretWithSpecialForms(
                """
                defmodule Bar do
                  def x, do: 1
                end

                defmodule W do
                  defmacro w(name) do
                    quote do
                      defmodule unquote(name) do
                        alias __MODULE__
                        def run, do: <caret>Bar.x()
                      end
                    end
                  end
                end
                """
            )
        )

    fun testCompletionAfterAnUnquotedModuleInAQuoteOffersTheQuotingModulesFunctions() {
        configureWithSpecialForms(
            """
            defmodule L do
              def f, do: 1
              def helper(x), do: x

              defmacro m do
                quote do
                  def run, do: unquote(__MODULE__).<caret>
                end
              end
            end
            """
        )
        assertEquals(listOf("f", "helper", "m"), walkCompletion())
    }

    fun testCompletionAfterAnUnquotedErlangModuleOffersWhatTheModuleItselfOffers() {
        val quoted = "defmodule L do\n  defmacro m do\n    quote(do: %s)\n  end\nend\n"
        configureWithSpecialForms(quoted.format(":math.<caret>"))
        val direct = walkCompletion()

        myFixture.configureByText("unquoted.ex", quoted.format("unquote(:math).<caret>"))

        assertTrue("`:math.` offers `pi`: $direct", direct.orEmpty().contains("pi"))
        assertEquals(direct, walkCompletion())
    }

    /** The completion at the caret as the walk answers it, which reads the module lookup; the switch's expander doesn't. */
    private fun walkCompletion(): List<String>? =
        ExpanderSourceRows.withoutExpanderSwitch { myFixture.completionStringsAtCaret()?.sorted() }

    /** What the call written [lookup] in [text] names as a module, with `Kernel.SpecialForms.__MODULE__/0` in source. */
    private fun lookupTexts(text: String, lookup: String): List<String> {
        configureWithSpecialForms(text)

        return firstLines(callWithText(lookup).maybeModularNameToModulars())
    }

    private fun resolvedAtCaretWithSpecialForms(text: String): List<String> {
        configureWithSpecialForms(text)

        return firstLines(resolvedCallsAtCaret())
    }

    private fun configureWithSpecialForms(text: String) {
        myFixture.addFileToProject("special_forms.ex", "defmodule Kernel.SpecialForms do\n  defmacro __MODULE__, do: nil\nend\n")
        myFixture.configureByText("lib.ex", text.trimIndent())
    }
}
