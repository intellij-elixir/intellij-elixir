package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.lowering.inspect
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * ExUnit's forms, `testData/org/elixir_lang/expander/ex_unit_forms`, compiled by the expander in a project whose module
 * has the leg's SDK and by Elixir on the quoter's node, whose VM has started `:ex_unit` as `test_helper.exs` does. Each
 * module's end, its definitions once every `@before_compile` callback has run, and its `@ex_unit_tests` then, which
 * `__ex_unit__/0` lists, are Elixir's.
 */
class ExUnitFormsHeavyTest : HeavyTestCase() {
    override fun setUp() {
        // Before the platform counts the threads a test leaves, so the quoter's connection is already open.
        exUnitOnQuoter()
        super.setUp()
        val sdk = LegSdk.setUp(module, testRootDisposable)
        // A library file has no module, so it reads the project's SDK.
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }
    }

    override fun tearDown() {
        try {
            WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = null }
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testEachModuleEndsAsElixirCompilesIt() {
        val version = LegManifest.environment("ELIXIR_VERSION")
        val dir = createTempDir("ex_unit_forms")
        FileUtil.copyDir(CORPUS, dir)
        if (isBefore(version, "1.20.0")) FileUtil.delete(File(dir, "test/v120"))
        // The templates first, as `elixirc_paths` compiles them before the tests.
        val files = File(dir, "test").walkTopDown().filter(File::isFile).map { it.relativeTo(dir).invariantSeparatorsPath }
            .sortedWith(compareBy({ !it.startsWith("test/support/") }, { it }))
            .toList()
        val elixir = elixir(dir, files)
        val corpus = PhoenixCorpus(project, testRootDisposable, dir)
        corpus.setUp(module)
        val holder = ProjectModules.getInstance(project)
        val expander = files.flatMap { file ->
            val psi = corpus.psi(File(dir, file))!!

            ProjectModules.results(corpus.quiet { expansion(holder, psi) }).flatMap { lines(file, psi.text, it) }.toList()
        }

        val pins = pins(version)
        val pinned = { line: String -> pins.keys.any { line.startsWith("$it ") } }

        assertEquals(
            (elixir.filterNot(pinned) + files.flatMap { file -> pins[file].orEmpty().map { "$file $it" } }).joinToString("\n"),
            (expander.filterNot(pinned) + expander.filter { pinned(it) && ENDED.containsMatchIn(it) }).joinToString("\n"),
        )
    }

    /**
     * Elixir's lines for [files] of [dir], each compiled in turn after the dependency's and the app's `lib`, which are
     * written to their `_build` `ebin`s first, where the project reads the dependency's. Each module records itself from
     * a `@before_compile` callback registered after its `use`, on the `use`'s own line so no line moves.
     */
    private fun elixir(dir: File, files: List<String>): List<String> {
        val compiled = Quoter.compile(
            """
            dir = ${elixirString(dir.invariantSeparatorsPath)}
            ExUnit.start(autorun: false)

            defmodule ExUnitForms.Recorder do
              @outer __ENV__.file

              defmacro __before_compile__(env) do
                definitions =
                  for kind <- [:def, :defp, :defmacro, :defmacrop],
                      {name, arity} <- Module.definitions_in(env.module, kind),
                      do: {kind, name, arity}

                tests = if Module.has_attribute?(env.module, :ex_unit_tests), do: Module.get_attribute(env.module, :ex_unit_tests)
                IntellijElixir.Quoter.Probe.send(%{file: @outer}, {:module, env.file, env.module, definitions, tests})
                nil
              end
            end

            for {app, sources} <- [ex_unit_forms_template: "deps/ex_unit_forms_template/lib", ex_unit_forms: "lib"] do
              ebin = Path.join([dir, "_build/dev/lib", Atom.to_string(app), "ebin"])
              File.mkdir_p!(ebin)
              {:ok, modules, _} =
                Kernel.ParallelCompiler.compile_to_path(Path.wildcard(Path.join([dir, sources, "**/*.ex"])), ebin, return_diagnostics: true)

              Code.prepend_path(ebin)

              # The quoter's embedded mode loads nothing from the code path until asked, as `mix test`'s VM would.
              for module <- modules, :code.is_loaded(module) == false do
                {:module, _} = :code.load_abs(String.to_charlist(Path.join(ebin, Atom.to_string(module))))
              end
            end

            for file <- [${files.joinToString(", ") { elixirString(it) }}] do
              path = Path.join(dir, file)
              source = Regex.replace(~r/^(\s*use .*)$/m, File.read!(path), "\\1; @before_compile ExUnitForms.Recorder", global: false)

              try do
                Code.compile_string(source, path)
                IntellijElixir.Quoter.Probe.send(__ENV__, {:compiled, path})
              rescue
                exception -> IntellijElixir.Quoter.Probe.send(__ENV__, {:raised, path, exception.__struct__})
              end
            end
            """.trimIndent(),
            COMPILE_TIMEOUT,
        )

        assertEquals("forms compile: ${compiled.diagnostics}", OtpErlangAtom("ok"), compiled.status)

        val prefix = dir.invariantSeparatorsPath + "/"
        val path = { term: OtpErlangObject -> String((term as OtpErlangBinary).binaryValue()).removePrefix(prefix) }
        val atom = { term: OtpErlangObject -> (term as OtpErlangAtom).atomValue() }

        return compiled.messages.flatMap { message ->
            val elements = (message as OtpErlangTuple).elements()

            when (atom(elements[0])) {
                "module" -> {
                    val (_, file, module, definitions, tests) = elements
                    val name = atom(module)
                    val defined = (definitions as OtpErlangList).map { definition ->
                        val (kind, function, arity) = (definition as OtpErlangTuple).elements()

                        "$name ${atom(kind)} ${atom(function)}/${(arity as OtpErlangLong).intValue()}"
                    }

                    buildList {
                        add("$name compiled")
                        addAll(defined.sorted())
                        addAll(tests(name, tests.takeUnless { it == NIL }))
                    }.map { "${path(file)} $it" }
                }
                "raised" -> listOf("${path(elements[1])} raised ${atom(elements[2])}")
                else -> emptyList()
            }
        }
    }

    /**
     * The expander's lines for [result], a module of [file], whose text is [text]: how it ended, then what it defined
     * and its tests.
     */
    private fun lines(file: String, text: String, result: ExpansionResult): List<String> {
        val name = result.module
        val ended = when (val ended = result.ended) {
            ExpansionResult.Ended.Compiled -> "$name compiled"
            is ExpansionResult.Ended.Raised -> return listOf("$file raised ${ended.error.kind}")
            is ExpansionResult.Ended.Stopped -> ended.at.meta.origin.let {
                "$name stopped on line ${text.substring(0, it.startOffset).count { c -> c == '\n' } + 1} " +
                    "`${text.substring(it.startOffset, it.endOffset).lines().first()}`"
            }
            else -> "$name ended ${ended::class.simpleName}"
        }
        val definitions = result.table.entries.map { (nameArity, entry) ->
            "$name ${entry.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity}"
        }.sorted()
        val tests = when (val tests = result.attributes.final["ex_unit_tests"]) {
            null -> emptyList()
            is AttributeValue.Held -> tests(name, tests.value)
            else -> listOf("$name tests $tests")
        }

        return buildList {
            add(ended)
            addAll(definitions)
            addAll(tests)
        }.map { "$file $it" }
    }

    /** Each of [tests], `@ex_unit_tests` newest first, as one line, its maps' pairs sorted. */
    private fun tests(module: String, tests: OtpErlangObject?): List<String> =
        (tests as OtpErlangList?)?.map { "$module test ${inspect(sorted(it))}" }.orEmpty()

    private fun sorted(term: OtpErlangObject): OtpErlangObject =
        when (term) {
            is OtpErlangMap -> {
                val pairs = term.keys().map { sorted(it) to sorted(term.get(it)) }.sortedBy { inspect(it.first) }

                OtpErlangMap(pairs.map { it.first }.toTypedArray(), pairs.map { it.second }.toTypedArray())
            }
            is OtpErlangList -> if (term.lastTail == null) OtpErlangList(term.elements().map(::sorted).toTypedArray()) else term
            is OtpErlangTuple -> OtpErlangTuple(term.elements().map(::sorted).toTypedArray())
            else -> term
        }

    private companion object {
        val CORPUS = File("testData/org/elixir_lang/expander/ex_unit_forms")

        /**
         * The files whose modules end otherwise than in Elixir on [version]'s leg, to how each ends in the expander: their
         * only lines compared ([ENDED]).
         * - Elixir raises where a module defines a test twice, where a test's name is too long for an atom before 1.20,
         *   and where a test body reads a variable it isn't given. Before 1.14 `use ExUnit.Case` passes over a raising
         *   arm whose test can't be decided, and from 1.17 every `test` reads the application's environment as it
         *   expands: an answer the expander takes as an assumption, so a raise after it may not be Elixir's, and the
         *   module stops there instead. Until 1.17 `test` gives `def` its body as a value, so the body's read stops at
         *   the `test`.
         * - From 1.14 to 1.16 the body's `x` is a call to an undefined `x/0`, which Elixir raises as a `CompileError`
         *   once the module's callbacks have run. The expander raises it too, but its lines don't say the callbacks ran.
         * - A `doctest` reads the documented module's docs, which the expander doesn't, and its decline may have
         *   written any of the module's attributes, so the tests after it aren't known.
         */
        fun pins(version: String): Map<String, List<String>> {
            val assumed = isBefore(version, "1.14.0") || !isBefore(version, "1.17.0")
            val longName = "Elixir.ExUnitForms.LongNameTest stopped on line 4 `${File(CORPUS, "test/long_name_test.exs").readLines()[3].trim()}`"

            return buildMap {
                put(
                    "test/doc_test.exs",
                    listOf("Elixir.ExUnitForms.DocTest stopped on line 4 `doctest ExUnitForms.Doc`", "Elixir.ExUnitForms.DocTest tests Unknown"),
                )
                if (assumed && isBefore(version, "1.20.0")) put("test/long_name_test.exs", listOf(longName))
                if (!assumed) {
                    put(
                        "test/for_read_test.exs",
                        listOf(if (isBefore(version, "1.15.0")) "raised undefined_function" else "Elixir.ExUnitForms.ForReadTest ended Tainted"),
                    )
                } else {
                    put("test/duplicate_test.exs", listOf("Elixir.ExUnitForms.DuplicateTest stopped on line 8 `test \"same\" do`"))
                    put(
                        "test/for_read_test.exs",
                        listOf(
                            if (isBefore(version, "1.17.0")) {
                                "Elixir.ExUnitForms.ForReadTest stopped on line 5 `test \"t #{x}\" do`"
                            } else {
                                "Elixir.ExUnitForms.ForReadTest stopped on line 6 `x`"
                            },
                        ),
                    )
                }
            }
        }

        /** A line saying how a module ended, or that its tests aren't known. */
        val ENDED = Regex(" (compiled|stopped on line \\d+ `.*`|raised \\S+|ended \\S+|tests \\S+)$")
        val COMPILE_TIMEOUT = 120.seconds
        val NIL = OtpErlangAtom("nil")
    }
}
