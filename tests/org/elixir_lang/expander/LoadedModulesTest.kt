package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.CACHE_ENV_IN_ELIXIR_MODULE
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
        ).map { it.trimIndent() }

        assertEquals("compared", probedBehaviours(compared), compared.map { sourceBehaviour(it) })

        val cachedEnv = if (CACHE_ENV_IN_ELIXIR_MODULE.isSufficient(legLevel())) ":elixir_module" else ":elixir_locals"
        val unreadable = listOf(
            "def behaviour_info(:callbacks), do: []",
            "@callback f() :: term\ndef behaviour_info(:callbacks), do: []",
            "n = :f\n@callback unquote(n)(term) :: term",
            "@callback f(term)",
            "@callback 1 :: term",
            "if true do\n  @callback f() :: term\nend",
            "@callback h(term) :: term\ndefp h(_x), do: 1\ndef f, do: h(1)",
            "@callback h(term) :: term\ndefp h(_x), do: 1",
            "@callback f() :: term\nif true do\n  Module.delete_attribute(__MODULE__, :callback)\nend",
        )

        assertEquals(
            "unreadable",
            unreadable.map { "$it\n  -> Unreadable" },
            unreadable.map { "$it\n  -> ${sourceBehaviour(it)}" },
        )
        // A write whose kind isn't known may be of `@before_compile` too, so the module stops at its end.
        assertEquals(
            "a kind that isn't known",
            ModuleExports.Unreadable,
            sourceExports(
                """
                kind = :callback
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
        /** The leg's modules read and probed; every test in the class shares it. */
        @Volatile
        var cachedCompared: Compared? = null
    }
}
