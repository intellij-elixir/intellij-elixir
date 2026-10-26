package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.CACHE_ENV_IN_ELIXIR_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_DELETE_DEFINITION
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ElixirFile

/**
 * What [LoadedModules] reads from each `.beam` in every app of the leg's Elixir and OTP, against what Elixir gives once it
 * loads the module: `__info__(:functions)` and `(:macros)` for a module that has `__info__/1`, `module_info(:exports)`
 * for one that doesn't. A module the node can't load is left out, and the failure lists them.
 */
class LoadedModulesTest : ProbeTestCase() {
    fun testEveryModuleExportsWhatElixirLoads() {
        val (read, probed, unloaded) = compared()

        assertEquals("modules compared (the node loads no $unloaded)", emptyList<String>(), mismatches(read, probed))
        assertTrue(
            "every Elixir app's and OTP's modules are compared: ${read.size}",
            listOf("Elixir.Kernel", "Elixir.EEx", "Elixir.ExUnit", "Elixir.IEx", "Elixir.Logger", "Elixir.Mix", "lists", "ssl")
                .all { it in read },
        )
    }

    fun testEveryModuleIsReadable() {
        assertEquals(
            "unreadable",
            emptyList<String>(),
            appBeams.keys.sorted().filter { read(it) !is ModuleExports.Present },
        )
    }

    fun testALostExportIsAMismatch() {
        val (read, probed) = compared()
        val module = read.entries.first { it.value.functions.size > 1 }
        val lost = read + (module.key to module.value.copy(functions = module.value.functions.dropLast(1)))

        assertEquals(listOf(module.key), mismatches(lost, probed).map { it.substringBefore(":") })
    }

    /**
     * A source module's behaviour from its callback writes: what `behaviour_info(:callbacks)` gives once the leg's
     * Elixir compiles it, as a set, or `None` where it exports no `behaviour_info/1`; `Unreadable` where that can't be
     * read from the writes; and `Callbacks` for the typespec errors Elixir raises at the module's end, which aren't
     * ported.
     */
    fun testSourceBehaviour() {
        val compared = listOf(
            """
            @callback f(integer) :: integer
            @callback f(atom) :: atom
            @callback g(a) :: a when a: term
            @macrocallback m(term) :: Macro.t
            @optional_callbacks g: 1
            """,
            "@type t :: term\n@callback f :: t",
            "@callback f(term) :: term\n@macrocallback f(term) :: Macro.t",
            "def a, do: 1",
            "@callback f() :: term\nModule.delete_attribute(__MODULE__, :callback)",
            "@callback f() :: term\n@macrocallback m(term) :: Macro.t\nModule.delete_attribute(__MODULE__, :callback)\n@callback g() :: term",
            "@macrocallback m(term) :: Macro.t\n@callback f() :: term\nModule.delete_attribute(__MODULE__, :macrocallback)",
            // A write in the branch an `if` selects runs once, where the `if` does.
            "if true do\n  @callback f() :: term\nend",
            "@callback f() :: term\nif true do\n  Module.delete_attribute(__MODULE__, :callback)\nend",
        ).map { it.trimIndent() }

        assertEquals("compared", probedBehaviours(compared), compared.map { sourceBehaviour(it) })

        val cachedEnv = if (CACHE_ENV_IN_ELIXIR_MODULE.isSufficient(legLevel())) ":elixir_module" else ":elixir_locals"
        val unreadable = listOf(
            "def behaviour_info(:callbacks), do: []",
            "@callback f() :: term\ndef behaviour_info(:callbacks), do: []",
            "n = :f\n@callback unquote(n)(term) :: term",
            "@callback f(term)",
            "@callback 1 :: term",
            "@callback h(term) :: term\ndefp h(_x), do: 1\ndef f, do: h(1)",
            "@callback h(term) :: term\ndefp h(_x), do: 1",
        )

        assertEquals(
            "unreadable",
            unreadable.map { "$it\n  -> Unreadable" },
            unreadable.map { "$it\n  -> ${sourceBehaviour(it)}" },
        )

        // Elixir may not run a write in a branch the evaluator can't select, so the behaviour isn't read; the rest is.
        val unordered = listOf(
            "if $RECV_TRUE do\n  @callback f() :: term\nend",
            "@callback f() :: term\nif $RECV_TRUE do\n  Module.delete_attribute(__MODULE__, :callback)\nend",
        )

        assertEquals(
            "unordered",
            unordered.map {
                "$it\n  -> Present(functions=[], macros=[], hasInfo=true, behaviour=Unreadable, deprecated=Known(reasons={}))"
            },
            unordered.map { "$it\n  -> ${sourceExports(it)}" },
        )
        // A write whose kind isn't known may be of `@before_compile` too, so the module stops at its end.
        assertEquals(
            "a kind that isn't known",
            ModuleExports.Unreadable,
            sourceExports(
                """
                kind = receive do after 0 -> :callback end
                spec = quote(do: f(integer) :: integer)
                Kernel.Typespec.deftypespec(kind, spec, __ENV__.line, __ENV__.file, __MODULE__, $cachedEnv.cache_env(__ENV__))
                """.trimIndent(),
            ),
        )
        assertEquals(
            "typespec errors aren't ported",
            listOf(callbacks("f" to 0), callbacks("f" to 0)),
            listOf("@callback f() :: undefined_type()", "@optional_callbacks h: 1\n@callback f() :: term").map {
                sourceBehaviour(it)
            },
        )
    }

    /** A module whose body raises isn't loaded, whatever it defined before the raise. */
    fun testARaisedModuleIsAbsent() {
        assertEquals(ModuleExports.Absent, sourceExports("n = :f\ndef unquote(n)(), do: 1\ndef g, do: 2\n1.f()"))
    }

    /**
     * A raise computed from what is known ends the run after a stop or a decline too, whatever its exception: Elixir
     * raised there or before it, so it compiled nothing after it, and the modules it ends are absent. A module that
     * stopped before it is unreadable. With no clauses for `Exception.normalize/3` here, `1 + :a` raises an exception
     * that isn't known.
     */
    fun testARaiseFromCertainInputsAfterAStopOrADeclineLeavesEveryModuleItEndsAbsent() {
        assertEquals(
            listOf("Unreadable", "Absent"),
            loadings("defmodule A do\n  $STOPS\nend\nx = 1 + :a\ndefmodule C do\n  def f, do: 1\nend", "A", "C"),
        )
        assertEquals(
            listOf("Absent", "Absent"),
            loadings("defmodule A do\n  def target(a), do: a\n  defmodule I do\n    $DECLINES\n    1 + :a\n  end\n  def after_it, do: 1\nend", "A", "A.I"),
        )
        assertEquals(
            listOf("Absent", "Absent", "Absent"),
            loadings("defmodule O5 do\n  $STOPS\n  defmodule I do\n    @a 1 + :a\n  end\nend\ndefmodule P do\nend", "O5", "O5.I", "P"),
        )
    }

    /**
     * A raise computed from what is known ends the run after a declined call given the module, or after an unordered
     * definition or effect, as it does after any other uncertainty.
     */
    fun testARaiseFromCertainInputsAfterAGivenDeclineOrAnUnorderedUnitLeavesTheModuleAbsent() {
        val h = "defmodule H do\n  def set(m) do\n    receive(do: (x -> x), after: (0 -> :ok))\n    Module.put_attribute(m, :x, :ok)\n  end\nend\n"

        assertEquals(listOf("Present", "Absent"), loadings("${h}defmodule A do\n  H.set(__MODULE__)\n  x = 1 + :a\nend", "H", "A"))
        assertEquals(listOf("Absent"), loadings("defmodule A do\n  if false do\n    def g, do: 1\n  end\n  1 + :a\nend", "A"))
        assertEquals(listOf("Absent"), loadings("defmodule A do\n  if false do\n    @x 1\n  end\n  1 + :a\nend", "A"))
    }

    /**
     * A callback the expander doesn't follow may write the module: one `@after_compile` or `@on_definition` names
     * leaves it unreadable, while the module after it is loaded.
     */
    fun testAModuleWithAnUnfollowedCallbackIsUnreadable() {
        assertEquals(
            listOf("Present", "Unreadable", "Present"),
            loadings(
                "defmodule CB do\n  def __after_compile__(_env, _bytecode), do: :ok\nend\n" +
                    "defmodule A do\n  @after_compile CB\n  def f, do: 1\nend\ndefmodule B do\n  def g, do: 1\nend",
                "CB",
                "A",
                "B",
            ),
        )
        assertEquals(
            listOf("Present", "Unreadable", "Present"),
            loadings(
                "defmodule OD do\n  def __on_definition__(_env, _kind, _name, _args, _guards, _body), do: :ok\nend\n" +
                    "defmodule A do\n  @on_definition OD\n  def f, do: 1\nend\ndefmodule B do\n  def g, do: 1\nend",
                "OD",
                "A",
                "B",
            ),
        )
    }

    /**
     * A raise from certain inputs ends the run after a callback the expander doesn't follow, as after any other
     * uncertainty: the modules it ends are absent, while a module before it with the callback is unreadable.
     */
    fun testARaiseFromCertainInputsAfterAnUnfollowedCallbackLeavesWhatItEndsAbsent() {
        assertEquals(
            listOf("Present", "Unreadable", "Absent", "Absent"),
            loadings(
                "defmodule CB do\n  def __after_compile__(_env, _bytecode), do: :ok\nend\n" +
                    "defmodule A do\n  @after_compile CB\n  def f, do: 1\nend\ndefmodule B do\n  :ok = :bad\nend\ndefmodule C do\nend",
                "CB",
                "A",
                "B",
                "C",
            ),
        )
        assertEquals(
            listOf("Present", "Absent", "Absent"),
            loadings(
                "defmodule OD do\n  def __on_definition__(_env, _kind, _name, _args, _guards, _body), do: :ok\nend\n" +
                    "defmodule A do\n  @on_definition OD\n  def f, do: 1\n  :ok = :bad\nend\ndefmodule C do\nend",
                "OD",
                "A",
                "C",
            ),
        )
    }

    /** A decline not given the module is assumed not to raise, so the module is loaded, with what it defined. */
    fun testAModuleHoldingADeclineNotGivenItIsLoaded() {
        assertEquals(listOf("Present"), loadings("defmodule A do\n  $DECLINES\n  def f, do: 1\nend", "A"))
        assertEquals(listOf("Present"), loadings("defmodule A do\n  def f, do: 1\n  $DECLINES\nend", "A"))
        assertEquals(
            listOf("Present", "Present"),
            loadings("defmodule A do\n  def f, do: 1\nend\n$DECLINES\ndefmodule B do\n  def g, do: 1\nend", "A", "B"),
        )
    }

    /**
     * An error that reads what an unordered unit may have changed isn't Elixir's for certain, so the module may be
     * loaded: the plugin's raise came from an entry Elixir never stored, or a body Elixir never expanded.
     */
    fun testAnErrorAfterAnUnorderedUnitMayNotBeElixirs() {
        assertEquals(
            listOf("Unreadable", "Unreadable"),
            loadings("defmodule A do\n  defstruct [:x]\n  if $RECV_FALSE do\n    defmacro f, do: 1\n  end\n  def f, do: 2\nend\ndefmodule B do\n  def g, do: %A{}\nend", "A", "B"),
        )
        assertEquals(
            listOf("Unreadable", "Unreadable"),
            loadings("defmodule A do\n  defstruct [:x]\n  if $RECV_FALSE do\n    def g, do: __STACKTRACE__\n  end\nend\ndefmodule B do\n  def g, do: %A{}\nend", "A", "B"),
        )
        assertEquals(
            listOf("Unreadable", "Unreadable"),
            loadings(
                "defmodule A do\n  if $RECV_FALSE do\n    def inspect(x), do: x\n  end\n  def f(x), do: inspect(x)\nend\ndefmodule B do\n  def unquote(if function_exported?(A, :f, 1), do: :yes, else: :no)(), do: 1\nend",
                "A",
                "B",
            ),
        )
        assertEquals(
            listOf("Unreadable", "Unreadable"),
            loadings(
                "defmodule A do\n  def f, do: 1\n  defoverridable f: 0\n  if $RECV_FALSE do\n    defmacro f, do: 1\n  end\n  def g, do: 2\nend\ndefmodule B do\n  def unquote(if function_exported?(A, :f, 0), do: :yes, else: :no)(), do: 1\nend",
                "A",
                "B",
            ),
        )
    }

    /** A nested module is loaded where its `defmodule` ran for certain, as a statement of a body that ran so far. */
    fun testANestedModuleIsLoadedWhereItsDefmoduleRanForCertain() {
        assertEquals(
            listOf("Present", "Present"),
            loadings("defmodule P do\n  defmodule Q do\n    def f, do: 1\n  end\n  def g, do: 1\nend", "P", "P.Q"),
        )
        assertEquals(
            listOf("Present", "Present", "Present"),
            loadings(
                "defmodule Outer do\n  defmodule Inner do\n    defmodule Deep do\n      def f, do: 1\n    end\n  end\nend",
                "Outer",
                "Outer.Inner",
                "Outer.Inner.Deep",
            ),
        )
        assertEquals(
            listOf("Present", "Present", "Present", "Present"),
            loadings(
                "defmodule S0 do\n  defmodule P do\n    defmodule Q do\n      defstruct [:x]\n    end\n  end\nend\ndefmodule B do\n  def f, do: %S0.P.Q{}\n  $DECLINES\nend",
                "S0",
                "S0.P",
                "S0.P.Q",
                "B",
            ),
        )
    }

    /**
     * A module nested in a module whose `defmodule` may not run may not have run either, whatever its own body did, so
     * neither its raise nor its struct is Elixir's for certain.
     */
    fun testAModuleNestedInAModuleWhoseDefmoduleMayNotRunIsUnreadable() {
        assertEquals(
            listOf("Unreadable", "Unreadable"),
            loadings(
                "case $RECV_B do\n  :a ->\n    defmodule P do\n      defmodule Q do\n        def f, do: raise(ArgumentError)\n      end\n    end\n  _ -> :ok\nend\nP.Q.f()",
                "P",
                "P.Q",
            ),
        )
        assertEquals(
            listOf("Present", "Unreadable", "Unreadable", "Unreadable"),
            loadings(
                "defmodule S0 do\n  if $RECV_FALSE do\n    defmodule P do\n      defmodule Q do\n        defstruct [:a]\n      end\n    end\n  end\nend\ndefmodule B do\n  @a %S0.P.Q{}\nend",
                "S0",
                "S0.P",
                "S0.P.Q",
                "B",
            ),
        )
    }

    /**
     * A nested module after a stop in the module around it is assumed to have run, so it is loaded where its own body
     * ran to its end, while the module that stopped is unreadable. Elixir raises at [STOPS], so the three are absent
     * there: the row pins the assumption that the project compiles. The nested module's own reads of its table, the
     * struct output's `@enforce_keys` up to 1.13 among them, are answered: the stop was another module's.
     */
    fun testANestedModuleAfterAStopIsLoadedAssumingTheStopCompiles() {
        assertEquals(
            listOf("Unreadable", "Present", "Present"),
            loadings("defmodule A do\n  $STOPS\n  defmodule I do\n    defstruct [:f]\n  end\nend\ndefmodule B do\n  def g, do: %A.I{}\nend", "A", "A.I", "B"),
        )
    }

    /**
     * The walk skips every module after a raise that may be Elixir's, and Elixir compiled them if it didn't raise
     * there, so a skipped module may be loaded, wherever the module whose run ended sits in the file.
     */
    fun testAModuleSkippedAfterARaiseThatMayBeElixirsIsLoaded() {
        // The error at `def f` is computed from a definition Elixir may not have stored, so the module after it loads.
        assertEquals(
            listOf("Unreadable", "Unreadable", "Present"),
            loadings(
                "defmodule O do\n  if true do\n    defmodule A do\n      if $RECV_FALSE do\n        defmacro f, do: 1\n      end\n      def f, do: 2\n      defmodule I do\n        def g, do: 1\n      end\n    end\n  end\nend",
                "O",
                "O.A",
                "O.A.I",
            ),
        )
        assertEquals(
            listOf("Unreadable", "Present"),
            loadings(
                "if true do\n  defmodule M do\n    if $RECV_FALSE do\n      defmacro f, do: 1\n    end\n    def f, do: 2\n    defmodule N do\n      defstruct [:n]\n    end\n  end\nend",
                "M",
                "M.N",
            ),
        )
    }

    /**
     * A module whose body reads an attribute a function it calls wrote is loaded, as is the module that reads its
     * struct, and a module after the read in a clause the body selects.
     */
    fun testAModuleThatReadsWhatAFunctionItCallsWroteIsLoaded() {
        val set = "defmodule H do\n  def set(m), do: Module.put_attribute(m, :x, :ok)\nend\n"

        assertEquals(
            listOf("Present", "Present", "Present"),
            loadings(
                set + "defmodule A do\n  defstruct [:x]\n  @x :bad\n  H.set(__MODULE__)\n  :ok = @x\nend\ndefmodule B do\n  def g, do: %A{}\nend",
                "H",
                "A",
                "B",
            ),
        )
        assertEquals(
            listOf("Present", "Present", "Present"),
            loadings(
                set + "case 1 do\n  1 ->\n    defmodule M do\n      @x :bad\n      H.set(__MODULE__)\n      :ok = @x\n      defmodule N do\n        def g, do: 1\n      end\n    end\nend",
                "H",
                "M",
                "M.N",
            ),
        )
    }

    /**
     * A stop in the expansion of the body around a module, after the module's `defmodule`: the module that stopped is
     * unreadable, and the one before the stop is loaded, as is the module after them.
     */
    fun testAModuleBeforeAStopInTheBodyAroundItIsLoaded() {
        assertEquals(
            listOf("Present", "Unreadable", "Present", "Present"),
            loadings(
                "defmodule M do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> :ok))\nend\ndefmodule O do\n  defmodule I do\n    def f, do: 1\n  end\n  require M\n  M.m()\nend\n" +
                    "defmodule C do\n  def g, do: 1\nend",
                "M",
                "O",
                "O.I",
                "C",
            ),
        )
    }

    /**
     * A raise from certain inputs in a module nested before a stop in the expansion of the body around it ends the run
     * there: the module, the one around it and the one after them are absent.
     */
    fun testARaiseInAModuleBeforeAStopInTheBodyAroundItLeavesThemAbsent() {
        assertEquals(
            listOf("Present", "Absent", "Absent", "Absent"),
            loadings(
                "defmodule M do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> :ok))\nend\ndefmodule O do\n  defmodule I do\n    1 + :a\n  end\n  require M\n  M.m()\nend\n" +
                    "defmodule C do\nend",
                "M",
                "O",
                "O.I",
                "C",
            ),
        )
    }

    /**
     * A module nested after the stop in its enclosing body's expansion was never queued, while Elixir may have defined
     * it: unreadable, not absent.
     */
    fun testAModuleNestedAfterTheStopInItsEnclosingBodysExpansionIsUnreadable() {
        assertEquals(
            listOf("Present", "Unreadable", "Unreadable"),
            loadings(
                "defmodule M do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> raise ArgumentError))\nend\ndefmodule O do\n  require M\n  M.m()\n  defmodule I do\n    def f, do: 1\n  end\nend",
                "M",
                "O",
                "O.I",
            ),
        )
    }

    /**
     * Elixir expands a `@before_compile` callback's whole output before it runs any of it, so where the callback's
     * expansion stops, what its output would have defined is unknown: unreadable, not absent. With no clauses for
     * `Macro.Env` here, the callback stops at its call, where the `__ENV__` it is given can't be built.
     */
    fun testAModuleABeforeCompileCallbackWouldDefineIsUnreadableWhereTheCallbacksExpansionStops() {
        assertEquals(
            listOf("Present", "Unreadable", "Unreadable"),
            loadings(
                "defmodule CB do\n  defmacro __before_compile__(_) do\n    quote do\n      defmodule Elixir.Foo do\n        def f, do: 1\n      end\n    end\n  end\nend\n" +
                    "defmodule O do\n  @before_compile CB\nend",
                "CB",
                "O",
                "Foo",
            ),
        )
    }

    /** Deleting `__struct__/1` leaves the struct `defstruct` recorded unreadable, so a later `%U{}` isn't built from it. */
    fun testDeletingStructLeavesItsStructUnreadable() {
        if (!MODULE_DELETE_DEFINITION.isSufficient(legLevel())) return

        assertEquals(
            listOf("Present", "Unreadable"),
            loadings(
                "defmodule U do\n  defstruct [:a]\n  Module.delete_definition(__MODULE__, {:__struct__, 1})\nend\ndefmodule B do\n  def g, do: %U{a: 1}\nend",
                "U",
                "B",
            ),
        )
    }

    /** A certain raise is Elixir's: the module and every module after it are absent. */
    fun testACertainRaiseLeavesTheModuleAndEveryModuleAfterItAbsent() {
        assertEquals(
            listOf("Absent", "Absent"),
            loadings("defmodule FA do\n  1.f()\nend\ndefmodule x do\nend\ndefmodule FC do\nend", "FA", "FC"),
        )
    }

    /**
     * What [LoadedModules] gives each of [modules] in the file [code] expands to, by class name: `Present`,
     * `Unreadable` or `Absent`, a module the file didn't compile as [LoadedModules.uncompiledExports], `Absent` where the
     * file leaves it no module, as the leg's `.beam`s define none of these names.
     */
    private fun loadings(code: String, vararg modules: String): List<String> {
        val level = legLevel()
        val forms = ReadAction.computeBlocking<_, Throwable> {
            Lowering.lower(createPsiFile(getTestName(false), code) as ElixirFile, level)
        }
        val file = Expander.expandFile(forms, Env.empty(level, legKernel), level, legExports, legStructs)
        val results = generateSequence(file.modules) { layer -> layer.flatMap { it.nested }.takeIf { it.isNotEmpty() } }
            .flatten()
            .associateBy { it.module.removePrefix("Elixir.") }

        return modules.map { module ->
            results[module]?.let { LoadedModules.exports(it)::class.simpleName!! }
                ?: LoadedModules.uncompiledExports(file)?.let { it::class.simpleName!! }
                ?: "Absent"
        }
    }

    /** The behaviour [LoadedModules] gives the module whose body is [body], with its callbacks sorted. */
    private fun sourceBehaviour(body: String): ModuleExports.Behaviour? =
        (sourceExports(body) as? ModuleExports.Present)?.behaviour?.let {
            if (it is ModuleExports.Behaviour.Callbacks) it.copy(callbacks = it.callbacks.sorted()) else it
        }

    /** What [LoadedModules] gives the module whose body is [body], expanded at the leg's level. */
    private fun sourceExports(body: String): ModuleExports {
        val level = legLevel()
        val code = "defmodule SourceBehaviour do\n${body.prependIndent("  ")}\nend"
        val forms = ReadAction.computeBlocking<_, Throwable> {
            Lowering.lower(createPsiFile(getTestName(false), code) as ElixirFile, level)
        }
        val file = Expander.expandFile(forms, Env.empty(level, legKernel), level, legExports, legStructs)

        return LoadedModules.exports(file.modules.single())
    }

    /** What the leg's Elixir gives each module whose body is one of [bodies]: as [sourceBehaviour] answers it. */
    private fun probedBehaviours(bodies: List<String>): List<ModuleExports.Behaviour> {
        val token = harness.token()
        val modules = bodies.indices.map { "SourceBehaviour$token.Case$it" }
        val compiled = harness.compileSource(
            bodies.mapIndexed { i, body -> "defmodule ${modules[i]} do\n${body.prependIndent("  ")}\nend\n" }
                .joinToString("\n") +
                """

                for m <- [${modules.joinToString(", ")}] do
                  answer = if function_exported?(m, :behaviour_info, 1), do: m.behaviour_info(:callbacks), else: :none
                  IntellijElixir.Quoter.Probe.send(__ENV__, answer)
                  :code.purge(m)
                  :code.delete(m)
                end
                """.trimIndent(),
        )

        assertEquals("probe compile status", OtpErlangAtom("ok"), compiled.status)

        return compiled.messages.map {
            if (it is OtpErlangAtom) {
                ModuleExports.Behaviour.None
            } else {
                ModuleExports.Behaviour.Callbacks(nameArities(it))
            }
        }
    }

    private fun callbacks(vararg callbacks: Pair<String, Int>) =
        ModuleExports.Behaviour.Callbacks(callbacks.map { (name, arity) -> NameArity(name, arity) })

    /** What Elixir gives a loaded module: with [hasInfo], `__info__`'s answers; without, `module_info(:exports)`. */
    private data class Loaded(val functions: List<NameArity>, val macros: List<NameArity>, val hasInfo: Boolean)

    private data class Compared(val read: Map<String, Loaded>, val probed: Map<String, Loaded>, val unloaded: Set<String>)

    private fun compared(): Compared = cachedCompared ?: compare().also { cachedCompared = it }

    private fun compare(): Compared {
        val read = appBeams.keys.sorted().mapNotNull { module ->
            (read(module) as? ModuleExports.Present)?.let {
                module to Loaded(it.functions.sorted(), it.macros.sorted(), it.hasInfo)
            }
        }.toMap()
        val (probed, unloaded) = probed(read.keys.toList())

        return Compared(read.filterKeys { it !in unloaded }, probed, unloaded)
    }

    /** What the node gives for each of [modules], and the modules it can't load. */
    private fun probed(modules: List<String>): Pair<Map<String, Loaded>, Set<String>> {
        val answers = harness.probeLoaded(
            modules,
            "if function_exported?(m, :__info__, 1), do: {m.__info__(:functions), m.__info__(:macros)}, " +
                "else: m.module_info(:exports)",
        )
        val probed = answers.mapNotNull { (module, answer) ->
            when (answer) {
                null -> null
                is OtpErlangTuple ->
                    module to Loaded(nameArities(answer.elementAt(0)), nameArities(answer.elementAt(1)), hasInfo = true)
                else -> module to Loaded(nameArities(answer), emptyList(), hasInfo = false)
            }
        }.toMap()

        return probed to answers.filterValues { it == null }.keys
    }

    private fun nameArities(list: OtpErlangObject): List<NameArity> =
        (list as OtpErlangList).elements().map {
            val (name, arity) = (it as OtpErlangTuple).elements()

            NameArity((name as OtpErlangAtom).atomValue(), (arity as OtpErlangLong).intValue())
        }.sorted()

    /** The modules whose [read] exports differ from the [probed] ones. */
    private fun mismatches(read: Map<String, Loaded>, probed: Map<String, Loaded>): List<String> =
        (read.keys + probed.keys).sorted().mapNotNull { module ->
            val ours = read[module]
            val theirs = probed[module]

            when {
                ours == theirs -> null
                ours == null -> "$module: only Elixir loads it"
                theirs == null -> "$module: only the leg read it"
                ours.hasInfo != theirs.hasInfo -> "$module: hasInfo ${ours.hasInfo}, Elixir ${theirs.hasInfo}"
                else -> "$module: functions lost ${theirs.functions - ours.functions.toSet()}, " +
                    "extra ${ours.functions - theirs.functions.toSet()}; " +
                    "macros lost ${theirs.macros - ours.macros.toSet()}, extra ${ours.macros - theirs.macros.toSet()}"
            }
        }

    private fun read(module: String): ModuleExports = appBeams.getValue(module).let { LoadedModules.exports(it.readBytes(), it.path) }

    private fun List<NameArity>.sorted() = sortedWith(compareBy({ it.name }, { it.arity }))

    private companion object {
        /** A subject the evaluator can't produce, so the branch it selects isn't known. */
        private const val RECV_FALSE = "(receive do after 0 -> false end)"
        private const val RECV_TRUE = "(receive do after 0 -> true end)"
        private const val RECV_B = "(receive do after 0 -> :b end)"

        /** The leg's modules read and probed; every test in the class shares it. */
        @Volatile
        var cachedCompared: Compared? = null

        /** A statement the evaluator declines. */
        const val DECLINES = "receive do after 0 -> :ok end"

        /** A write Elixir may refuse, which stops the module. */
        const val STOPS = "Module.put_attribute(String, :a, 1)"
    }
}
