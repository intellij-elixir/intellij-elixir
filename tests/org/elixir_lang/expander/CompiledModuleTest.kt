package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.call.Call
import org.elixir_lang.toOtpErlangList
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** [CompiledModule] read from `.beam` files of the leg's Elixir and OTP, and from a fixture the leg's Elixir compiles. */
class CompiledModuleTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()

        appBeams.values.map { it.parentFile.parentFile.parentFile.path }.distinct().forEach {
            VfsRootAccess.allowRootAccess(testRootDisposable, it)
        }
    }

    /**
     * Each definition of the leg's Elixir apps has as many clauses in its mirror as in its debug info, but a private one
     * its code doesn't list, which the decompiler, rendering the code's definitions, has no clause of, and whose entry
     * takes its origin inside the module there.
     */
    fun testClauseCountsMatchMirror() {
        var compared = 0
        var unlisted = 0
        val differing = elixirBeams().flatMap { (module, beam) ->
            val mirror = mirror(beam)
            val mirrored = mirrorClauses(mirror)
            val modular = ReadAction.computeBlocking<TextRange, Throwable> { mirror.modulars().single().textRange }
            val table by lazy { read(beam, mirror).table }
            val locals = reading(beam) { reader ->
                reader.localsResult.valueOrNull?.macroNameAritySortedSetByMacro()?.values?.flatten().orEmpty()
                    .map { it.toNameArity() }
                    .toSet()
            }

            definitions(beam).mapNotNull { definition ->
                compared++

                val clauses = (definition.clausesTerm as OtpErlangList).arity()
                val inMirror = mirrored[Triple(definition.macro, definition.name, definition.arity)].orEmpty().size
                val nameArity = NameArity(definition.name!!, definition.arity!!)
                val inCode = definition.macro !in PRIVATE || nameArity in locals
                val origin = if (inCode) null else table[nameArity]?.at?.meta?.origin

                if (!inCode) unlisted++

                val definitionText = "$module ${definition.macro} ${definition.name}/${definition.arity}"

                "$definitionText: debug info $clauses, mirror $inMirror, at $origin in $modular"
                    .takeIf { inMirror != (if (inCode) clauses else 0) || !inCode && origin?.let(modular::contains) != true }
            }
        }

        assertTrue("definitions compared: $compared", compared > 1_000)
        assertTrue("private definitions the code doesn't list: $unlisted", unlisted > 0)
        assertEquals("${differing.size} of $compared differ", emptyList<String>(), differing.take(20))
    }

    /**
     * Each definition of the leg's Elixir apps has a body per debug info clause, in order: an `fn` clause that
     * `elixir_utils:extract_splat_guards/1` reads back as that clause's arguments and guards, whose origin is the same
     * clause in the mirror, or the module there for a definition the mirror has no clause of.
     */
    fun testBodiesAreMirroredFnClauses() {
        var compared = 0
        val differing = elixirBeams().flatMap { (module, beam) ->
            differingBodies(module, beam) { compared++ }
        }

        assertEquals("${differing.size} differ", emptyList<String>(), differing.take(20))
        assertTrue("clauses compared: $compared", compared > 300)
    }

    /** A module with debug info: every definition Elixir's table holds, each one's bodies, and its deprecations. */
    fun testDebugInfo() {
        val fixture = fixture()
        val compiled = fixture.debugInfo

        assertEquals("Elixir.CompiledFixture", compiled.module)
        assertEquals(
            listOf(
                "f/1 DEF 1 0 default", "f/2 DEF 1 1", "g/1 DEF 2 0", "h/1 DEF 1 0", "hidden/1 DEFP 1 0", "k/1 DEF 1 0 default",
                "k/2 DEF 2 1", "m/1 DEFMACRO 2 0", "max/1 DEF 2 0", "max/2 DEF 1 0 default", "max/3 DEF 1 2",
                "n/1 DEF 2 0", "n/2 DEF 1 0 default", "n/3 DEF 1 2", "o/0 DEF 1 0 default", "o/1 DEF 1 1",
                "p/1 DEFMACROP 1 0", "z/1 DEFMACRO 1 0",
            ),
            rows(compiled.table),
        )
        assertEquals(
            compiled.table.entries.mapValues { it.value.clauses },
            (compiled.bodies as CompiledModule.Bodies.Read).clauses.mapValues { it.value.size },
        )
        assertEquals(emptyList<String>(), differingBodies("CompiledFixture", fixture.debugInfoBeam) {})
        assertEquals(Deprecations.Known(mapOf(NameArity("h", 1) to "Use g/1 instead")), compiled.deprecations)
    }

    /** A module compiled without debug info: `Docs`' functions and macros, and each arity their defaults cover. */
    fun testDocsOnly() {
        val compiled = fixture().docsOnly

        assertEquals(
            listOf(
                "f/1 DEF default", "f/2 DEF 1", "g/1 DEF", "h/1 DEF", "k/1 DEF default", "k/2 DEF 1", "m/1 DEFMACRO",
                "max/1 DEF", "max/2 DEF default", "max/3 DEF 2", "n/1 DEF", "n/2 DEF default", "n/3 DEF 2",
                "o/0 DEF default", "o/1 DEF 1", "z/1 DEFMACRO",
            ),
            rows(compiled.table).map(::kindDefaults),
        )
        assertEquals(CompiledModule.Bodies.Unreadable, compiled.bodies)
    }

    /** A stripped module: its exports, less those the compiler adds, `behaviour_info/1` among them. */
    fun testStripped() {
        val compiled = fixture().stripped

        assertEquals(
            listOf(
                "f/1 DEF", "f/2 DEF", "g/1 DEF", "h/1 DEF", "k/1 DEF", "k/2 DEF", "m/1 DEFMACRO", "max/1 DEF", "max/2 DEF",
                "max/3 DEF", "n/1 DEF", "n/2 DEF", "n/3 DEF", "o/0 DEF", "o/1 DEF", "z/1 DEFMACRO",
            ),
            rows(compiled.table).map(::kind),
        )
        assertEquals(CompiledModule.Bodies.Unreadable, compiled.bodies)
    }

    /** An Erlang module: its exports as functions, each with as many clauses as its mirror gives it. */
    fun testErlang() {
        val beam = appBeams.getValue("queue")
        val mirror = mirror(beam)
        val mirrored = mirrorClauses(mirror)
        val compiled = read(beam, mirror)
        val exports = reading(beam) { reader ->
            reader.exportsResult.valueOrNull!!.macroNameAritySortedSetByMacro().values.flatten()
                .map { it.toNameArity() }
                .filterNot { it.name == "module_info" }
        }

        assertEquals(exports.toSet(), compiled.table.entries.keys)
        assertEquals(
            emptyList<String>(),
            compiled.table.entries.mapNotNull { (nameArity, entry) ->
                val mirror = mirrored[Triple("def", nameArity.name, nameArity.arity)].orEmpty().size

                "$nameArity ${entry.kind} ${entry.clauses} ${entry.defaults} ${entry.default}, mirror $mirror".takeUnless {
                    entry.kind == DefinitionTable.Kind.DEF && entry.clauses == mirror && entry.defaults == 0 && !entry.default
                }
            },
        )
        assertEquals(CompiledModule.Bodies.Unreadable, compiled.bodies)
    }

    /**
     * The fixture's table read from source agrees with its `.beam`'s: every entry's kind, clauses, defaults and default
     * with debug info; the public entries' kind, defaults and default from `Docs`; the exported entries' kind stripped.
     */
    fun testTableMatchesSource() {
        val fixture = fixture()
        val source = rows(sourceTable())
        val public = source.filterNot { it.split(" ")[1] in setOf("DEFP", "DEFMACROP") }

        assertEquals(source, rows(fixture.debugInfo.table))
        assertEquals(public.map(::kindDefaults), rows(fixture.docsOnly.table).map(::kindDefaults))
        assertEquals(public.map(::kind), rows(fixture.stripped.table).map(::kind))
    }

    private class Fixture(
        val debugInfoBeam: File,
        val debugInfo: CompiledModule,
        val docsOnly: CompiledModule,
        val stripped: CompiledModule,
    )

    /**
     * [FIXTURE] compiled by the leg's Elixir with debug info, without it, and with it then stripped. The quoter's
     * compiler options are shared by every test fork, so the one without debug info comes from its own `@compile`.
     */
    private fun fixture(): Fixture {
        val compiled = Quoter.compile(
            """
            compile = fn source ->
              [{module, binary}] = Code.compile_string(source)
              :code.delete(module)
              :code.purge(module)
              binary
            end

            full = compile.(${elixirString(FIXTURE)})
            {:ok, {_, stripped}} = :beam_lib.strip(full)
            IntellijElixir.Quoter.Probe.send(__ENV__, {full, compile.(${elixirString(FIXTURE_WITHOUT_DEBUG_INFO)}), stripped})
            """.trimIndent(),
            60.seconds,
        )

        assertEquals("fixture compile ${compiled.diagnostics.map(::inspect)}", OtpErlangAtom("ok"), compiled.status)

        val binaries = (compiled.messages.single() as OtpErlangTuple).elements().map { (it as OtpErlangBinary).binaryValue() }
        val directory = FileUtil.createTempDirectory("compiled_module", null)

        VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)

        val (debugInfo, docsOnly, stripped) = listOf("debug_info", "docs_only", "stripped").zip(binaries).map { (name, bytes) ->
            File(File(directory, name).apply { mkdirs() }, "Elixir.CompiledFixture.beam").apply { writeBytes(bytes) }
        }

        return Fixture(debugInfo, read(debugInfo), read(docsOnly), read(stripped))
    }

    /** [FIXTURE]'s table, as the expander reads it from source at the leg's level. */
    private fun sourceTable(): DefinitionTable {
        val level = legLevel()
        val forms = ReadAction.computeBlocking<_, Throwable> {
            val file = PsiFileFactory.getInstance(project).createFileFromText("fixture.ex", ElixirLanguage, FIXTURE)

            Lowering.lower(file as ElixirFile, level)
        }
        val module = Expander.expandFile(forms, Env.empty(level, legKernel), level, legExports, legStructs).modules.single()

        val stopped = (module.ended as? ExpansionResult.Ended.Stopped)?.at?.meta?.origin?.substring(FIXTURE)

        assertEquals("the fixture compiles from source, but stops at $stopped", ExpansionResult.Ended.Compiled, module.ended)

        return module.table
    }

    /** Each entry as `name/arity KIND clauses defaults`, then `default` when it was stored for a default arity. */
    private fun rows(table: DefinitionTable): List<String> =
        table.entries.map { (nameArity, entry) ->
            val words = listOf("${nameArity.name}/${nameArity.arity}", entry.kind.name, "${entry.clauses}", "${entry.defaults}")

            (words + listOfNotNull("default".takeIf { entry.default })).joinToString(" ")
        }.sorted()

    /** A [rows] row without its clauses, and with its defaults only where there are some. */
    private fun kindDefaults(row: String): String {
        val words = row.split(" ")

        return listOfNotNull(words[0], words[1], words[3].takeIf { it != "0" }, words.getOrNull(4)).joinToString(" ")
    }

    /** A [rows] row's name, arity and kind. */
    private fun kind(row: String): String = row.split(" ").take(2).joinToString(" ")

    /** The arguments, guards and body `elixir_utils:extract_splat_guards/1` reads from an `fn` clause. */
    private fun fnClause(term: OtpErlangObject): List<OtpErlangObject> {
        val (_, _, arrow) = (term as OtpErlangTuple).elements()
        val (head, body) = (arrow as OtpErlangList).elements()
        val arguments = head.toOtpErlangList().elements().toList()
        val splat = (arguments.singleOrNull() as? OtpErlangTuple)?.let(::whenArguments)?.takeIf { it.isNotEmpty() }
        val (left, guards) = splat?.let { it.dropLast(1) to orGuards(it.last()) } ?: (arguments to emptyList())

        return listOf(OtpErlangList(left.toTypedArray()), OtpErlangList(guards.toTypedArray()), body)
    }

    /** `elixir_utils:extract_or_guards/1`. */
    private fun orGuards(term: OtpErlangObject): List<OtpErlangObject> =
        (term as? OtpErlangTuple)?.let(::whenArguments)?.takeIf { it.size == 2 }?.let { (left, right) -> listOf(left) + orGuards(right) }
            ?: listOf(term)

    private fun whenArguments(tuple: OtpErlangTuple): List<OtpErlangObject>? =
        tuple.takeIf { it.arity() == 3 && it.elementAt(0) == OtpErlangAtom("when") }
            ?.let { (it.elementAt(2) as? OtpErlangList)?.elements()?.toList() }

    /**
     * Each [beam] definition clause whose body isn't an `fn` clause `elixir_utils:extract_splat_guards/1` reads back as that
     * clause's arguments and guards, with its mirror clause's range, or the module's for one the mirror has no clause of;
     * [compared] is called for each clause.
     */
    private fun differingBodies(module: String, beam: File, compared: () -> Unit): List<String> {
        val mirror = mirror(beam)
        val mirrored = mirrorClauses(mirror)
        val modular = ReadAction.computeBlocking<TextRange, Throwable> { mirror.modulars().single().textRange }
        val bodies = (read(beam, mirror).bodies as CompiledModule.Bodies.Read).clauses

        return definitions(beam).flatMap { definition ->
            val nameArity = "${definition.name}/${definition.arity}"
            val clauses = (definition.clausesTerm as OtpErlangList).elements().map { it as OtpErlangTuple }
            val origins = mirrored[Triple(definition.macro, definition.name, definition.arity)]
                ?.map { ReadAction.computeBlocking<TextRange, Throwable> { it.textRange } }
                ?: clauses.map { modular }
            val read = bodies[NameArity(definition.name!!, definition.arity!!)].orEmpty()
            val counted = "$module $nameArity: ${read.size} bodies, ${clauses.size} clauses".takeIf { read.size != clauses.size }

            listOfNotNull(counted) + clauses.zip(read).withIndex().mapNotNull { (index, pair) ->
                compared()

                val (clause, body) = pair
                val (_, arguments, guards, block) = clause.elements()
                val actual = fnClause(body.toOtp()) to body.meta.origin
                val expected = listOf(arguments.toOtpErlangList(), guards, block) to origins.getOrNull(index)

                "$module $nameArity clause $index: ${actual.first.map(::inspect)} at ${actual.second}"
                    .takeIf { actual != expected }
            }
        }
    }

    /** Each module of the leg's Elixir apps with debug info, by name. */
    private fun elixirBeams(): List<Pair<String, File>> =
        appBeams.keys.sorted().map { it to appBeams.getValue(it) }.filter { (_, beam) -> definitions(beam).isNotEmpty() }

    private fun definitions(beam: File) =
        reading(beam) { reader -> (reader.debugInfoResult.valueOrNull as? V1)?.definitions?.definitionList.orEmpty() }

    private fun read(beam: File, mirror: ElixirFile = mirror(beam)): CompiledModule =
        ReadAction.computeBlocking<CompiledModule?, Throwable> {
            val bytes = beam.readBytes()
            val deprecations = (LoadedModules.exports(bytes, beam.path) as ModuleExports.Present).deprecated

            CompiledModule.of(bytes, beam.path, mirror, legLevel(), deprecations)
        }
            ?: throw AssertionError("$beam isn't read")

    private fun <T : Any> reading(beam: File, block: (BeamReader) -> T): T =
        (BeamReader.readResult(beam.readBytes(), beam.path, block) as ReadResult.Present).value

    /** [beam]'s mirror: its decompiled source. */
    private fun mirror(beam: File): ElixirFile =
        ReadAction.computeBlocking<ElixirFile, Throwable> {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(beam)!!

            (PsiManager.getInstance(project).findFile(file) as PsiCompiledFile).decompiledPsiFile as ElixirFile
        }

    /** The clauses of each definition of [mirror]'s module, by kind, name and arity, in source order. */
    private fun mirrorClauses(mirror: ElixirFile): Map<Triple<String, String, Int>, List<Call>> =
        ReadAction.computeBlocking<Map<Triple<String, String, Int>, List<Call>>, Throwable> {
            Modular.callDefinitionClauseCallSequence(mirror.modulars().single())
                .mapNotNull { call ->
                    val definer = CallDefinitionClause.definer(call) ?: return@mapNotNull null
                    val (name, arities) = CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
                        ?: return@mapNotNull null

                    Triple(definer.keyword, name, arities.maximum ?: arities.minimum) to call
                }
                .groupBy({ it.first }, { it.second })
        }

    private companion object {
        val PRIVATE = setOf("defp", "defmacrop")

        /**
         * Every kind of definition; three guards, which nest by `when`; a default arity of its own (`f/1`), one a head
         * without a body declares (`k/1`), and one another definition defines first, on its own line (`max/1`) and on
         * the same line (`n/1`); a default arity whose `super` arguments debug info holds as a byte list (`o/0`); a
         * deprecation; a callback, for which the compiler exports `behaviour_info/1`; a
         * `defmacrop`, which no code holds; and a macro whose arguments debug info holds as a byte list (`z/1`).
         */
        val FIXTURE = """
            defmodule CompiledFixture do
              @callback cb(term) :: term

              def f(a, b \\ 1), do: {a, b}

              def g(1), do: :one
              def g(x) when is_integer(x) when is_float(x), do: x

              def k(a, b \\ 1)
              def k(a, 1), do: a
              def k(a, b), do: {a, b}

              def max(list), do: list
              def max(e, s \\ :s, f \\ :f), do: {e, s, f}

              def n(a), do: a; def n(a, b \\ 1, c \\ 2), do: {a, b, c}

              def o(a \\ 1), do: a

              @deprecated "Use g/1 instead"
              def h(x), do: hidden(x)

              defp hidden(x), do: x

              defmacro m(a) when is_atom(a) when is_integer(a) when is_float(a), do: a
              defmacro m(a), do: a

              defmacrop p(a), do: a

              defmacro z(0), do: :zero
            end
        """.trimIndent()

        /** [FIXTURE] whose module opts out of debug info itself. */
        val FIXTURE_WITHOUT_DEBUG_INFO = FIXTURE.replaceFirst("CompiledFixture do", "CompiledFixture do @compile {:debug_info, false}")
    }
}
