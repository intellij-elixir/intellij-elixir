package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.lowering.inspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** [AppFile] reads an application resource file as OTP's `application:load/1` does, which the leg's VM runs as the oracle. */
class AppFileTest {
    @Test
    fun `each leg SDK app reads as OTP loads it`() {
        val home = LegManifest.environment("ELIXIR_LANG_ELIXIR_PATH")

        assertReadsAsLoaded(
            listOf("elixir", "ex_unit", "logger").associateWith { app ->
                val text = File(home, "lib/$app/ebin/$app.app").readText()
                // Under a name of its own, as the oracle's VM has loaded the leg's.
                val named: (String) -> String =
                    { name -> text.replaceFirst(Regex("""\{application,\s*$app,"""), "{application,$name,") }

                named
            },
        )
    }

    @Test
    fun `each term shape reads as OTP loads it`() {
        assertReadsAsLoaded(
            mapOf(
                "atoms" to { name: String -> app(name, "{a, b}, {q, 'Elixir.Q R'}, {e, 'it\\'s'}, {u, '\\x{263A}'}, {r, 'end'}") },
                "numbers" to { name: String ->
                    app(name, $$"{i, 42}, {n, -7}, {p, +7}, {s, 1_000}, {h, 16#1F}, {c, $a}, {ce, $\\n}, {f, 1.5e3}, {nf, -0.25}")
                },
                "strings" to { name: String ->
                    app(name, "{s, \"abc\"}, {e, \"\"}, {x, \"a\\nb\\tc\\\\\\\"\\101\\x41\\x{263A}\\^a\"}, {j, \"ab\" \"cd\"}")
                },
                "binaries" to { name: String -> app(name, "{b, <<\"bin\">>}, {i, <<1, 2, 300>>}, {e, <<>>}, {m, <<\"a\", 98>>}") },
                "containers" to { name: String ->
                    app(name, "{t, {}}, {t3, {a, 1, \"x\"}}, {l, [1, [2]]}, {e, []}, {i, [1 | 2]}, {m, #{a => 1, \"k\" => [b]}}, {em, #{}}")
                },
                "a map keyed by a string and the list it is" to { name: String ->
                    app(
                        name,
                        "{m, #{[107] => one, \"k\" => two}}, {t, #{{[107]} => one, {\"k\"} => two}}, " +
                            "{n, #{#{a => [107]} => one, #{a => \"k\"} => two}}",
                    )
                },
                "comments" to { name: String -> "%% coding: utf-8\n{application, $name, % its name\n  [{env, [{k, v}]}]}. % done\n" },
                "funs" to { name: String -> app(name, "{f, fun lists:reverse/1}, {q, fun 'Elixir.Enum':'map'/2}") },
                "no env" to { name: String -> "{application, $name, [{vsn, \"1\"}]}.\n" },
                "the first env" to { name: String -> "{application, $name, [{env, [{k, 1}]}, {env, [{k, 2}]}]}.\n" },
                "maybe_expr's keywords" to { name: String -> app(name, "{m, maybe}, {e, else}") },
                "a caret question mark" to { name: String -> app(name, "{k, \"\\^?\"}") },
                "a caret digit" to { name: String -> app(name, "{k, \"\\^1\"}") },
                "a surrogate escape" to { name: String -> app(name, "{k, \"\\x{D800}\"}") },
                "malformed: no dot" to { name: String -> "{application, $name, [{env, []}]}\n" },
                "malformed: two terms" to { name: String -> "{application, $name, [{env, []}]}.\n{a, b}.\n" },
                "malformed: a variable" to { name: String -> "{application, $name, [{env, [{k, V}]}]}.\n" },
                "malformed: a reserved word" to { name: String -> app(name, "{k, end}") },
                "a duplicate key" to { name: String -> app(name, "{k, 1}, {j, 0}, {k, 2}, {k, 3}") },
                "a key not an atom" to { name: String -> app(name, "{\"k\", 1}, {2, x}, {a, 3}") },
                "malformed: an entry not a pair" to { name: String -> app(name, "{k, 1, 2}") },
                "malformed: an entry not a tuple" to { name: String -> app(name, "{a, 1}, k") },
                "malformed: an improper env" to { name: String -> app(name, "{k, 1} | x") },
                "malformed: env not a list" to { name: String -> "{application, $name, [{env, k}]}.\n" },
                "malformed: not an application" to { name: String -> "{library, $name, [{env, []}]}.\n" },
                "malformed: a binary opened by a lone `>`" to { name: String -> app(name, "{k, <<>1>>}") },
                "malformed: unclosed" to { name: String -> "{application, $name, [{env, [{k, \"v}]}]}.\n" },
                "malformed: a string escape past any code point" to { name: String -> app(name, "{k, \"\\x{80000000}\"}") },
                "malformed: an atom escape past Unicode" to { name: String -> app(name, "{k, '\\x{110000}'}") },
                "malformed: a base past any" to { name: String -> app(name, "{k, 99999999999#1}") },
                "malformed: a float past any" to { name: String -> app(name, "{k, 1.0e999}") },
                "a float below the least" to { name: String -> app(name, "{k, 1.0e-999}") },
                "malformed: an atom over 255 characters" to { name: String -> app(name, "{k, ${"a".repeat(256)}}") },
                "a fun arity past 255" to { name: String -> app(name, "{k, fun a:b/300}") },
                "malformed: a fun module over 255 characters" to { name: String -> app(name, "{k, fun ${"a".repeat(256)}:b/1}") },
                "malformed: a fun function over 255 characters" to { name: String -> app(name, "{k, fun a:${"b".repeat(256)}/1}") },
                "malformed: a literal U+FFFE in a string" to { name: String -> app(name, "{k, \"\uFFFE\"}") },
                "malformed: a literal U+FFFF in a quoted atom" to { name: String -> app(name, "{k, '\uFFFF'}") },
                "malformed: a literal U+FFFF character" to { name: String -> app(name, "{k, $\uFFFF}") },
                "malformed: an escaped U+FFFF" to { name: String -> app(name, "{k, \"\\\uFFFF\"}") },
                "malformed: a U+FFFE in a comment" to { name: String -> "{application, $name, [{env, []}]}. % \uFFFE\n" },
                "malformed: an ideographic space" to { name: String -> "{application,\u3000$name, [{env, []}]}.\n" },
                "control characters and a no-break space as white space" to { name: String ->
                    "{application,\u0001$name,\u0085[{env,\u00A0[]}]}.\n"
                },
                "adjacent strings with no space" to { name: String -> app(name, "{j, \"ab\"\"cd\"}") },
                "malformed: a fun arity past a small" to { name: String -> app(name, "{k, fun a:b/1152921504606846976}") },
                "malformed: a start spec not a pair" to { name: String -> "{application, $name, [{mod, foo}, {env, []}]}.\n" },
                "an empty start spec" to { name: String -> "{application, $name, [{mod, []}, {env, []}]}.\n" },
            ),
        )
    }

    /**
     * A fun arity past an `Int`, which JInterface can't hold, on every OTP: from
     * [MAKE_FUN_ARITY_CHECKED][org.elixir_lang.language_level.ElixirLanguageFeature.MAKE_FUN_ARITY_CHECKED] the VM refuses
     * it, and before it the VM keeps it whole, which the oracle can't read back to compare.
     */
    @Test
    fun `a fun arity past an int is unreadable`() {
        assertNull(AppFile.read(app("app_file_arity", "{k, fun a:b/4294967296}"), legLevel()))
    }

    /**
     * `"""` where the leg's VM reads it as three adjacent strings is read as the VM joins them, and where the VM scans
     * it as a triple-quoted string it is refused.
     */
    @Test
    fun `a triple-quoted string is refused where the leg's VM scans one`() {
        val loaded = loaded(mapOf("triple" to { name: String -> app(name, "{k, \"\"\"\nabc\n\"\"\"}") })).single()
        val joined = rendered(listOf(OtpErlangAtom("k") to OtpErlangString("\nabc\n")))

        assertEquals(loaded.byVm.takeIf { it == joined } ?: "error", loaded.byReader)
    }

    /**
     * A map keyed by `0.0` and `-0.0` reads as the leg's VM loads it where the VM keeps both keys, and is refused where
     * it keeps one, as which of the two it keeps isn't known.
     */
    @Test
    fun `a map keyed by both zeros is refused where the leg's VM keeps one`() {
        val loaded = loaded(mapOf("zeros" to { name: String -> app(name, "{m, #{0.0 => one, -0.0 => two}}") })).single()
        val both = rendered(
            listOf(
                OtpErlangAtom("m") to
                    OtpErlangMap(arrayOf(OtpErlangDouble(0.0), OtpErlangDouble(-0.0)), arrayOf(OtpErlangAtom("one"), OtpErlangAtom("two"))),
            ),
        )

        assertEquals(loaded.byVm.takeIf { it == both } ?: "error", loaded.byReader)
    }

    /**
     * Asserts that each of [cases], given the application name to write, reads as the oracle's VM loads it: the env
     * [AppFile.read] gives, or `error` where it gives none.
     */
    private fun assertReadsAsLoaded(cases: Map<String, (String) -> String>) {
        val loaded = loaded(cases)

        assertEquals(
            loaded.joinToString("\n") { "${it.case}: ${it.byVm}" },
            loaded.joinToString("\n") { "${it.case}: ${it.byReader}" },
        )
    }

    /** One of [loaded]'s cases: the env the oracle's VM loads and the env [AppFile.read] gives, each `error` where none. */
    private class Loaded(val case: String, val byVm: String, val byReader: String)

    /** Each of [cases], given the application name to write, as the oracle's VM loads it and as [AppFile.read] reads it. */
    private fun loaded(cases: Map<String, (String) -> String>): List<Loaded> {
        val directory = Files.createTempDirectory("app_file").toFile()
        val token = "app_file_" + UUID.randomUUID().toString().replace("-", "")
        val names = cases.keys.withIndex().associate { (index, case) -> case to "${token}_$index" }
        val texts = cases.mapValues { (case, text) -> text(names.getValue(case)) }
        texts.forEach { (case, text) -> File(directory, "${names.getValue(case)}.app").writeText(text) }

        try {
            val compiled = Quoter.compile(
                """
                Code.prepend_path(${elixirString(directory.invariantSeparatorsPath)})

                for name <- [${names.values.joinToString(", ") { ":$it" }}] do
                  loaded =
                    case :application.load(name) do
                      :ok -> {:ok, :application.get_all_env(name)}
                      {:error, _} -> :error
                    end

                  :application.unload(name)
                  IntellijElixir.Quoter.Probe.send(__ENV__, {name, loaded})
                end

                Code.delete_path(${elixirString(directory.invariantSeparatorsPath)})
                """.trimIndent(),
                60.seconds,
            )

            assertEquals("oracle status ${compiled.diagnostics.map(::inspect)}", OtpErlangAtom("ok"), compiled.status)

            val loaded = compiled.messages.associate { message ->
                val (name, result) = (message as OtpErlangTuple).elements()

                (name as OtpErlangAtom).atomValue() to result
            }

            return cases.keys.map { case ->
                val result = loaded.getValue(names.getValue(case))
                val read = AppFile.read(texts.getValue(case), legLevel())

                Loaded(
                    case,
                    if (result is OtpErlangTuple) rendered((result.elementAt(1) as OtpErlangList).map(::pair)) else "error",
                    if (read?.name == names.getValue(case)) rendered(read.env.toList()) else "error",
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        fun app(name: String, env: String) = "{application, $name, [{vsn, \"0.1.0\"}, {env, [$env]}]}.\n"

        fun pair(entry: OtpErlangObject): Pair<OtpErlangObject, OtpErlangObject> {
            val (key, value) = (entry as OtpErlangTuple).elements()

            return key to value
        }

        /**
         * [env] by key, each term with a charlist as the list of integers it is, however it arrived, a fun as a tuple, and
         * a map as its pairs, each one kept.
         */
        fun rendered(env: List<Pair<OtpErlangObject, OtpErlangObject>>): String =
            env.map { (key, value) -> inspect(normalised(key)) to inspect(normalised(value)) }
                .sortedBy { it.first }
                .joinToString(", ", "[", "]") { (key, value) -> "$key: $value" }

        fun normalised(term: OtpErlangObject): OtpErlangObject =
            when (term) {
                is OtpErlangExternalFun ->
                    OtpErlangTuple(arrayOf(OtpErlangAtom("fun"), OtpErlangAtom(term.module), OtpErlangAtom(term.function), OtpErlangLong(term.arity.toLong())))
                is OtpErlangString ->
                    OtpErlangList(term.stringValue().codePoints().toArray().map { OtpErlangLong(it.toLong()) }.toTypedArray())
                is OtpErlangList ->
                    if (term.lastTail == null) {
                        OtpErlangList(term.elements().map(::normalised).toTypedArray())
                    } else {
                        OtpErlangList(term.elements().map(::normalised).toTypedArray(), normalised(term.lastTail))
                    }
                is OtpErlangTuple -> OtpErlangTuple(term.elements().map(::normalised).toTypedArray())
                is OtpErlangMap -> {
                    // Not a map: one built from the normalised keys would fold two that differ only in how a charlist arrived.
                    val pairs = term.keys().map { OtpErlangTuple(arrayOf(normalised(it), normalised(term.get(it)))) }.sortedBy(::inspect)

                    OtpErlangTuple(arrayOf(OtpErlangAtom("map"), OtpErlangList(pairs.toTypedArray())))
                }
                else -> term
            }
    }
}
