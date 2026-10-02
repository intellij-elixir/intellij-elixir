package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.expander.DefinitionTable.Kind.DEF
import org.elixir_lang.expander.DefinitionTable.Kind.DEFMACRO
import org.elixir_lang.expander.DefinitionTable.Kind.DEFP
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Term
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * [postModuleChecks] over modules built by hand, each the shape of a module compiled in plain Elixir on every supported
 * minor, with what each reported in its order. Before 1.15 only the first is reported, as it raises.
 */
class PostModuleChecksTest {
    @Test
    fun `every check at once`() =
        assertChecks(
            module {
                write("on_load", atom("init"))
                write("dialyzer", pair(atom("nowarn_function"), keywords("d" to 0)))
                write("compile", pair(atom("inline"), keywords("i" to 0)))
                write("nifs", keywords("n" to 0))
                head("h", 1)
                def("f", arity = 1)
                def("first", arity = 1)
                def("g", call("nope", 10, 14))
                import("first", 1)
            }
        ) { version ->
            when {
                isBefore(version, "1.15.0-rc.0") -> listOf(head("h/1"))
                isBefore(version, "1.16.0-rc.0") ->
                    listOf(head("h/1"), undefinedIn("init/0"), undefinedIn("d/0"), undefined("nope", "g/0"),
                        conflict("first/1"), undefinedIn("i/0"))
                isBefore(version, "1.18.0-rc.0") ->
                    listOf(head("h/1"), undefinedIn("init/0"), undefinedIn("d/0"), undefinedIn("n/0"),
                        undefined("nope", "g/0"), conflict("first/1"), undefinedIn("i/0"))
                else ->
                    listOf(
                        head("h/1"), undefinedIn("init/0"), undefinedIn("d/0"), undefinedIn("n/0"), conflict("first/1"),
                    )
            }
        }

    // H, `function_head`

    @Test
    fun `bodiless heads are reported ascending`() =
        assertChecks(
            module {
                head("b", 1)
                head("a", 1)
            }
        ) { listOf(head("a/1"), head("b/1")) }

    @Test
    fun `a bodiless head, then an undefined local`() =
        assertChecks(
            module {
                head("f", 1)
                def("g", call("nope", 3, 14))
            }
        ) { version ->
            if (isBefore(version, "1.18.0-rc.0")) listOf(head("f/1"), undefined("nope", "g/0")) else listOf(head("f/1"))
        }

    /** The callee lookup reads the whole table, a bodiless head's entry included. */
    @Test
    fun `a call to a bodiless head is no undefined local`() =
        assertChecks(
            module {
                head("f", 1)
                def("g", call("f", 3, 14, arity = 1))
            }
        ) { listOf(head("f/1")) }

    /** Before 1.12.0-rc.1 a head stored last without checking its clauses isn't reported. */
    @Test
    fun `a generated bodiless head`() =
        assertChecks(
            module { head("f", 1, checksClauses = false) },
            LEVELS + "1.12.0-rc.0" + "1.12.0-rc.1",
        ) { version -> if (isBefore(version, "1.12.0-rc.1")) emptyList() else listOf(head("f/1")) }

    /** Before 1.12.0-rc.1 `Module`'s own bodiless heads aren't reported. */
    @Test
    fun `a bodiless head in Module`() =
        assertChecks(
            module {
                name = "Elixir.Module"
                head("f", 1)
            },
            LEVELS + "1.12.0-rc.0" + "1.12.0-rc.1",
        ) { version -> if (isBefore(version, "1.12.0-rc.1")) emptyList() else listOf(head("f/1")) }

    // O, `@on_load`

    @Test
    fun `an undefined on_load function, then an undefined local`() =
        assertChecks(
            module {
                write("on_load", atom("init"))
                def("g", call("nope", 3, 14))
            }
        ) { version ->
            when {
                isBefore(version, "1.12.0-rc.0") -> listOf(undefined("nope", "g/0"))
                isBefore(version, "1.15.0-rc.0") -> listOf(undefinedIn("init/0"))
                isBefore(version, "1.18.0-rc.0") -> listOf(undefinedIn("init/0"), undefined("nope", "g/0"))
                else -> listOf(undefinedIn("init/0"))
            }
        }

    @Test
    fun `an undefined on_load function, then an undefined inline function`() =
        assertChecks(
            module {
                write("on_load", atom("init"))
                write("compile", pair(atom("inline"), keywords("i" to 0)))
                def("g")
            }
        ) { version ->
            when {
                isBefore(version, "1.12.0-rc.0") -> listOf(undefinedIn("i/0"))
                isBefore(version, "1.15.0-rc.0") || !isBefore(version, "1.18.0-rc.0") -> listOf(undefinedIn("init/0"))
                else -> listOf(undefinedIn("init/0"), undefinedIn("i/0"))
            }
        }

    @Test
    fun `the on_load check comes before the dialyzer check whatever the order of their writes`() =
        assertChecks(
            module {
                write("dialyzer", pair(atom("nowarn_function"), keywords("d" to 0)))
                write("on_load", atom("init"))
                def("g")
            }
        ) { version ->
            when {
                isBefore(version, "1.15.0-rc.0") -> listOf(undefinedIn("init/0"))
                else -> listOf(undefinedIn("init/0"), undefinedIn("d/0"))
            }
        }

    @Test
    fun `a private on_load function`() =
        assertChecks(
            module {
                write("on_load", atom("init"))
                def("init", kind = DEFP)
            },
            LEVELS + "1.12.0-rc.0",
        ) { version -> if (isBefore(version, "1.12.0-rc.0")) listOf(wrongKind("init/0")) else emptyList() }

    @Test
    fun `an on_load macro`() =
        assertChecks(
            module {
                write("on_load", atom("init"))
                def("init", kind = DEFMACRO)
            }
        ) { listOf(wrongKind("init/0")) }

    /** `fetch_definitions` drops a bodiless head, so the function `@on_load` names is missing. */
    @Test
    fun `a bodiless on_load function`() =
        assertChecks(
            module {
                write("on_load", atom("init"))
                head("init")
            }
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                listOf(head("init/0"))
            } else {
                listOf(head("init/0"), undefinedIn("init/0"))
            }
        }

    /** From 1.18 a private `@on_load` function is visited with the private macros dispatched as local macros. */
    @Test
    fun `a private on_load function is visited first`() =
        assertChecks(
            module {
                write("on_load", atom("init"))
                def("init", call("a", 3, 22), kind = DEFP)
                def("z", call("b", 4, 19), kind = DEFP)
            },
            CONTINUING,
        ) { listOf(undefined("a", "init/0"), undefined("b", "z/0")) }

    // D, `@dialyzer`

    @Test
    fun `only the first dialyzer write is checked`() =
        assertChecks(
            module {
                write("dialyzer", pair(atom("nowarn_function"), keywords("a" to 0)))
                write("dialyzer", pair(atom("nowarn_function"), keywords("c" to 0, "b" to 0)))
            },
            LEVELS + "1.12.0-rc.0",
        ) { version -> if (isBefore(version, "1.12.0-rc.0")) emptyList() else listOf(undefinedIn("a/0")) }

    @Test
    fun `a second dialyzer write naming a missing function`() =
        assertChecks(
            module {
                write("dialyzer", pair(atom("nowarn_function"), keywords("f" to 0)))
                write("dialyzer", pair(atom("nowarn_function"), keywords("nope" to 0)))
                def("f")
            }
        ) { emptyList() }

    @Test
    fun `a dialyzer write of several options`() =
        assertChecks(
            module {
                write(
                    "dialyzer",
                    keywords("nowarn_function" to keywords("b" to 0), "no_return" to keywords("a" to 0)),
                )
            }
        ) { version ->
            when {
                isBefore(version, "1.12.0-rc.0") -> emptyList()
                isBefore(version, "1.15.0-rc.0") -> listOf(undefinedIn("b/0"))
                else -> listOf(undefinedIn("b/0"), undefinedIn("a/0"))
            }
        }

    @Test
    fun `a dialyzer macro`() =
        assertChecks(
            module {
                write("dialyzer", pair(atom("nowarn_function"), keywords("m" to 0)))
                def("m", kind = DEFMACRO)
            },
            LEVELS + "1.15.0-rc.0" + "1.15.0-rc.1",
        ) { version -> if (isBefore(version, "1.15.0-rc.1")) emptyList() else listOf(wrongKind("m/0")) }

    // N, `@nifs`

    @Test
    fun `each missing nif in list order`() =
        assertChecks(
            module { write("nifs", keywords("c" to 0, "b" to 0)) },
            LEVELS + "1.16.0-rc.0",
        ) { version ->
            if (isBefore(version, "1.16.0-rc.0")) emptyList() else listOf(undefinedIn("c/0"), undefinedIn("b/0"))
        }

    /** Until 1.19 a second write replaces the first; from it they accumulate, and only the first is checked. */
    @Test
    fun `a second nifs write naming a missing function`() =
        assertChecks(
            module {
                write("nifs", keywords("f" to 0))
                write("nifs", keywords("nope" to 0))
                def("f")
            },
            LEVELS + "1.19.0-rc.0",
        ) { version ->
            if (isBefore(version, "1.16.0-rc.0") || !isBefore(version, "1.19.0-rc.0")) emptyList()
            else listOf(undefinedIn("nope/0"))
        }

    @Test
    fun `a nifs macro`() =
        assertChecks(
            module {
                write("nifs", keywords("m" to 0))
                def("m", kind = DEFMACRO)
            }
        ) { version -> if (isBefore(version, "1.16.0-rc.0")) emptyList() else listOf(wrongKind("m/0")) }

    @Test
    fun `a bodiless nif`() =
        assertChecks(
            module {
                write("nifs", keywords("n" to 0))
                head("n")
            }
        ) { version ->
            if (isBefore(version, "1.16.0-rc.0")) listOf(head("n/0")) else listOf(head("n/0"), undefinedIn("n/0"))
        }

    // I, the import conflict

    @Test
    fun `import conflicts are reported descending`() =
        assertChecks(
            module {
                def("f", arity = 1)
                def("first", arity = 1)
                def("last", arity = 1)
                import("first", 1)
                import("last", 1)
            }
        ) { listOf(conflict("last/1"), conflict("first/1")) }

    @Test
    fun `an import conflict, then an undefined local`() =
        assertChecks(
            module {
                def("f", arity = 1)
                def("first", arity = 1)
                def("g", call("nope", 5, 14))
                import("first", 1)
            }
        ) { version ->
            when {
                isBefore(version, "1.15.0-rc.0") -> listOf(undefined("nope", "g/0"))
                isBefore(version, "1.18.0-rc.0") -> listOf(undefined("nope", "g/0"), conflict("first/1"))
                else -> listOf(conflict("first/1"))
            }
        }

    @Test
    fun `a default arity conflicts with an import`() =
        assertChecks(
            module {
                def("f", arity = 1)
                def("first", arity = 2)
                def("first", arity = 1)
                import("first", 1)
            }
        ) { listOf(conflict("first/1")) }

    @Test
    fun `a bodiless head conflicts with no import`() =
        assertChecks(
            module {
                def("f", arity = 1)
                head("first", 1)
                import("first", 1)
            }
        ) { listOf(head("first/1")) }

    @Test
    fun `Kernel's definitions conflict with no import`() =
        assertChecks(
            module {
                name = "Elixir.Kernel"
                def("first", arity = 1)
                import("first", 1)
            }
        ) { emptyList() }

    // C, `@compile :inline`

    @Test
    fun `one inline error per write, in write order`() =
        assertChecks(
            module {
                write("compile", pair(atom("inline"), keywords("a" to 0)))
                write("compile", pair(atom("inline"), keywords("c" to 0, "b" to 0)))
            }
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                listOf(undefinedIn("a/0"))
            } else {
                listOf(undefinedIn("a/0"), undefinedIn("c/0"))
            }
        }

    @Test
    fun `one inline error per element of a list write`() =
        assertChecks(
            module {
                write(
                    "compile",
                    Term.List(
                        listOf(pair(atom("inline"), keywords("b" to 0)), pair(atom("inline"), keywords("a" to 0))),
                    ),
                )
            }
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) {
                listOf(undefinedIn("b/0"))
            } else {
                listOf(undefinedIn("b/0"), undefinedIn("a/0"))
            }
        }

    @Test
    fun `an inline macro`() =
        assertChecks(
            module {
                write("compile", pair(atom("inline"), keywords("m" to 0)))
                def("m", kind = DEFMACRO)
            },
            LEVELS + "1.16.0-rc.0",
        ) { version -> if (isBefore(version, "1.16.0-rc.0")) emptyList() else listOf(wrongKind("m/0")) }

    @Test
    fun `an undefined local, then an undefined inline function`() =
        assertChecks(
            module {
                write("compile", pair(atom("inline"), keywords("nope" to 0)))
                def("g", call("nope2", 3, 14))
            }
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) listOf(undefined("nope2", "g/0"))
            else listOf(undefined("nope2", "g/0"), undefinedIn("nope/0"))
        }

    // T, the taint stop

    @Test
    fun `a module that reported an error before the checks`() =
        assertChecks(
            module {
                write("compile", pair(atom("inline"), keywords("nope" to 0)))
                head("h")
                def("g", call("nope2", 3, 14))
                tainted = true
            },
            CONTINUING,
        ) { version ->
            if (isBefore(version, "1.18.0-rc.0")) listOf(head("h/0"), undefined("nope2", "g/0"), undefinedIn("nope/0"))
            else listOf(head("h/0"))
        }

    @Test
    fun `a macro called before its definition in a module that reported an error`() =
        assertChecks(
            module {
                def("f", call("m", 2, 14))
                def("g")
                def("m", kind = DEFMACRO)
                tainted = true
            },
            CONTINUING,
        ) { version -> if (isBefore(version, "1.18.0-rc.0")) listOf(dispatch("m", "f/0")) else emptyList() }

    // Values the expander doesn't know

    @Test
    fun `an unknown on_load value stops the checks there`() =
        assertChecks(
            module {
                unknown("on_load")
                head("h")
                def("g", call("nope", 3, 14))
            }
        ) { version ->
            when {
                isBefore(version, "1.12.0-rc.0") -> listOf(head("h/0"))
                isBefore(version, "1.15.0-rc.0") -> listOf(head("h/0"))
                else -> listOf(head("h/0"), STOP)
            }
        }

    @Test
    fun `an unknown compile value stops the checks there`() =
        assertChecks(
            module {
                unknown("compile")
                def("g", call("nope", 3, 14))
            }
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) listOf(undefined("nope", "g/0")) else listOf(undefined("nope", "g/0"), STOP)
        }

    @Test
    fun `an inline value that isn't a list stops the checks there`() =
        assertChecks(
            module { write("compile", pair(atom("inline"), pair(atom("a"), integer(0)))) }
        ) { listOf(STOP) }

    /** Elixir's own error text has no clause for an inline function that isn't a name and an arity, so it fails there. */
    @Test
    fun `an inline function that isn't a name and an arity stops the checks there`() {
        assertChecks(module { write("compile", pair(atom("inline"), Term.List(listOf(atom("a"))))) }) { listOf(STOP) }
        val text = Term.Binary("x".toByteArray())

        assertChecks(module { write("compile", pair(atom("inline"), keywords("a" to text))) }) { listOf(STOP) }
    }

    /** A name given as text is never a definition's, and Elixir's error text prints it. */
    @Test
    fun `an inline function named by a binary or a charlist is undefined`() {
        for (name in listOf(Term.Binary("f".toByteArray()), Term.List(listOf(integer('f'.code))))) {
            assertChecks(
                module {
                    write("compile", pair(atom("inline"), Term.List(listOf(pair(name, integer(0))))))
                    def("f")
                }
            ) { listOf(undefinedIn("f/0")) }
        }
    }

    /** Elixir's own error text has no clause for a function named `nil`, so its compile fails there. */
    @Test
    fun `a dialyzer option for nil stops the checks there`() =
        assertChecks(
            module {
                write("dialyzer", pair(atom("nowarn_function"), atom("nil")))
                def("g", call("nope", 3, 14))
            }
        ) { version ->
            when {
                isBefore(version, "1.12.0-rc.0") -> listOf(undefined("nope", "g/0"))
                else -> listOf(STOP)
            }
        }

    @Test
    fun `an unknown dialyzer value stops the checks there`() =
        assertChecks(
            module {
                unknown("dialyzer")
                def("g", call("nope", 3, 14))
            }
        ) { version ->
            when {
                isBefore(version, "1.12.0-rc.0") -> listOf(undefined("nope", "g/0"))
                else -> listOf(STOP)
            }
        }

    private class Module {
        var name = "Elixir.A"
        val definitions = linkedMapOf<NameArity, Defined<String>>()
        val calls = linkedMapOf<NameArity, List<LocalCall<String>>>()
        val imports = linkedMapOf<NameArity, String>()
        var usedPrivate = emptyList<NameArity>()
        val effects = mutableListOf<Pair<Effect, Boolean>>()
        var tainted = false

        fun def(name: String, vararg calls: LocalCall<String>, arity: Int = 0, kind: Kind = DEF) {
            val nameArity = NameArity(name, arity)
            definitions[nameArity] = Defined(kind, "$name/$arity", clauses = true, checksClauses = true)
            this.calls[nameArity] = calls.toList()
        }

        fun head(name: String, arity: Int = 0, kind: Kind = DEF, checksClauses: Boolean = true) {
            definitions[NameArity(name, arity)] = Defined(kind, "$name/$arity", clauses = false, checksClauses)
        }

        fun import(name: String, arity: Int, receiver: String = "Elixir.List") {
            imports[NameArity(name, arity)] = receiver
        }

        fun write(name: String, value: Term) {
            effects += Effect.Write(name, value) to true
        }

        /** A write [name] gets that isn't a statement of the module body, so its value is unknown. */
        fun unknown(name: String) {
            effects += Effect.Write(name, atom("x")) to false
        }
    }

    private fun module(build: Module.() -> Unit) = Module().apply(build)

    private fun call(name: String, line: Int, column: Int, arity: Int = 0) =
        LocalCall(name, NameArity(name, arity), line, column)

    private fun head(at: String) = "function_head $at"

    private fun conflict(at: String) = "import_conflict $at"

    private fun undefinedIn(named: String) = "undefined_attribute_function module $named"

    private fun wrongKind(named: String) = "wrong_kind_attribute_function module $named"

    private fun undefined(at: String, caller: String) = "undefined_function $at in $caller"

    private fun dispatch(at: String, caller: String) = "incorrect_dispatch $at in $caller"

    private fun assertChecks(module: Module, versions: List<String> = LEVELS, expected: (String) -> List<String>) =
        assertEquals(
            versions.joinToString("\n") { "$it: ${reported(it, expected(it)).joinToString(", ")}" },
            versions.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val attributes = AttributeTable(level)

                module.effects.forEach { (effect, statement) -> attributes.apply(effect, statement) }

                val checks = postModuleChecks(
                    level,
                    module.name,
                    "module",
                    module.definitions,
                    module.calls,
                    module.usedPrivate,
                    module.imports,
                    attributes::values,
                    module.tainted,
                ).map(::render)

                "$version: ${reported(version, checks).joinToString(", ")}"
            }
        )

    /** What [version] reports of [checks]: before 1.15 the first raises. */
    private fun reported(version: String, checks: List<String>): List<String> =
        if (FUNCTION_ERRORS_CONTINUE.isSufficient(ElixirLanguageLevel.of(version))) checks else checks.take(1)

    private fun render(check: PostModuleCheck<String>): String =
        when (check) {
            is PostModuleCheck.Error ->
                "${check.site.kind} ${check.at}" +
                    (check.named?.let { " ${render(it)}" } ?: "") +
                    (check.caller?.let { " in ${it.name}/${it.arity}" } ?: "")
            PostModuleCheck.Stop -> STOP
        }

    private fun render(term: Term): String {
        val pair = term as? Term.Pair
        val name = when (val first = pair?.first) {
            is Term.Atom -> first.name
            is Term.Binary -> first.bytes?.let(::String)
            is Term.List -> first.elements.joinToString("") { Character.toString((it as Term.Integer).value.toInt()) }
            else -> null
        }
        val arity = (pair?.second as? Term.Integer)?.value

        return if (name != null && arity != null) "$name/$arity" else term.toString()
    }

    private fun isBefore(version: String, boundary: String) =
        ElixirLanguageLevel.of(version).elixir < ElixirLanguageLevel.of(boundary).elixir

    private companion object {
        const val STOP = "stop"

        val LEVELS = ExpanderTestCase.LEVELS

        /** The legs where a module can report an error and carry on. */
        val CONTINUING = LEVELS.filter { FUNCTION_ERRORS_CONTINUE.isSufficient(ElixirLanguageLevel.of(it)) }

        fun atom(name: String) = Term.Atom(name)

        fun integer(value: Int) = Term.Integer(BigInteger.valueOf(value.toLong()))

        fun pair(first: Term, second: Term) = Term.Pair(first, second)

        fun keywords(vararg pairs: Pair<String, Any>) =
            Term.List(
                pairs.map { (key, value) -> pair(atom(key), if (value is Int) integer(value) else value as Term) },
            )
    }
}
