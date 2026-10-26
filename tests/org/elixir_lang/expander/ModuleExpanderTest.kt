package org.elixir_lang.expander

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import org.elixir_lang.language_level.ElixirLanguageFeature.CLAUSES_TAKE_VERSION
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

    /** A definition in the clause a module-body `case` selects runs once, where the `case` does: a statement. */
    fun testADefinitionInASelectedCaseClauseIsAStatement() =
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
              def f/0 line 3 clauses 1
              def g/0 line 5 clauses 1
              body: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 0 else 2} context [Elixir.A]
              def f/0: expanded {} next 0
              def g/0: expanded {} next 0
            """.trimIndent()
        }

    /**
     * A definition in a clause of a `case` whose subject the evaluator can't produce, and every body after it, may run
     * in another order.
     */
    fun testADefinitionInACaseClauseTheEvaluatorCannotSelectIsUnordered() =
        assertLevels(
            """
            defmodule A do
              case (receive do after 0 -> 1 end) do
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
              body: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 0 else 3} context [Elixir.A]
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

    /**
     * A module defined before a name that isn't an atom loads with its definitions on either side of the fast path:
     * before 1.13 the file's expansion stops at the name, after the module, and from 1.13 the fast path raises there.
     */
    fun testAModuleBeforeANameThatIsntAnAtomIsLoaded() {
        val code = "defmodule A do\n  def f, do: 1\nend\ndefmodule Module.concat(Foo, Bar) do\nend\ndefmodule C do\nend"

        assertEquals(
            FAST_PATH_LEVELS.joinToString("\n") { "$it: Elixir.A Present f/0" },
            FAST_PATH_LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

                "$version: " + file.modules.filter { it.module == "Elixir.A" }.joinToString { result ->
                    val loaded = LoadedModules.exports(result)
                    val functions = (loaded as? ModuleExports.Present)?.functions?.joinToString { "${it.name}/${it.arity}" }

                    "${result.module} ${loaded::class.simpleName} $functions"
                }
            },
        )
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
            module Elixir.B compiled
              body: expanded {} next 0 context ${if (isBefore(version, "1.13.0-rc.0")) "[Elixir.B, Elixir.A]" else "[Elixir.B]"}
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
            module Elixir.A compiled
              defmacro m/0 line 2 clauses 1
              def f/0 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/0: expanded {} next 0 value ok
              def f/0: expanded {} next 0 value ok
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

    /** A module defined again expands its body with its own macros, not the ones the first definition loaded. */
    fun testARedefinedModulesLocalMacro() =
        assertLevels(
            """
            defmodule A do
              defmacro m, do: :old
            end

            defmodule A do
              defmacro m, do: :new
              def f, do: m()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val context = if (isBefore(version, "1.13.0-rc.0")) "[Elixir.A, Elixir.A]" else "[]"

            """
            top expanded {} next 0 context $context
            module Elixir.A compiled
              defmacro m/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/0: expanded {} next 0 value old
            module Elixir.A compiled
              defmacro m/0 line 6 clauses 1
              def f/0 line 7 clauses 1
              body: expanded {} next 0 context ${if (isBefore(version, "1.13.0-rc.0")) context else "[Elixir.A]"}
              defmacro m/0: expanded {} next 0 value new
              def f/0: expanded {} next 0 value new
            """.trimIndent()
        }

    /** Before 1.15 a bare name that isn't a variable calls the local function of that name, in a macro's body too. */
    fun testABareNameInAMacroCallsALocalFunctionBefore1_15() =
        assertLevels(
            """
            defmodule A do
              defp h, do: :ok
              defmacro m, do: h
              def f, do: m()
            end
            """.trimIndent(),
            LEVELS.filter { isBefore(it, "1.15.0-rc.0") },
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              defp h/0 line 2 clauses 1
              defmacro m/0 line 3 clauses 1
              def f/0 line 4 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defp h/0: expanded {} next 0 value ok
              defmacro m/0: expanded {} next 0
              def f/0: expanded {} next 0 value ok
            """.trimIndent()
        }

    /** A zero-width generator never advances, so Elixir never finishes the comprehension and the call is not answered. */
    fun testAZeroWidthBitstringGeneratorThatSkips() = assertZeroWidthGenerator("1::size(0)", "do: :ok", binds = 0)

    fun testAZeroWidthBitstringGeneratorThatMatches() = assertZeroWidthGenerator("x::size(0)", "do: x", binds = 1)

    private fun assertZeroWidthGenerator(segment: String, body: String, binds: Int) =
        assertLevels(
            """
            defmodule A do
              defmacro m, do: for(<<$segment <- <<1>> >>, $body)
            end

            defmodule B do
              require A
              A.m()
            end
            """.trimIndent(),
            LEVELS + "1.20.0-rc.4",
        ) { version ->
            """
            top expanded {} next 0 context ${if (isBefore(version, "1.13.0-rc.0")) "[Elixir.B, Elixir.A]" else "[]"}
            module Elixir.A compiled
              defmacro m/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/0: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) binds else binds + 1}
            module Elixir.B stopped `A.m()`
              body: opaque remote_macro Elixir.A.m/0 `A.m()`
            """.trimIndent()
        }

    /** A macro building a bitstring is cancelled while it writes the bits. */
    fun testAWideBitstringIsCancelledAsItIsWritten() = assertCancelledAt("<<0::size($WIDE)>>", WIDE / 2)

    /** A macro matching a bitstring is cancelled while it reads the bits, once it has written them. */
    fun testAWideBitstringIsCancelledAsItIsRead() =
        assertCancelledAt("(<<x::size($WIDE)>> = <<-1::size($WIDE)>>; x > 0)", WIDE + WIDE / 2)

    /** A macro subtracting one long list from another is cancelled while it scans them. */
    fun testAListSubtractionIsCancelledAsItScans() {
        val lists = "l = [1]; " + "l = :erlang.++(l, l); ".repeat(DOUBLINGS) +
            "r = [2]; " + "r = :erlang.++(r, r); ".repeat(DOUBLINGS)
        // Parsing the body makes most of the checks, so the scan's are counted from the same body's with one element
        // to scan.
        val unscanned = checks("($lists :erlang.length(:erlang.--(l, [r])))")

        assertCancelledAt("($lists :erlang.length(:erlang.--(l, r)))", unscanned + (1 shl DOUBLINGS) / 2)
    }

    /** A macro collecting a long comprehension's unique results is cancelled while it scans them. */
    fun testAUniqueComprehensionIsCancelledAsItScans() {
        val digits = (1..8).joinToString(", ", "[", "]")
        val comprehension = { uniq: Boolean ->
            "for a <- $digits, b <- $digits, c <- $digits, d <- $digits, uniq: $uniq, do: {a, b, c, d}"
        }
        // Running the generators makes most of the checks, so the scan's are counted from the same comprehension's
        // without `uniq`.
        val unscanned = checks(":erlang.length(${comprehension(false)})")

        assertCancelledAt(":erlang.length(${comprehension(true)})", unscanned + 8 * 8 * 8 * 8 / 2)
    }

    /** A macro merging two long keyword lists is cancelled while it scans them. */
    fun testAKeywordMergeIsCancelledAsItScans() {
        val lists = "l = [a: 1]; " + "l = :erlang.++(l, l); ".repeat(DOUBLINGS) +
            "r = [b: 2]; " + "r = :erlang.++(r, r); ".repeat(DOUBLINGS)
        // Parsing the body makes most of the checks, so the scan's are counted from the same body's with one pair on
        // the left.
        val unscanned = checks("($lists :erlang.length(Keyword.merge([a: 1], r)))")

        assertCancelledAt("($lists :erlang.length(Keyword.merge(l, r)))", unscanned + (1 shl DOUBLINGS) / 2)
    }

    /** A macro flattening a list that holds itself twice at every depth is cancelled while it walks it. */
    fun testAFlattenIsCancelledAsItWalks() {
        // Building the list makes most of the checks, so the walk's are counted from the same body's flattening `[]`.
        val unwalked = checks("($TREE :erlang.length(:lists.flatten([])))")

        assertCancelledAt("($TREE :erlang.length(:lists.flatten(t)))", unwalked + (1 shl DOUBLINGS) / 2)
    }

    /**
     * A macro asking whether two lists that each hold one part twice at every depth are equal is cancelled as it walks
     * them. Two lists built apart, since the VM, and the evaluator, answer a term equal to itself without walking it.
     */
    fun testAnEqualityIsCancelledAsItWalks() {
        // Building the lists makes most of the checks, so the walk's are counted from the same body's not comparing them.
        val trees = "$TREE ${TREE.replace("t", "u")}"
        val unwalked = checks("($trees :erlang.is_list(t))")

        assertCancelledAt("($trees t == u)", unwalked + (1 shl DOUBLINGS) / 2)
    }

    /** Asking whether two such lists are equal looks through both for references, ports and pids, cancelled as it looks. */
    fun testAnEqualityIsCancelledAsItLooksForIdentities() {
        val trees = "$TREE ${TREE.replace("t", "u")}"

        assertCancelledIn("org.elixir_lang.expander.TermOrderKt", "holdsDistinctIdentities", (1 shl DOUBLINGS) / 2) {
            expandMacro("($trees t == u)")
        }
    }

    /** A macro ordering two such lists is cancelled while it compares them, once it has walked each. */
    fun testAComparisonIsCancelledAsItWalks() {
        val trees = "$TREE ${TREE.replace("t", "u")}"
        // Ordering a list before a tuple compares no elements, so the walks before the comparison are counted from it.
        val uncompared = checks("($trees :erlang.<(t, {u}))")

        assertCancelledAt("($trees :erlang.<(t, u))", uncompared + (1 shl DOUBLINGS) / 2)
    }

    /** A macro ordering two equal wide bitstrings is cancelled while it compares their bits, once it has written them. */
    fun testABitstringComparisonIsCancelledAsItScans() {
        val bits = 1 shl 15
        val bitstrings = "a = <<0::size($bits)>>; b = <<a::bitstring>>;"
        // Writing the two bitstrings makes most of the checks, so the comparison's are counted from not comparing them.
        val uncompared = checks("($bitstrings :erlang.is_bitstring(b))")

        assertCancelledAt("($bitstrings :erlang.<(a, b))", uncompared + bits / 2)
    }

    /** A macro ordering such a list is cancelled while it walks it for an unknown value. */
    fun testAComparedTermIsCancelledAsItIsWalked() {
        // Building the list makes most of the checks, so the walk's are counted from the same body's not ordering it.
        val unwalked = checks("($TREE :erlang.is_list(t))")

        assertCancelledAt("($TREE :erlang.<(t, {:a}))", unwalked + (1 shl DOUBLINGS) / 2)
    }

    /** A macro keying a map by such a list is cancelled while it looks in the key for a function. */
    fun testAMapKeyIsCancelledAsItIsWalked() {
        // Ordering the list walks it for an unknown value, as a key is, so the look for a function is counted from it.
        val unwalked = checks("($TREE :erlang.<(t, {:a}))")

        assertCancelledAt("($TREE :erlang.map_size(%{t => 1}))", unwalked + (1 shl DOUBLINGS) / 2)
    }

    /** A macro escaping such a list is cancelled while it escapes it. */
    fun testAnEscapeIsCancelledAsItWalks() {
        // Building the list makes most of the checks, so the escape's are counted from the same body's escaping `[[], []]`.
        val unwalked = checks("($TREE :erlang.length(Macro.escape([[], []])))")

        assertCancelledAt("($TREE :erlang.length(Macro.escape(t)))", unwalked + (1 shl DOUBLINGS) / 2)
    }

    /** A macro unquoting a pair that holds itself twice at every depth is cancelled while it validates it. */
    fun testAnUnquoteIsCancelledAsItValidates() {
        val pairs = "p = :a; " + "p = {p, p}; ".repeat(DOUBLINGS)
        // Building the pair makes most of the checks, so the validation's are counted from the same body's of `{:a, :a}`.
        val unwalked = checks("($pairs :erlang.tuple_size(quote(do: unquote({:a, :a}))))")

        assertCancelledAt("($pairs :erlang.tuple_size(quote(do: unquote(p))))", unwalked + (1 shl DOUBLINGS) / 2)
    }

    /** A macro whose comprehension rejects every element of a long list is cancelled while it scans them. */
    fun testARejectingComprehensionIsCancelledAsItScans() {
        val list = "l = [:a]; " + "l = :erlang.++(l, l); ".repeat(DOUBLINGS)
        // Building the list makes most of the checks, so the scan's are counted from the same body's over one element.
        val unscanned = checks("($list :erlang.length(for :b <- [:a], do: 1))")

        assertCancelledAt("($list :erlang.length(for :b <- l, do: 1))", unscanned + (1 shl DOUBLINGS) / 2)
    }

    /** A macro folding a long list with a function that calls nothing is cancelled while it calls the function. */
    fun testAFoldIsCancelledAsItCallsItsFunction() =
        assertCancelledAsItCalls { ":lists.foldl(fn _, acc -> acc end, 0, $it)" }

    /** A macro filtering a long list with a predicate that calls nothing is cancelled while it calls the predicate. */
    fun testAFilterIsCancelledAsItCallsItsPredicate() =
        assertCancelledAsItCalls { ":erlang.length(:lists.filter(fn _ -> true end, $it))" }

    /** A macro asking whether a predicate that calls nothing holds for all of a long list is cancelled while it asks. */
    fun testAnAllIsCancelledAsItCallsItsPredicate() = assertCancelledAsItCalls { ":lists.all(fn _ -> true end, $it)" }

    /** A macro whose body is [call] of a long list, built from one element by doubling, is cancelled within [call]. */
    private fun assertCancelledAsItCalls(call: (String) -> String) {
        val list = "l = [1]; " + "l = :erlang.++(l, l); ".repeat(DOUBLINGS)
        // Building the list makes most of the checks, so the calls' are counted from the same body's over one element.
        val uncalled = checks("($list ${call("[1]")})")

        assertCancelledAt("($list ${call("l")})", uncalled + (1 shl DOUBLINGS) / 2)
    }

    /** The expansion of a macro whose body is [body] is cancelled by a cancel at its [check]th check. */
    private fun assertCancelledAt(body: String, check: Int) {
        val indicator = CancelAt(check)

        try {
            expandMacro(body, indicator)
            fail("no cancel at check $check, of ${indicator.checks}")
        } catch (_: ProcessCanceledException) {
        }
    }

    /** The checks the expansion of a macro whose body is [body] makes. */
    private fun checks(body: String): Int = CancelAt(Int.MAX_VALUE).also { expandMacro(body, it) }.checks

    private fun expandMacro(body: String, indicator: CancelAt) {
        whileCheckingIndicators {
            ProgressManager.getInstance().runProcess({ expandMacro(body) }, indicator)
        }
    }

    /** Expands `B`, which calls `A.m`, a macro whose body is [body]. */
    private fun expandMacro(body: String) {
        val code = """
            defmodule A do
              defmacro m, do: $body
            end

            defmodule B do
              require A
              A.m()
            end
        """.trimIndent()

        expandAndRender(code, "1.20.4")
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
            module Elixir.A compiled
              defmacro m/2 line 2 clauses 1
              defmacro m/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/2: expanded {x:0 y:1} next 2
              defmacro m/1: expanded {x:0} next 1
            """.trimIndent()
        }

    /** A clause stopped at a fragment is still a clause of its macro, so a call it matches stops, not the next. */
    fun testACallMatchingAStoppedMacroClause() =
        assertLevels(
            """
            defmodule A do
              defmacrop m(:a), do: unquote(receive do after 0 -> 1 end)
              defmacrop m(x), do: x
              def f, do: m(:a)
              def g, do: m(:b)
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `unquote(receive do after 0 -> 1 end)`
              defmacrop m/1 line 2 clauses 2
              def f/0 line 4 clauses 1
              def g/0 line 5 clauses 1
              body: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 0 else 1} context [Elixir.A]
              defmacrop m/1: unported `unquote(receive do after 0 -> 1 end)`
              defmacrop m/1: expanded {x:0} next 1
              def f/0: opaque local_macro Elixir.A.m/1 `m(:a)`
              def g/0: expanded {} next 0 value b
            """.trimIndent()
        }

    /** An unnamed macro may be the one a later local call reaches, as `m/1`'s first clause here, so the call declines. */
    fun testALocalMacroCallAfterAnUnnamedDefinitionDeclines() =
        assertLevels(
            """
            defmodule A do
              defmacrop unquote(receive do after 0 -> :m end)(x), do: :first
              defmacrop m(x), do: x
              def g, do: m(:b)
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `unquote(receive do after 0 -> :m end)`
              defmacrop m/1 line 3 clauses 1
              def g/0 line 4 clauses 1
              defmacrop unnamed
              body: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 0 else 1} context [Elixir.A]
              defmacrop unnamed unordered: unported `unquote(receive do after 0 -> :m end)`
              defmacrop m/1 unordered: expanded {x:0} next 1
              def g/0 unordered: opaque local_macro Elixir.A.m/1 `m(:b)`
            """.trimIndent()
        }

    /** A name the module body's value gives is its clause's, so a later local call reaches it first, as in Elixir. */
    fun testALocalMacroCallReachesTheClauseAFragmentNames() =
        assertLevels(
            """
            defmodule A do
              n = :m
              defmacrop unquote(n)(x), do: :first
              defmacrop m(x), do: x
              def g, do: m(:b)
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              defmacrop m/1 line 3 clauses 2
              def g/0 line 5 clauses 1
              body: expanded {n:0} next 1 context [Elixir.A]
              defmacrop m/1: expanded {x:0} next 1 value first
              defmacrop m/1: expanded {x:0} next 1
              def g/0: expanded {} next 0 value first
            """.trimIndent()
        }

    /** `case/1` isn't the special form, so a macro body calling it makes a local call. */
    fun testAMacroBodysCaseOfOneArgumentIsALocalCall() = assertSpecialFormNameIsALocalCall("case(x)")

    /** `try/2` isn't the special form, so a macro body calling it makes a local call. */
    fun testAMacroBodysTryOfTwoArgumentsIsALocalCall() = assertSpecialFormNameIsALocalCall("try(x, x)")

    /** A macro body of [body] calls a local function the module lacks, which the evaluator declines. */
    private fun assertSpecialFormNameIsALocalCall(body: String) =
        assertLevels("defmodule A do\n  defmacrop m(x), do: $body\n  def f, do: m(1)\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `m(1)`
              defmacrop m/1 line 2 clauses 1
              def f/0 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacrop m/1: expanded {x:0} next 1
              def f/0: opaque local_macro Elixir.A.m/1 `m(1)`
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
            module Elixir.A compiled
              defmacro s/0 line 2 clauses 1
              def f/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro s/0: expanded {} next 0
              def f/1: expanded {x:0 y:1} next 2
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
            module Elixir.A compiled
              defmacro m/0 line 2 clauses 2
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/0: expanded {} next 0
              defmacro m/0: expanded {y:0} next 1
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
            module Elixir.A compiled
              defmacro e/0 line 2 clauses 1
              def f/0 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro e/0: expanded {} next 0 value ok
              def f/0: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 0 else 2}
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

    /** A capture of a macro the module has defined is an `fn` that calls it. */
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
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              defmacro m/1 line 2 clauses 1
              def f/0 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              defmacro m/1: expanded {x:0} next 1
              def f/0: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 1 else 2}
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

    /**
     * An `unquote` that unquotes past a `quote` whose options disable unquoting is that `quote`'s, so a `quote` in a
     * pattern holding it doesn't raise, as one holding it past a `quote` that doesn't disable it does.
     */
    fun testAQuoteInAPatternHoldingAQuoteThatDisablesUnquote() =
        assertLevels(
            """
            defmodule A do
              def f(v), do: (quote(do: quote([bind_quoted: [x: 1]], do: unquote(unquote(y)))) = v)
              def g(v), do: (quote(do: quote([unquote: false], do: unquote(unquote(y)))) = v)
            end

            defmodule B do
              def h(v), do: (quote(do: quote([], do: unquote(unquote(y)))) = v)
            end
            """.trimIndent(),
            listOf("1.20.4"),
        ) {
            """
            top expanded {} next 0 context []
            module Elixir.A compiled
              def f/1 line 2 clauses 1
              def g/1 line 3 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/1: expanded {v:0} next 1
              def g/1: expanded {v:0} next 1
            module Elixir.B raised quote_in_pattern_with_unquote `quote(do: quote([], do: unquote(unquote(y))))`
              body: expanded {} next 0 context [Elixir.B]
              def h/1: error quote_in_pattern_with_unquote `quote(do: quote([], do: unquote(unquote(y))))`
            """.trimIndent()
        }

    /**
     * A macro's term at a `quote` in a pattern is read as a term, as `elixir_quote:has_unquotes/1` reads it: an
     * `unquote` in it is `quote_in_pattern_with_unquote`, and an improper list with none before its tail raises
     * `FunctionClauseError`.
     */
    fun testAMacrosTermAtAQuoteInAPatternIsReadAsATerm() {
        val mac = """
            defmodule Mac do
              defmacro m do
                {:def, [context: Elixir, imports: [{1, Kernel}, {2, Kernel}]],
                 [{:f, [], [{:=, [], [{:quote, [], [[do: {1, 2, [{:unquote, [], [{:y, [], nil}]}]}]]}, {:v, [], nil}]}]}, [do: :ok]]}
              end

              defmacro n, do: {:=, [], [{:quote, [], [[do: {1, 2, [:a | :b]}]]}, :ok]}
            end
        """.trimIndent()

        val compiled = """
            top expanded {} next 0 context []
            module Elixir.Mac compiled
              defmacro m/0 line 2 clauses 1
              defmacro n/0 line 7 clauses 1
              body: expanded {} next 0 context [Elixir.Mac]
              defmacro m/0: expanded {} next 0
              defmacro n/0: expanded {} next 0
        """.trimIndent()

        assertLevels("$mac\n\ndefmodule A do\n  require Mac\n  Mac.m()\nend", listOf("1.20.4")) {
            compiled + "\n" + """
                module Elixir.A raised quote_in_pattern_with_unquote `Mac.m()`
                  body: expanded {} next 0 context [Elixir.A]
                  def f/1: error quote_in_pattern_with_unquote `Mac.m()`
            """.trimIndent()
        }
        assertLevels("$mac\n\ndefmodule A do\n  require Mac\n  Mac.n()\nend", listOf("1.20.4")) {
            compiled + "\n" + """
                module Elixir.A raised Elixir.FunctionClauseError `Mac.n()`
                  body: error Elixir.FunctionClauseError `Mac.n()`
            """.trimIndent()
        }
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

    fun testABodyFragmentIsTheModuleBodysValue() =
        assertLevels("defmodule A do\n  x = 1\n  def f, do: unquote(x)\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 3 clauses 1
              body: expanded {x:0} next 1 context [Elixir.A]
              def f/0: expanded {} next 0
            """.trimIndent()
        }

    fun testANameFragmentIsTheModuleBodysValue() =
        assertLevels("defmodule A do\n  name = :f\n  def unquote(name)(), do: 1\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 3 clauses 1
              body: expanded {name:0} next 1 context [Elixir.A]
              def f/0: expanded {} next 0
            """.trimIndent()
        }

    fun testANameFragmentIsTheValueOfItsCall() =
        assertLevels("defmodule A do\n  def unquote(String.to_atom(\"f\"))(), do: 1\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: expanded {} next 0
            """.trimIndent()
        }

    /** `store_definition/5` reads the name only after the head is unquoted: a head fragment that isn't a call raises. */
    fun testAHeadFragmentThatIsNotACallRaises() =
        assertLevels("defmodule S do\n  h = :h\n  def unquote(h)\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.S")}
            module Elixir.S raised invalid_def `def unquote(h)`
              body: expanded {h:0} next 1 context [Elixir.S]
              def unnamed: error invalid_def `def unquote(h)`
            """.trimIndent()
        }

    /**
     * After a match the evaluator can't run, which may have raised first, a later raise isn't the module's error, and
     * it ends the file's run as a raise does.
     */
    fun testARaiseAfterADeclinedMatchStops() =
        assertLevels(
            "defmodule S do\n  _ = receive do after 0 -> :erlang.error(:first) end\n  h = :h\n  def unquote(h)\nend",
            LEVELS,
        ) { version ->
            """
            top unported `def unquote(h)`
            module Elixir.S stopped `def unquote(h)`
              body: expanded ${if (isBefore(version, "1.20.0-rc.5")) "{h:0} next 1" else "{h:2} next 3"} context [Elixir.S]
              def unnamed: unported `def unquote(h)`
            """.trimIndent()
        }

    /** An attribute whose value nests too deeply to convert stops its module, as the evaluator's own overflow does. */
    fun testAnAttributeNestedTooDeeplyStops() =
        assertLevels(
            "defmodule S do\n  l = [1]\n  ${"l = l ++ l; ".repeat(DEEP_DOUBLINGS)}\n  Module.put_attribute(__MODULE__, :a, :lists.foldl(fn x, acc -> {x, acc} end, nil, l))\n  def f, do: 1\nend",
            listOf("1.20.4"),
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.S")}
            module Elixir.S stopped `Module.put_attribute(__MODULE__, :a, :lists.foldl(fn x, acc -> {x, acc} end, nil, l))`
              def f/0 line 5 clauses 1
              body: expanded {l:17} next 21 context [Elixir.S]
              def f/0: expanded {} next 0
            """.trimIndent()
        }

    /** A fragment whose value nests too deeply to convert stops its definition, as the evaluator's own overflow does. */
    fun testAFragmentNestedTooDeeplyStops() =
        assertLevels(
            "defmodule S do\n  l = [1]\n  ${"l = l ++ l; ".repeat(DEEP_DOUBLINGS)}\n  t = :lists.foldl(fn x, acc -> {x, acc} end, nil, l)\n  def f, do: unquote(t)\nend",
            listOf("1.20.4"),
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.S")}
            module Elixir.S stopped `unquote(t)`
              def f/0 line 5 clauses 1
              body: expanded {l:17 t:21} next 22 context [Elixir.S]
              def f/0: unported `unquote(t)`
            """.trimIndent()
        }

    /** A nested module's write to the module enclosing it is applied to that module's attributes, which its body reads after. */
    fun testAWriteToAnEnclosingModuleIsAppliedToItsAttributes() =
        assertLevels(
            """
            defmodule Outer do
              defmodule Inner do
                Module.put_attribute(Outer, :a, 1)
              end

              x = Module.get_attribute(__MODULE__, :a)
              def unquote(if x == 1, do: :f1, else: :f)(), do: 1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.Outer")}
            module Elixir.Outer compiled
              def f1/0 line 7 clauses 1
              body: expanded {x:0} next ${if (isBefore(version, "1.20.0-rc.5")) 1 else 2} context [Elixir.Outer.Inner, Elixir.Outer]
              def f1/0: expanded {} next 0
              module Elixir.Outer.Inner compiled
                body: expanded {} next 0 context [Elixir.Outer.Inner, Elixir.Outer]
            """.trimIndent()
        }

    /**
     * A write through a receiver the expansion doesn't know may be `Module`'s, so it leaves the attributes unknown and
     * stops the module there: whether Elixir raises at it isn't known.
     */
    fun testAnAttributeWriteThroughAnUnknownReceiverLeavesTheAttributesUnknown() =
        assertLevels(
            """
            defmodule S do
              mod = Module
              mod.put_attribute(__MODULE__, :name, :foo)
              def unquote(Module.get_attribute(__MODULE__, :name, :bar))(), do: 1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.S")}
            module Elixir.S stopped `mod.put_attribute(__MODULE__, :name, :foo)`
              def unnamed
              body: expanded {mod:0} next 1 context [Elixir.S]
              def unnamed unordered: unported `unquote(Module.get_attribute(__MODULE__, :name, :bar))`
            """.trimIndent()
        }

    /** A macro's output holding a call whose name is an `unquote` and whose arguments are an atom raises, as Elixir rejects it as code. */
    fun testAnUnquotedCallNameWithNoArgumentsRaises() =
        assertLevels(
            """
            defmodule M do
              defmacro m, do: {:def, [], [{:f, [], []}, [do: {{{:., [], [String, :unquote]}, [], [:upcase]}, [], nil}]]}
            end

            defmodule S do
              require M
              M.m()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.S, Elixir.M")}
            module Elixir.M compiled
              defmacro m/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.M]
              defmacro m/0: expanded {} next 0
            module Elixir.S raised invalid_quoted_expr `M.m()`
              body: expanded {} next 0 context ${if (isBefore(version, "1.13.0-rc.0")) "[Elixir.S, Elixir.M]" else "[Elixir.S]"}
              def f/0: error invalid_quoted_expr `M.m()`
            """.trimIndent()
        }

    /**
     * `store_definition/5` reads the name and `length(Args)` after the splice, so a head one of whose arguments is a
     * splice has the arity of the splice's value. A splice that is the whole head is a bodiless head of
     * `unquote_splicing/1` before v1.15, skipped before 1.12; from v1.15 the head is an argument of the `{}` call
     * `do_quote/2` makes of the pair, whose value a helper the light fixture can't run checks, and the store defines
     * nothing.
     */
    fun testAHeadWithASpliceTakesItsArityFromItsValue() =
        assertLevels(
            """
            defmodule S do
              args = [1, 2]
              h = :h
              b = 1
              def spliced(unquote_splicing(args)), do: :ok
              def spliced_after(a, unquote_splicing(args)), do: a
              def k(x, unquote_splicing([1, 2])), do: x
              def unquote_splicing(h)
              def f(a, unquote(b)), do: a
              def guarded(unquote_splicing(args)) when true, do: :ok
              def g(a, unquote(b)) when true, do: a
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                val ended = if (isBefore(version, "1.12.0-rc.1")) "compiled" else "raised function_head `def unquote_splicing(h)`"

                """
                top expanded {} next 0 context ${topContext(version, "Elixir.S")}
                module Elixir.S $ended
                  def spliced/2 line 5 clauses 1
                  def spliced_after/3 line 6 clauses 1
                  def k/3 line 7 clauses 1
                  def unquote_splicing/1 line 8 clauses 0
                  def f/2 line 9 clauses 1
                  def guarded/2 line 10 clauses 1
                  def g/2 line 11 clauses 1
                  body: expanded {args:0 b:2 h:1} next 3 context [Elixir.S]
                  def spliced/2: expanded {} next 0 value ok
                  def spliced_after/3: expanded {a:0} next 1
                  def k/3: expanded {x:0} next 1
                  def unquote_splicing/1: expanded {} next 0
                  def f/2: expanded {a:0} next 1
                  def guarded/2: expanded {} next 0 value ok
                  def g/2: expanded {a:0} next 1
                """.trimIndent()
            } else {
                """
                top expanded {} next 0 context ${topContext(version, "Elixir.S")}
                module Elixir.S stopped `unquote_splicing(h)`
                  def spliced/2 line 5 clauses 1
                  def spliced_after/3 line 6 clauses 1
                  def k/3 line 7 clauses 1
                  def f/2 line 9 clauses 1
                  def guarded/2 line 10 clauses 1
                  def g/2 line 11 clauses 1
                  body: expanded {args:0 b:2 h:1} next 3 context [Elixir.S]
                  def spliced/2: expanded {} next 0 value ok
                  def spliced_after/3: expanded {a:0} next 1
                  def k/3: expanded {x:0} next 1
                  def unnamed unordered: unported `unquote_splicing(h)`
                  def f/2 unordered: expanded {a:0} next 1
                  def guarded/2 unordered: expanded {} next 0 value ok
                  def g/2 unordered: expanded {a:0} next 1
                """.trimIndent()
            }
        }

    /**
     * `unquote` inserts its value as quoted code, in which a tuple of three is a call and of four or more isn't code at
     * all, so such a value is not a tuple literal in the definition.
     */
    fun testATupleOfThreeFragmentStopsItsBody() =
        assertLevels("defmodule A do\n  def f, do: unquote({:a, :b, :c})\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A stopped `unquote({:a, :b, :c})`
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/0: unported `unquote({:a, :b, :c})`
            """.trimIndent()
        }

    /** A value that is a call, as `{:y, [], nil}` is, binds in a head what a tuple literal wouldn't. */
    fun testACallAsATupleFragmentBindsInItsHead() =
        assertLevels("defmodule A do\n  def f(unquote({:y, [], nil})), do: 1\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def f/1 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def f/1: expanded {y:0} next 1
            """.trimIndent()
        }

    /** The escape of a definition calls `elixir_quote.dot/5` for an unquoted call name and `list/2` for a splice, in order. */
    fun testADefinitionDispatchesTheQuoteCallsItsUnquotesMake() {
        val code = "defmodule A do\n  def f(x), do: unquote(:a).unquote(:b)(unquote_splicing([1]))\nend"

        assertQuoteCalls(code, "elixir_quote.dot/5, elixir_quote.list/2")
    }

    /** A splice among quoted elements joins them to its list with `++`, whose call comes before the `list/2` it joins. */
    fun testASpliceAmongQuotedElementsDispatchesTheJoin() =
        assertQuoteCalls("defmodule A do\n  def f(a, unquote_splicing([1])), do: a\nend", "erlang.++/2, elixir_quote.list/2")

    /** A splice before the tail of a list is `tail_list/3`, whatever elements precede it. */
    fun testASpliceBeforeATailDispatchesTailList() =
        assertQuoteCalls(
            "defmodule A do\n  def g, do: [1, unquote_splicing([2]) | [3]]\nend",
            "elixir_quote.tail_list/3",
        )

    /** An `unquote` in the receiver of an unquoted call name is evaluated beside the `dot/5` that joins it to the name. */
    fun testAnUnquoteInTheReceiverOfAnUnquotedCallNameIsEvaluated() =
        assertQuoteCalls(
            "defmodule A do\n  def h(x), do: unquote(Enum.max([:a])).unquote(:b)(x)\nend",
            "elixir_quote.dot/5, Elixir.Enum.max/1",
        )

    /** The name is evaluated, where the receiver is quoted: `do_quote_call` quotes `Left` and unquotes `Expr`. */
    fun testTheExpressionOfAnUnquotedCallNameIsEvaluated() =
        assertQuoteCalls(
            "defmodule A do\n  def h(x), do: x.unquote(Enum.max([:b]))(x)\nend",
            "elixir_quote.dot/5, Elixir.Enum.max/1",
        )

    /** The definition stops at the `receive`, so its body, which calls `Enum.max/1` when it runs, isn't expanded. */
    fun testTheReceiverOfAnUnquotedCallNameIsQuoted() =
        assertQuoteCalls(
            "defmodule A do\n  def h(x), do: {Enum.max([:a]).unquote(:b)(x), unquote(receive do after 0 -> :u end)}\nend",
            "elixir_quote.dot/5",
        )

    /** `do_quote_call` quotes the receiver of an unquoted call name, so a receiver that raises isn't run when the `def` is. */
    fun testAQuotedReceiverOfAnUnquotedCallNameIsNotRun() =
        assertLevels("defmodule A do\n  def h(x), do: :erlang.hd([]).unquote(:b)(x)\nend", LEVELS) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A compiled
              def h/1 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A]
              def h/1: expanded {x:0} next 1
            """.trimIndent()
        }

    /** A placeholder that stops expansion stops the definition holding it, as it does without the `unquote`, not the module body. */
    fun testAStoppingPlaceholderInADefinitionWithUnquotesStopsOnlyThatDefinition() {
        val code = "defmodule A do\n  def f(unquote(:x)), do: c\n  def g, do: 1\nend"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: f/1 g/0" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val file = Expander.expandFile(
                    placeholding(lower(code, level), "c"),
                    Env.empty(level, kernel),
                    level,
                    exports,
                    structs,
                )

                "$version: " + file.modules.single().table.entries.keys.joinToString(" ") { "${it.name}/${it.arity}" }
            },
        )
    }

    /**
     * A definition holding code the port doesn't lower leaves what its module defined unknown, while a raise computed
     * from what is known after it still leaves the module absent, and the module after it loads.
     */
    fun testAnUnreadDefinitionLeavesItsModuleUnreadable() {
        val unlowered = ElixirAst.Placeholder.Reason.Unlowered(PsiElement::class.java)

        fun loadings(code: String, version: String): String {
            val level = ElixirLanguageLevel.of(version)
            val file = Expander.expandFile(placeholding(lower(code, level), "c", unlowered), Env.empty(level, kernel), level, exports, structs)

            return file.modules.joinToString(" ") { "${it.module} ${LoadedModules.exports(it)::class.simpleName}" }
        }

        val versions = listOf("1.11.4", "1.20.4")

        assertEquals(
            versions.joinToString("\n") { "$it: Elixir.A Unreadable Elixir.B Present / Elixir.A Absent" },
            versions.joinToString("\n") { version ->
                "$version: " + loadings("defmodule A do\n  def f, do: c\nend\ndefmodule B do\n  def g, do: 1\nend", version) +
                    " / " + loadings("defmodule A do\n  def f, do: c\n  1 + :a\nend", version)
            },
        )
    }

    /**
     * When the escape stops, the definition's own `:erlang` calls are the body's to dispatch: they are not helpers of an
     * escape that built nothing, and none is traced where the definition is made.
     */
    fun testACallOfTheCodeIsNotATraceOfTheEscapeItStopsAt() =
        assertQuoteCalls("defmodule A do\n  def f(unquote(:x)), do: :erlang.length(c)\nend", "erlang.length/1", placeholder = "c")

    /** An expression the definition unquotes is dispatched once where the definition is made, though the escape stops. */
    fun testAnUnquotedExpressionInACodeTheEscapeStopsAtIsEvaluatedOnce() =
        assertQuoteCalls(
            "defmodule A do\n  def f(unquote(Enum.max([:a]))), do: c\nend",
            "Elixir.Enum.max/1",
            placeholder = "c",
        )

    /** The expression a splice unquotes is evaluated after the `list/2` that takes it, and before the elements it joins. */
    fun testTheExpressionOfASpliceIsEvaluated() =
        assertQuoteCalls(
            "defmodule A do\n  def k(x, unquote_splicing(Enum.to_list([1, 2])), y), do: [1, unquote_splicing([2]), 3]\nend",
            "erlang.++/2, elixir_quote.list/2, Elixir.Enum.to_list/1, erlang.++/2, elixir_quote.list/2",
        )

    /** [code]'s calls of `elixir_quote`, `erlang` and `Enum`, in order, at every level are [expected], [placeholder] standing for code the port stops at. */
    private fun assertQuoteCalls(code: String, expected: String, placeholder: String? = null) =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: $expected" },
            LEVELS.joinToString("\n") { version ->
                val calls = mutableListOf<String>()
                val observer = object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                        if (dispatch.receiver in QUOTE_RECEIVERS) calls.add("${dispatch.receiver}.${dispatch.name}/${dispatch.arity}")
                    }
                }
                val level = ElixirLanguageLevel.of(version)

                val lowered = lower(code, level).let { if (placeholder == null) it else placeholding(it, placeholder) }

                Expander.expandFile(lowered, Env.empty(level, kernel), level, exports, structs, observer)

                "$version: " + calls.joinToString()
            },
        )

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

    /**
     * A definition that raises after one that stopped isn't the module's raise for certain, as Elixir may have raised
     * at the stop, so the module keeps its stop. The walk ends there all the same: Elixir ran nothing after a raise,
     * so nothing after it is expanded.
     */
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
        ) {
            """
            top unported `:ok`
            module Elixir.A stopped `:ok`
              def f/0 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.A.Inner, Elixir.A]
              def f/0: unported `:ok`
              def __info__/1: unported `def __info__(x), do: 1`
            """.trimIndent()
        }

    /**
     * A module nested in another stops, and the enclosing module's first definition then raises. The nested module's
     * stop leaves what Elixir runs after it unfollowed, so the enclosing module's tables are read as unknown and what
     * it defines isn't known, but Elixir raises at that definition all the same: the file's run ends there and the
     * module after it is never compiled.
     */
    fun testANestedModulesStopLeavesTheEnclosingModulesRaiseCertain() =
        assertLevels(
            """
            defmodule M do
              defmodule N do
                def $UNNAMED(), do: 1
              end
              def __info__(x), do: 1
            end

            defmodule L do
              def h, do: 1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top unported `$UNNAMED`
            module Elixir.M stopped `$UNNAMED`
              body: expanded {} next 0 context [Elixir.M.N, Elixir.M]
              def __info__/1: unported `def __info__(x), do: 1`
            """.trimIndent() + "\n" + nestedStopped(version)
        }

    /**
     * After a module nested in it stops, the enclosing module's `@on_definition` entries aren't known, so a definition
     * it stores after the stop may run a callback the expander doesn't follow. That callback is possible only through
     * the stop, so it marks the module as the stop does: a raise computed from its tables after that definition is still
     * certain, as Elixir's at the `defmacro` is, and the module after it is never compiled.
     */
    fun testADefinitionStoredAfterANestedStopLeavesALaterRaiseFromTheTableCertain() =
        assertLevels(
            """
            defmodule M do
              defmodule N do
                def $UNNAMED(), do: 1
              end
              def f, do: 1
              defmacro f, do: 1
            end

            defmodule L do
              def h, do: 1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top unported `$UNNAMED`
            module Elixir.M stopped `$UNNAMED`
              def f/0 line 5 clauses 1
              body: expanded {} next 0 context [Elixir.M.N, Elixir.M]
              def f/0: expanded {} next 0
              defmacro f/0: unported `defmacro f, do: 1`
            """.trimIndent() + "\n" + nestedStopped(version)
        }

    /**
     * After a module nested in it stops, a module's `@after_compile` entries aren't known, so its end may run a callback
     * the expander doesn't follow, possible only through the stop: it marks the modules enclosing it as the stop does,
     * and the outermost module's raise at its first definition is still certain, so the module after it is never
     * compiled.
     */
    fun testANestedModulesPossibleAfterCompileCallbackLeavesTheEnclosingModulesRaiseCertain() =
        assertLevels(
            """
            defmodule O do
              defmodule M do
                defmodule N do
                  def $UNNAMED(), do: 1
                end
              end
              def __info__(x), do: 1
            end

            defmodule L do
              def h, do: 1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top unported `$UNNAMED`
            module Elixir.O stopped `$UNNAMED`
              body: expanded {} next 0 context [Elixir.O.M, Elixir.O]
              def __info__/1: unported `def __info__(x), do: 1`
              module Elixir.O.M stopped `$UNNAMED`
                body: expanded {} next 0 context [Elixir.O.M.N, Elixir.O.M, Elixir.O]
                module Elixir.O.M.N stopped `$UNNAMED`
                  def unnamed
                  body: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 0 else 1} context [Elixir.O.M.N, Elixir.O.M, Elixir.O]
                  def unnamed unordered: unported `$UNNAMED`
            """.trimIndent()
        }

    /** `M.N`'s rendering where it stops at [UNNAMED]: from 1.20.0-rc.5 its body's counter counts the definition. */
    private fun nestedStopped(version: String) =
        """
          module Elixir.M.N stopped `$UNNAMED`
            def unnamed
            body: expanded {} next ${if (isBefore(version, "1.20.0-rc.5")) 0 else 1} context [Elixir.M.N, Elixir.M]
            def unnamed unordered: unported `$UNNAMED`
        """.trimIndent().prependIndent("  ")

    /**
     * A body that raises stores its definitions with no clause expanded, so no macro of theirs runs, as Elixir raises
     * `CompileError` for the module before it evaluates any of the body.
     */
    fun testABodyThatRaisesExpandsNoClause() =
        assertLevels(
            """
            defmodule D do
              defmacro d(name, arity), do: Module.defines?(__CALLER__.module, {name, arity})
            end

            defmodule RD do
              require D
              def f, do: 1
              def in_def, do: D.d(:f, 0)
              undefined()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.RD, Elixir.D")}
            module Elixir.D compiled
              defmacro d/2 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.D]
              defmacro d/2: expanded {arity:1 name:0} next 2
            module Elixir.RD raised undefined_function `undefined()`
              def f/0 line 7 clauses 1
              def in_def/0 line 8 clauses 1
              body: error undefined_function `undefined()`
            """.trimIndent()
        }

    /**
     * A closure a module-body statement bound, called where no run of that body is on the stack while a run around the
     * module keeps the unit's callback registered: in a nested module the body walks as a statement, and in a
     * definition's fragment. The unit's raise ends the module's run where Elixir raises; its reach outside every run of
     * the body isn't certain, so the module stops there and the file's top declines, where Elixir loads nothing.
     */
    fun testALateUnitRaiseOutsideEveryRunOfTheBodyStopsTheModule() {
        val reduce = "g = Enum.reduce([1], fn\n      :a -> :ok\n      :b -> :erlang.hd([])\n    end, fn _, g -> g.(:a); g end)"

        assertLevels(
            """
            for _ <- [1] do
              defmodule A do
                $reduce
                defmodule N do
                  g.(:b)
                end
                def h, do: 1
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val counters = if (CLAUSES_TAKE_VERSION.isSufficient(ElixirLanguageLevel.of(version))) "{g:4} next 5" else "{g:1} next 2"

            """
            top unported `:erlang.hd([])`
            module Elixir.A stopped `:erlang.hd([])`
              body: expanded $counters context [Elixir.A.N, Elixir.A]
            """.trimIndent()
        }
        assertLevels(
            """
            for _ <- [1] do
              defmodule A do
                $reduce
                def unquote(g.(:b))(), do: 1
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val counters = if (CLAUSES_TAKE_VERSION.isSufficient(ElixirLanguageLevel.of(version))) "{g:4} next 5" else "{g:1} next 2"

            """
            top unported `:erlang.hd([])`
            module Elixir.A stopped `:erlang.hd([])`
              body: expanded $counters context [Elixir.A]
            """.trimIndent()
        }
    }

    /**
     * A macro output too deep to expand is given up on at the innermost guard on the stack, the macro's own or a port's
     * inside its output, in a function body, in the module body or in a module two levels in, without ending every
     * module around the caller, so a read of a module the output would have written, after the give-up, must not find
     * the old value: at every depth the read is Elixir's or declines. An output that expands but is too deep to run in
     * the module body is given up on once, at the unit running it: were the units nested in it run again from the body,
     * one at every level of a nested `if`, each would descend to the same boundary and be given up on there in turn,
     * each repeat redoing the work below it, and the attempt would not finish. The depth sweeps by quarters on a thread
     * with the default stack, from below the depth whose run overflows, as the overflow points move with the JIT, and
     * each attempt is cancelled at a bound.
     */
    fun testAMacroOutputTooDeepToExpandMisleadsNoReadAfterIt() {
        val inFunction = { ones: String, nest: String ->
            """
            defmodule W do
              defmacro w do
                Module.put_attribute(Enum.at(__CALLER__.context_modules, 1), :x, :b)
                :ok
              end
              defmacro deep(l), do: Enum.reduce(l, quote(do: W.w()), $nest)
              defmacro r(module, key), do: Module.get_attribute(module, key)
            end

            defmodule NS do
              Module.put_attribute(__MODULE__, :x, :a)

              defmodule Inner do
                require W
                def h, do: W.deep([$ones])
                def g, do: W.r(:"Elixir.NS", :x)

                defmodule Two do
                  require W
                  def unquote(W.r(:"Elixir.NS", :x))(), do: 1
                end
              end
            end
            """.trimIndent()
        }
        val inBody = { ones: String, nest: String ->
            """
            defmodule W do
              defmacro deep(l), do: Enum.reduce(l, quote(do: Module.put_attribute(__MODULE__, :x, :b)), $nest)
              defmacro r(module, key), do: Module.get_attribute(module, key)
            end

            defmodule NS do
              require W
              Module.put_attribute(__MODULE__, :x, :a)
              W.deep([$ones])
              def g, do: W.r(:"Elixir.NS", :x)

              defmodule Two do
                require W
                def unquote(W.r(:"Elixir.NS", :x))(), do: 1
              end
            end
            """.trimIndent()
        }
        val twoLevelsOut = { ones: String, nest: String ->
            """
            defmodule W do
              defmacro deep(l), do: Enum.reduce(l, quote(do: Module.put_attribute(:"Elixir.O2", :x, :b)), $nest)
              defmacro r(module, key), do: Module.get_attribute(module, key)
            end

            defmodule O2 do
              Module.put_attribute(__MODULE__, :x, :a)

              defmodule O1 do
                defmodule NS do
                  require W
                  W.deep([$ones])
                end

                defmodule Two do
                  require W
                  def unquote(W.r(:"Elixir.O2", :x))(), do: 1
                end
              end
            end
            """.trimIndent()
        }

        // The output nests lists, or `if`s, whose port's guard is the innermost one on the stack when it overflows.
        val nests = listOf("list" to "fn _, acc -> [acc] end", "if" to "fn _, acc -> {:if, [], [true, [do: acc]]} end")
        val sweep = listOf("in-function" to inFunction, "in-body" to inBody, "two-levels-out" to twoLevelsOut).flatMap { (label, code) ->
            nests.map { (nest, reducer) -> "$label $nest: " + sweepDepths { ones, version -> readAfterDeepOutput(code(ones, reducer), version) } }
        }.joinToString("\n")

        assertFalse(sweep, sweep.contains("misled") || sweep.contains("unexpected") || sweep.contains("unfinished"))
        assertTrue(sweep, sweep.contains("in-body list: 1.20.4 100=elixir") && sweep.contains("two-levels-out list: 1.20.4 100=elixir"))
    }

    /**
     * What a read in `Two` found at [code] expanded at [version] on a thread with the default stack, or `unfinished` where
     * the expansion was cancelled at [BOUND_MS].
     */
    private fun readAfterDeepOutput(code: String, version: String): String {
        val indicator = EmptyProgressIndicator()
        var rendered: String? = null
        var escaped: Throwable? = null
        val thread = Thread(
            null,
            {
                try {
                    ProgressManager.getInstance().runProcess({ rendered = expandAndRender(code, version) }, indicator)
                } catch (e: Throwable) {
                    escaped = e
                }
            },
            "default stack",
            0,
        )

        thread.start()
        thread.join(BOUND_MS)

        if (thread.isAlive) {
            indicator.cancel()
            thread.join()

            return "unfinished"
        }

        assertNull("escaped: $escaped", escaped)

        val lines = rendered!!.lines()
        val two = lines.indexOfFirst { it.trim().startsWith("module Elixir.") && it.contains(".Two ") }

        if (two < 0) return if (lines.any { it.contains(" stopped `") }) "absent" else "unexpected: no Two in ${lines.filter { it.contains("module ") }}"

        val twoLine = lines[two].trim()
        val twoBody = lines.drop(two + 1).takeWhile { it.startsWith(" ".repeat(lines[two].indexOf("module") + 2)) }

        return when {
            twoBody.any { it.contains("def a/0") } -> "misled: $twoLine"
            twoBody.any { it.contains("def b/0") } -> "elixir"
            twoLine.contains(" stopped `") -> "stopped"
            else -> "unexpected: $twoLine"
        }
    }

    /** [outcome] at each depth from 100 to 30000 by quarters, on 1.20.4 and 1.11.4, as `depth=outcome` pairs. */
    private fun sweepDepths(outcome: (String, String) -> String): String =
        listOf("1.20.4", "1.11.4").joinToString(" | ") { version ->
            var depth = 100
            val outcomes = mutableListOf<String>()

            while (depth <= 30000) {
                outcomes += "$depth=" + outcome(List(depth) { "1" }.joinToString(", "), version)
                depth += depth / 4
            }

            "$version " + outcomes.joinToString(" ")
        }

    /**
     * A unit reached after the evaluator declined an iteration of a loop it went on with may never run, so its raise is
     * not Elixir's for certain, where the unit's callback fires from a statement run after its own ended: here the
     * `receive` declines at `z = 1`, where Elixir raises `:boom`, and the `defp f` reached at `z = 2` would raise.
     */
    fun testAUnitReachedAfterAContinuedDeclineIsUncertainInALaterRun() {
        assertLevels(
            """
            defmodule A do
              Enum.reduce([1, 2], nil, fn
                1, nil ->
                  Enum.reduce([:a], fn
                    :a -> def f, do: 1
                    :b -> defp f, do: 2
                  end, fn x, g -> g.(x); g end)
                2, g ->
                  for z <- [1, 2], (case z do 2 -> true; _ -> receive do after 0 -> :erlang.error(:boom) end end), g.(:b), do: :ok
              end)
              def h, do: 1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top unported `defp f, do: 2`
            module Elixir.A stopped `defp f, do: 2`
              def f/0 line 5 clauses 1
              body: expanded {} next ${if (CLAUSES_TAKE_VERSION.isSufficient(ElixirLanguageLevel.of(version))) 11 else 4} context [Elixir.A]
              def f/0: expanded {} next 0
              defp f/0: unported `defp f, do: 2`
            """.trimIndent()
        }
    }

    /**
     * A raise in the outer module's unit, reached while a nested module's statement runs, ends both modules, as Elixir
     * raises while compiling the nested one.
     */
    fun testAnOuterUnitsRaiseInsideANestedModuleEndsBoth() {
        assertLevels(
            """
            defmodule A do
              def f, do: 1
              Enum.reduce([1], fn -> defp f, do: 2 end, fn _, g ->
                defmodule N do
                  def n, do: 1
                  g.()
                  def m, do: 1
                end
                g
              end)
              def h, do: 1
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.A")}
            module Elixir.A raised changed_kind `defp f, do: 2`
              def f/0 line 2 clauses 1
              body: expanded {} next ${if (CLAUSES_TAKE_VERSION.isSufficient(ElixirLanguageLevel.of(version))) 4 else 1} context [Elixir.A]
              def f/0: expanded {} next 0
              defp f/0: error changed_kind `defp f, do: 2`
            """.trimIndent()
        }
    }

    /**
     * After a stop, which may hide a removal, `Module.defines?/2` declines, `true` included. The module is named as an
     * atom, as `__CALLER__` needs `Macro.Env`'s clauses, which aren't read here.
     */
    fun testDefinesDeclinesAfterTheModuleStops() =
        assertLevels(
            """
            defmodule D do
              defmacro d(module, name, arity), do: Module.defines?(module, {name, arity})
            end

            defmodule Y do
              require D
              def f, do: 1
              def unquote(Application.get_env(:app, :name, :g))(), do: 1
              def in_def, do: D.d(:"Elixir.Y", :f, 0)
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val context = if (isBefore(version, "1.13.0-rc.0")) "[Elixir.Y, Elixir.D]" else "[Elixir.Y]"

            """
            top expanded {} next 0 context ${topContext(version, "Elixir.Y, Elixir.D")}
            module Elixir.D compiled
              defmacro d/3 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.D]
              defmacro d/3: expanded {arity:2 module:0 name:1} next 3
            module Elixir.Y stopped `unquote(Application.get_env(:app, :name, :g))`
              def f/0 line 7 clauses 1
              def in_def/0 line 9 clauses 1
              def unnamed
              body: expanded {} next 0 context $context
              def f/0: expanded {} next 0
              def unnamed unordered: unported `unquote(Application.get_env(:app, :name, :g))`
              def in_def/0 unordered: opaque remote_macro Elixir.D.d/3 `D.d(:"Elixir.Y", :f, 0)`
            """.trimIndent()
        }

    /** A nested module's stop at a remover given the module it is in may remove from that module. */
    fun testDefinesDeclinesForTheEnclosingModuleAfterANestedStop() =
        assertDefinesDeclinesForTheEnclosingModuleAfterANestedStopAt("Module.make_overridable(NS, f: 0)")

    fun testDefinesDeclinesForTheEnclosingModuleAfterANestedStopAtTakeDefinition() =
        assertDefinesDeclinesForTheEnclosingModuleAfterANestedStopAt(":elixir_def.take_definition(NS, {:f, 0})")

    private fun assertDefinesDeclinesForTheEnclosingModuleAfterANestedStopAt(remover: String) =
        assertLevels(
            """
            defmodule DM do
              defmacro dm(module, name, arity), do: Module.defines?(module, {name, arity})
            end

            defmodule NS do
              def f, do: 1

              defmodule Inner do
                require DM
                $remover
                def g, do: DM.dm(:"Elixir.NS", :f, 0)
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            val context =
                if (isBefore(version, "1.13.0-rc.0")) "[Elixir.NS.Inner, Elixir.NS, Elixir.DM]" else "[Elixir.NS.Inner, Elixir.NS]"

            """
            top expanded {} next 0 context ${topContext(version, "Elixir.NS, Elixir.DM")}
            module Elixir.DM compiled
              defmacro dm/3 line 2 clauses 1
              body: expanded {} next 0 context [Elixir.DM]
              defmacro dm/3: expanded {arity:2 module:0 name:1} next 3
            module Elixir.NS stopped `$remover`
              def f/0 line 6 clauses 1
              body: expanded {} next 0 context $context
              def f/0: expanded {} next 0
              module Elixir.NS.Inner stopped `$remover`
                def g/0 line 11 clauses 1
                body: expanded {} next 0 context $context
                def g/0: opaque remote_macro Elixir.DM.dm/3 `DM.dm(:"Elixir.NS", :f, 0)`
            """.trimIndent()
        }

    /**
     * A macro expanded in a function body of a nested module writes the module around it, given by its argument, and a
     * later read of that module's attributes reads what it wrote, as Elixir's does.
     */
    fun testAMacroWritingTheEnclosingModuleIsReadAfterIt() =
        assertLevels(
            """
            defmodule W do
              defmacro w(module) do
                Module.put_attribute(module, :x, :written)
                :ok
              end

              defmacro r(module, key), do: Module.get_attribute(module, key)
            end

            defmodule NS do
              Module.put_attribute(__MODULE__, :x, 1)

              defmodule Inner do
                require W
                def h, do: W.w(:"Elixir.NS")
                def g, do: W.r(:"Elixir.NS", :x)
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.NS, Elixir.W")}
            module Elixir.W compiled
              defmacro w/1 line 2 clauses 1
              defmacro r/2 line 7 clauses 1
              body: expanded {} next 0 context [Elixir.W]
              defmacro w/1: expanded {module:0} next 1
              defmacro r/2: expanded {key:1 module:0} next 2
            module Elixir.NS compiled
              body: expanded {} next 0 context ${innerContext(version)}
              module Elixir.NS.Inner compiled
                def h/0 line 15 clauses 1
                def g/0 line 16 clauses 1
                body: expanded {} next 0 context ${innerContext(version)}
                def h/0: expanded {} next 0 value ok
                def g/0: expanded {} next 0 value written
            """.trimIndent()
        }

    /**
     * A macro the expander can't follow may write every module being compiled around its caller, whatever its arguments:
     * this one writes the module around the nested one through `__CALLER__.context_modules`, so a later read of it
     * declines where Elixir reads what the macro wrote.
     */
    fun testAnUnfollowedMacroGivenNothingLeavesTheEnclosingModulesAttributesUnknown() =
        assertLevels(
            """
            defmodule W do
              defmacro w do
                receive do after 0 -> :ok end
                Module.put_attribute(Enum.at(__CALLER__.context_modules, 1), :x, 2)
              end
              defmacro r(module, key), do: Module.get_attribute(module, key)
            end

            defmodule NS do
              Module.put_attribute(__MODULE__, :x, 1)

              defmodule Inner do
                require W
                def h, do: W.w()
                def g, do: W.r(:"Elixir.NS", :x)
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.NS, Elixir.W")}
            module Elixir.W compiled
              defmacro w/0 line 2 clauses 1
              defmacro r/2 line 6 clauses 1
              body: expanded {} next 0 context [Elixir.W]
              defmacro w/0: expanded {} next ${if (CLAUSES_TAKE_VERSION.isSufficient(ElixirLanguageLevel.of(version))) 1 else 0}
              defmacro r/2: expanded {key:1 module:0} next 2
            module Elixir.NS stopped `W.w()`
              body: expanded {} next 0 context ${innerContext(version)}
              module Elixir.NS.Inner stopped `W.w()`
                def h/0 line 14 clauses 1
                def g/0 line 15 clauses 1
                body: expanded {} next 0 context ${innerContext(version)}
                def h/0: opaque remote_macro Elixir.W.w/0 `W.w()`
                def g/0: opaque remote_macro Elixir.W.r/2 `W.r(:"Elixir.NS", :x)`
            """.trimIndent()
        }

    /** The same for the table: a remover in a macro the expander can't follow may have taken the definition out. */
    fun testAnUnfollowedMacroGivenTheEnclosingModuleLeavesItsTableUnknown() =
        assertLevels(
            """
            defmodule W do
              defmacro w(module) do
                :elixir_def.take_definition(module, {:f, 0})
                :ok
              end

              defmacro d(module, name, arity), do: Module.defines?(module, {name, arity})
            end

            defmodule NS do
              def f, do: 1

              defmodule Inner do
                require W
                def h, do: W.w(:"Elixir.NS")
                def g, do: W.d(:"Elixir.NS", :f, 0)
              end
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            """
            top expanded {} next 0 context ${topContext(version, "Elixir.NS, Elixir.W")}
            module Elixir.W compiled
              defmacro w/1 line 2 clauses 1
              defmacro d/3 line 7 clauses 1
              body: expanded {} next 0 context [Elixir.W]
              defmacro w/1: expanded {module:0} next 1
              defmacro d/3: expanded {arity:2 module:0 name:1} next 3
            module Elixir.NS stopped `W.w(:"Elixir.NS")`
              def f/0 line 11 clauses 1
              body: expanded {} next 0 context ${innerContext(version)}
              def f/0: expanded {} next 0
              module Elixir.NS.Inner stopped `W.w(:"Elixir.NS")`
                def h/0 line 15 clauses 1
                def g/0 line 16 clauses 1
                body: expanded {} next 0 context ${innerContext(version)}
                def h/0: opaque remote_macro Elixir.W.w/1 `W.w(:"Elixir.NS")`
                def g/0: opaque remote_macro Elixir.W.d/3 `W.d(:"Elixir.NS", :f, 0)`
            """.trimIndent()
        }

    /** The `context_modules` in a module nested in `NS` at [version]: `W`, compiled before them, is there before 1.13, as in [topContext]. */
    private fun innerContext(version: String): String =
        if (isBefore(version, "1.13.0-rc.0")) "[Elixir.NS.Inner, Elixir.NS, Elixir.W]" else "[Elixir.NS.Inner, Elixir.NS]"

    /** Each module-body call of a remover's name and arity reaches the table or stops, whatever its receiver and target. */
    fun testEachRemoverCallReachesTheTableOrStopsItsModule() {
        val code =
            """
            defmodule DD do
              def f, do: 1
              Module.delete_definition(__MODULE__, {:f, 0})
            end

            defmodule UD do
              def f, do: 1
              m = Application.get_env(:app, :mod, Module)
              m.delete_definition(__MODULE__, {:f, 0})
            end

            defmodule UT do
              def f, do: 1
              Module.delete_definition(Application.get_env(:app, :t, __MODULE__), {:f, 0})
            end

            defmodule UO do
              def f, do: 1
              Module.make_overridable(Application.get_env(:app, :t, __MODULE__), f: 0)
            end

            defmodule UM do
              def f, do: 1
              m = Application.get_env(:app, :mod, Module)
              m.make_overridable(__MODULE__, f: 0)
            end

            defmodule ED do
              def f, do: 1
              :elixir_def.take_definition(__MODULE__, {:f, 0})
            end

            defmodule TU do
              def f, do: 1
              m = Application.get_env(:app, :mod, :elixir_def)
              m.take_definition(__MODULE__, {:f, 0})
            end

            defmodule NO do
              def f, do: 1

              defmodule Inner do
                Module.make_overridable(NO, f: 0)
              end
            end
            """.trimIndent()

        assertLevels(code, LEVELS) { version ->
            val before = isBefore(version, "1.13.0-rc.0")
            val names = listOf("DD", "UD", "UT", "UO", "UM", "ED", "TU", "NO")
            val listed = { modules: List<String> -> modules.joinToString(prefix = "[", postfix = "]") { "Elixir.$it" } }
            // Before 1.13 a module's body has the context of every module the file defined before it.
            val context = { index: Int, inner: List<String> ->
                listed(inner + if (before) names.take(index + 1).reversed() else listOf(names[index]))
            }
            val lines = listOf(2, 7, 13, 18, 23, 29, 34, 40)

            fun module(index: Int, stop: String, bound: String, inner: List<String> = emptyList(), removed: Boolean = false) =
                listOfNotNull(
                    "module Elixir.${names[index]} ${if (stop.isEmpty()) "compiled" else "stopped `$stop`"}",
                    "  def f/0 line ${lines[index]} clauses 1".takeUnless { removed },
                    "  body: expanded {$bound} next ${if (bound.isEmpty()) 0 else 1} context ${context(index, inner)}",
                    "  def f/0: expanded {} next 0",
                ).joinToString("\n")
            val deletes = !isBefore(version, "1.12.0-rc.0")
            // Before 1.12 `delete_definition` is no function of `Module`: a call given the module may have written it,
            // after which its `@before_compile` callbacks aren't known. `get_env` may return its default, the module.
            val delete = { stop: String, index: Int -> if (deletes) stop else code.split("\n\n")[index] }

            listOf(
                "top expanded {} next 0 context ${if (before) listed(names.reversed()) else "[]"}",
                // From 1.12 the call takes `f/0` out of the table, as Elixir's does.
                if (deletes) module(0, "", "", removed = true) else module(0, code.split("\n\n")[0], ""),
                module(1, delete("m.delete_definition(__MODULE__, {:f, 0})", 1), "m:0"),
                module(2, delete("Module.delete_definition(Application.get_env(:app, :t, __MODULE__), {:f, 0})", 2), ""),
                module(3, "Module.make_overridable(Application.get_env(:app, :t, __MODULE__), f: 0)", ""),
                module(4, "m.make_overridable(__MODULE__, f: 0)", "m:0"),
                module(5, ":elixir_def.take_definition(__MODULE__, {:f, 0})", ""),
                module(6, "m.take_definition(__MODULE__, {:f, 0})", "m:0"),
                module(7, "Module.make_overridable(NO, f: 0)", "", listOf("NO.Inner")),
                "  module Elixir.NO.Inner stopped `Module.make_overridable(NO, f: 0)`",
                "    body: expanded {} next 0 context ${context(7, listOf("NO.Inner"))}",
            ).joinToString("\n")
        }
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
    /** A definition whose name the evaluator can't produce, which stops the module. */
    private val UNNAMED = "unquote((receive do after 0 -> :g end))"

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
        val QUOTE_RECEIVERS = setOf("elixir_quote", "erlang", "Elixir.Enum")

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

        /** The bits of a wide bitstring: far more than the checks the rest of its expansion makes. */
        const val WIDE = 50_000

        /** The doublings of a one-element list that make a long one: far more elements than the checks of the rest. */
        const val DOUBLINGS = 12

        /** The doublings of a one-element list whose fold into nested pairs nests deeper than a thread's stack goes. */
        const val DEEP_DOUBLINGS = 17

        /** How long one attempt at a deep output may take before it is cancelled: a give-up repeated per level takes far longer. */
        const val BOUND_MS = 60_000L

        /** A list `t` that holds itself twice at every depth: built in [DOUBLINGS] steps, walked in 2^[DOUBLINGS]. */
        val TREE = "t = []; " + "t = [t, t]; ".repeat(DOUBLINGS)
    }
}
