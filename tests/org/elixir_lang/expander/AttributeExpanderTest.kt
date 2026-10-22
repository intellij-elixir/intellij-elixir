package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

/**
 * Files expanded as Elixir compiles them, by what each module's body does to its attributes: the effects in the order
 * the body runs, what each definition reads, the attributes when the body ends, and what each definition takes.
 */
class AttributeExpanderTest : ExpanderTestCase() {
    override val exports: Exports = AttributeFixtures.EXPORTS
    override val kernel: KernelImports = AttributeFixtures.KERNEL

    // `Kernel.@/1`

    fun testOutsideAModule() =
        assertEvery("@x 1", "top error attribute_outside_module `@x 1`")

    fun testAWriteInAMatchOutsideAFunction() =
        assertEvery(
            """
            defmodule A do
              @x = 1
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised attribute_in_match_or_guard `@x`
            """.trimIndent(),
        )

    /** Before 1.15.0 a guard reads the attribute, through a remote call the guard refuses. */
    fun testAReadInAGuardOutsideAFunction() =
        assertLevels(
            """
            defmodule A do
              case 1 do
                y when @x -> y
              end
            end
            """.trimIndent(),
            (LEVELS + listOf("1.15.0-rc.2", "1.15.0")).sortedBy { ElixirLanguageLevel.of(it).elixir },
        ) { version ->
            val kind = if (isBefore(version, "1.15.0")) "invalid_guard" else "attribute_in_match_or_guard"

            """
            top expanded {} next 0
            module Elixir.A raised $kind `@x`
            """.trimIndent()
        }

    fun testAReadInAFunctionsPattern() =
        assertEvery(
            """
            defmodule A do
              @x 1
              def f(@x), do: 1
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write x 1 `@x 1`
              read x in def f/1: 1
              final x: 1
            """.trimIndent(),
        )

    fun testAWriteInAFunction() =
        assertEvery(
            """
            defmodule A do
              def f do
                @x 1
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised attribute_set_in_function `@x 1`
            """.trimIndent(),
        )

    /** Before 1.14 `@behavior` only warns, and writes nothing. */
    fun testBehavior() =
        assertSplit(
            """
            defmodule A do
              @behavior Foo
            end
            """.trimIndent(),
            "1.14.0-rc.0",
            """
            top expanded {} next 0
            module Elixir.A compiled
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised behavior_attribute `@behavior Foo`
            """.trimIndent(),
        )

    fun testTwoArguments() =
        assertEvery(
            """
            defmodule A do
              @x 1, 2
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised attribute_arity `@x 1, 2`
            """.trimIndent(),
        )

    fun testATypespec() =
        assertEvery(
            """
            defmodule A do
              @type t :: integer
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              typespec type `@type t :: integer`
              final type: unknown
            """.trimIndent(),
        )

    /**
     * `@` expands the call Elixir builds: a write, a typespec and a module-body read are remote calls, then the value's
     * own calls; a read in a function injects the value and calls nothing.
     */
    fun testEvents() =
        assertEvents(
            """
            defmodule A do
              @doc "a"
              @x String.length("a")
              @type t :: integer
              y = @x
              def f, do: @x
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val lazily = !isBefore(version, "1.14.0-rc.0")
            val put = "remote_function Elixir.Module.__put_attribute__/${if (lazily) 5 else 4}"
            val get = "remote_function Elixir.Module.__get_attribute__/${if (lazily) 4 else 3}"

            (if (isBefore(version, "1.13.0-rc.0")) "imported_macro Elixir.Kernel.defmodule/2; " else "") +
                "$AT; $put; " +
                "$AT; $put; remote_function Elixir.String.length/1; " +
                "$AT; remote_function Elixir.Kernel.Typespec.deftypespec/6; " +
                "$AT; $get; " +
                "imported_macro Elixir.Kernel.def/2; " +
                AT
        }

    /** A typespec is escaped, not quoted: its `unquote` runs where the `@` is, and is never validated. */
    fun testATypespecsUnquote() =
        assertEvents(
            """
            defmodule A do
              @type t :: unquote(String.length("a"))
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            (if (isBefore(version, "1.13.0-rc.0")) "imported_macro Elixir.Kernel.defmodule/2; " else "") +
                "$AT; remote_function Elixir.Kernel.Typespec.deftypespec/6; remote_function Elixir.String.length/1"
        }

    /**
     * A doc read in the module body unwraps the doc in a `case`, whose two clauses each bind a variable; from
     * 1.20.0-rc.5 its `_` and the `case` take one too.
     */
    fun testADocReadInTheModuleBody() =
        assertBody(
            """
            defmodule A do
              @doc "a"
              y = @doc
            end
            """.trimIndent(),
        ) { version -> if (isBefore(version, "1.20.0-rc.5")) "expanded {y:2} next 3" else "expanded {y:4} next 5" }

    // The hygiene counter

    /** Each `@` that returns takes its module's next counter, as `elixir_dispatch:expand_quoted/7` gives it. */
    fun testEachAtThatReturnsTakesACounter() =
        assertCounted(
            """
            defmodule A do
              @x 1
              y = @x
              @type t :: integer
              def f, do: @x
            end
            """.trimIndent(),
        ) { 5 }

    /** A raise is the macro's, before `expand_quoted/7`, so it takes no counter; the `def` takes one. */
    fun testAnAtThatRaisesTakesNoCounter() =
        assertCounted(
            """
            defmodule A do
              def f, do: (@y 2)
            end
            """.trimIndent(),
        ) { 1 }

    /** Before 1.14.0-rc.0 `@behavior` returns the warning's `:ok`, and takes a counter; from it, it raises. */
    fun testBehaviorTakesACounterOnlyWhereItReturns() =
        assertCounted(
            """
            defmodule A do
              @behavior Foo
            end
            """.trimIndent(),
        ) { version -> if (isBefore(version, "1.14.0-rc.0")) 1 else 0 }

    /** The variables a doc read's `case` binds are `Kernel`'s, so they take the `@`'s counter. */
    fun testADocReadsVariablesTakeTheAtsCounter() =
        assertVariables(
            """
            defmodule A do
              @doc "a"
              y = @doc
            end
            """.trimIndent(),
        ) { version ->
            val fallback = if (isBefore(version, "1.18.4")) "other" else "value"

            "doc/{Elixir.A,2} $fallback/{Elixir.A,2}"
        }

    // The module body's effects

    fun testEffectsInTheOrderTheBodyRuns() =
        assertEvery(
            """
            defmodule A do
              Module.put_attribute(__MODULE__, :p, 3)
              Module.register_attribute(__MODULE__, :acc, accumulate: true)
              @acc 1
              Module.delete_attribute(__MODULE__, :p)
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write p 3 `Module.put_attribute(__MODULE__, :p, 3)`
              register acc accumulate true `Module.register_attribute(__MODULE__, :acc, accumulate: true)`
              write acc 1 `@acc 1`
              delete p `Module.delete_attribute(__MODULE__, :p)`
              final acc: [1]
              final p: :nil
            """.trimIndent(),
        )

    /** `for` checks its `uniq:` as it expanded, so an attribute holding a boolean is one. */
    fun testABooleanAttributeAsAForsUniqInADefinition() =
        assertEvery(
            """
            defmodule A do
              @u true
              def f, do: for(x <- [1], uniq: @u, do: x)
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write u :true `@u true`
              read u in def f/0: :true
              final u: :true
            """.trimIndent(),
        )

    /** An unknown attribute may hold a boolean, which a `for`'s `uniq:` must be, so the `for` stops, not raises. */
    fun testAnUnknownAttributeAsAForsUniqInADefinition() =
        assertEvery(
            """
            defmodule A do
              @u (receive do after 0 -> true end)
              def f, do: for(x <- [1], uniq: @u, do: x)
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `@u`
              write u node `@u (receive do after 0 -> true end)`
              read u in def f/0: unexpanded
              final u: unknown
            """.trimIndent(),
        )

    /** A call in a function runs only when the function does. */
    fun testAWriteInAFunctionBodyHasNoEffect() =
        assertEvery(
            """
            defmodule A do
              def f, do: Module.put_attribute(__MODULE__, :x, 1)
              def r_x, do: @x
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              read x in def r_x/0: :nil
            """.trimIndent(),
        )

    /** A refused value raises where the body runs it, so nothing after it is expanded. */
    fun testARefusedValue() =
        assertEvery(
            """
            defmodule A do
              @impl 1
              def r_x, do: @x
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised invalid_attribute_value `@impl 1`
              write impl 1 `@impl 1`
              final impl: :nil
            """.trimIndent(),
        )

    fun testABehaviourThatIsNotAModuleFrom1_13() =
        assertSplit(
            """
            defmodule A do
              @behaviour "a"
            end
            """.trimIndent(),
            "1.13.0-rc.0",
            """
            top expanded {} next 0
            module Elixir.A compiled
              write behaviour "a" `@behaviour "a"`
              final behaviour: ["a"]
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised invalid_attribute_value `@behaviour "a"`
              write behaviour "a" `@behaviour "a"`
              final behaviour: []
            """.trimIndent(),
        )

    /** A write that isn't a statement of the module body may run any number of times, so a later read is unknown. */
    fun testAWriteInACaseClause() =
        assertEvery(
            """
            defmodule A do
              case 1 do
                _ -> @x 1
              end
              def r_x, do: @x
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write x 1 not a statement `@x 1`
              read x in def r_x/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    fun testAValueWithNoTerm() =
        assertEvery(
            """
            defmodule A do
              @x self()
              def r_x, do: @x
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write x node `@x self()`
              read x in def r_x/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** An unknown attribute may hold a literal, so a check of what a literal may be stops rather than reporting. */
    fun testAnUnknownAttributeAsAUnitStops() =
        assertEvery(
            """
            defmodule A do
              @u (receive do after 0 -> 2 end)
              def g(y), do: <<y::size(4)-unit(@u)>>
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `unit(@u)`
              write u node `@u (receive do after 0 -> 2 end)`
              read u in def g/1: unexpanded
              final u: unknown
            """.trimIndent(),
        )

    /**
     * An unknown attribute may hold an integer, which from 1.16 a sign folds into a literal unit, so the unit stops.
     * Before it, the sign is a call, which no unit may be, and which 1.15's `number_size/2` can't multiply.
     */
    fun testAnUnknownAttributeUnderASignAsAUnitStopsFrom1_16() =
        assertLevels(
            """
            defmodule A do
              _ = fn -> @u 2 end
              def g(y), do: <<y::size(4)-unit(+@u)>>
            end
            """.trimIndent(),
            listOf("1.14.5", "1.15.8", "1.16.3", "1.20.4"),
        ) { version ->
            val segment = "`y::size(4)-unit(+@u)`"
            val attribute = "  write u 2 not a statement `@u 2`\n  read u in def g/1: unexpanded\n  final u: unknown"

            when (version) {
                "1.14.5" -> "top expanded {} next 0\nmodule Elixir.A raised bad_unit_argument $segment\n$attribute"
                "1.15.8" ->
                    "top expanded {} next 0\nmodule Elixir.A crashed bad_unit_argument $segment Elixir.ArithmeticError\n" +
                        "$attribute\n  reported bad_unit_argument $segment"
                else -> "top expanded {} next 0\nmodule Elixir.A stopped `+@u`\n$attribute"
            }
        }

    /** An unknown attribute may hold a binary, which `String.Chars.to_string/1` folds into, so the call stops. */
    fun testAnUnknownAttributeAsToStringsArgumentStops() =
        assertEvery(
            """
            defmodule A do
              @x (receive do after 0 -> "a" end)
              def g, do: String.Chars.to_string(@x).foo()
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `String.Chars.to_string(@x)`
              write x node `@x (receive do after 0 -> "a" end)`
              read x in def g/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** An unknown attribute interpolated in a pattern may hold a binary, which folds into the pattern, so it stops. */
    fun testAnUnknownAttributeInterpolatedInAPatternStops() =
        assertEvery(
            """
            defmodule A do
              @x (receive do after 0 -> "a" end)
              def g("#{@x}"), do: 1
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `@x`
              write x node `@x (receive do after 0 -> "a" end)`
              read x in def g/1: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** A `rescue ... in` list holding a known term other than an atom raises whatever an unknown attribute in it holds. */
    fun testAnUnknownAttributeBesideANonAtomInARescueRaises() =
        assertEvery(
            """
            defmodule A do
              _ = fn -> @e ArgumentError end
              def g, do: (try do :ok rescue x in [@e, 1] -> x end)
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised invalid_rescue_clause `x in [@e, 1] -> x`
              write e :Elixir.ArgumentError not a statement `@e ArgumentError`
              read e in def g/0: unexpanded
              final e: unknown
            """.trimIndent(),
        )

    fun testAnUnknownAttributeAsASizeStopsBefore1_14() =
        assertSplit(
            """
            defmodule A do
              @n (receive do after 0 -> 8 end)
              def f(b) do
                <<x::size(@n)>> = b
                x
              end
            end
            """.trimIndent(),
            "1.14.0-rc.0",
            """
            top expanded {} next 0
            module Elixir.A stopped `size(@n)`
              write n node `@n (receive do after 0 -> 8 end)`
              read n in def f/1: unexpanded
              final n: unknown
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write n node `@n (receive do after 0 -> 8 end)`
              read n in def f/1: unexpanded
              final n: unknown
            """.trimIndent(),
        )

    /**
     * In a definition an unknown attribute may hold a keyword list, which a directive's options must be, so the
     * directive stops, not raises.
     */
    fun testAnUnknownAttributeAsADirectivesOptionsInADefinition() =
        assertEvery(
            """
            defmodule A do
              @opts (receive do after 0 -> [only: [is_atom: 1]] end)
              def f do
                import Kernel, @opts
                is_atom(1)
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `@opts`
              write opts node `@opts (receive do after 0 -> [only: [is_atom: 1]] end)`
              read opts in def f/0: unexpanded
              final opts: unknown
            """.trimIndent(),
        )

    /** In the module body `@opts` is `Module.__get_attribute__/4`'s call, which is no keyword list. */
    fun testAnAttributeAsADirectivesOptionsInTheModuleBody() =
        assertEvery(
            """
            defmodule A do
              @opts (receive do after 0 -> [only: [is_atom: 1]] end)
              import Kernel, @opts
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised options_are_not_keyword `import Kernel, @opts`
              write opts node `@opts (receive do after 0 -> [only: [is_atom: 1]] end)`
              final opts: unknown
            """.trimIndent(),
        )

    /** An unknown attribute may hold `as:`, so an `alias` given it stops rather than naming the last segment. */
    fun testAnUnknownAttributeAsAnAliasesOptionsInADefinition() =
        assertEvery(
            """
            defmodule A do
              @opts (receive do after 0 -> [as: L] end)
              def f do
                alias :lists, @opts
                L.reverse([1, 2])
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `@opts`
              write opts node `@opts (receive do after 0 -> [as: L] end)`
              read opts in def f/0: unexpanded
              final opts: unknown
            """.trimIndent(),
        )

    /** An unknown attribute may hold what `except:` names, so the `import` stops rather than importing everything. */
    fun testAnUnknownAttributeAsAnImportsExceptInADefinition() =
        assertEvery(
            """
            defmodule A do
              @fns (receive do after 0 -> [is_atom: 1] end)
              def f do
                import Kernel, except: @fns
                is_atom(1)
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `except: @fns`
              write fns node `@fns (receive do after 0 -> [is_atom: 1] end)`
              read fns in def f/0: unexpanded
              final fns: unknown
            """.trimIndent(),
        )

    /** `validate_opts/5` reads only the keys, so an unsupported one raises whatever an unknown attribute holds. */
    fun testAnUnknownAttributeAsAnUnsupportedImportOptionInADefinition() =
        assertEvery(
            """
            defmodule A do
              _ = fn -> @x 1 end
              def f do
                import Kernel, foo: @x
                1
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised unsupported_option `import Kernel, foo: @x`
              write x 1 not a statement `@x 1`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    fun testAnUnknownAttributeAsAnUnsupportedAliasOptionInADefinition() =
        assertEvery(
            """
            defmodule A do
              _ = fn -> @x 1 end
              def f do
                alias :lists, as: L, foo: @x
                1
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised unsupported_option `alias :lists, as: L, foo: @x`
              write x 1 not a statement `@x 1`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** Before 1.17 an `only:` list beside any `except:` raises; from 1.17 `except:` is checked first. */
    fun testAnUnknownAttributeAsAnImportsExceptBesideAnOnlyListInADefinition() =
        assertSplit(
            """
            defmodule A do
              _ = fn -> @x [is_atom: 1] end
              def f do
                import Kernel, only: [is_atom: 1], except: @x
                1
              end
            end
            """.trimIndent(),
            "1.17.0-rc.0",
            """
            top expanded {} next 0
            module Elixir.A raised only_and_except_given `import Kernel, only: [is_atom: 1], except: @x`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `only: [is_atom: 1], except: @x`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    fun testAnUnknownAttributeInAnImportsOnlyListBesideExceptInADefinition() =
        assertEvery(
            """
            defmodule A do
              @x (receive do after 0 -> 1 end)
              def f do
                import Kernel, only: [is_atom: @x], except: [is_atom: 1]
                1
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `only: [is_atom: @x], except: [is_atom: 1]`
              write x node `@x (receive do after 0 -> 1 end)`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** Before 1.17 `only:` is checked first, so a value it doesn't take raises whatever `except:` holds. */
    fun testAnUnknownAttributeAsAnImportsExceptBesideAnInvalidOnlyInADefinition() =
        assertSplit(
            """
            defmodule A do
              _ = fn -> @x [is_atom: 1] end
              def f do
                import Kernel, only: :foo, except: @x
                1
              end
            end
            """.trimIndent(),
            "1.17.0-rc.0",
            """
            top expanded {} next 0
            module Elixir.A raised invalid_option `import Kernel, only: :foo, except: @x`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `only: :foo, except: @x`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** From 1.17 `except:` is checked first, so a value it doesn't take raises whatever `only:` holds. */
    fun testAnUnknownAttributeAsAnImportsOnlyBesideAnInvalidExceptInADefinition() =
        assertSplit(
            """
            defmodule A do
              _ = fn -> @x [is_atom: 1] end
              def f do
                import Kernel, only: @x, except: :foo
                1
              end
            end
            """.trimIndent(),
            "1.17.0-rc.0",
            """
            top expanded {} next 0
            module Elixir.A stopped `only: @x, except: :foo`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised invalid_option `import Kernel, only: @x, except: :foo`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** An `only:` list with a `|` tail is no keyword list, so it raises whatever the tail holds. */
    fun testAnUnknownAttributeAsTheTailOfAnImportsOnlyInADefinition() =
        assertEvery(
            """
            defmodule A do
              _ = fn -> @x [is_atom: 1] end
              def f do
                import Kernel, only: [{:is_list, 1} | @x]
                1
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised invalid_option `import Kernel, only: [{:is_list, 1} | @x]`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** `validate_opts/5` raises on any unsupported key, whatever an element before it holds. */
    fun testAnUnknownAttributeBesideAnUnsupportedImportOptionInADefinition() =
        assertEvery(
            """
            defmodule A do
              _ = fn -> @x [is_atom: 1] end
              def f do
                import Kernel, [@x, foo: 1]
                1
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised unsupported_option `import Kernel, [@x, foo: 1]`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** An unknown attribute may hold `:only`, so a key it gives stops rather than raising `unsupported_option`. */
    fun testAnUnknownAttributeAsAnImportsKeyInADefinition() =
        assertEvery(
            """
            defmodule A do
              @x (receive do after 0 -> :only end)
              def f do
                import Kernel, [{@x, [is_atom: 1]}]
                is_atom(1)
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `[{@x, [is_atom: 1]}]`
              write x node `@x (receive do after 0 -> :only end)`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** A `warn:` that isn't a boolean raises, so an unknown one stops beside options that compile. */
    fun testAnUnknownAttributeAsAnImportsWarnInADefinition() =
        assertEvery(
            """
            defmodule A do
              @x (receive do after 0 -> :maybe end)
              def f do
                import Kernel, only: [is_atom: 1], warn: @x
                1
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `only: [is_atom: 1], warn: @x`
              write x node `@x (receive do after 0 -> :maybe end)`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    fun testAnUnknownAttributeAsAKeyBesideAnUnsupportedRequireOptionInADefinition() =
        assertEvery(
            """
            defmodule A do
              _ = fn -> @x [is_atom: 1] end
              def f do
                require Kernel, [{@x, L}, foo: 1]
                1
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A raised unsupported_option `require Kernel, [{@x, L}, foo: 1]`
              write x [{:is_atom, 1}] not a statement `@x [is_atom: 1]`
              read x in def f/0: unexpanded
              final x: unknown
            """.trimIndent(),
        )

    /** An attribute holding a list is no unit, as Elixir reports, and from 1.15 its size then crashes. */
    fun testAListAttributeAsAUnitIsReported() =
        assertSplit(
            """
            defmodule A do
              @l [1]
              def g(y), do: <<y::size(4)-unit(@l)>>
            end
            """.trimIndent(),
            "1.15.0-rc.0",
            """
            top expanded {} next 0
            module Elixir.A raised bad_unit_argument `y::size(4)-unit(@l)`
              write l [1] `@l [1]`
              read l in def g/1: [1]
              final l: [1]
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A crashed bad_unit_argument `y::size(4)-unit(@l)` Elixir.ArithmeticError
              write l [1] `@l [1]`
              read l in def g/1: [1]
              final l: [1]
              reported bad_unit_argument `y::size(4)-unit(@l)`
            """.trimIndent(),
        )

    /** A float is no unit, as Elixir reports, but from 1.15 its size is a number, so only the report fails. */
    fun testAMacrosFloatAsAUnitIsReported() =
        assertSplit(
            """
            defmodule A do
              defmacrop fl, do: 2.0
              def g(y), do: <<y::size(4)-unit(fl())>>
            end
            """.trimIndent(),
            "1.15.0-rc.0",
            """
            top expanded {} next 0
            module Elixir.A raised bad_unit_argument `y::size(4)-unit(fl())`
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A tainted
              reported bad_unit_argument `y::size(4)-unit(fl())`
            """.trimIndent(),
        )

    /** In 1.15 `-2.0` is `:erlang.-/1`'s call, which `number_size/2` can't multiply; from 1.16 it folds to a float. */
    fun testANegatedFloatAsAUnitIsReported() =
        assertLevels(
            """
            defmodule A do
              def g(y), do: <<y::size(4)-unit(-2.0)>>
            end
            """.trimIndent(),
            listOf("1.14.5", "1.15.8", "1.16.3", "1.20.4"),
        ) { version ->
            val segment = "`y::size(4)-unit(-2.0)`"

            when (version) {
                "1.14.5" -> "top expanded {} next 0\nmodule Elixir.A raised bad_unit_argument $segment"
                "1.15.8" ->
                    "top expanded {} next 0\nmodule Elixir.A crashed bad_unit_argument $segment Elixir.ArithmeticError\n" +
                        "  reported bad_unit_argument $segment"
                else -> "top expanded {} next 0\nmodule Elixir.A tainted\n  reported bad_unit_argument $segment"
            }
        }

    /** A bitstring's unit must be 1, which a float's may be, so from 1.16 a folded float stops after its report. */
    fun testAFloatUnitOnABitstringStops() =
        assertLevels(
            """
            defmodule A do
              def g(y), do: <<y::bitstring-unit(+1.0)>>
            end
            """.trimIndent(),
            listOf("1.14.5", "1.15.8", "1.16.3", "1.20.4"),
        ) { version ->
            val segment = "`y::bitstring-unit(+1.0)`"

            when (version) {
                "1.14.5" -> "top expanded {} next 0\nmodule Elixir.A raised bad_unit_argument $segment"
                "1.15.8" ->
                    "top expanded {} next 0\nmodule Elixir.A tainted\n  reported bad_unit_argument $segment\n" +
                        "  reported bittype_mismatch $segment"
                else -> "top expanded {} next 0\nmodule Elixir.A stopped $segment\n  reported bad_unit_argument $segment"
            }
        }

    /** A repeated spec matches its earlier value exactly, so a float is never the integer before it. */
    fun testAFloatUnitAfterAnIntegerUnitIsAMismatch() =
        assertLevels(
            """
            defmodule A do
              def g(y), do: <<y::size(4)-unit(2)-unit(+2.0)>>
            end
            """.trimIndent(),
            listOf("1.14.5", "1.16.3", "1.20.4"),
        ) { version ->
            val segment = "`y::size(4)-unit(2)-unit(+2.0)`"

            if (version == "1.14.5") {
                "top expanded {} next 0\nmodule Elixir.A raised bad_unit_argument $segment"
            } else {
                "top expanded {} next 0\nmodule Elixir.A tainted\n  reported bad_unit_argument $segment\n" +
                    "  reported bittype_mismatch $segment"
            }
        }

    /** A float is never the list before it, so the units conflict. */
    fun testAFloatUnitAfterAListUnitIsAMismatch() =
        assertLevels(
            """
            defmodule A do
              @l [1]
              def g(y), do: <<y::size(4)-unit(@l)-unit(+2.0)>>
            end
            """.trimIndent(),
            listOf("1.14.5", "1.16.3", "1.20.4"),
        ) { version ->
            val segment = "`y::size(4)-unit(@l)-unit(+2.0)`"
            val l = "  write l [1] `@l [1]`\n  read l in def g/1: [1]\n  final l: [1]"

            if (version == "1.14.5") {
                "top expanded {} next 0\nmodule Elixir.A raised bad_unit_argument $segment\n$l"
            } else {
                "top expanded {} next 0\nmodule Elixir.A tainted\n$l\n  reported bad_unit_argument $segment\n" +
                    "  reported bad_unit_argument $segment\n  reported bittype_mismatch $segment"
            }
        }

    /** Two floats whose values aren't kept may be the same, so the spec stops after their reports. */
    fun testTwoFloatUnitsStop() =
        assertLevels(
            """
            defmodule A do
              def g(y), do: <<y::size(4)-unit(+2.0)-unit(+2.0)>>
            end
            """.trimIndent(),
            listOf("1.16.3", "1.20.4"),
        ) { _ ->
            val segment = "`y::size(4)-unit(+2.0)-unit(+2.0)`"

            "top expanded {} next 0\nmodule Elixir.A stopped `unit(+2.0)`\n  reported bad_unit_argument $segment\n" +
                "  reported bad_unit_argument $segment"
        }

    /** An unknown attribute may hold a module, so a struct of it stops rather than raising. */
    fun testAnUnknownAttributeAsAStructNameInADefinition() =
        assertEvery(
            """
            defmodule A do
              @s (receive do after 0 -> URI end)
              def f, do: %@s{}
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `%@s{}`
              write s node `@s (receive do after 0 -> URI end)`
              read s in def f/0: unexpanded
              final s: unknown
            """.trimIndent(),
        )

    fun testAnUnknownAttributeAsAStructNameInAPattern() =
        assertEvery(
            """
            defmodule A do
              @s (receive do after 0 -> URI end)
              def f(%@s{} = u), do: u
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `%@s{}`
              write s node `@s (receive do after 0 -> URI end)`
              read s in def f/1: unexpanded
              final s: unknown
            """.trimIndent(),
        )

    /** An unknown attribute may hold a boolean, which `quote`'s `generated:` must be, so the `quote` stops. */
    fun testAnUnknownAttributeAsAQuotesGeneratedOption() =
        assertUnknownStops(
            "@g (receive do after 0 -> true end)",
            "def f, do: quote(generated: @g, do: 1)",
            "@g",
            "g",
            "def f/0",
        )

    /** An unknown attribute may hold an exception's module, which `rescue x in` must name, so the `rescue` stops. */
    fun testAnUnknownAttributeAsARescuesModules() =
        assertUnknownStops(
            "@e (receive do after 0 -> ArgumentError end)",
            "def f do\n    try do\n      :ok\n    rescue\n      x in @e -> x\n    end\n  end",
            "@e",
            "e",
            "def f/0",
        )

    /** An unknown attribute may hold 16, 32 or 64, which a float's size must be, so the segment stops. */
    fun testAnUnknownAttributeAsAFloatsSize() =
        assertUnknownStops(
            "@n :erlang.length(:lists.duplicate(64, 1))",
            "def g(y), do: <<y::float-size(@n)>>",
            "size(@n)",
            "n",
            "def g/1",
        )

    /** An unknown attribute may hold a module, so a remote call on it in a guard stops rather than reading a field. */
    fun testAnUnknownAttributeAsAGuardsReceiver() =
        assertUnknownStops(
            "@m (receive do after 0 -> Kernel end)",
            "def g(x) when @m.is_atom(x), do: x",
            "@m",
            "m",
            "def g/1",
        )

    /** An unknown attribute may hold a module, so an alias with it as its head stops rather than raising. */
    fun testAnUnknownAttributeAsAnAliasHead() =
        assertUnknownStops(
            "@m (receive do after 0 -> Kernel end)",
            "def f, do: @m.Sub",
            "@m",
            "m",
            "def f/0",
        )

    /** An unknown attribute may hold a struct's key, so a struct with it as a key stops rather than raising. */
    fun testAnUnknownAttributeAsAStructKey() =
        assertUnknownStops(
            "@k (receive do after 0 -> :host end)",
            "def f, do: %URI{@k => \"h\"}",
            "@k",
            "k",
            "def f/0",
        )

    /**
     * `A`, which writes [write] and then has [definition], whose read of the attribute [name] is unknown, stops at
     * [at] on every level.
     */
    private fun assertUnknownStops(write: String, definition: String, at: String, name: String, reader: String) =
        assertEvery(
            "defmodule A do\n  $write\n  $definition\nend",
            """
            top expanded {} next 0
            module Elixir.A stopped `$at`
              write $name node `$write`
              read $name in $reader: unexpanded
              final $name: unknown
            """.trimIndent(),
        )

    /** An unknown attribute may hold a module, so a directive of it stops rather than raising. */
    fun testAnUnknownAttributeAsADirectivesModuleInADefinition() =
        assertEvery(
            """
            defmodule A do
              @m (receive do after 0 -> URI end)
              def f do
                alias @m
                :ok
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `alias @m`
              write m node `@m (receive do after 0 -> URI end)`
              read m in def f/0: unexpanded
              final m: unknown
            """.trimIndent(),
        )

    /** An unknown attribute may hold an integer or a binary, which no call can be made on, so a call on it stops. */
    fun testAnUnknownAttributeAsAReceiver() =
        assertUnknownStops(
            "@m (receive do after 0 -> String end)",
            "def r_m, do: @m.length(\"a\")",
            "@m",
            "m",
            "def r_m/0",
        )

    // Positional reads

    /** A definition reads each attribute as it stands where the definition is, in the order the body runs. */
    fun testPositionalReads() =
        assertEvery(
            """
            defmodule A do
              @x 1
              def r_a, do: @x
              @x 2
              def r_b, do: @x
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write x 1 `@x 1`
              write x 2 `@x 2`
              read x in def r_a/0: 1
              read x in def r_b/0: 2
              final x: 2
            """.trimIndent(),
        )

    fun testReadsOfEachKindOfWrite() =
        assertEvery(
            """
            defmodule A do
              Module.register_attribute(__MODULE__, :acc, accumulate: true)
              @acc 1
              def r_acc1, do: @acc
              @acc 2
              def r_acc2, do: @acc
              Module.put_attribute(__MODULE__, :p, 3)
              def r_p, do: @p
              Module.delete_attribute(__MODULE__, :p)
              def r_d, do: @p
              def r_n, do: @n
              @n 1
              @x 2
              def r_default(a \\ @x), do: a
              def r_parens, do: @x()
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              register acc accumulate true `Module.register_attribute(__MODULE__, :acc, accumulate: true)`
              write acc 1 `@acc 1`
              write acc 2 `@acc 2`
              write p 3 `Module.put_attribute(__MODULE__, :p, 3)`
              delete p `Module.delete_attribute(__MODULE__, :p)`
              write n 1 `@n 1`
              write x 2 `@x 2`
              read acc in def r_acc1/0: [1]
              read acc in def r_acc2/0: [2, 1]
              read p in def r_p/0: 3
              read p in def r_d/0: :nil
              read n in def r_n/0: :nil
              read x in def r_default/1: 2
              read x in def r_parens/0: 2
              final acc: [2, 1]
              final n: 1
              final p: :nil
              final x: 2
            """.trimIndent(),
        )

    fun testReadsOfLiterals() =
        assertEvery(
            """
            defmodule A do
              @m Foo.Bar
              @mm __MODULE__
              @k [1, a: :b, c: {1, 2}]
              @s "str"
              def r_m, do: @m
              def r_mm, do: @mm
              def r_k, do: @k
              def r_s, do: @s
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write m :Elixir.Foo.Bar `@m Foo.Bar`
              write mm :Elixir.A `@mm __MODULE__`
              write k [1, {:a, :b}, {:c, {1, 2}}] `@k [1, a: :b, c: {1, 2}]`
              write s "str" `@s "str"`
              read m in def r_m/0: :Elixir.Foo.Bar
              read mm in def r_mm/0: :Elixir.A
              read k in def r_k/0: [1, {:a, :b}, {:c, {1, 2}}]
              read s in def r_s/0: "str"
              final k: [1, {:a, :b}, {:c, {1, 2}}]
              final m: :Elixir.Foo.Bar
              final mm: :Elixir.A
              final s: "str"
            """.trimIndent(),
        )

    /** A nested module has attributes of its own. */
    fun testANestedModuleDoesNotReadTheOuterModules() =
        assertEvery(
            """
            defmodule A do
              @x 1
              defmodule Inner do
                def r_outer, do: @x
              end
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write x 1 `@x 1`
              final x: 1
              module Elixir.A.Inner compiled
                read x in def r_outer/0: :nil
            """.trimIndent(),
        )

    // What each definition takes

    /** A definition takes `@doc` and `@impl` once its own body has read them, so a later definition reads `nil`. */
    fun testADefinitionTakesItsDocAndImpl() =
        assertEvery(
            """
            defmodule A do
              @doc "hi"
              def r_doc, do: @doc
              def r_doc_after, do: @doc
              @impl true
              def r_impl, do: @impl
              def r_impl_after, do: @impl
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write doc {2, "hi"} `@doc "hi"`
              write impl :true `@impl true`
              read doc in def r_doc/0: "hi"
              read doc in def r_doc_after/0: :nil
              read impl in def r_impl/0: :true
              read impl in def r_impl_after/0: :nil
              final doc: :nil
              final impl: :nil
              attributes r_doc/0: doc "hi"
              attributes r_impl/0: impls [:true]; doc :false
            """.trimIndent(),
        )

    fun testWhatEachKindOfDefinitionTakes() =
        assertEvery(
            """
            defmodule A do
              @impl false
              def cb_false, do: 1
              @deprecated "old"
              def f(a, b \\ 1), do: {a, b}
              @doc "a"
              def m(1), do: 1
              def m(2), do: 2
              @doc "p"
              defp p, do: 1
              def r_doc, do: @doc
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write impl :false `@impl false`
              write deprecated "old" `@deprecated "old"`
              write doc {6, "a"} `@doc "a"`
              write doc {9, "p"} `@doc "p"`
              read doc in def r_doc/0: :nil
              final deprecated: :nil
              final doc: :nil
              final impl: :nil
              attributes cb_false/0: impls [:false]; doc :nil
              attributes f/2: doc :nil; deprecated "old"
              attributes f/1: deprecated "old"
              attributes m/1: doc "a"
            """.trimIndent(),
        )

    /** A definition that isn't a statement may take them at any point, so a later read is unknown. */
    fun testADefinitionThatIsNotAStatement() =
        assertEvery(
            """
            defmodule A do
              @doc "a"
              case 1 do
                _ -> def f, do: 1
              end
              def r_doc, do: @doc
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A compiled
              write doc {2, "a"} `@doc "a"`
              read doc in def r_doc/0: unexpanded
              final doc: unknown
              attributes f/0: doc "a"
              attributes r_doc/0: impls [unknown]; doc unknown; deprecated unknown
            """.trimIndent(),
        )

    /** A head with a splice has no arity the expander can read, so its `@doc` is an unnamed definition's. */
    fun testADocBeforeASplicedHeadIsUnnamed() =
        assertEvery(
            """
            defmodule S do
              args = [1, 2]
              @doc "s"
              def spliced(unquote_splicing(args)), do: :ok
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.S stopped `unquote_splicing(args)`
              write doc {3, "s"} `@doc "s"`
              final doc: :nil
              attributes unnamed: doc "s"
            """.trimIndent(),
        )

    fun testAnUnnamedDefinition() =
        assertEvery(
            """
            defmodule A do
              @doc "a"
              def unquote(String.to_atom("f"))(), do: 1
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `unquote(String.to_atom("f"))`
              write doc {2, "a"} `@doc "a"`
              final doc: :nil
              attributes unnamed: doc "a"
            """.trimIndent(),
        )

    /**
     * A definition takes `@file` before its name is checked, so the body ends without it. A later definition would take
     * it too, so none follows.
     */
    fun testAnUnnamedDefinitionTakesFile() =
        assertEvery(
            """
            defmodule A do
              @file "x.ex"
              def unquote(String.to_atom("f"))(), do: 1
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `unquote(String.to_atom("f"))`
              write file "x.ex" `@file "x.ex"`
              final file: :nil
            """.trimIndent(),
        )

    fun testADefinitionNamedByAnAliasTakesFile() =
        assertEvery(
            """
            defmodule A do
              @file "x.ex"
              def Foo.Bar, do: 1
            end
            """.trimIndent(),
            """
            top expanded {} next 0
            module Elixir.A stopped `Foo.Bar`
              write file "x.ex" `@file "x.ex"`
              final file: :nil
            """.trimIndent(),
        )

    /** `@behaviour` as the body leaves it, and each clause's `@impl` by definition. */
    fun testBehavioursAndImpls() {
        val code = """
            defmodule A do
              @behaviour Foo
              @behaviour Bar
              @impl true
              def f(1), do: 1
              @impl Bar
              def f(2), do: 2
              def g, do: 1
            end
        """.trimIndent()

        for (version in LEVELS) {
            val level = ElixirLanguageLevel.of(version)
            val log = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)
                .modules.single().attributes
            val impls = log.impls.map { (nameArity, impls) ->
                "impls ${nameArity.name}/${nameArity.arity}: " +
                    impls.joinToString { "${it.kind.name.lowercase()} ${it.line} ${render(it.value)}" }
            }

            assertEquals(
                version,
                "behaviours [:Elixir.Bar, :Elixir.Foo]\nimpls f/1: def 5 :true, def 7 :Elixir.Bar",
                (listOf("behaviours ${log.behaviours?.let(::render)}") + impls).joinToString("\n"),
            )
        }
    }

    /**
     * `@before_compile`'s entries are dispatched once the body has run, oldest first, each as a required call of the
     * module's env; the first the expander doesn't model stops the module there.
     */
    fun testBeforeCompile() {
        val code = """
            defmodule A do
              @before_compile Foo
              @before_compile {Bar, :b}
              def f, do: 1
            end
        """.trimIndent()

        for (version in LEVELS) {
            val level = ElixirLanguageLevel.of(version)
            val ended = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)
                .modules.single().ended
            val at = (ended as? ExpansionResult.Ended.Stopped)?.at as? ElixirAst.Call

            assertEquals(
                version,
                "Elixir.Foo.__before_compile__(__ENV__) [line 1, required true]",
                render(at) ?: "$ended",
            )
        }
    }

    // Rendering

    /**
     * Each module's effects, reads, the final values of the attributes its effects name, what each definition took,
     * and its errors. A definition whose doc is `nil` and which took nothing else isn't shown.
     */
    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return (listOf("top " + render(code, file.top)) + file.modules.flatMap { renderModule(code, it, "") })
            .joinToString("\n")
    }

    private fun renderModule(code: String, result: ExpansionResult, indent: String): List<String> {
        val ended = when (val ended = result.ended) {
            ExpansionResult.Ended.Compiled -> "compiled"
            ExpansionResult.Ended.Tainted -> "tainted"
            is ExpansionResult.Ended.Raised -> "raised ${ended.error.kind} `${source(code, ended.error.at)}`"
            is ExpansionResult.Ended.Crashed ->
                "crashed ${ended.error.kind} `${source(code, ended.error.at)}` ${ended.exception}"
            is ExpansionResult.Ended.Stopped -> "stopped `${source(code, ended.at)}`"
        }
        val log = result.attributes
        val effects = log.effects.map { logged ->
            "${render(logged.effect)}${if (logged.statement) "" else " not a statement"} `${source(code, logged.at)}`"
        }
        val reads = log.reads.map { "read ${it.name} in ${render(it.owner)}: ${render(it.value)}" }
        val named = log.effects.mapNotNull { named(it.effect) }.toSortedSet()
        val finals = named.map { "final $it: ${log.final[it]?.let(::render) ?: ":nil"}" }
        val definitions =
            log.definitions.mapNotNull { (nameArity, taken) ->
                render(taken)?.let { "attributes ${nameArity.name}/${nameArity.arity}: $it" }
            } + log.unnamed.mapNotNull { taken -> render(taken)?.let { "attributes unnamed: $it" } }
        val errors = result.errors.map { "reported ${it.kind} `${source(code, it.at)}`" }

        return buildList {
            add("${indent}module ${result.module} $ended")
            listOf(effects, reads, finals, definitions, errors).flatten().mapTo(this) { "$indent  $it" }
            result.nested.flatMapTo(this) { renderModule(code, it, "$indent  ") }
        }
    }

    /** A built remote call: `M.f(args)` and its meta's keys. */
    private fun render(call: ElixirAst.Call?): String? {
        val dot = call?.callee as? ElixirAst.Call ?: return null
        val (receiver, name) = dot.arguments.orEmpty().map { (it as? ElixirAst.Literal.Atom)?.name ?: return null }
        val arguments = call.arguments.orEmpty().joinToString(", ") { argument ->
            ((argument as? ElixirAst.Call)?.callee as? ElixirAst.Literal.Atom)?.name ?: "$argument"
        }
        val keys = call.meta.keys.joinToString(", ", "[", "]") { key ->
            when (key) {
                is Meta.Key.Location -> "line ${key.position.line}"
                is Meta.Key.Entry -> "${key.name} ${(key.value as? Meta.Value.Atom)?.name ?: key.value}"
            }
        }

        return "$receiver.$name($arguments) $keys"
    }

    private fun render(effect: Effect): String =
        when (effect) {
            is Effect.Write -> "write ${effect.name} ${render(effect.value)}"
            is Effect.Register -> "register ${effect.name} accumulate ${effect.accumulate}"
            is Effect.Delete -> "delete ${effect.name}"
            is Effect.TypespecWrite -> "typespec ${effect.name}"
            Effect.UnknownEffect -> "unknown effect"
            is Effect.OtherModule -> "${render(effect.effect)} in ${effect.module ?: "an unknown module"}"
        }

    private fun named(effect: Effect): String? =
        when (effect) {
            is Effect.Write -> effect.name
            is Effect.Register -> effect.name
            is Effect.Delete -> effect.name
            is Effect.TypespecWrite -> effect.name
            Effect.UnknownEffect, is Effect.OtherModule -> null
        }

    private fun render(owner: ExpansionResult.Owner): String =
        when (owner) {
            ExpansionResult.Owner.ModuleBody -> "body"
            is ExpansionResult.Owner.Definition ->
                "${owner.kind.name.lowercase()} " + (owner.name?.let { "$it/${owner.arity}" } ?: "unnamed")
        }

    private fun render(taken: AttributeLog.DefinitionAttributes): String? {
        val parts = listOfNotNull(
            taken.impls.takeIf { it.isNotEmpty() }?.joinToString(", ", "impls [", "]") { render(it.value) },
            taken.doc?.let { "doc ${render(it)}" },
            taken.deprecated?.let { "deprecated ${render(it)}" },
        )

        return parts.takeUnless { it == listOf("doc :nil") || it.isEmpty() }?.joinToString("; ")
    }

    private fun render(value: AttributeValue): String =
        when (value) {
            is AttributeValue.Known -> render(value.term)
            AttributeValue.Unknown -> "unknown"
        }

    private fun render(term: Term): String =
        when (term) {
            is Term.Atom -> ":${term.name}"
            is Term.Integer -> term.value.toString()
            is Term.Binary -> term.bytes?.let { "\"${String(it)}\"" } ?: "binary"
            is Term.List ->
                term.elements.joinToString(", ", "[", "") { render(it) } +
                    (term.tail?.let { " | ${render(it)}" } ?: "") + "]"
            is Term.Pair -> "{${render(term.first)}, ${render(term.second)}}"
            is Term.Tuple -> term.elements.joinToString(", ", "{", "}") { render(it) }
            is Term.Node -> "node"
            Term.NonTuple -> "non-tuple"
            Term.Unexpanded -> "unexpanded"
        }

    private fun source(code: String, node: ElixirAst) = node.meta.origin.substring(code)

    /** The module body of [code]'s only module expands to [expected] at every level in [LEVELS]. */
    private fun assertBody(code: String, expected: (String) -> String) =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it)}" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)
                val body = file.modules.single().units.first { it.owner == ExpansionResult.Owner.ModuleBody }

                "$version: ${render(code, body.expansion)}"
            },
        )

    /** The dispatches [code]'s expansion reports, in order, at each of [versions]. */
    private fun assertEvents(code: String, versions: List<String>, expected: (String) -> String) =
        assertEquals(
            versions.joinToString("\n") { "$it: ${expected(it)}" },
            versions.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val events = mutableListOf<String>()
                val observer = object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                        events += render(dispatch)
                    }
                }

                Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, observer)

                "$version: ${events.joinToString("; ")}"
            },
        )

    /** The counters `Elixir.A` gives in [code]'s expansion, at every level in [LEVELS]. */
    private fun assertCounted(code: String, expected: (String) -> Long) =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it)}" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val counters = Counters()

                Expander.expandFile(
                    lower(code, level), Env.empty(level, kernel), level, exports, structs, counters = counters
                )

                "$version: ${counters.count("Elixir.A")}"
            },
        )

    /** Every variable read in a state [code]'s expansion enters a node with, with its context, at every level. */
    private fun assertVariables(code: String, expected: (String) -> String) =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it)}" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val variables = sortedSetOf<String>()
                val observer = ExpansionObserver { _, state, _ ->
                    state.read.keys.mapTo(variables) { "${it.name}/${render(it.context)}" }
                }

                Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, observer)

                "$version: ${variables.joinToString(" ")}"
            },
        )

    private fun render(context: Variable.Context): String =
        when (context) {
            is Variable.Context.Atom -> context.text
            is Variable.Context.Counter -> when (val counter = context.counter) {
                is Env.Counter.InModule -> "{${counter.module},${counter.n}}"
                is Env.Counter.Unique -> "${counter.n}"
            }
        }

    private companion object {
        const val AT = "imported_macro Elixir.Kernel.@/1"
    }
}
