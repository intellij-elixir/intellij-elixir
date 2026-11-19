package org.elixir_lang.reference.module

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.QualifiedAlias
import org.elixir_lang.psi.__MODULE__
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
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
class __MODULE__QualifierTest : PlatformTestCase() {

    private fun qualifiedAliasWithText(text: String): QualifiedAlias =
        PsiTreeUtil.findChildrenOfType(myFixture.file, QualifiedAlias::class.java)
            .single { it.text == text }

    private fun __MODULE__Call(): Call =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == "__MODULE__" }

    private fun resolvedModulars(): List<Call> =
        (__MODULE__.reference(__MODULE__Call()) as PsiPolyVariantReference)
            .multiResolve(false)
            .mapNotNull { it.element as? Call }

    private fun resolvedModularTexts(): List<String> = resolvedModulars().map { it.text.lineSequence().first() }

    private fun resolvedModularTexts(text: String): List<String> {
        myFixture.configureByText("outer.ex", text.trimIndent())

        return resolvedModularTexts()
    }

    /**
     * The stub names a module by expanding `__MODULE__` in its text; the reference resolves `__MODULE__` through PSI.
     * The module the reference resolves to must be the one the stub's canonical name for [written] expanded it to.
     */
    private fun assertStubNameAgreesWithReference(written: String) {
        val named = generateSequence(__MODULE__Call().parent) { it.parent }
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

        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
            .map { it.text.lineSequence().first() }

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
}
