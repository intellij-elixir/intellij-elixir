package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.InlayHintsProvider

class CurrentModuleHintsProviderTest : InlayHintsTestCase() {
    override val providerId = "elixir.current.module"

    override fun provider(): InlayHintsProvider = CurrentModuleHintsProvider()

    fun testItIsOffByDefault() {
        val info = providerInfo()

        assertInstanceOf(info.provider, CurrentModuleHintsProvider::class.java)
        assertFalse(info.isEnabledByDefault)
    }

    fun testAModuleBodyShowsItsModule() = assertHints(
        """
        defmodule Foo do
          def name, do: __MODULE__/*<# Foo #>*/
        end
        """
    )

    fun testANestedModuleShowsItsFullName() = assertHints(
        """
        defmodule Outer do
          defmodule Inner do
            def name, do: __MODULE__/*<# Outer.Inner #>*/
          end
        end
        """
    )

    fun testAnAtomNamedModuleShowsAsElixirWritesIt() = assertHints(
        """
        defmodule :erlang_style do
          def name, do: __MODULE__/*<# :erlang_style #>*/
        end
        """
    )

    fun testAnImplementationShowsItsModule() = assertHints(
        """
        defimpl Inspect, for: Tuple do
          def inspect(_, _), do: __MODULE__/*<# Inspect.Tuple #>*/
        end
        """
    )

    fun testAnImplementationForAListShowsNothing() = assertHints(
        """
        defimpl Inspect, for: [Tuple, List] do
          def inspect(_, _), do: __MODULE__
        end
        """
    )

    fun testAnImplementationForAVariableShowsNothing() = assertHints(
        """
        for target <- [Tuple, List] do
          defimpl Inspect, for: target do
            def inspect(_, _), do: __MODULE__
          end
        end
        """
    )

    fun testAnImplementationForAnUnquoteFragmentShowsNothing() = assertHints(
        """
        for target <- [Tuple, List] do
          defimpl Inspect, for: unquote(target) do
            def inspect(_, _), do: __MODULE__
          end
        end
        """
    )

    fun testAModuleNamedByUnquoteShowsNothing() = assertHints(
        """
        defmodule unquote(name) do
          def name, do: __MODULE__
        end
        """
    )

    fun testTheTopLevelShowsNothing() = assertHints(
        """
        IO.inspect(__MODULE__)
        """
    )

    fun testAMacroBodyOutsideQuoteShowsTheDefiningModule() = assertHints(
        """
        defmodule Macros do
          defmacro named do
            IO.inspect(__MODULE__/*<# Macros #>*/)

            quote do
              __MODULE__
            end
          end
        end
        """
    )

    fun testAnUnquoteInAQuoteShowsTheModuleTheQuoteIsWrittenIn() = assertHints(
        """
        defmodule Macros do
          defmacro named do
            quote do
              {__MODULE__, unquote(__MODULE__/*<# Macros #>*/)}
            end
          end
        end
        """
    )

    fun testAStructNameShowsItsModule() = assertHints(
        """
        defmodule Foo do
          defstruct [:a]

          def new, do: %__MODULE__/*<# Foo #>*/{}
        end
        """
    )

    fun testAnAliasQualifierShowsItsModule() = assertHints(
        """
        defmodule Foo do
          def child, do: __MODULE__/*<# Foo #>*/.Child
        end
        """
    )
}
