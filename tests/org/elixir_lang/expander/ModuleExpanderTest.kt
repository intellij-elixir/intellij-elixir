package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

/**
 * Files expanded as Elixir compiles them: each module's body, then its definitions' bodies and nested modules in the
 * order Elixir evaluates the body, the table of what it defines, its errors, and how it ended.
 */
class ModuleExpanderTest : ExpanderTestCase() {
    override val exports: Exports = ModuleFixtures.EXPORTS
    override val kernel: KernelImports = ModuleFixtures.KERNEL

    // Definitions

    fun testOneDefinitionOfEachKind() =
        assertLevels(
            """
            defmodule A do
              def a(x), do: x
              defp b(x), do: x
              defmacro c(x), do: x
              defmacrop d(x), do: x
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def a/1 line 2 clauses 1
              defp b/1 line 3 clauses 1
              defmacro c/1 line 4 clauses 1
              defmacrop d/1 line 5 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def a/1: expanded {x:0} next 1
              defp b/1: expanded {x:0} next 1
              defmacro c/1: expanded {x:0} next 1
              defmacrop d/1: expanded {x:0} next 1
            """.trimIndent()
        }

    fun testEachDefaultArityIsAnEntry() =
        assertLevels(
            """
            defmodule A do
              def f(a, b \\ 1, c \\ 2), do: {a, b, c}
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/3 line 2 clauses 1 defaults 2
              def f/1 line 2 clauses 1 default
              def f/2 line 2 clauses 1 default
              body: expanded {} next 0 context [Elixir.A]
              def f/3: expanded {a:0 b:1 c:2} next 3
            """.trimIndent()
        }

    /** Each default is expanded in the function's env, so a directive in one isn't seen by the next. */
    fun testADefaultDoesNotSeeTheDirectivesOfAnother() =
        assertEvents("defmodule A do\n  def f(a \\\\ (alias Foo.Bar; 1), b \\\\ Bar.g()), do: {a, b}\nend\n:ok", LEVELS) {
            "imported_macro Elixir.Kernel.defmodule/2; imported_macro Elixir.Kernel.def/2; " +
                "remote_function Elixir.Bar.g/0"
        }

    /** Before 1.14 each default is expanded from the state before the defaults, so `x` in the second is a call. */
    fun testADefaultSeesTheVariablesOfAnEarlierOneFrom1_14() =
        assertLevels(
            """
            defmodule A do
              def f(a \\ (x = 1), b \\ x), do: {a, b}
            end
            """.trimIndent(),
            LEVELS + "1.14.0-rc.0",
        ) { version ->
            val ended = if (isBefore(version, "1.14.0-rc.0")) "raised undefined_function `x`" else "compiled"

            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A $ended
              def f/2 line 2 clauses 1 defaults 2
              def f/0 line 2 clauses 1 default
              def f/1 line 2 clauses 1 default
              body: expanded {} next 0 context [Elixir.A]
              def f/2: expanded {a:0 b:1} next 2
            """.trimIndent()
        }

    /**
     * From 1.18 each default arity is checked as a definition of its own, which calls the full arity with the defaults
     * from its first missing argument on.
     */
    fun testTheCallsInDefaults() =
        assertLevels(
            """
            defmodule A do
              def f(a \\ u(), b \\ v()), do: w()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val definitions = """
                def f/2 line 2 clauses 1 defaults 2
                def f/0 line 2 clauses 1 default
                def f/1 line 2 clauses 1 default
                body: expanded {} next 0 context [Elixir.A]
                def f/2: expanded {a:0 b:1} next 2
            """.trimIndent().prependIndent("  ")

            if (isBefore(version, "1.15.0-rc.0")) {
                "top expanded {} next 0 context ${topContext(version, "Elixir.A")}\n" +
                    "module Elixir.A raised undefined_function `u()`\n$definitions"
            } else {
                val calls = when {
                    isBefore(version, "1.18.0-rc.0") -> "u v w"
                    isBefore(version, "1.20.0-rc.0") -> "w v u v"
                    else -> "w u v v"
                }

                "top expanded {} next 0 context []\nmodule Elixir.A tainted\n$definitions\n" +
                    calls.split(" ").joinToString("\n") { "  reported undefined_function `$it()`" }
            }
        }

    /**
     * A macro's default clause calls the macro's own function, so that call is no `incorrect_dispatch`, and from 1.18
     * the macro is visited there as a function is.
     */
    fun testTheCallsInTheDefaultsOfAMacro() =
        assertLevels(
            """
            defmodule A do
              defmacro m(a \\ u(), b \\ v()), do: w()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val definitions = """
                defmacro m/2 line 2 clauses 1 defaults 2
                defmacro m/0 line 2 clauses 1 default
                defmacro m/1 line 2 clauses 1 default
                body: expanded {} next 0 context [Elixir.A]
                defmacro m/2: expanded {a:0 b:1} next 2
            """.trimIndent().prependIndent("  ")

            if (isBefore(version, "1.15.0-rc.0")) {
                "top expanded {} next 0 context ${topContext(version, "Elixir.A")}\n" +
                    "module Elixir.A raised undefined_function `u()`\n$definitions"
            } else {
                val calls = when {
                    isBefore(version, "1.18.0-rc.0") -> "u v w"
                    isBefore(version, "1.20.0-rc.0") -> "w v u v"
                    else -> "w u v v"
                }

                "top expanded {} next 0 context []\nmodule Elixir.A tainted\n$definitions\n" +
                    calls.split(" ").joinToString("\n") { "  reported undefined_function `$it()`" }
            }
        }

    /** Each argument that isn't a variable is an error at the `def`, and the head is stored with no clauses. */
    fun testABodilessHeadWithPatterns() =
        assertLevels(
            """
            defmodule A do
              def f(1, 2)
              def f(a, b), do: {a, b}
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised invalid_function_head `def f(1, 2)`
                  body: expanded {} next 0 context [Elixir.A]
                  def f/2: error invalid_function_head `def f(1, 2)`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A tainted
                  def f/2 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/2: expanded {} next 0
                  def f/2: expanded {a:0 b:1} next 2
                  reported invalid_function_head `def f(1, 2)`
                  reported invalid_function_head `def f(1, 2)`
                """.trimIndent()
            }
        }

    /** Before 1.20.0-rc.2 a guarded bodiless head matches no bodiless clause, and is missing its `do`. */
    fun testAGuardedBodilessHead() =
        assertLevels(
            """
            defmodule A do
              def g(1) when true
              def g(a), do: a
            end
            """.trimIndent(),
            LEVELS + "1.20.0-rc.2",
        ) { version ->
            if (isBefore(version, "1.20.0-rc.2")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised missing_option `def g(1) when true`
                  body: expanded {} next 0 context [Elixir.A]
                  def g/1: error missing_option `def g(1) when true`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A tainted
                  def g/1 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def g/1: expanded {} next 0
                  def g/1: expanded {a:0} next 1
                  reported invalid_function_head `def g(1) when true`
                  reported invalid_function_head `def g(1) when true`
                """.trimIndent()
            }
        }

    fun testAnUnquotedNameAndTheSameNameAreOneDefinition() =
        assertLevels(
            """
            defmodule A do
              def unquote(:bare)(x), do: x
              def bare(x), do: x
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def bare/1 line 2 clauses 2
              body: expanded {} next 0 context [Elixir.A]
              def bare/1: expanded {x:0} next 1
              def bare/1: expanded {x:0} next 1
            """.trimIndent()
        }

    /** A head named `defmodule` defines `defmodule/2`, guarded or not, and no module. */
    fun testADefinitionNamedDefmodule() =
        assertLevels(
            """
            defmodule A do
              def defmodule(name, do: block), do: {name, block}
              def defmodule(name, do: block) when name == :a, do: block
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def defmodule/2 line 2 clauses 2
              body: expanded {} next 0 context [Elixir.A]
              def defmodule/2: expanded {block:1 name:0} next 2
              def defmodule/2: expanded {block:1 name:0} next 2
            """.trimIndent()
        }

    /** A definition that isn't a statement of the module body, and every body after it, may run in another order. */
    fun testADefinitionInACaseClauseIsUnordered() =
        assertLevels(
            """
            defmodule A do
              case 1 do
                _ -> def f, do: 1
              end
              def g, do: 2
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 3 clauses 1 unordered
              def g/0 line 5 clauses 1
              body: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 0 else 2} context [Elixir.A]
              def f/0 unordered: expanded {} next 0
              def g/0 unordered: expanded {} next 0
            """.trimIndent()
        }

    fun testADefinitionInAnotherCallsArgumentsIsUnordered() =
        assertLevels(
            """
            defmodule A do
              {def(f, do: 1), :ok}
              def g, do: 2
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 2 clauses 1 unordered
              def g/0 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0 unordered: expanded {} next 0
              def g/0 unordered: expanded {} next 0
            """.trimIndent()
        }

    /** A definition's body has the env of the line that defines it. */
    fun testADirectiveAfterADefinitionIsNotSeenInItsBody() =
        assertLevels(
            """
            defmodule A do
              def f, do: Baz
              alias Foo.Baz
              def g, do: Baz
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 2 clauses 1
              def g/0 line 4 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: expanded {} next 0 value Elixir.Baz
              def g/0: expanded {} next 0 value Elixir.Foo.Baz
            """.trimIndent()
        }

    /** The module body is expanded whole, then each definition and nested module in statement order. */
    fun testRunOrder() =
        assertEntered(
            """
            defmodule Outer do
              :body1
              def f, do: :f
              defmodule Inner do
                :inner_body
                def h, do: :h
              end
              def g, do: :g
              :body2
            end
            """.trimIndent(),
            LEVELS,
        ) { "body1 body2 f inner_body h g" }

    fun testANestedModule() =
        assertLevels(
            """
            defmodule Outer do
              defmodule Inner do
                :ok
              end
              :ok
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.Outer")}
            module Elixir.Outer compiled
              body: expanded {} next 0 context [Elixir.Outer.Inner, Elixir.Outer]
              module Elixir.Outer.Inner compiled
                body: expanded {} next 0 context [Elixir.Outer.Inner, Elixir.Outer] value ok
            """.trimIndent()
        }

    /** The alias a nested module defines names the enclosing module's, not the name it expanded to. */
    fun testANestedModulesAliasIsTheEnclosingModules() =
        assertLevels(
            """
            defmodule Outer do
              alias Foo.Bar
              defmodule Bar.Baz do
              end
              def f, do: Bar
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.Outer")}
            module Elixir.Outer compiled
              def f/0 line 5 clauses 1
              body: expanded {} next 0 context [Elixir.Outer.Bar.Baz, Elixir.Outer]
              def f/0: expanded {} next 0 value Elixir.Outer.Bar
              module Elixir.Outer.Bar.Baz compiled
                body: expanded {} next 0 context [Elixir.Outer.Bar.Baz, Elixir.Outer] value nil
            """.trimIndent()
        }

    /** A name led by `__MODULE__` is the enclosing module's, and defines no alias. */
    fun testANestedNameLedByTheModule() =
        assertLevels("defmodule A do\n  defmodule __MODULE__.Inner, do: :ok\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              body: expanded {} next 0 context [Elixir.A.Inner, Elixir.A]
              module Elixir.A.Inner compiled
                body: expanded {} next 0 context [Elixir.A.Inner, Elixir.A] value ok
            """.trimIndent()
        }

    /** A module can't be defined while it is being defined. */
    fun testAModuleInsideItself() =
        assertLevels("defmodule A do\n  defmodule Elixir.A, do: :ok\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A raised module_in_definition `defmodule Elixir.A, do: :ok`
              body: expanded {} next 0 context [Elixir.A, Elixir.A]
              module Elixir.A raised module_in_definition `defmodule Elixir.A, do: :ok`
            """.trimIndent()
        }

    /** A nested module that raises ends the module around it, so nothing after it is expanded. */
    fun testANestedModuleWhoseNameIsNotAnAtom() =
        assertLevels("defmodule A do\n  defmodule \"foo\", do: :ok\n  def f, do: 1\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A raised invalid_module_name `defmodule "foo", do: :ok`
              body: expanded {} next 0 context [Elixir.A]
              module "foo" raised invalid_module_name `defmodule "foo", do: :ok`
            """.trimIndent()
        }

    /** Up to 1.15 a root module's `alias ..., as: nil` removes an alias of its name; from 1.16 its `require` doesn't. */
    fun testARootModuleRemovesAnAliasOfItsNameUpTo1_15() =
        assertLevels(
            """
            defmodule A do
              alias X.Foo
              defmodule Elixir.Foo do
                :ok
              end
              def f, do: Foo
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val foo = if (isBefore(version, "1.16.0-rc.0")) "Elixir.Foo" else "Elixir.X.Foo"

            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 6 clauses 1
              body: expanded {} next 0 context [Elixir.Foo, Elixir.A]
              def f/0: expanded {} next 0 value $foo
              module Elixir.Foo compiled
                body: expanded {} next 0 context [Elixir.Foo, Elixir.A] value ok
            """.trimIndent()
        }

    /**
     * From 1.13 the module body starts with the variables before `defmodule` renumbered from 0 in term order; before it,
     * with them as they were.
     */
    fun testAModuleBodyReadsTheVariablesBeforeIt() =
        assertLevels(
            """
            a = 1
            b = 2
            a = 3
            defmodule A do
              :ok
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val body = if (isBefore(version, "1.13.0-rc.0")) "{a:2 b:1} next 3" else "{a:0 b:1} next 2"

            """
            top expanded {a:2 b:1} next 3 context [Elixir.A]
            module Elixir.A compiled
              body: expanded $body context [Elixir.A] value ok
            """.trimIndent()
        }

    // The file

    /**
     * From 1.13 a file of only modules compiles each directly, which adds it to `context_modules` from 1.13.2; before
     * 1.13 each `defmodule` adds its module to the file's.
     */
    fun testAFileOfOnlyModules() =
        assertLevels(
            """
            defmodule A do
              :a
            end

            defmodule B do
              :b
            end
            """.trimIndent(),
            FAST_PATH_LEVELS,
        ) { version ->
            val (top, a, b) = when {
                isBefore(version, "1.13.0-rc.0") ->
                    Triple("[Elixir.B, Elixir.A]", "[Elixir.A]", "[Elixir.B, Elixir.A]")
                isBefore(version, "1.13.2") -> Triple("[]", "[]", "[]")
                else -> Triple("[]", "[Elixir.A]", "[Elixir.B]")
            }

            """
            top expanded {} next 0 context $top
            module Elixir.A compiled
              body: expanded {} next 0 context $a value a
            module Elixir.B compiled
              body: expanded {} next 0 context $b value b
            """.trimIndent()
        }

    fun testAFileOfOnlyModulesDispatchesDefmoduleBefore1_13() =
        assertEvents("defmodule A do\n  :a\nend\n\ndefmodule B do\n  :b\nend", FAST_PATH_LEVELS) { version ->
            if (isBefore(version, "1.13.0-rc.0")) {
                "imported_macro Elixir.Kernel.defmodule/2; imported_macro Elixir.Kernel.defmodule/2"
            } else {
                ""
            }
        }

    fun testAFileWithAnotherForm() =
        assertLevels(
            """
            defmodule A do
              :a
            end

            :ok
            """.trimIndent(),
            FAST_PATH_LEVELS,
        ) {
            """
            top expanded {} next 0 context [Elixir.A]
            module Elixir.A compiled
              body: expanded {} next 0 context [Elixir.A] value a
            """.trimIndent()
        }

    /** A block of modules is modules too. */
    fun testAFileOfOnlyModulesWithABlock() =
        assertLevels("(defmodule A, do: :a; defmodule B, do: :b)\ndefmodule C, do: :c", FAST_PATH_LEVELS) { version ->
            val (top, contexts) = when {
                isBefore(version, "1.13.0-rc.0") ->
                    "[Elixir.C, Elixir.B, Elixir.A]" to
                        listOf("[Elixir.A]", "[Elixir.B, Elixir.A]", "[Elixir.C, Elixir.B, Elixir.A]")
                isBefore(version, "1.13.2") -> "[]" to listOf("[]", "[]", "[]")
                else -> "[]" to listOf("[Elixir.A]", "[Elixir.B]", "[Elixir.C]")
            }

            "top expanded {} next 0 context $top\n" +
                listOf("a", "b", "c").zip(contexts).joinToString("\n") { (name, context) ->
                    "module Elixir.${name.uppercase()} compiled\n  body: expanded {} next 0 context $context value $name"
                }
        }

    /** On the fast path a name `expand_or_concat` can't make an atom is `Macro.expand`ed: `__MODULE__` is `nil`. */
    fun testAFileOfOnlyModulesWithANameLedByTheModule() =
        assertLevels("defmodule __MODULE__.Foo, do: :ok", FAST_PATH_LEVELS) { version ->
            val (top, body) = when {
                isBefore(version, "1.13.0-rc.0") -> "[Elixir.Foo]" to "[Elixir.Foo]"
                isBefore(version, "1.13.2") -> "[]" to "[]"
                else -> "[]" to "[Elixir.Foo]"
            }

            """
            top expanded {} next 0 context $top
            module Elixir.Foo compiled
              body: expanded {} next 0 context $body value ok
            """.trimIndent()
        }

    fun testAFileOfOnlyModulesNamedByTheModule() =
        assertLevels("defmodule __MODULE__, do: :ok", FAST_PATH_LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "nil")}
            module nil raised invalid_module_name `defmodule __MODULE__, do: :ok`
            """.trimIndent()
        }

    /** A module compiled earlier in the file is loaded for the modules after it. */
    fun testAModuleRequiresOneDefinedBeforeIt() =
        assertLevels(
            """
            defmodule A do
              defmacro m, do: :ok
            end

            defmodule B do
              require A
              A.m()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${if (isBefore(version, "1.13.0-rc.0")) "[Elixir.B, Elixir.A]" else "[]"}
            module Elixir.A compiled
              defmacro m/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/0: expanded {} next 0 value ok
            module Elixir.B stopped `A.m()`
              body: opaque remote_macro Elixir.A.m/0 `A.m()`
            """.trimIndent()
        }

    /** A module compiled later in the file isn't loaded yet. */
    fun testAModuleRequiresOneDefinedAfterIt() =
        assertLevels(
            """
            defmodule B do
              require A
            end

            defmodule A do
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${if (isBefore(version, "1.13.0-rc.0")) "[Elixir.A, Elixir.B]" else "[]"}
            module Elixir.B raised unloaded_module `require A`
              body: error unloaded_module `require A`
            """.trimIndent()
        }

    // Local calls

    fun testALocalMacroDefinedBeforeTheCall() =
        assertLevels(
            """
            defmodule A do
              defmacro m, do: :ok
              def f, do: m()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `m()`
              defmacro m/0 line 2 clauses 1
              def f/0 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/0: expanded {} next 0 value ok
              def f/0: opaque local_macro Elixir.A.m/0 `m()`
            """.trimIndent()
        }

    /** A call to a macro the module defines only after it is a local function call, which fails once the body ran. */
    fun testALocalMacroDefinedAfterTheCall() =
        assertLevels(
            """
            defmodule A do
              def f, do: m()
              defmacro m, do: :ok
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val start = """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A ${if (isBefore(version, "1.15.0-rc.0")) "raised incorrect_dispatch `m()`" else "tainted"}
                  def f/0 line 2 clauses 1
                  defmacro m/0 line 3 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {} next 0
                  defmacro m/0: expanded {} next 0 value ok
            """.trimIndent()

            if (isBefore(version, "1.15.0-rc.0")) start else "$start\n  reported incorrect_dispatch `m()`"
        }

    fun testALocalMacroThatConflictsWithAnImport() =
        assertLevels(
            """
            defmodule A do
              import List, only: [first: 1]
              defmacro first(x), do: x
              def f(y), do: first(y)
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A raised macro_conflict `first(y)`
              defmacro first/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro first/1: expanded {x:0} next 1
              def f/1: error macro_conflict `first(y)`
            """.trimIndent()
        }

    /** `expand_import/7` dispatches an import `quote` recorded before it looks for a local macro. */
    fun testAQuotedImportIsDispatchedBeforeALocalMacro() =
        withKeys("first(y)" to QUOTED_LIST_FIRST) {
            assertEvents("defmodule A do\n  defmacro first(x), do: x\n  def f(y), do: first(y)\nend\n:ok", LEVELS) {
                "imported_macro Elixir.Kernel.defmodule/2; imported_macro Elixir.Kernel.defmacro/2; " +
                    "imported_macro Elixir.Kernel.def/2; remote_function Elixir.List.first/1"
            }
        }

    /** A function's own name and arity is never a local macro, so a macro that calls itself calls a function. */
    fun testAMacroThatCallsItself() =
        assertEvents("defmodule A do\n  defmacro m(x), do: m(x)\nend\n:ok", LEVELS) {
            "imported_macro Elixir.Kernel.defmodule/2; imported_macro Elixir.Kernel.defmacro/2; " +
                "local_function Elixir.A.m/1"
        }

    /** The macro's earlier clause is in the table, and still isn't a local macro for its own name and arity. */
    fun testALaterClauseOfAMacroThatCallsItself() =
        assertEvents("defmodule A do\n  defmacro m(0), do: 0\n  defmacro m(x), do: m(x)\nend\n:ok", LEVELS) {
            "imported_macro Elixir.Kernel.defmodule/2; imported_macro Elixir.Kernel.defmacro/2; " +
                "imported_macro Elixir.Kernel.defmacro/2; local_function Elixir.A.m/1"
        }

    fun testAMacroThatCallsItsOtherArity() =
        assertLevels(
            """
            defmodule A do
              defmacro m(x, y), do: {x, y}
              defmacro m(x), do: m(x, 1)
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `m(x, 1)`
              defmacro m/2 line 2 clauses 1
              defmacro m/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/2: expanded {x:0 y:1} next 2
              defmacro m/1: opaque local_macro Elixir.A.m/2 `m(x, 1)`
            """.trimIndent()
        }

    /** `Macro.expand/2` of a bitstring spec reads the module's macros as a local call does. */
    fun testANamedBitstringSpecIsALocalMacro() =
        assertLevels(
            """
            defmodule A do
              defmacro s, do: 8
              def f(x) do
                <<y::s()>> = x
                y
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `s()`
              defmacro s/0 line 2 clauses 1
              def f/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro s/0: expanded {} next 0
              def f/1: opaque local_macro Elixir.A.s/0 `s()`
            """.trimIndent()
        }

    /** Unlike a local call, `Macro.expand/2` reads the macro being defined too. */
    fun testANamedBitstringSpecIsTheMacroBeingDefined() =
        assertLevels(
            """
            defmodule A do
              defmacro m, do: 8
              defmacro m do
                <<y::m()>> = <<1>>
                y
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `m()`
              defmacro m/0 line 2 clauses 2
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/0: expanded {} next 0
              defmacro m/0: opaque local_macro Elixir.A.m/0 `m()`
            """.trimIndent()
        }

    fun testARescueCallIsALocalMacro() =
        assertLevels(
            """
            defmodule A do
              defmacro e, do: :ok
              def f do
                try do
                  1
                rescue
                  e() -> 1
                end
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `e()`
              defmacro e/0 line 2 clauses 1
              def f/0 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro e/0: expanded {} next 0 value ok
              def f/0: opaque local_macro Elixir.A.e/0 `e()`
            """.trimIndent()
        }

    /** A named bitstring spec that is no macro is an unknown spec, which carries on from 1.15. */
    fun testANamedBitstringSpecThatIsNotAMacro() =
        assertLevels(
            """
            defmodule A do
              def f do
                <<x::foo()>> = <<1>>
                x
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised undefined_bittype `x::foo()`
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: error undefined_bittype `x::foo()`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A tainted
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {x:0} next 1
                  reported undefined_bittype `x::foo()`
                """.trimIndent()
            }
        }

    /** A `rescue` call that is no macro is an invalid clause, which raises on every release. */
    fun testARescueCallThatIsNotAMacro() =
        assertLevels(
            """
            defmodule A do
              def f do
                try do
                  1
                rescue
                  foo() -> 1
                end
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A raised invalid_rescue_clause `foo() -> 1`
              body: expanded {} next 0 context [Elixir.A]
              def f/0: error invalid_rescue_clause `foo() -> 1`
            """.trimIndent()
        }

    /** `Macro.expand/2` gives a modelled macro's output, which no summary models, so nothing is defined. */
    fun testANamedBitstringSpecThatIsAModelledMacro() =
        assertLevels("defmodule A do\n  <<1::def(a)>>\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `def(a)`
              body: unported `def(a)`
            """.trimIndent()
        }

    fun testASignedBitstringSpecOfAModelledMacro() =
        assertLevels("defmodule A do\n  <<1::-def(a)>>\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `def(a)`
              body: unported `def(a)`
            """.trimIndent()
        }

    fun testARescueCallThatIsAModelledMacro() =
        assertLevels(
            """
            defmodule A do
              def f do
                try do
                  1
                rescue
                  def(a) -> 1
                end
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised definer_inside_function `def(a)`
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: error definer_inside_function `def(a)`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A stopped `def(a)`
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: unported `def(a)`
                """.trimIndent()
            }
        }

    fun testALocalFunctionCall() {
        val code = """
            defmodule A do
              def f(x), do: g(x)
              def g(x), do: x
            end
        """.trimIndent()

        assertLevels(code, LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/1 line 2 clauses 1
              def g/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/1: expanded {x:0} next 1
              def g/1: expanded {x:0} next 1
            """.trimIndent()
        }
        assertEvents("$code\n:ok", LEVELS) {
            "imported_macro Elixir.Kernel.defmodule/2; imported_macro Elixir.Kernel.def/2; " +
                "imported_macro Elixir.Kernel.def/2; local_function Elixir.A.g/1"
        }
    }

    /** Inside a function the deprecation check doesn't load the module, so before 1.13 the node's loaded modules decide. */
    fun testARemoteCallOfAMacroThatIsNotRequiredInAFunction() =
        assertLevels(
            """
            defmodule A do
              def f, do: Record.is_record(1)
            end
            """.trimIndent(),
            listOf("1.11.4", "1.12.1", "1.12.2", "1.13.4", "1.20.4"),
        ) { version ->
            val body = if (isBefore(version, "1.13.0-rc.0")) "unported `Record.is_record(1)`" else "expanded {} next 0"
            val ended = if (isBefore(version, "1.13.0-rc.0")) "stopped `Record.is_record(1)`" else "compiled"

            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A $ended
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: $body
            """.trimIndent()
        }

    fun testAnUndefinedLocalFunction() =
        assertLevels(
            """
            defmodule A do
              def f, do: h()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised undefined_function `h()`
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {} next 0
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A tainted
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {} next 0
                  reported undefined_function `h()`
                """.trimIndent()
            }
        }

    fun testALocalCaptureInAFunction() {
        val code = """
            defmodule A do
              def f, do: &g/1
              def g(x), do: x
            end
        """.trimIndent()

        assertLevels(code, LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 2 clauses 1
              def g/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: expanded {} next 0
              def g/1: expanded {x:0} next 1
            """.trimIndent()
        }
        assertEvents("$code\n:ok", LEVELS) {
            "imported_macro Elixir.Kernel.defmodule/2; imported_macro Elixir.Kernel.def/2; " +
                "imported_macro Elixir.Kernel.def/2; local_function Elixir.A.g/1"
        }
    }

    /** A capture of an undefined local is checked as a call is, at the name from 1.14.0-rc.1 and at the `&` before. */
    fun testALocalCaptureOfAnUndefinedFunction() =
        assertLevels(
            """
            defmodule A do
              def f, do: &h/1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val at = if (isBefore(version, "1.14.0-rc.1")) "&h/1" else "h"

            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised undefined_function `$at`
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {} next 0
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A tainted
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {} next 0
                  reported undefined_function `$at`
                """.trimIndent()
            }
        }

    /** A macro's own name and arity is never a local macro, so a macro capturing itself captures a function. */
    fun testAMacroThatCapturesItself() {
        val code = """
            defmodule A do
              defmacro m(x), do: &m/1
            end
        """.trimIndent()

        assertLevels(code, LEVELS) { version ->
            val at = if (isBefore(version, "1.14.0-rc.1")) "&m/1" else "m"
            val ended = if (isBefore(version, "1.15.0-rc.0")) "raised incorrect_dispatch `$at`" else "tainted"
            val start = """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A $ended
                  defmacro m/1 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  defmacro m/1: expanded {x:0} next 1
            """.trimIndent()

            if (isBefore(version, "1.15.0-rc.0")) start else "$start\n  reported incorrect_dispatch `$at`"
        }
        assertEvents("$code\n:ok", LEVELS) {
            "imported_macro Elixir.Kernel.defmodule/2; imported_macro Elixir.Kernel.defmacro/2; " +
                "local_function Elixir.A.m/1"
        }
    }

    /** A capture of a macro the module has defined is an `fn` that calls it, so it stops at that macro. */
    fun testALocalCaptureOfALocalMacro() =
        assertLevels(
            """
            defmodule A do
              defmacro m(x), do: x
              def f, do: &m/1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val at = if (isBefore(version, "1.14.0-rc.1")) "&m/1" else "m"

            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `$at`
              defmacro m/1 line 2 clauses 1
              def f/0 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/1: expanded {x:0} next 1
              def f/0: opaque local_macro Elixir.A.m/1 `$at`
            """.trimIndent()
        }

    /** A capture in a local call's arguments is checked with that call's arguments. */
    fun testALocalCaptureInALocalCallsArguments() =
        assertLevels(
            """
            defmodule A do
              def f, do: k(&h/1)
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val capture = if (isBefore(version, "1.14.0-rc.1")) "&h/1" else "h"

            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised undefined_function `$capture`
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {} next 0
                """.trimIndent()
            } else {
                // The capture first: by name below 1.16, and as an argument walked before its call on 1.18.
                val captureFirst = isBefore(version, "1.16.0-rc.0") ||
                    !isBefore(version, "1.18.0-rc.0") && isBefore(version, "1.19.0-rc.0")
                val errors = listOf("reported undefined_function `$capture`", "reported undefined_function `k(&h/1)`")

                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A tainted
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {} next 0
                """.trimIndent() + (if (captureFirst) errors else errors.reversed()).joinToString("") { "\n  $it" }
            }
        }

    /** From 1.18 the checks of local calls run only in a module with no error. */
    fun testAModuleWithAnErrorSkipsTheLocalChecksFrom1_18() =
        assertLevels(
            """
            defmodule A do
              def f, do: __STACKTRACE__
              def g, do: h()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            when {
                isBefore(version, "1.15.0-rc.0") ->
                    """
                    top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                    module Elixir.A raised stacktrace_not_allowed `__STACKTRACE__`
                      body: expanded {} next 0 context [Elixir.A]
                      def f/0: error stacktrace_not_allowed `__STACKTRACE__`
                    """.trimIndent()
                else ->
                    """
                    top expanded {} next 0 context []
                    module Elixir.A tainted
                      def f/0 line 2 clauses 1
                      def g/0 line 3 clauses 1
                      body: expanded {} next 0 context [Elixir.A]
                      def f/0: expanded {} next 0
                      def g/0: expanded {} next 0
                      reported stacktrace_not_allowed `__STACKTRACE__`
                    """.trimIndent() +
                        if (isBefore(version, "1.18.0-rc.0")) "\n  reported undefined_function `h()`" else ""
            }
        }

    fun testALocalCallInAPattern() =
        assertLevels(
            """
            defmodule A do
              def f(x) do
                g(y) = x
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised invalid_local_invocation `g(y)`
                  body: expanded {} next 0 context [Elixir.A]
                  def f/1: error invalid_local_invocation `g(y)`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A tainted
                  def f/1 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/1: expanded {x:0 y:1} next 2
                  reported invalid_local_invocation `g(y)`
                """.trimIndent()
            }
        }

    fun testALocalCallInAGuard() =
        assertLevels(
            """
            defmodule A do
              def f(x) when g(x), do: x
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised invalid_local_invocation `g(x)`
                  body: expanded {} next 0 context [Elixir.A]
                  def f/1: error invalid_local_invocation `g(x)`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A tainted
                  def f/1 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/1: expanded {x:0} next 1
                  reported invalid_local_invocation `g(x)`
                """.trimIndent()
            }
        }

    fun testARemoteDefinition() {
        val code = "defmodule A do\n  Kernel.def f, do: 1\nend\n:ok"

        assertEvents(code, LEVELS) {
            "imported_macro Elixir.Kernel.defmodule/2; remote_macro Elixir.Kernel.def/2"
        }
        assertLevels(code, LEVELS) {
            """
            top expanded {} next 0 context [Elixir.A]
            module Elixir.A compiled
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: expanded {} next 0
            """.trimIndent()
        }
    }

    // Definers in the wrong place

    fun testADefinitionOutsideAModule() =
        assertEvery("def f, do: 1", "top error definer_outside_module `def f, do: 1`")

    fun testADefinitionInsideAFunction() =
        assertLevels(
            """
            defmodule A do
              def f do
                def g, do: 1
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A raised definer_inside_function `def g, do: 1`
              body: expanded {} next 0 context [Elixir.A]
              def f/0: error definer_inside_function `def g, do: 1`
            """.trimIndent()
        }

    fun testADefinitionInAPattern() =
        assertLevels("defmodule A do\n  def(f, do: 1) = 1\nend", LEVELS) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A stopped `def(f, do: 1)`
                  body: unported `def(f, do: 1)`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A raised definer_in_match `def(f, do: 1)`
                  body: error definer_in_match `def(f, do: 1)`
                """.trimIndent()
            }
        }

    fun testADefinitionInAGuard() =
        assertLevels("defmodule A do\n  case 1 do\n    x when def(f, do: 1) -> x\n  end\nend", LEVELS) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A stopped `def(f, do: 1)`
                  body: unported `def(f, do: 1)`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A raised definer_in_guard `def(f, do: 1)`
                  body: error definer_in_guard `def(f, do: 1)`
                """.trimIndent()
            }
        }

    fun testAModuleInAPattern() =
        assertSplit(
            "defmodule(A, do: 1) = 1",
            "1.15.0-rc.0",
            "top unported `defmodule(A, do: 1)`",
            "top error definer_in_match `defmodule(A, do: 1)`",
        )

    /** Any option but a lone `do` raises: from 1.20.0-rc.2 for a reserved word, and before it in no clause. */
    fun testAModuleWithAnotherBlock() =
        assertEvery(
            "defmodule A, do: :ok, else: :error",
            "top error reserved_word `defmodule A, do: :ok, else: :error`",
        )

    fun testAModuleInsideAFunctionIsNotCompiled() =
        assertLevels("defmodule A do\n  def f do\n    defmodule B do\n      :ok\n    end\n  end\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `:ok`
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: unported `:ok`
            """.trimIndent()
        }

    // Names

    fun testANameWithASlashFrom1_13() =
        assertLevels("defmodule :\"a/b\", do: :ok", LEVELS) { version ->
            if (isBefore(version, "1.13.0-rc.0")) {
                """
                top expanded {} next 0 context [a/b]
                module a/b compiled
                  body: expanded {} next 0 context [a/b] value ok
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module a/b raised invalid_module_name `defmodule :"a/b", do: :ok`
                """.trimIndent()
            }
        }

    fun testReservedNames() {
        for (name in listOf("Any", "Elixir")) {
            val module = if (name == "Elixir") "Elixir" else "Elixir.$name"

            assertLevels("defmodule $name, do: :ok", LEVELS) { version ->
                """
                top expanded {} next 0 context ${topContext(version, module)}
                module $module raised module_reserved `defmodule $name, do: :ok`
                """.trimIndent()
            }
        }
    }

    fun testTrueIsReservedFrom1_14() =
        assertLevels("defmodule True, do: :ok", LEVELS) { version ->
            if (isBefore(version, "1.14.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.True")}
                module Elixir.True compiled
                  body: expanded {} next 0 context [Elixir.True] value ok
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.True raised module_reserved `defmodule True, do: :ok`
                """.trimIndent()
            }
        }

    /** `nil` and the booleans are atoms, so before 1.13 `defmodule` records them as it does any module. */
    fun testNilAndTheBooleansAreInvalidNames() {
        for (name in listOf("nil", "true", "false")) {
            assertLevels("defmodule $name, do: :ok", LEVELS) { version ->
                """
                top expanded {} next 0 context ${topContext(version, name)}
                module $name raised invalid_module_name `defmodule $name, do: :ok`
                """.trimIndent()
            }
        }
    }

    /** A name that isn't an atom defines nothing before it is refused. */
    fun testANameThatIsNotAnAtom() {
        for ((name, module) in listOf("\"foo\"" to "\"foo\"", "1" to "1")) {
            assertEvery(
                "defmodule $name, do: :ok",
                "top expanded {} next 0 context []\nmodule $module raised invalid_module_name `defmodule $name, do: :ok`",
            )
        }
    }

    fun testANameWithABackslashFrom1_13() =
        assertLevels("defmodule :\"a\\\\b\", do: :ok", LEVELS) { version ->
            if (isBefore(version, "1.13.0-rc.0")) {
                """
                top expanded {} next 0 context [a\b]
                module a\b compiled
                  body: expanded {} next 0 context [a\b] value ok
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module a\b raised invalid_module_name `defmodule :"a\\b", do: :ok`
                """.trimIndent()
            }
        }

    /** A name led by a variable is an alias that doesn't expand to an atom. */
    fun testANameLedByAVariable() =
        assertEvery("x = 1\ndefmodule x.Foo, do: :ok", "top error invalid_alias `x.Foo`")

    fun testAModuleInAGuard() =
        assertLevels("defmodule A do\n  case 1 do\n    x when defmodule(B, do: 1) -> x\n  end\nend", LEVELS) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A stopped `defmodule(B, do: 1)`
                  body: unported `defmodule(B, do: 1)`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A raised definer_in_guard `defmodule(B, do: 1)`
                  body: error definer_in_guard `defmodule(B, do: 1)`
                """.trimIndent()
            }
        }

    fun testAnInvalidDefinition() =
        assertLevels("defmodule A do\n  def 1, do: 1\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A raised invalid_def `def 1, do: 1`
              body: expanded {} next 0 context [Elixir.A]
              def unnamed: error invalid_def `def 1, do: 1`
            """.trimIndent()
        }

    fun testNamesTheCompilerDefines() {
        for ((code, owner, kind) in listOf(
            Triple("def __info__(x), do: x", "def __info__/1", "__info__"),
            Triple("def module_info, do: 1", "def module_info/0", "module_info"),
            Triple("defp is_record(a, b), do: {a, b}", "defp is_record/2", "is_record"),
        )) {
            assertLevels("defmodule A do\n  $code\nend", LEVELS) { version ->
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised $kind `$code`
                  body: expanded {} next 0 context [Elixir.A]
                  $owner: error $kind `$code`
                """.trimIndent()
            }
        }
    }

    // Callers

    fun testCallerInAMacroAndInAFunction() =
        assertLevels(
            """
            defmodule A do
              defmacro m, do: __CALLER__
              def f, do: __CALLER__
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A raised caller_not_allowed `__CALLER__`
                  defmacro m/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  defmacro m/0: expanded {} next 0
                  def f/0: error caller_not_allowed `__CALLER__`
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A tainted
                  defmacro m/0 line 2 clauses 1
                  def f/0 line 3 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  defmacro m/0: expanded {} next 0
                  def f/0: expanded {} next 0
                  reported caller_not_allowed `__CALLER__`
                """.trimIndent()
            }
        }

    // Unquote fragments

    /** An `unquote` inside a `quote` in a body is the `quote`'s, so the definition has no fragments. */
    fun testUnquoteInsideAQuoteIsNotAFragment() =
        assertLevels(
            """
            defmodule A do
              def f(x), do: quote(do: unquote(x))
              def g(f), do: quote(do: unquote(f)(1))
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/1 line 2 clauses 1
              def g/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/1: expanded {x:0} next 1
              def g/1: expanded {f:0} next 1
            """.trimIndent()
        }

    fun testALiteralFragmentIsItsValue() =
        assertLevels("defmodule A do\n  def f, do: unquote(:a)\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: expanded {} next 0 value a
            """.trimIndent()
        }

    fun testAFragmentWithNoLiteralValueStopsItsBody() =
        assertLevels("defmodule A do\n  x = 1\n  def f, do: unquote(x)\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `unquote(x)`
              def f/0 line 3 clauses 1
              body: expanded {x:0} next 1 context [Elixir.A]
              def f/0: unported `unquote(x)`
            """.trimIndent()
        }

    fun testANameWithNoAtomValueIsUnnamed() =
        assertLevels("defmodule A do\n  name = :f\n  def unquote(name)(), do: 1\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `unquote(name)`
              def unnamed
              body: expanded {name:0} next 1 context [Elixir.A]
              def unnamed unordered: unported `unquote(name)`
            """.trimIndent()
        }

    fun testAListOrTupleFragmentIsItsValue() =
        assertLevels("defmodule A do\n  def f, do: unquote([:a, {:b, 1}])\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: expanded {} next 0
            """.trimIndent()
        }

    /**
     * An `unquote` in a `quote`'s options is unquoted when the definition is escaped. From 1.16 it doesn't make the
     * definition one with fragments, so the body isn't escaped and the `unquote` is outside any `quote`.
     */
    fun testUnquoteInTheOptionsOfAQuote() =
        assertLevels(
            """
            defmodule A do
              def f do
                quote bind_quoted: [y: unquote(:a)] do
                  y
                end
              end
            end
            """.trimIndent(),
            LEVELS + "1.16.0-rc.0",
        ) { version ->
            if (isBefore(version, "1.16.0-rc.0")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A compiled
                  def f/0 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: expanded {} next 0
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A raised unquote_outside_quote `unquote(:a)`
                  body: expanded {} next 0 context [Elixir.A]
                  def f/0: error unquote_outside_quote `unquote(:a)`
                """.trimIndent()
            }
        }

    /** An unquoted name makes the definition one with fragments, which escapes the `quote`'s options too. */
    fun testUnquoteInTheOptionsOfAQuoteWithAnUnquotedName() =
        assertLevels(
            """
            defmodule A do
              def unquote(:f)() do
                quote bind_quoted: [y: unquote(:a)] do
                  y
                end
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: expanded {} next 0
            """.trimIndent()
        }

    /**
     * An unquoted call name inside a `quote` makes the definition one with fragments until 1.20.0-rc.5, which reads the
     * name inside the `quote`.
     */
    fun testUnquoteInTheOptionsOfAQuoteBesideAnUnquotedCallName() =
        assertLevels(
            """
            defmodule A do
              def f(g) do
                quote(do: unquote(g)(1))
                quote bind_quoted: [y: unquote(:a)] do
                  y
                end
              end
            end
            """.trimIndent(),
            LEVELS + listOf("1.20.0-rc.4", "1.20.0-rc.5"),
        ) { version ->
            if (isBefore(version, "1.20.0-rc.5")) {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.A")}
                module Elixir.A compiled
                  def f/1 line 2 clauses 1
                  body: expanded {} next 0 context [Elixir.A]
                  def f/1: expanded {g:0} next 1
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context []
                module Elixir.A raised unquote_outside_quote `unquote(:a)`
                  body: expanded {} next 0 context [Elixir.A]
                  def f/1: error unquote_outside_quote `unquote(:a)`
                """.trimIndent()
            }
        }

    // Ending a module

    /** A definition that raises ends the module even after one that stopped, so nothing after it is expanded. */
    fun testADefinitionThatRaisesAfterOneThatStopped() =
        assertLevels(
            """
            defmodule A do
              def f do
                defmodule B, do: :ok
              end
              def __info__(x), do: 1
              defmodule Inner, do: :ok
              def g, do: __STACKTRACE__
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `:ok`
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A.Inner, Elixir.A]
              def f/0: unported `:ok`
              def __info__/1: error __info__ `def __info__(x), do: 1`
            """.trimIndent()
        }

    // Rendering

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return (listOf("top " + renderUnit(code, file.top, context = true)) + file.modules.flatMap { renderModule(code, it, "") })
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
        val table = result.table.entries.map { (nameArity, entry) ->
            "${entry.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity} line ${entry.line} clauses ${entry.clauses}" +
                (if (entry.defaults > 0) " defaults ${entry.defaults}" else "") +
                (if (entry.default) " default" else "") +
                (if (entry.ordered) "" else " unordered")
        } + result.table.unnamed.map { "${it.kind.name.lowercase()} unnamed" }
        val units = result.units.map { unit ->
            val owner = when (val owner = unit.owner) {
                ExpansionResult.Owner.ModuleBody -> "body"
                is ExpansionResult.Owner.Definition ->
                    "${owner.kind.name.lowercase()} " + (owner.name?.let { "$it/${owner.arity}" } ?: "unnamed")
            }

            "$owner${if (unit.ordered) "" else " unordered"}: " +
                renderUnit(code, unit.expansion, context = unit.owner == ExpansionResult.Owner.ModuleBody)
        }
        val errors = result.errors.map { "reported ${it.kind} `${source(code, it.at)}`" }

        return listOf(
            listOf("${indent}module ${result.module} $ended"),
            listOf(table, units, errors).flatten().map { "$indent  $it" },
            result.nested.flatMap { renderModule(code, it, "$indent  ") },
        ).flatten()
    }

    /** [expansion] as [render] gives it, with the env's `context_modules` if [context], and an atom value. */
    private fun renderUnit(code: String, expansion: Expansion, context: Boolean): String {
        val expanded = expansion as? Expansion.Expanded ?: return render(code, expansion)

        return render(code, expansion) +
            (if (context) " context ${expanded.env.contextModules.joinToString(", ", "[", "]")}" else "") +
            ((expanded.value as? Term.Atom)?.let { " value ${it.name}" } ?: "")
    }

    private fun source(code: String, node: ElixirAst) = node.meta.origin.substring(code)

    /** The file's `context_modules` after only [module]: empty on the fast path from 1.13. */
    private fun topContext(version: String, module: String) =
        if (isBefore(version, "1.13.0-rc.0")) "[$module]" else "[]"

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

    /** The atoms written in [code] that its expansion enters, in order, at each of [versions]. */
    private fun assertEntered(code: String, versions: List<String>, expected: (String) -> String) =
        assertEquals(
            versions.joinToString("\n") { "$it: ${expected(it)}" },
            versions.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val entered = mutableListOf<String>()
                val observer = ExpansionObserver { node, _, _ ->
                    if (node is ElixirAst.Literal.Atom && source(code, node).startsWith(":")) entered += node.name
                }

                Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, observer)

                "$version: ${entered.joinToString(" ")}"
            },
        )

    private companion object {
        val FAST_PATH_LEVELS = listOf(
            "1.11.4", "1.12.3", "1.13.0-rc.0", "1.13.1", "1.13.2", "1.13.4", "1.14.5", "1.15.8", "1.16.3", "1.17.3",
            "1.18.4", "1.19.5", "1.20.4",
        )

        /** `List.first/1`'s import as each era's `quote` records it. */
        val QUOTED_LIST_FIRST = listOf(
            Meta.Key.Entry("context", Meta.Value.Atom("Elixir.Quoter")),
            Meta.Key.Entry("import", Meta.Value.Atom("Elixir.List")),
            Meta.Key.Entry(
                "imports",
                Meta.Value.List(listOf(Meta.Value.Tuple(listOf(Meta.Value.Integer(1), Meta.Value.Atom("Elixir.List"))))),
            ),
        )
    }
}
