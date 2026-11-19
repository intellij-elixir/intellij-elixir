package org.elixir_lang.psi.stub

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.stub.call.Stubbic
import org.elixir_lang.psi.stub.type.File

/** What stub building stores for each call, which the indices and every stub-backed element read back. */
class StoredStubTest : PlatformTestCase() {
    fun testDefimplWithFor() = assertStored(
        "defimpl P, for: X do\nend\n",
        "IMPLEMENTATION P.X Kernel.defimpl/3 do [P.X] P",
    )

    fun testDefimplWithForList() = assertStored(
        "defimpl P, for: [X, Y] do\nend\n",
        "IMPLEMENTATION ? Kernel.defimpl/3 do [P.X, P.Y] P",
    )

    fun testDefimplWithParenthesesAndForList() = assertStored(
        "defimpl(P, for: [X, Y]) do\nend\n",
        "IMPLEMENTATION ? Kernel.defimpl/3 do [P.X, P.Y] P",
    )

    fun testDefimplWithForInAModule() = assertStored(
        "defmodule Outer do\n  defimpl P, for: X do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "IMPLEMENTATION P.X Kernel.defimpl/3 do [P.X] P",
    )

    fun testModuleOfModuleAliasInADefimplInAModule() = assertStored(
        "defmodule Outer do\n  defimpl P, for: X do\n    defmodule __MODULE__.Y do\n    end\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "IMPLEMENTATION P.X Kernel.defimpl/3 do [P.X] P",
        "MODULE __MODULE__.Y Kernel.defmodule/2 do [P.X.Y] -",
    )

    fun testModuleOfModuleAliasInADefimplForAListWithModule() = assertStored(
        "defmodule Outer do\n  defimpl P, for: [__MODULE__, Y] do\n    defmodule __MODULE__.Z do\n    end\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "IMPLEMENTATION ? Kernel.defimpl/3 do [P.Outer, P.Y] P",
        "MODULE __MODULE__.Z Kernel.defmodule/2 do [P.[Outer, Y].Z] -",
    )

    fun testModuleOfModuleAliasInADefimplForModuleAtTopLevel() = assertStored(
        "defimpl P, for: __MODULE__ do\n  defmodule __MODULE__.Z do\n  end\nend\n",
        "IMPLEMENTATION ? Kernel.defimpl/3 do [P.?] P",
        "MODULE __MODULE__.Z Kernel.defmodule/2 do [P.?.Z] -",
    )

    fun testModuleOfModuleAliasInADefimplForAnEmptyList() = assertStored(
        "defimpl P, for: [] do\n  defmodule __MODULE__.Z do\n  end\nend\n",
        "IMPLEMENTATION ? Kernel.defimpl/3 do [P.?] P",
        "MODULE __MODULE__.Z Kernel.defmodule/2 do [P.[].Z] -",
    )

    fun testDefimplWithForListInAModule() = assertStored(
        "defmodule Outer do\n  defimpl P, for: [X, Y] do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "IMPLEMENTATION ? Kernel.defimpl/3 do [P.X, P.Y] P",
    )

    fun testDefimplWithoutForAtTopLevel() = assertStored(
        "defimpl P do\nend\n",
        "IMPLEMENTATION ? Kernel.defimpl/2 do [P.?] P",
    )

    fun testDefimplWithParenthesesAndWithoutForAtTopLevel() = assertStored(
        "defimpl(P) do\nend\n",
        "IMPLEMENTATION ? Kernel.defimpl/2 do [P.?] P",
    )

    fun testDefimplWithoutForInAModule() = assertStored(
        "defmodule Outer do\n  defimpl P do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "IMPLEMENTATION P.Outer Kernel.defimpl/2 do [P.Outer] P",
    )

    fun testDefimplWithoutForInANestedModule() = assertStored(
        "defmodule Outer do\n  defmodule Inner do\n    defimpl P do\n    end\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "MODULE Inner Kernel.defmodule/2 do [Outer.Inner] -",
        "IMPLEMENTATION P.Outer.Inner Kernel.defimpl/2 do [P.Outer.Inner] P",
    )

    fun testDefimplForModuleInAModule() = assertStored(
        "defmodule Outer do\n  defimpl P, for: __MODULE__ do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "IMPLEMENTATION P.Outer Kernel.defimpl/3 do [P.Outer] P",
    )

    fun testDefimplOfModuleAliasAtTopLevel() = assertStored(
        "defimpl __MODULE__.Q, for: X do\nend\n",
        "IMPLEMENTATION Q.X Kernel.defimpl/3 do [Q.X] Q",
    )

    fun testDefimplOfModuleAliasInAModule() = assertStored(
        "defmodule Outer do\n  defimpl __MODULE__.Q, for: X do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "IMPLEMENTATION Outer.Q.X Kernel.defimpl/3 do [Outer.Q.X] Outer.Q",
    )

    fun testDefimplOfModuleAliasInANestedModule() = assertStored(
        "defmodule Outer do\n  defmodule Inner do\n    defimpl __MODULE__.Q, for: X do\n    end\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "MODULE Inner Kernel.defmodule/2 do [Outer.Inner] -",
        "IMPLEMENTATION Outer.Inner.Q.X Kernel.defimpl/3 do [Outer.Inner.Q.X] Outer.Inner.Q",
    )

    fun testDefimplOfModuleAliasInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      defimpl __MODULE__.Q, for: X do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "IMPLEMENTATION ?.Q.X Kernel.defimpl/3 do [?.Q.X] ?.Q",
    )

    fun testModuleInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      defmodule Foo do\n" +
            "        defmodule Bar do\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE Foo Kernel.defmodule/2 do [?.Foo] -",
        "MODULE Bar Kernel.defmodule/2 do [?.Foo.Bar] -",
    )

    fun testModuleInAQuoteAtTopLevel() = assertStored(
        "quote do\n  defmodule Foo do\n  end\nend\n",
        "MODULE Foo Kernel.defmodule/2 do [?.Foo] -",
    )

    fun testModuleInAQuoteWritesNoNameKey() = assertKeys(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      defmodule Foo do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U [elixir.all.name:U, elixir.modular.name:U]",
        "PUBLIC_MACRO __using__ [elixir.all.name:__using__]",
        "MODULE Foo []",
    )

    fun testModuleOfAnUnquotedModuleAliasInAQuote() = assertStored(
        UNQUOTED_MODULE_ALIAS_IN_A_QUOTE,
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [U.Sub] -",
    )

    /** The inner `unquote` runs where the outer `quote`'s code lands, which is not known here. */
    fun testModuleOfAnUnquotedModuleAliasInANestedQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      quote do\n" +
            "        defmodule unquote(__MODULE__.Sub) do\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [?.Sub] -",
    )

    /** A `fn` is not a module, so an `unquote` in a `quote` in a `fn` in `U` runs in `U`. */
    fun testModuleOfAnUnquotedModuleAliasInAQuoteInAFn() = assertStored(
        "defmodule U do\n" +
            "  def f do\n" +
            "    fn ->\n" +
            "      quote do\n" +
            "        defmodule unquote(__MODULE__.Sub) do\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_FUNCTION f Kernel.def/2 do [f] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [U.Sub] -",
    )

    fun testModuleOfAnUnquotedModuleAliasInAQuoteWritesItsNameKey() = assertKeys(
        UNQUOTED_MODULE_ALIAS_IN_A_QUOTE,
        "MODULE U [elixir.all.name:U, elixir.modular.name:U]",
        "PUBLIC_MACRO __using__ [elixir.all.name:__using__]",
        "MODULE unquote(__MODULE__.Sub) [elixir.all.name:U.Sub, elixir.modular.name:U.Sub]",
    )

    fun testModuleOfAnUnquotedModuleAliasInACallInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      IO.inspect(defmodule unquote(__MODULE__.Sub) do\n" +
            "      end)\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [U.Sub] -",
    )

    fun testModuleOfAnUnquotedAliasInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      defmodule unquote(Sub) do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(Sub) Kernel.defmodule/2 do [Sub] -",
    )

    fun testModuleOfAnUnquotedAtomInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      defmodule unquote(:sub) do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(:sub) Kernel.defmodule/2 do [:sub] -",
    )

    fun testModuleOfAnUnquotedVariableInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      defmodule unquote(name) do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(name) Kernel.defmodule/2 do [?] -",
    )

    fun testModuleOfAnUnquoteInAQuoteWithUnquoteFalseOptions() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote([unquote: false], do: defmodule(unquote(__MODULE__.Sub), do: nil))\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [?.?] -",
    )

    /** With options before the `do:` list, `quote` ignores the keys beside `do:`. */
    fun testModuleOfAnUnquoteInAQuoteWithUnquoteFalseBesideDoAfterOptions() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote([], unquote: false, do: defmodule(unquote(__MODULE__.Sub), do: nil))\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [U.Sub] -",
    )

    fun testModuleOfAnUnquoteInAQuoteWithBindQuotedOptions() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote([bind_quoted: [a: 1]], do: defmodule(unquote(__MODULE__.Sub), do: nil))\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [?.?] -",
    )

    fun testModuleOfAnUnquoteInAQuoteWithUnquoteFalse() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote unquote: false do\n" +
            "      defmodule unquote(__MODULE__.Sub) do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [?.?] -",
    )

    fun testModuleOfAnUnquoteInAQuoteWithBindQuoted() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote bind_quoted: [a: 1] do\n" +
            "      defmodule unquote(__MODULE__.Sub) do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [?.?] -",
    )

    fun testModuleOfAnUnquoteInAQuoteWithBindQuotedAndUnquoteTrue() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote bind_quoted: [a: 1], unquote: true do\n" +
            "      defmodule unquote(__MODULE__.Sub) do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [U.Sub] -",
    )

    fun testModuleOfAnUnquoteInAQuoteWithAnAttributeTrueAsUnquote() = assertStored(
        "defmodule U do\n" +
            "  @on true\n" +
            "  defmacro __using__(_) do\n" +
            "    quote unquote: @on do\n" +
            "      defmodule unquote(__MODULE__.Sub) do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE unquote(__MODULE__.Sub) Kernel.defmodule/2 do [U.Sub] -",
    )

    fun testModuleInADefimplForAList() = assertStored(
        "defimpl P, for: [X, Y] do\n  defmodule Foo do\n  end\nend\n",
        "IMPLEMENTATION ? Kernel.defimpl/3 do [P.X, P.Y] P",
        "MODULE Foo Kernel.defmodule/2 do [P.X.Foo, P.Y.Foo] -",
    )

    fun testModuleInAFunctionBody() = assertStored(
        "defmodule U do\n  def f do\n    defmodule Foo do\n    end\n  end\nend\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_FUNCTION f Kernel.def/2 do [f] -",
        "MODULE Foo Kernel.defmodule/2 do [U.Foo] -",
    )

    fun testModuleInAFunctionBodyAtTopLevel() = assertStored(
        "def f do\n  defmodule Foo do\n  end\nend\n",
        "PUBLIC_FUNCTION f Kernel.def/2 do [f] -",
        "MODULE Foo Kernel.defmodule/2 do [Foo] -",
    )

    fun testModuleInACallsArguments() = assertStored(
        "defmodule U do\n" +
            "  IO.inspect(defmodule Sub do\n" +
            "  end)\n" +
            "  IO.inspect(defmodule __MODULE__.Sub2 do\n" +
            "  end)\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "MODULE Sub Kernel.defmodule/2 do [U.Sub] -",
        "MODULE __MODULE__.Sub2 Kernel.defmodule/2 do [U.Sub2] -",
    )

    fun testModuleInAFnInACallsArgumentsInAFunctionBody() = assertStored(
        "defmodule U do\n" +
            "  def f do\n" +
            "    Task.async(fn -> defmodule F do\n" +
            "    end end)\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_FUNCTION f Kernel.def/2 do [f] -",
        "MODULE F Kernel.defmodule/2 do [U.F] -",
    )

    fun testModuleInAnUnquoteInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      unquote(defmodule __MODULE__.Sub do\n" +
            "      end)\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE __MODULE__.Sub Kernel.defmodule/2 do [U.Sub] -",
    )

    /** The `unquote` runs where the `quote` is, past the module the `quote` writes around it. */
    fun testModuleInAnUnquoteInAModuleInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      defmodule Inner do\n" +
            "        unquote(defmodule __MODULE__.X do\n" +
            "        end)\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE Inner Kernel.defmodule/2 do [?.Inner] -",
        "MODULE __MODULE__.X Kernel.defmodule/2 do [U.X] -",
    )

    fun testModuleInAQuotesBindQuoted() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote bind_quoted: [m: defmodule(__MODULE__.Sub, do: nil)] do\n" +
            "      m\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE __MODULE__.Sub Kernel.defmodule/2 do [U.Sub] -",
    )

    /** `Module.create/3`'s arguments are evaluated in the module around it, not in the module it creates. */
    fun testModuleInModuleCreatesContents() = assertStored(
        "defmodule Outer do\n" +
            "  Module.create(Inner, (defmodule __MODULE__.Child do\n" +
            "  end; nil), Macro.Env.location(__ENV__))\n" +
            "  Module.create(Inner2, (defmodule Plain do\n" +
            "  end; nil), Macro.Env.location(__ENV__))\n" +
            "end\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "- Inner Module.create/3 - [Inner] -",
        "MODULE __MODULE__.Child Kernel.defmodule/2 do [Outer.Child] -",
        "- Inner2 Module.create/3 - [Inner2] -",
        "MODULE Plain Kernel.defmodule/2 do [Outer.Plain] -",
    )

    /** Elixir defines `Name.X`, but a `quote` leaves `__MODULE__` to the module it is injected into. */
    fun testModuleInAQuoteAsModuleCreatesContents() = assertStored(
        MODULE_CREATE_OF_A_QUOTE,
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "- Name Module.create/3 - [Name] -",
        "MODULE X Kernel.defmodule/2 do [?.X] -",
    )

    fun testModuleInAQuoteAsModuleCreatesContentsWritesNoNameKey() = assertKeys(
        MODULE_CREATE_OF_A_QUOTE,
        "MODULE Outer [elixir.all.name:Outer, elixir.modular.name:Outer]",
        "- Name [elixir.all.name:Name]",
        "MODULE X []",
    )

    /** A module's one canonical name, which navigation and documentation read, agrees with its stored set. */
    fun testCanonicalNamesInAQuote() = assertCanonicalNames(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      defmodule Foo do\n" +
            "        defmodule Bar do\n" +
            "        end\n" +
            "      end\n" +
            "      defmodule unquote(__MODULE__.Sub) do\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "  def f do\n" +
            "    defmodule Baz do\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "U",
        "?.Foo",
        "?.Foo.Bar",
        "U.Sub",
        "U.Baz",
    )

    fun testNestedModules() = assertStored(
        "defmodule Outer do\n  defmodule Inner do\n  end\n  defmodule __MODULE__.Named do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "MODULE Inner Kernel.defmodule/2 do [Outer.Inner] -",
        "MODULE __MODULE__.Named Kernel.defmodule/2 do [Outer.Named] -",
    )

    fun testModuleOfModuleAliasInANestedModule() = assertStored(
        "defmodule Outer do\n  defmodule Inner do\n    defmodule __MODULE__.Deep do\n    end\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "MODULE Inner Kernel.defmodule/2 do [Outer.Inner] -",
        "MODULE __MODULE__.Deep Kernel.defmodule/2 do [Outer.Inner.Deep] -",
    )

    fun testModuleOfModuleAliasAtTopLevel() = assertStored(
        "defmodule __MODULE__.Top do\nend\n",
        "MODULE __MODULE__.Top Kernel.defmodule/2 do [Top] -",
    )

    fun testModuleNamedWithAQuotedAtom() = assertStored(
        "defmodule :\"a.b\" do\nend\n",
        "MODULE :a.b Kernel.defmodule/2 do [:a.b] -",
    )

    fun testModuleNamedWithAnAtom() = assertStored(
        "defmodule :plain do\nend\n",
        "MODULE :plain Kernel.defmodule/2 do [:plain] -",
    )

    fun testModuleNamedWithAnAtomInAModule() = assertStored(
        "defmodule Outer do\n  defmodule :inner_target do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "MODULE :inner_target Kernel.defmodule/2 do [:inner_target] -",
    )

    fun testModuleNamedWithAnElixirPrefixedAtomInAModule() = assertStored(
        "defmodule Outer do\n  defmodule :\"Elixir.Foo\" do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "MODULE Foo Kernel.defmodule/2 do [Foo] -",
    )

    fun testModuleNamedWithACharListQuotedAtom() = assertStored(
        "defmodule :'a.b' do\nend\n",
        "MODULE :a.b Kernel.defmodule/2 do [:a.b] -",
    )

    fun testModuleNamedWithAnInterpolatedAtomInAModule() = assertStored(
        "defmodule Outer do\n  defmodule :\"#{x}\" do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "MODULE ? Kernel.defmodule/2 do [?] -",
    )

    fun testModuleNamedWithAnAtomLongerThanAnAtomCanBe() = assertStored(
        "defmodule :\"${"a".repeat(256)}\" do\nend\n",
        "MODULE ? Kernel.defmodule/2 do [?] -",
    )

    fun testModuleNamedWithAnUnterminatedQuotedAtom() = assertStored("defmodule :\"a")

    fun testModuleNamedWithAnUnterminatedInterpolation() = assertStored("defmodule :\"#{a do\nend\n")

    fun testDefprotocol() = assertStored(
        "defprotocol P do\n  def f(t)\nend\n",
        "PROTOCOL P Kernel.defprotocol/2 do [P] -",
        "PUBLIC_FUNCTION f Kernel.def/1 - [f] -",
    )

    fun testQualifiedDefmoduleAndDef() = assertStored(
        "Kernel.defmodule Foo do\n  Kernel.def k, do: 1\nend\n",
        "MODULE Foo Kernel.defmodule/2 do [Foo] -",
        "PUBLIC_FUNCTION k Kernel.def/2 do [k] -",
    )

    /** A macro named `defmodule` writes a head, not a module, as a release tool's redefinition does. */
    fun testDefmacroNamedDefmoduleIsNoModule() = assertStored(
        "defmodule K do\n  defmacro defmodule(name, do: block) do\n    {name, block}\n  end\nend\n",
        "MODULE K Kernel.defmodule/2 do [K] -",
        "PUBLIC_MACRO defmodule Kernel.defmacro/2 do [defmodule] -",
    )

    fun testParenthesizedDefmacroNamedDefmoduleIsNoModule() = assertStored(
        "defmodule K do\n  defmacro(defmodule(name, do: block), do: {name, block})\nend\n",
        "MODULE K Kernel.defmodule/2 do [K] -",
        "PUBLIC_MACRO defmodule Kernel.defmacro/2 do [defmodule] -",
    )

    fun testModuleCreate() = assertStored(
        "Module.create(Bar, quote(do: nil), Macro.Env.location(__ENV__))\n",
        "- Bar Module.create/3 - [Bar] -",
    )

    /** `Module.create/3` names its module with an atom, which Elixir does not nest in the module around it. */
    fun testModuleCreateInAModule() = assertStored(
        "defmodule U do\n  Module.create(Foo, quote(do: nil), Macro.Env.location(__ENV__))\nend\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "- Foo Module.create/3 - [Foo] -",
    )

    fun testModuleCreateInAFunctionBody() = assertStored(
        "defmodule U do\n  def f do\n    Module.create(Foo, quote(do: nil), Macro.Env.location(__ENV__))\n  end\nend\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_FUNCTION f Kernel.def/2 do [f] -",
        "- Foo Module.create/3 - [Foo] -",
    )

    fun testModuleCreateOfAModuleAliasInAModule() = assertStored(
        "defmodule U do\n  Module.create(__MODULE__.Foo, quote(do: nil), Macro.Env.location(__ENV__))\nend\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "- __MODULE__.Foo Module.create/3 - [U.Foo] -",
    )

    fun testModuleCreateInAQuote() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      Module.create(Foo, quote(do: nil), Macro.Env.location(__ENV__))\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "- Foo Module.create/3 - [Foo] -",
    )

    fun testCanonicalNameOfModuleCreateInAModule() = assertCanonicalNames(
        "defmodule U do\n" +
            "  Module.create(Foo, quote(do: nil), Macro.Env.location(__ENV__))\n" +
            "  Module.create(__MODULE__.Bar, quote(do: nil), Macro.Env.location(__ENV__))\n" +
            "end\n",
        "U",
        "Foo",
        "U.Bar",
    )

    fun testDefinitions() = assertStored(
        "defmodule D do\n" +
            "  defdelegate f(a), to: M\n" +
            "  def g(a), do: a\n" +
            "  defp h, do: 1\n" +
            "  defmacro m(x), do: x\n" +
            "  defmacrop mp(x), do: x\n" +
            "  defguard gd(x) when is_integer(x)\n" +
            "  defguardp gdp(x) when is_integer(x)\n" +
            "  def multi(a, b \\\\ 1) do\n    a + b\n  end\n" +
            "end\n",
        "MODULE D Kernel.defmodule/2 do [D] -",
        "- f Kernel.f/1 - [f] -",
        "PUBLIC_FUNCTION g Kernel.def/2 do [g] -",
        "PRIVATE_FUNCTION h Kernel.defp/2 do [h] -",
        "PUBLIC_MACRO m Kernel.defmacro/2 do [m] -",
        "PRIVATE_MACRO mp Kernel.defmacrop/2 do [mp] -",
        "PUBLIC_GUARD gd Kernel.defguard/1 - [gd] -",
        "PRIVATE_GUARD gdp Kernel.defguardp/1 - [gdp] -",
        "PUBLIC_FUNCTION multi Kernel.def/2 do [multi] -",
    )

    fun testSpecificationAndCallbacks() = assertStored(
        "defmodule S do\n" +
            "  @spec f(integer) :: integer\n" +
            "  @callback c(atom) :: atom\n" +
            "  @macrocallback mc(atom) :: Macro.t\n" +
            "  @a 1\n" +
            "end\n",
        "MODULE S Kernel.defmodule/2 do [S] -",
        "- f null.null/1 - [f] -",
        "- c null.null/1 - [c] -",
        "- mc null.null/1 - [mc] -",
    )

    fun testSpecificationAndCallbacksWithSpaceAfterAt() = assertStored(
        "defmodule S do\n" +
            "  @ spec f(integer) :: integer\n" +
            "  @ callback c(atom) :: atom\n" +
            "  @ macrocallback mc(atom) :: Macro.t\n" +
            "end\n",
        "MODULE S Kernel.defmodule/2 do [S] -",
        "- f null.null/1 - [f] -",
        "- c null.null/1 - [c] -",
        "- mc null.null/1 - [mc] -",
    )

    fun testQuotedDeclarations() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      @a 1\n" +
            "      x = 1\n" +
            "      Module.register_attribute(__MODULE__, :b, [])\n" +
            "      Module.put_attribute(__MODULE__, :c, 1)\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE_ATTRIBUTE @a null.null/1 - [@a] -",
        "VARIABLE x Kernel.x/0 - [x] -",
        "MODULE_ATTRIBUTE @b Module.register_attribute/3 - [@b] -",
        "MODULE_ATTRIBUTE @c Module.put_attribute/3 - [@c] -",
    )

    fun testQuotedDeclarationsWithQuotedAtoms() = assertStored(
        "defmodule U do\n" +
            "  defmacro __using__(_) do\n" +
            "    quote do\n" +
            "      Module.register_attribute(__MODULE__, :\"b\", [])\n" +
            "      Module.put_attribute(__MODULE__, :\"c\", 1)\n" +
            "    end\n" +
            "  end\n" +
            "end\n",
        "MODULE U Kernel.defmodule/2 do [U] -",
        "PUBLIC_MACRO __using__ Kernel.defmacro/2 do [__using__] -",
        "MODULE_ATTRIBUTE @b Module.register_attribute/3 - [@b] -",
        "MODULE_ATTRIBUTE @c Module.put_attribute/3 - [@c] -",
    )

    fun testModuleNamedWithAnElixirPrefixedAlias() = assertStored(
        "defmodule Elixir.Foo do\nend\n",
        "MODULE Foo Kernel.defmodule/2 do [Foo] -",
    )

    fun testModuleNamedWithAnElixirPrefixedAliasInAModule() = assertStored(
        "defmodule Outer do\n  defmodule Elixir.Inner do\n  end\nend\n",
        "MODULE Outer Kernel.defmodule/2 do [Outer] -",
        "MODULE Inner Kernel.defmodule/2 do [Inner] -",
    )

    fun testModuleNamedWithAnElixirPrefixedAtom() = assertStored(
        "defmodule :\"Elixir.Foo\" do\nend\n",
        "MODULE Foo Kernel.defmodule/2 do [Foo] -",
    )

    fun testModuleNamedWithAnAliasWithAnElixirSegment() = assertStored(
        "defmodule Foo.Elixir.Bar do\nend\n",
        "MODULE Foo.Elixir.Bar Kernel.defmodule/2 do [Foo.Elixir.Bar] -",
    )

    fun testModuleNamedWithSpacesAroundTheDot() = assertStored(
        "defmodule Foo . Bar do\nend\n",
        "MODULE Foo.Bar Kernel.defmodule/2 do [Foo.Bar] -",
    )

    fun testModuleNamedWithANewlineAfterTheDot() = assertStored(
        "defmodule Foo.\n  Bar do\nend\n",
        "MODULE Foo.Bar Kernel.defmodule/2 do [Foo.Bar] -",
    )

    fun testDefimplForAnElixirPrefixedAlias() = assertStored(
        "defimpl P, for: Elixir.Integer do\nend\n",
        "IMPLEMENTATION P.Integer Kernel.defimpl/3 do [P.Integer] P",
    )

    fun testDefimplOfAProtocolWithAnElixirSegment() = assertStored(
        "defimpl Foo.Elixir.Bar, for: X do\nend\n",
        "IMPLEMENTATION Foo.Elixir.Bar.X Kernel.defimpl/3 do [Foo.Elixir.Bar.X] Foo.Elixir.Bar",
    )

    fun testDefimplOfAnElixirPrefixedAtomProtocol() = assertStored(
        "defimpl :\"Elixir.Outer.P\", for: BitString do\nend\n",
        "IMPLEMENTATION Outer.P.BitString Kernel.defimpl/3 do [Outer.P.BitString] Outer.P",
    )

    fun testDefinitionsNamedByUnquotedAtoms() = assertStored(
        "defmodule Q do\n" +
            "  def unquote(:\"a\\x62\")(), do: 1\n" +
            "  def unquote(:in)(x, _y), do: x\n" +
            "  defmacro unquote(:!)(value), do: value\n" +
            "  @spec unquote(:\"foo bar\")(integer) :: integer\n" +
            "end\n",
        "MODULE Q Kernel.defmodule/2 do [Q] -",
        "PUBLIC_FUNCTION ab Kernel.def/2 do [ab] -",
        "PUBLIC_FUNCTION in Kernel.def/2 do [in] -",
        "PUBLIC_MACRO ! Kernel.defmacro/2 do [!] -",
        "- foo bar null.null/1 - [foo bar] -",
    )

    fun testDefinitionNamedByADecomposedIdentifierIsComposed() = assertStored(
        "defmodule N do\n  def $DECOMPOSED(), do: 1\nend\n",
        "MODULE N Kernel.defmodule/2 do [N] -",
        "PUBLIC_FUNCTION $PRECOMPOSED Kernel.def/2 do [$PRECOMPOSED] -",
    )

    /** Below 1.14 Elixir rejects a decomposed identifier, and the quoter keeps it as written. */
    fun testDefinitionNamedByADecomposedIdentifierBeforeNormalizedIdentifiers() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of("1.13.4"))

        try {
            assertStored(
                "defmodule N do\n  def $DECOMPOSED(), do: 1\nend\n",
                "MODULE N Kernel.defmodule/2 do [N] -",
                "PUBLIC_FUNCTION $DECOMPOSED Kernel.def/2 do [$DECOMPOSED] -",
            )
        } finally {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        }
    }

    fun testDefinitionByAQuotedName() = assertStored(
        "defmodule D do\n  Kernel.\"def\"(g(a), do: a)\n  Kernel.def(h(a), do: a)\nend\n",
        "MODULE D Kernel.defmodule/2 do [D] -",
        "PUBLIC_FUNCTION g Kernel.def/2 do [g] -",
        "PUBLIC_FUNCTION h Kernel.def/2 do [h] -",
    )

    fun testModuleByAQuotedName() = assertStoredAlike(
        "defmodule D do\n  Kernel.\"defmodule\" Inner do\n    def f, do: 1\n  end\nend\n",
        "defmodule D do\n  Kernel.defmodule Inner do\n    def f, do: 1\n  end\nend\n",
    )

    fun testDefinitionByACharListName() = assertStoredAlike(
        "defmodule D do\n  Kernel.'def'(g(a), do: a)\nend\n",
        "defmodule D do\n  Kernel.def(g(a), do: a)\nend\n",
    )

    fun testModuleWithNoValueAndModuleNamedByTheNoValueSpellingDoNotShareAKey() =
        assertNoSharedKey("defmodule :\"#{x}\" do\nend\n", "defmodule :\"Elixir.?\" do\nend\n")

    fun testElixirPrefixedAtomNamingAnAtomAndThatAtomDoNotShareAKey() =
        assertNoSharedKey("defmodule :\"Elixir.:foo\" do\nend\n", "defmodule :foo do\nend\n")

    fun testModuleNamedByAnInterpolatedAtomWritesNoNameKey() = assertKeys(
        "defmodule :\"#{x}\" do\nend\n",
        "MODULE ? []",
    )

    fun testModulesNamedInsideAModuleNamedAtRunTimeWriteNoNameKey() = assertKeys(
        "defmodule unquote(n) do\n  defmodule Inner do\n  end\nend\n",
        "MODULE unquote(n) []",
        "MODULE Inner []",
    )

    /** Elixir names it `elixir_aliases:concat([:foo, :Inner])`, `:"Elixir.foo.Inner"`. */
    fun testModuleNestedInAnAtomNamedModule() = assertKeys(
        "defmodule :foo do\n  defmodule Inner do\n  end\nend\n",
        "MODULE :foo [elixir.all.name::foo, elixir.modular.name::foo]",
        "MODULE Inner [elixir.all.name::Elixir.foo.Inner, elixir.modular.name::Elixir.foo.Inner]",
    )

    fun testModuleOfModuleAliasNestedInAnAtomNamedModule() = assertKeys(
        "defmodule :foo do\n  defmodule __MODULE__.Rel do\n  end\nend\n",
        "MODULE :foo [elixir.all.name::foo, elixir.modular.name::foo]",
        "MODULE __MODULE__.Rel [elixir.all.name::Elixir.foo.Rel, elixir.modular.name::Elixir.foo.Rel]",
    )

    /** Elixir rejects `x.Inner` as a module name at compile time, so there is no module to key. */
    fun testModuleWithNoValueNestedInAnAtomNamedModuleWritesNoNameKey() = assertKeys(
        "defmodule :foo do\n  defmodule x.Inner do\n  end\nend\n",
        "MODULE :foo [elixir.all.name::foo, elixir.modular.name::foo]",
        "MODULE ?.Inner []",
    )

    fun testModuleNestedInAnAliasNamedModule() = assertKeys(
        "defmodule Foo do\n  defmodule Inner do\n  end\nend\n",
        "MODULE Foo [elixir.all.name:Foo, elixir.modular.name:Foo]",
        "MODULE Inner [elixir.all.name:Foo.Inner, elixir.modular.name:Foo.Inner]",
    )

    fun testModuleNestedInAModuleNamedByAnElixirPrefixedAtomShapedAsAnAlias() = assertKeys(
        "defmodule :\"Elixir.Foo\" do\n  defmodule Inner do\n  end\nend\n",
        "MODULE Foo [elixir.all.name:Foo, elixir.modular.name:Foo]",
        "MODULE Inner [elixir.all.name:Foo.Inner, elixir.modular.name:Foo.Inner]",
    )

    fun testModuleNestedInAModuleNamedByAnElixirPrefixedAtomNotShapedAsAnAlias() = assertKeys(
        "defmodule :\"Elixir.foo\" do\n  defmodule Inner do\n  end\nend\n",
        "MODULE :Elixir.foo [elixir.all.name::Elixir.foo, elixir.modular.name::Elixir.foo]",
        "MODULE Inner [elixir.all.name::Elixir.foo.Inner, elixir.modular.name::Elixir.foo.Inner]",
    )

    fun testModuleNestedInAModuleNamedByAnInterpolatedAtomWritesNoNameKey() = assertKeys(
        "defmodule :\"#{x}\" do\n  defmodule Inner do\n  end\nend\n",
        "MODULE ? []",
        "MODULE Inner []",
    )

    fun testDefimplWithoutForKeepsItsProtocolKey() = assertKeys(
        "defimpl P do\nend\n",
        "IMPLEMENTATION ? [elixir.implemented_protocol.name:P]",
    )

    fun testModuleNamedByTheNoValueSpellingKeepsItsKey() = assertKeys(
        "defmodule :\"Elixir.?\" do\nend\n",
        "MODULE :Elixir.? [elixir.all.name::Elixir.?, elixir.modular.name::Elixir.?]",
    )

    fun testDefinitionNamedWithTheNoValueSpellingKeepsItsKey() = assertKeys(
        "defmodule M do\n  def unquote(:\"a.?\")(), do: 1\nend\n",
        "MODULE M [elixir.all.name:M, elixir.modular.name:M]",
        "PUBLIC_FUNCTION a.? [elixir.all.name:a.?]",
    )

    private fun assertCanonicalNames(source: String, vararg expected: String) {
        val file = myFixture.configureByText("canonical.ex", source)

        assertEquals(
            expected.joinToString("\n"),
            PsiTreeUtil.findChildrenOfType(file, Call::class.java)
                .filter { Module.`is`(it) }
                .joinToString("\n") { (it as CanonicallyNamed).canonicalName().toString() },
        )
    }

    private fun assertKeys(source: String, vararg expected: String) {
        val root = File.INSTANCE.builder.buildStubTree(myFixture.configureByText("stored.ex", source))

        assertEquals(
            expected.joinToString("\n"),
            stubbics(root).joinToString("\n") { stubbic ->
                listOf(stubbic.definition?.name ?: "-", stubbic.name, occurrences(stubbic as StubElement<*>)).joinToString(" ")
            },
        )
    }

    private fun occurrences(stub: StubElement<*>): String {
        val occurrences = mutableListOf<String>()
        val sink = object : IndexSink {
            override fun <Psi : PsiElement, K : Any> occurrence(indexKey: StubIndexKey<K, Psi>, value: K) {
                occurrences.add("${indexKey.name}:$value")
            }
        }
        @Suppress("UNCHECKED_CAST")
        (stub.elementType as IStubElementType<StubElement<*>, *>).indexStub(stub, sink)

        return occurrences.sorted().joinToString(", ", "[", "]")
    }

    private fun assertNoSharedKey(source: String, otherSource: String) {
        val keys = keys(source)
        val otherKeys = keys(otherSource)

        assertEquals("$keys and $otherKeys", emptySet<String>(), keys intersect otherKeys)
    }

    private fun keys(source: String): Set<String> =
        stubbics(File.INSTANCE.builder.buildStubTree(myFixture.configureByText("stored.ex", source)))
            .flatMap { it.canonicalNameSet() }
            .toSet()

    private fun assertStored(source: String, vararg expected: String) {
        val file = myFixture.configureByText("stored.ex", source)
        val root = File.INSTANCE.builder.buildStubTree(file)

        assertEquals(expected.joinToString("\n"), stubbics(root).joinToString("\n") { render(it) })
    }

    /** The stubs of [source] are those of [unquoted], which spells the same calls without quoting a callee. */
    private fun assertStoredAlike(source: String, unquoted: String) {
        val stored = stubbics(File.INSTANCE.builder.buildStubTree(myFixture.configureByText("stored.ex", source)))
        val expected = stubbics(File.INSTANCE.builder.buildStubTree(myFixture.configureByText("unquoted.ex", unquoted)))

        assertEquals(expected.joinToString("\n") { render(it) }, stored.joinToString("\n") { render(it) })
        assertTrue(expected.isNotEmpty())
    }

    private fun stubbics(stub: StubElement<*>): List<Stubbic> =
        stub.childrenStubs.flatMap { child -> listOfNotNull(child as? Stubbic) + stubbics(child) }

    private fun render(stubbic: Stubbic): String =
        listOf(
            stubbic.definition?.name ?: "-",
            stubbic.name,
            "${stubbic.resolvedModuleName()}.${stubbic.resolvedFunctionName()}/${stubbic.resolvedFinalArity()}",
            if (stubbic.hasDoBlockOrKeyword()) "do" else "-",
            stubbic.canonicalNameSet().sorted().joinToString(", ", "[", "]"),
            stubbic.implementedProtocolName ?: "-",
        ).joinToString(" ")

    private companion object {
        const val DECOMPOSED = "cafe\u0301"
        const val PRECOMPOSED = "caf\u00e9"
        const val UNQUOTED_MODULE_ALIAS_IN_A_QUOTE =
            "defmodule U do\n" +
                "  defmacro __using__(_) do\n" +
                "    quote do\n" +
                "      defmodule unquote(__MODULE__.Sub) do\n" +
                "      end\n" +
                "    end\n" +
                "  end\n" +
                "end\n"
        const val MODULE_CREATE_OF_A_QUOTE =
            "defmodule Outer do\n" +
                "  Module.create(Name, quote(do: defmodule X do\n" +
                "  end), Macro.Env.location(__ENV__))\n" +
                "end\n"
    }
}
