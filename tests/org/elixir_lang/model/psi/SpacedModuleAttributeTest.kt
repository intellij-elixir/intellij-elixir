package org.elixir_lang.model.psi

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.application.runReadAction
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.model.psi.type.TypeSymbol
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.structure_view.element.CallDefinitionSpecification
import org.elixir_lang.structure_view.element.Type
import org.elixir_lang.structure_view.element.Callback as CallbackElement

/**
 * Elixir reads the attribute in `@ doc "x"`, `@ spec f() :: t` and `@ behaviour B` as it reads `@doc`, `@spec` and
 * `@behaviour`, so every feature built on those attributes must too.
 */
@Suppress("UnstableApiUsage")
class SpacedModuleAttributeTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testSpacedTypeIsAStructureViewType() {
        myFixture.configureByText("types.ex", "defmodule M do\n  @ type t :: atom\n  @ typep p :: atom\n  @ opaque o :: atom\nend\n")

        assertEquals(3, myFixture.structureViewElements().filterIsInstance<Type>().size)
    }

    fun testSpacedCallbackIsAStructureViewCallback() {
        myFixture.configureByText(
            "callbacks.ex",
            "defmodule M do\n  @ callback c() :: atom\n  @ macrocallback m() :: atom\nend\n"
        )

        assertEquals(2, myFixture.structureViewElements().filterIsInstance<CallbackElement>().size)
    }

    fun testSpacedSpecIsASpecification() {
        myFixture.configureByText("spec.ex", "defmodule M do\n  @ spec f() :: atom\n  def f, do: :ok\nend\n")

        assertEquals(listOf(true), myFixture.attributes().map { runReadAction { CallDefinitionSpecification.`is`(SyntacticCall.of(it)) } })
    }

    fun testTypeUseInSpecResolvesToSpacedType() {
        myFixture.configureByText(
            "type_use.ex",
            "defmodule M do\n  @ type t :: atom\n\n  @spec f() :: <caret>t()\n  def f, do: :ok\nend\n"
        )

        assertTrue("`t` should resolve to the spaced type", myFixture.resolvedSymbolsAtCaret().any { it is TypeSymbol })
    }

    fun testSpacedSpecNameResolvesToItsFunction() {
        myFixture.configureByText(
            "spec_name.ex",
            "defmodule M do\n  @ spec <caret>f() :: atom\n  def f, do: :ok\nend\n"
        )

        assertTrue(
            "the name in a spaced @spec should resolve to M.f/0",
            myFixture.resolvedSymbolsAtCaret().filterIsInstance<FunctionSymbol>().any { it.name == "f" && it.moduleName == "M" }
        )
    }

    fun testSpacedCompileInlineKeyResolvesToFunction() {
        myFixture.configureByText(
            "compile_inline.ex",
            "defmodule M do\n  @ compile inline: [per<caret>form: 0]\n\n  def perform, do: :ok\nend\n"
        )

        assertTrue(
            "the `inline:` key of a spaced @compile should resolve to M.perform/0",
            myFixture.resolvedSymbolsAtCaret().filterIsInstance<FunctionSymbol>().any { it.name == "perform" && it.moduleName == "M" }
        )
    }

    fun testSpacedBehaviourMakesADefImplementTheCallback() {
        myFixture.configureByText(
            "behaviour.ex",
            "defmodule B do\n  @callback perform() :: :ok\nend\n\n" +
                "defmodule Impl do\n  @ behaviour B\n\n  def per<caret>form, do: :ok\nend\n"
        )

        assertTrue(
            "a def under `@ behaviour B` should implement B.perform",
            myFixture.resolvedCallbacksAtCaretDef().any { it.name == "perform" && it.moduleName == "B" }
        )
    }

    fun testSpacedCallbackIsImplementedByADef() {
        myFixture.configureByText(
            "callback.ex",
            "defmodule B do\n  @ callback perform() :: :ok\nend\n\n" +
                "defmodule Impl do\n  @behaviour B\n\n  def per<caret>form, do: :ok\nend\n"
        )

        assertTrue(
            "a def under `@behaviour B` should implement the spaced `@ callback`",
            myFixture.resolvedCallbacksAtCaretDef().any { it.name == "perform" && it.moduleName == "B" }
        )
    }
}
