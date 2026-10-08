package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFGUARD_UNQUOTE_NAMES
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFSTRUCT_ESCAPES_STRUCT
import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_OF_MODULE_BEING_DEFINED_NEVER_LOADED
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Import.Term

/**
 * The macros that define definitions, `defstruct`, `defexception`, `defguard`, `defoverridable` and `super`, compiled
 * in module bodies on the leg's Elixir: at each probe the env, the variables and the hygiene counters taken, each
 * error they raise, and the dispatch events where the module compiles. A module that stops at a macro the expander
 * doesn't model, a `@derive`'s `__deriving__` or a guard's template, equals Elixir up to that macro.
 */
class DefinitionMacroProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, accounting = true) {
        createPsiFile(getTestName(false), it) as ElixirFile
    }

    /** Each case ends as the compiler ends it, whether it compiles or raises, and ports every macro it calls. */
    fun testEachCaseMatchesElixir() {
        val bodies = listOf(compiling(), raising(), either()).flatten()
        val expansions = bodies.associateWith { probes.expand(it) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome is Expansion.Unported || it.outcome is Expansion.Opaque }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    /**
     * The dispatches of each case that compiles, as the compiler traces them, `elixir_bootstrap`'s `def/2` and `@/1`
     * of `defstruct` and `defexception` among them.
     */
    fun testEachCompilingCaseTracesAsTheCompilerDoes() {
        printed { probes.assertTracesMatchElixir(probes.expandAll(compiling())) }
    }

    /** A non-empty `@derive` stops at the `__deriving__` macro Elixir expands when the struct is defined. */
    fun testADeriveStopsAtItsMacro() {
        val bodies = DERIVES
        val expansions = bodies.associateWith { probes.expand(it) }

        assertEquals(
            bodies.joinToString("\n") { "${it.replace("\n", "; ")}: opaque" },
            expansions.map { (body, expansion) ->
                "${body.replace("\n", "; ")}: ${if (expansion.outcome is Expansion.Opaque) "opaque" else expansion.outcome}"
            }.joinToString("\n"),
        )
        printed { probes.assertMatchesElixirUpToMacro(expansions) }
    }

    /**
     * A guard's macro is stored, its guard expanded, and the module stops at the template that builds its body. The
     * dispatches of the guard follow the macro's in Elixir's trace, and the expander's equal them.
     */
    fun testAGuardStopsAtItsTemplate() {
        val bodies = guards()
        val expansions = bodies.associateWith { probes.expand(it) }

        assertEquals(
            "a guard that dispatches is compared by them",
            bodies.map { it != "defguard always when true" },
            bodies.map { expansions.getValue(it).guarded.isNotEmpty() },
        )

        assertEquals(
            bodies.joinToString("\n") { "${it.replace("\n", "; ")}: opaque" },
            expansions.map { (body, expansion) ->
                "${body.replace("\n", "; ")}: ${if (expansion.outcome is Expansion.Opaque) "opaque" else expansion.outcome}"
            }.joinToString("\n"),
        )
        printed { probes.assertMatchesElixirUpToMacro(expansions) }
    }

    /**
     * `@doc guard: true` is no hook's to see, so the Docs chunk of the compiled module says which of its macros are
     * guards, and the expander says the same of the definitions it stored: those a `@doc guard: true` it wrote lies
     * over.
     */
    fun testAGuardIsDocumentedAsOneInTheDocsChunk() {
        val expansions = probes.expandAll(listOf(DOCUMENTED), hook = ProbeHarness.Hook())
        val expansion = expansions.cases.single()
        val attempt = harness.attempt(expansions.layout)

        assertEquals(
            "compile status ${attempt.compiled.diagnostics.map(::inspect)}",
            OtpErlangAtom("ok"),
            attempt.compiled.status,
        )

        val docs = attempt.batch.docs.getValue(expansions.layout.caseModule(0))

        assertTrue("docs enabled", docs.enabled)

        val guarded = expansion.result.attributes.effects.filter { isGuardDoc(it.effect) }.map { it.at.meta.origin }

        assertEquals(
            docs.entries!!.map { "${it.kind} ${it.name}/${it.arity} guard ${it.guard}" }.sorted().joinToString("\n"),
            expansion.result.table.entries
                .filter { (_, entry) -> entry.kind.public && !entry.default }
                .map { (nameArity, entry) ->
                    val kind = if (entry.kind == Kind.DEFMACRO) "macro" else "function"

                    "$kind ${nameArity.name}/${nameArity.arity} guard ${guarded.any { it.contains(entry.at.meta.origin) }}"
                }
                .sorted()
                .joinToString("\n"),
        )
        assertTrue("a guard is among them", docs.entries.any { it.guard })
        assertLeftNothingBehind(expansions.layout)
    }


    /** Whether [effect] writes `@doc guard: true`, a doc's value being its line and what was written. */
    private fun isGuardDoc(effect: Effect): Boolean {
        val write = effect as? Effect.Write ?: return false
        val written = (write.value as? Term.Pair)?.second ?: write.value

        return write.name == "doc" &&
            (written as? Term.List)?.elements?.contains(Term.Pair(Term.Atom("guard"), Term.Atom("true"))) == true
    }

    /** Each compiles on every leg, an external capture where the struct is escaped in the output. */
    private fun compiling(): List<String> =
        COMPILING + if (DEFSTRUCT_ESCAPES_STRUCT.isSufficient(legLevel())) listOf(EXTERNAL_CAPTURE) else emptyList()

    /** Each raises on every leg, or on every leg that has the check. */
    private fun raising(): List<String> {
        val level = legLevel()

        // Before that a loaded module of the same name answers, which the expander doesn't follow: StructRecordTest
        // asserts each `Unported` there.
        val inaccessible = if (STRUCT_OF_MODULE_BEING_DEFINED_NEVER_LOADED.isSufficient(level)) {
            listOf("defstruct [:a]\n_ = %__MODULE__{}", "def f, do: %__MODULE__{}\ndefstruct [:a]")
        } else {
            emptyList()
        }

        val unquoted = if (DEFGUARD_UNQUOTE_NAMES.isSufficient(level)) emptyList() else listOf(UNQUOTED_GUARD)

        return listOf(RAISING, inaccessible, unquoted).flatten()
    }

    /** Each compiles on some legs and raises on others. */
    private fun either(): List<String> = EITHER

    private fun guards(): List<String> =
        GUARDS + if (DEFGUARD_UNQUOTE_NAMES.isSufficient(legLevel())) listOf(UNQUOTED_GUARD) else emptyList()

    private companion object {
        const val UNQUOTED_GUARD = "defguard unquote(:is_one)(x) when x == 1"

        val COMPILING = listOf(
            // defstruct
            "defstruct [:a, :b]",
            "defstruct a: 1, b: [1, 2], c: nil",
            "@enforce_keys [:a]\ndefstruct [:a, :b]",
            "@enforce_keys :a\ndefstruct [:a]",
            "@enforce_keys []\ndefstruct [:a]",
            "a = 1\ndefstruct [:a]\nb = a",
            // defaults that name modules
            "alias Foo.Bar\ndefstruct a: Bar, b: [Bar, __MODULE__], c: {Bar, 1}, d: %{}, e: %{x: 1}, f: 1.5",
            // what `defstruct` reads of `@enforce_keys` and `@derive`
            "Module.register_attribute(__MODULE__, :enforce_keys, accumulate: true)\n@enforce_keys :a\ndefstruct [:a]",
            "@derive []\ndefstruct [:a]",
            // the struct, read where the module has defined it, and where it has not
            "defstruct [:a]\ndef f, do: %__MODULE__{a: 1}",
            "defstruct [:a]\ndef f(s), do: %__MODULE__{s | a: 1}",
            "defstruct [:a]\ndef f(%__MODULE__{a: a}), do: a",
            "alias __MODULE__, as: Outer\ndefstruct [:a]\ndefmodule Inner do\n  def f, do: %Outer{a: 1}\nend",
            "alias __MODULE__, as: Outer\ndefstruct [:a]\ndefmodule Inner do\n  _ = %Outer{a: 1}\nend",
            // defexception
            "defexception [:message]",
            "defexception [:reason]",
            "defexception message: \"x\"",
            "defexception [:message]\ndef message(e), do: e.message",
            "defexception [:reason]\ndef exception(args), do: struct!(__MODULE__, args)",
            "@enforce_keys [:reason]\ndefexception [:reason]",
            // defoverridable and super
            "def f(x), do: x\ndefoverridable f: 1\ndef g, do: 1",
            "def f(x), do: x\ndefoverridable f: 1\ndef f(x), do: x + 1",
            "def f(x), do: x\ndef g, do: 1\ndefoverridable f: 1, g: 0\ndef h, do: 2",
            "def f(x), do: x\ndefoverridable f: 1\ndef f(x), do: super(x)",
            "defmacro m(x), do: x\ndefoverridable m: 1\ndefmacro m(x), do: super(x)",
            "def f(x), do: x\ndefoverridable f: 1\ndef f(x), do: super(x) + super(x)",
            "def f(x), do: x\ndefoverridable f: 1\ndef f(x), do: super(x)\ndefoverridable f: 1\ndef f(x), do: super(x)",
            "def f(x), do: x\ndefoverridable f: 1\ndef f(x), do: (&super/1).(x)",
            "def f(x), do: x\ndefoverridable f: 1\ndef f(x), do: (&super(&1)).(x)",
            "@behaviour Access\ndef fetch(_, _), do: :error\ndef get_and_update(_, _, _), do: :error\n" +
                "def pop(_, _), do: :error\ndefoverridable Access",
            "@behaviour Access\ndef fetch(_, _), do: :error\ndef get_and_update(_, _, _), do: :error\n" +
                "def pop(_, _), do: :error\ndefoverridable Access\ndef fetch(a, b), do: super(a, b)",
            "def f, do: 1\ndefoverridable f: 0\ndef f, do: super()\ndef g, do: 2",
            // a replaced body's local calls go with it
            "def f, do: missing()\ndefoverridable f: 0\ndef f, do: :ok",
        )

        /** A default that escapes as its external fun, which the compiler expands where a definition reads it. */
        const val EXTERNAL_CAPTURE = "defstruct f: &String.upcase/1"

        val RAISING = listOf(
            "defstruct [:a]\ndefstruct [:b]",
            "defstruct :a",
            "defstruct [\"a\"]",
            "defstruct a: fn -> 1 end",
            "@enforce_keys [\"a\"]\ndefstruct [:a]",
            "@enforce_keys [:a]\ndefstruct [:a, :b]\ndef f, do: %__MODULE__{b: 1}",
            "defstruct [:a]\ndef f, do: %__MODULE__{b: 1}",
            "@derive [Enum]\ndefstruct [:a]",
            "@derive [Enumerable]\ndefstruct [:a]",
            "@derive [Missing]\ndefstruct [:a]",
            "@derive [Enum, Inspect]\ndefstruct [:a]",
            "defexception [\"a\"]",
            "defguard is_one(x) when x == 1 when x == 2",
            "defguard 1 when true",
            "defguard is_one(1) when true",
            "defguard is_one(x) when System.halt() == x",
            "def f(x) when defguard(is_one(y) when y == 1), do: x",
            "def f(defguard(is_one(y) when y == 1)), do: 1",
            "defoverridable f: 1",
            "defoverridable [:f]",
            "defoverridable f: 256",
            "def f(x), do: x\ndefoverridable [{:f, 1}, :g]",
            "def f(x), do: x\ndefoverridable f: 1\ndefmacro f(x), do: x\ndefoverridable f: 1",
            "def f(x), do: x\ndefoverridable f: 1\ndefmacro f(x), do: x",
            "def f(x), do: super(x)",
            "def f(x), do: x\ndefoverridable f: 1\ndef f(x), do: super()",
            "def f(x), do: x\ndefoverridable f: 1\ndef f(x), do: &super/2",
            "defoverridable Access",
            "defoverridable NoSuchBehaviour",
            "defoverridable String",
            // a body's local calls stay with it where it is kept
            "def f, do: missing()\ndefoverridable f: 0",
            "def f, do: missing()\ndefoverridable f: 0\ndef f, do: super()",
            "def f, do: a()\ndefoverridable f: 0\ndef f, do: super() + b()",
            "def f, do: a()\ndefoverridable f: 0\ndef f, do: b() + super()",
            "def f, do: a()\ndefoverridable f: 0\ndef f, do: super() + b()\ndefoverridable f: 0\ndef f, do: super() + c()",
            "def f(a \\\\ missing()), do: a\ndefoverridable f: 1\ndef f(a), do: a",
        )

        val EITHER = listOf(
            // a hidden body's calls stay where the checks visit it, from 1.18
            "def f, do: missing()\ndefoverridable f: 0\ndef f, do: super()\ndefoverridable f: 0\ndef f, do: :ok",
            "def f, do: a()\ndefoverridable f: 0\ndef f, do: super() + b()\ndefoverridable f: 0\ndef f, do: :ok",
            "def f, do: a()\ndefoverridable f: 0\ndef f, do: b() + super()\ndefoverridable f: 0\ndef f, do: :ok",
            "defstruct [:__struct__]",
            "@enforce_keys [:b]\ndefstruct [:a]",
            "Module.register_attribute(__MODULE__, :enforce_keys, [])\ndefstruct [:a]",
            "Module.register_attribute(__MODULE__, :enforce_keys, accumulate: true)\n@enforce_keys :b\ndefstruct [:a]",
            "defguard is_three(x)",
        )

        /** Each stops at the `__deriving__` macro of `Inspect`, which the `defstruct` last in the body runs. */
        val DERIVES = listOf(
            "@derive [Inspect]\ndefstruct [:a]",
            "@derive Inspect\ndefstruct [:a]",
            "@derive [{Inspect, only: [:a]}]\ndefstruct [:a, :b]",
            "@derive [Inspect]\n@enforce_keys [:a]\ndefstruct [:a]",
            "a = 1\n@derive Inspect\ndefstruct [:a]",
            "def before, do: 1\n@derive Inspect\ndefstruct [:a]",
            "@derive [Enum]\n@derive [Inspect]\ndefstruct [:a]",
            "@derive Inspect\ndefexception [:message]",
        )

        /** Each stops at the template of the macro it defines, whose guard it has expanded. */
        val GUARDS = listOf(
            "defguard is_even(x) when is_integer(x) and rem(x, 2) == 0",
            "defguard always when true",
            "defguard is_one(x \\\\ 1) when x == 1",
            "defguard is_twice(x) when x + x == 2",
            "defguardp is_p(x) when is_integer(x)",
            "def a, do: 1\ndefguard is_one(x) when x == 1\ndef b, do: 2",
        )

        const val DOCUMENTED =
            "defguard is_one(x) when x == 1\ndefguardp is_two(x) when x == 2\n" +
                "@doc \"documented\"\ndefmacro plain(x), do: x\ndef f, do: 1"
    }
}
