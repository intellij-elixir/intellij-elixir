package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_EXPANDED_AS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.DUPLICATE_MATCH_UNPACKED_ONCE
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_ENTRIES_QUOTED_APART
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_ENTRIES_SORTED
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_IN_VM_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADKEY
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADMAP
import org.elixir_lang.language_level.ElixirLanguageFeature.FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATOR_RIGHT_SIDE_SCOPED
import org.elixir_lang.language_level.ElixirLanguageFeature.INTEGERS_UP_TO_4_MBIT
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_REFUSES_UNESCAPED_RIGHT_SIDE
import org.elixir_lang.language_level.ElixirLanguageFeature.KEY_ERROR_MESSAGE_INSPECTED_WITH_OPTIONS
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_CHECK_THE_FUN_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ARGUMENTS_STOP_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ESCAPE_MARKS_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_DELETE_DEFINITION
import org.elixir_lang.language_level.ElixirLanguageFeature.NEGATIVE_ZERO_IS_DISTINCT
import org.elixir_lang.language_level.ElixirLanguageFeature.PID_IN_FUNCTION_RAISES_MATCH_ERROR
import org.elixir_lang.language_level.ElixirLanguageFeature.PINNED_BINARY_SEGMENT_INFERS_SIZE
import org.elixir_lang.language_level.ElixirLanguageFeature.POST_MODULE_LOCAL_CHECKS_TYPED
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_EVERY_ARITY
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_KEEPS_IMPORTS_META
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_ESCAPED_IN_FIXED_FIELD_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_CALL_EXPANDED_AS_MACRO
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_EXCEPTION_KEY_PRESENT
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUED_ERROR_MESSAGE_FROM_ERROR_INFO
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_SHALLOW_VALIDATED
import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_CALLED_AS_FUNCTION
import org.elixir_lang.language_level.ElixirLanguageFeature.WITH_OPTIONS_BEFORE_LAST_ARGUMENT
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import java.io.File
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A call to a macro with no summary, evaluated from its clauses, from source and from its `.beam`'s `Dbgi`, a row for
 * each clause the evaluator ports. Each row is judged three ways: its output against
 * Elixir's `Macro.expand_once/2` of the call, up to metadata and counters; the probes after the call against Elixir's;
 * and the definition table against what Elixir's `@before_compile` sees.
 */
class InterpreterTest : PlatformTestCase() {
    private val parse: (String) -> ElixirFile = { text ->
        ReadAction.computeBlocking<ElixirFile, Throwable> {
            PsiFileFactory.getInstance(project).createFileFromText("probe.ex", ElixirLanguage, text) as ElixirFile
        }
    }
    private val harness = ProbeHarness(parse)
    private val probes = ExpansionProbes(harness, parse = parse)
    private val sdkClauses = HashMap<String, ModuleClauses?>()

    /** The leg's modules' clauses, read from their `.beam`s as `ProjectModules` reads an SDK's. */
    private val sdk = ModuleClauses.Lookup { module ->
        sdkClauses.getOrPut(module) { appBeams[module]?.let(::clausesOf) }
    }

    override fun setUp() {
        super.setUp()

        appBeams.values.map { it.parentFile.parentFile.parentFile.path }.distinct().forEach {
            VfsRootAccess.allowRootAccess(testRootDisposable, it)
        }
    }

    fun testFromSource() = assertRows(Source.SOURCE)

    fun testFromDebugInfo() = assertRows(Source.DBGI)

    /**
     * Each row that runs from [source] on the leg: its output, how its case module ended and its definition table,
     * against Elixir's, then its probes.
     */
    private fun assertRows(source: Source) {
        val level = legLevel()
        val rows = ROWS.filter { it.runsFrom(source) && it.legs(level) }
        val token = harness.token()
        val oracle = oracle(rows, token)
        val copies = if (source == Source.DBGI) copies(oracle.binaries) else emptyMap()
        val outputs = outputs(rows, token, source, copies)
        val exports = Exports { module -> copies[module]?.exports ?: legExports.of(module) }
        val hook = ProbeHarness.Hook()
        val clauses = ModuleClauses.Lookup { module -> copies[module]?.clauses ?: sdk.of(module) }
        val tokens = mutableListOf(token)
        val ownEnds = mutableMapOf<Int, String>()
        // A raise ends its file, so each row has a file of its own, with only its own modules: Elixir takes longer
        // over each compile the more modules it has compiled and unloaded, and every row's would pass the timeout.
        val expansions = rows.mapIndexed { index, row ->
            val expansion = probes.expandAll(
                listOf(row.body(index, if (source == Source.DBGI) token else TOKEN)),
                if (source == Source.SOURCE) preamble(rows, TOKEN, only = listOf(index)) else "",
                exports,
                hook = hook,
                clauses = clauses,
                file = FILE,
                directories = DIRECTORIES,
            )

            tokens += expansion.layout.token
            if (source == Source.SOURCE && row.ownEnd != null) {
                val module = "Elixir.${module(index, expansion.layout.token)}"

                ownEnds[index] = expansion.modules.single { it.module == module }.let(::ownEnd)
            }
            expansion.cases.single()
        }
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        rows.forEachIndexed { index, row ->
            val pinned = row.pinned(source, level)
            val answer = oracle.answers.getValue(index)

            expected += "${row.id} output: " + when (pinned) {
                null -> rendered(answer, tokens, level, row.unordered)
                UNPORTED -> "unported at `${row.call(index, TOKEN)}`"
                else -> pinned
            }
            actual += "${row.id} output: ${outputs[index]}"
            expected += "${row.id} ended: " + when {
                pinned == UNPORTED -> "stopped at `require ${module(index, TOKEN)}`"
                pinned == OPAQUE || pinned == null && answer.isCaught() -> "stopped at `${row.stopText(index, TOKEN)}`"
                pinned == null && answer.raised() != null -> "raised ${answer.raised()}"
                row.ends(level) != null -> row.ends(level)?.replace(ROW, module(index, TOKEN))
                row.stopsAfter != null -> "stopped at ${row.stopsAfter}"
                else -> "compiled"
            }
            actual += "${row.id} ended: ${ended(expansions[index], row, tokens)}"
            ownEnds[index]?.let {
                expected += "${row.id} own module: ${row.ownEnd}"
                actual += "${row.id} own module: $it"
            }
            row.table?.let {
                expected += "${row.id} expander's table: $it"
                actual += "${row.id} expander's table: ${table(expansions[index].result)}"
            }
        }

        tables(rows, source, expansions, expected, actual)

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))

        probes.assertMatchesElixir(
            rows.indices
                .filter { expansions[it].ended.let { ended -> ended == Ended.Compiled || ended.raises } }
                .associate { rows[it].id to expansions[it] }
        )
    }

    /** Each compiled row's definition table against what the hook saw of its module, compiled [TABLES_BATCH] together. */
    private fun tables(
        rows: List<Row>,
        source: Source,
        expansions: List<ExpansionProbes.CaseExpansion>,
        expected: MutableList<String>,
        actual: MutableList<String>,
    ) {
        for (batch in rows.indices.filter { expansions[it].ended == Ended.Compiled }.chunked(TABLES_BATCH)) {
            val origin = expansions[batch.first()].origin
            val together = probes.expandAll(
                batch.map { expansions[it].origin.body },
                if (source == Source.SOURCE) preamble(rows, TOKEN, only = batch) else "",
                origin.exports,
                hook = origin.hook,
                clauses = origin.clauses,
                file = origin.file,
                directories = origin.directories,
            )
            val attempt = harness.attempt(together.layout)

            batch.forEachIndexed { index, row ->
                val theirs = attempt.batch.hooks[together.layout.caseModule(index)]
                    ?.map { "${it.name}/${it.arity} ${it.kind}" }
                    ?.sorted()
                expected += "${rows[row].id} table: ${theirs ?: "the hook didn't run"}"
                actual += "${rows[row].id} table: ${table(together.cases[index].result)}"
            }
        }
    }

    /** [result]'s definition table, each entry as its name, arity and kind. */
    private fun table(result: ExpansionResult): List<String> =
        result.table.entries
            .map { (nameArity, entry) -> "${nameArity.name}/${nameArity.arity} ${entry.kind.name.lowercase()}" }
            .sorted()

    /** How a row's own module ended: a stop by its kind and the dispatch it stopped at. */
    private fun ownEnd(result: ExpansionResult): String =
        when (val ended = result.ended) {
            is Ended.Stopped -> {
                val opaque = result.opaque.singleOrNull { it.at === ended.at }

                if (opaque != null) "opaque at ${opaque.dispatch.receiver}.${opaque.dispatch.name}" else "stopped"
            }
            else -> ended.toString()
        }

    /** How [expansion]'s case module ended, a stop in the case body by its text. */
    private fun ended(expansion: ExpansionProbes.CaseExpansion, row: Row, tokens: List<String>): String =
        when (val ended = expansion.ended) {
            Ended.Compiled -> "compiled"
            is Ended.Raised -> "raised ${ended.error.kind}"
            is Ended.Crashed -> "crashed ${ended.exception}"
            is Ended.Stopped -> {
                val range = expansion.range(ended.at)
                val body = expansion.case.body
                val at = rendered(ended.at.toOtp(), tokens, legLevel(), row.unordered)
                val after = row.stopsAfter?.takeIf { at.startsWith(it) }

                when {
                    // A node of the macro's output has the call's origin.
                    after != null -> "stopped at $after"
                    range.startOffset >= 0 && range.endOffset <= body.length ->
                        "stopped at `${detokened(range.substring(body), tokens)}`"
                    else -> "stopped at $at"
                }
            }
            Ended.Tainted -> "tainted"
        }

    /**
     * Each of [rows]' calls expanded once as `Macro.expand_once/2` expands it, in a `{token}.Case` that requires the
     * row's modules, with those modules as [source] holds them: compiled from the preamble, or [copies].
     */
    private fun outputs(rows: List<Row>, token: String, source: Source, copies: Map<String, Copy>): List<String> {
        val level = legLevel()
        val loaded = HashMap<String, ModuleExports>()
        val sourceClauses = HashMap<String, ModuleClauses>()

        if (source == Source.SOURCE) {
            val observer = object : ExpansionObserver {
                override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                override fun loaded(result: ExpansionResult, exports: ModuleExports, struct: ModuleStruct) {
                    loaded[result.module] = exports
                }

                override fun clausesLoaded(module: String, clauses: ModuleClauses?) {
                    if (clauses == null) sourceClauses.remove(module) else sourceClauses[module] = clauses
                }
            }

            Expander.expandFile(
                lowered(preamble(rows, token), level),
                Env.empty(level, legKernel),
                level,
                legExports,
                legStructs,
                observer,
                file = FILE,
                directories = DIRECTORIES,
                clauses = sdk,
            )
        }

        val exports = Exports { module -> loaded[module] ?: copies[module]?.exports ?: legExports.of(module) }
        val clauses = ModuleClauses.Lookup { module ->
            sourceClauses[module] ?: copies[module]?.clauses ?: sdk.of(module)
        }
        val module = "Elixir.$token.Case"
        val empty = Env.empty(level, legKernel)

        return rows.mapIndexed { index, row ->
            val env = empty.copy(
                requires = (empty.requires + row.requires(index, token).map { "Elixir.$it" }).distinct().sorted(),
                contextModules = listOf(module),
                module = module,
            )
            val run = Run(
                level,
                ExpansionObserver.NONE,
                exports,
                legStructs,
                file = FILE,
                directories = DIRECTORIES,
                clauses = clauses,
            )
            val call = lowered(row.call(index, token), level)

            when (val expanded = macroExpandOnce(call, ExState.empty(level), env, run)) {
                is MacroExpanded.Node ->
                    if (expanded.expanded) rendered(expanded.node.toOtp(), listOf(token), level, row.unordered) else "unexpanded"
                MacroExpanded.Dir -> "dir"
                is MacroExpanded.Stopped -> when (val stop = expanded.expansion) {
                    is Expansion.Opaque -> OPAQUE
                    is Expansion.Error ->
                        inspect(OtpErlangTuple(arrayOf(OtpErlangAtom("raise"), OtpErlangAtom(stop.kind))))
                    is Expansion.Unported ->
                        if (stop.at === call) {
                            "unported at `${detokened(row.call(index, token), listOf(token))}`"
                        } else {
                            "unported " + rendered(stop.at.toOtp(), listOf(token), level, row.unordered)
                        }
                    is Expansion.Expanded -> "expanded"
                }
            }
        }
    }

    private fun lowered(text: String, level: ElixirLanguageLevel): ElixirAst {
        val file = parse(text)

        return ReadAction.computeBlocking<ElixirAst, Throwable> { Lowering.lower(file, level) }
    }

    /**
     * Elixir's `Macro.expand_once/2` of each of [rows]' calls, in `{token}.Case`, once the rows' modules are compiled
     * with debug info and loaded, and each of those modules' binary, compiled [ORACLE_BATCH] rows together.
     */
    private fun oracle(rows: List<Row>, token: String): Oracle {
        val answers = HashMap<Int, OtpErlangObject>()
        val binaries = HashMap<String, ByteArray>()

        for (batch in rows.indices.chunked(ORACLE_BATCH)) {
            val requires = batch.flatMap { rows[it].requires(it, token) }.distinct()
            val expansions = batch.map { index ->
                """
                IntellijElixir.Quoter.Probe.send(__ENV__, {:row, $index, try do
                  Macro.expand_once(quote(do: ${rows[index].call(index, token)}), __ENV__)
                rescue
                  e -> {:raise, e.__struct__}
                catch
                  kind, _ -> {:caught, kind}
                end})
                """.trimIndent()
            }
            // The quoter unloads `{token}.Case` after each compile, so the next batch defines it again.
            val preamble = elixirString(preamble(rows, token, only = batch))
            val compiled = Quoter.compile(
                "for {module, binary} <- Code.compile_string($preamble) do\n" +
                    "  IntellijElixir.Quoter.Probe.send(__ENV__, {:module, module, binary})\n" +
                    "end\n" +
                    "defmodule $token.Case do\n" +
                    requires.joinToString("") { "require $it\n" } +
                    expansions.joinToString("\n") +
                    "\nend\n",
                COMPILE_TIMEOUT,
            )

            val raised = (compiled.status as? OtpErlangTuple)?.elements()?.lastOrNull() as? OtpErlangBinary

            assertEquals(
                "oracle: ${raised?.binaryValue()?.decodeToString()} ${compiled.diagnostics.map(::inspect)}",
                OtpErlangAtom("ok"),
                compiled.status,
            )

            for (message in compiled.messages) {
                val elements = (message as OtpErlangTuple).elements()

                if (elements[0] == OtpErlangAtom("row")) {
                    answers[(elements[1] as OtpErlangLong).intValue()] = elements[2]
                } else {
                    binaries[(elements[1] as OtpErlangAtom).atomValue()] =
                        (elements[2] as OtpErlangBinary).binaryValue()
                }
            }
        }

        return Oracle(answers, binaries)
    }

    /** Each of [binaries] written as a `.beam`, and read as its clauses and its exports. */
    private fun copies(binaries: Map<String, ByteArray>): Map<String, Copy> {
        val directory = FileUtil.createTempDirectory("interpreter", null)

        VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)

        return binaries.mapValues { (module, binary) ->
            val beam = File(directory, "$module.beam").apply { writeBytes(binary) }

            Copy(
                clausesOf(beam) ?: throw AssertionError("$module's clauses aren't read"),
                LoadedModules.exports(binary, beam.path),
            )
        }
    }

    private fun clausesOf(beam: File): ModuleClauses? =
        ReadAction.computeBlocking<ModuleClauses?, Throwable> {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(beam)!!
            val compiled = PsiManager.getInstance(project).findFile(file) as PsiCompiledFile
            val mirror = compiled.decompiledPsiFile as ElixirFile
            val bytes = beam.readBytes()
            val deprecations = (LoadedModules.exports(bytes, beam.path) as? ModuleExports.Present)?.deprecated

            deprecations
                ?.let { CompiledModule.of(bytes, beam.path, mirror, legLevel(), it) }
                ?.let { ModuleClauses.of(it, file) }
        }

    private enum class Source { SOURCE, DBGI }

    /** How a row runs from source. */
    private enum class SourceRun {
        ANSWERS,

        /** Its macro is the leg's, which only a `.beam` holds. */
        NONE,
    }

    private class Oracle(val answers: Map<Int, OtpErlangObject>, val binaries: Map<String, ByteArray>)

    private class Copy(val clauses: ModuleClauses, val exports: ModuleExports)

    /**
     * @property defs the body of the row's module, `{row}`; empty where its macro is the leg's
     * @property call the call `Macro.expand_once/2` expands
     * @property body the case body
     * @property stop where [body] stops when the call is declined: the call's text, by default
     * @property requires the modules the call's env requires
     * @property pins the output the evaluator gives where it isn't Elixir's, from a source on a leg: [OPAQUE] where it
     *   declines by one of its rules, [UNPORTED] where the call's module isn't loaded
     * @property stopsAfter where the case module stops after the call, at a gap outside the evaluator, as the start of
     *   the node rendered
     * @property unordered whether the output holds an escaped map, whose pairs Elixir lists in an order the VM keeps
     * @property ownEnd how the row's own module ends from source, where it doesn't compile
     * @property helper a module defined before the row's, for the row's module to require or import
     * @property ends how the case module ends on a leg, where it doesn't compile though `Macro.expand_once/2` returns
     * @property table the definitions the expander stores for the case module, for a row whose module raises
     */
    private class Row(
        val id: String,
        val defs: String,
        private val call: String,
        private val body: String,
        private val stop: String,
        private val requires: List<String>,
        private val source: SourceRun,
        val legs: (ElixirLanguageLevel) -> Boolean,
        private val pins: (Source, ElixirLanguageLevel) -> String?,
        val stopsAfter: String?,
        val unordered: Boolean = false,
        val ownEnd: String? = null,
        val helper: String = "",
        val ends: (ElixirLanguageLevel) -> String? = { null },
        val table: String? = null,
    ) {
        fun runsFrom(source: Source) = source == Source.DBGI || this.source != SourceRun.NONE

        fun pinned(source: Source, level: ElixirLanguageLevel): String? =
            pins(source, level)

        fun call(index: Int, token: String) = named(call, index, token)

        fun body(index: Int, token: String) = named(body, index, token)

        fun stopText(index: Int, token: String) = named(stop, index, token)

        fun requires(index: Int, token: String) = requires.map { named(it, index, token) }

        private fun named(text: String, index: Int, token: String) = text.replace(ROW, module(index, token))
    }

    /**
     * [term] with every node's metadata emptied, as Elixir's `inspect` prints it, each of [tokens] as `{token}`. Where
     * [ESCAPED_MAP_IN_VM_ORDER] holds and [unordered], as for a row that escapes a map, a map's pairs are sorted, as
     * Elixir's order is the atoms' index in the VM.
     */
    private fun rendered(term: OtpErlangObject, tokens: List<String>, level: ElixirLanguageLevel, unordered: Boolean): String =
        detokened(
            inspect(withoutMeta(term).let { if (unordered && ESCAPED_MAP_IN_VM_ORDER.isSufficient(level)) pairsSorted(it) else it }),
            tokens,
        )

    private fun pairsSorted(term: OtpErlangObject): OtpErlangObject =
        when (term) {
            is OtpErlangTuple -> {
                val elements = term.elements().map(::pairsSorted)
                val pairs = elements.getOrNull(2) as? OtpErlangList

                if (term.arity() == 3 && elements[0] == OtpErlangAtom("%{}") && pairs != null && pairs.lastTail == null) {
                    val sorted = pairs.elements().sortedBy { pair -> inspect((pair as? OtpErlangTuple)?.elementAt(0) ?: pair) }

                    OtpErlangTuple(arrayOf(elements[0], elements[1], OtpErlangList(sorted.toTypedArray())))
                } else {
                    OtpErlangTuple(elements.toTypedArray())
                }
            }
            is OtpErlangList -> {
                val elements = term.elements().map(::pairsSorted).toTypedArray()

                term.lastTail?.let { OtpErlangList(elements, pairsSorted(it)) } ?: OtpErlangList(elements)
            }
            else -> term
        }

    private fun detokened(text: String, tokens: List<String>): String =
        tokens.fold(text) { acc, token -> acc.replace(token, TOKEN) }

    private fun withoutMeta(term: OtpErlangObject): OtpErlangObject =
        when (term) {
            is OtpErlangTuple ->
                if (term.arity() == 3 && term.elementAt(1) is OtpErlangList) {
                    OtpErlangTuple(arrayOf(withoutMeta(term.elementAt(0)), OtpErlangList(), withoutMeta(term.elementAt(2))))
                } else {
                    OtpErlangTuple(term.elements().map(::withoutMeta).toTypedArray())
                }
            is OtpErlangList -> {
                val elements = term.elements().map(::withoutMeta).toTypedArray()

                term.lastTail?.let { OtpErlangList(elements, withoutMeta(it)) } ?: OtpErlangList(elements)
            }
            // As `elixir_quote:fun_to_quoted/1` gives it, which expanding a macro's result does.
            is OtpErlangExternalFun -> {
                val receiver = OtpErlangList(arrayOf(OtpErlangAtom(term.module), OtpErlangAtom(term.function)))
                val dot = OtpErlangTuple(arrayOf(OtpErlangAtom("."), OtpErlangList(), receiver))
                val callee = OtpErlangTuple(arrayOf(dot, OtpErlangList(), OtpErlangList()))
                val arguments = OtpErlangList(arrayOf(callee, OtpErlangLong(term.arity.toLong())))
                val slash = OtpErlangTuple(arrayOf(OtpErlangAtom("/"), OtpErlangList(), arguments))

                OtpErlangTuple(arrayOf(OtpErlangAtom("&"), OtpErlangList(), OtpErlangList(arrayOf(slash))))
            }
            is OtpErlangFun -> OtpErlangAtom("#Function")
            // A process or reference is one of the VM's own.
            is OtpErlangPid -> OtpErlangAtom("#PID")
            is OtpErlangRef -> OtpErlangAtom("#Reference")
            else -> term
        }

    private fun OtpErlangObject.isCaught(): Boolean =
        (this as? OtpErlangTuple)?.elementAt(0) == OtpErlangAtom("caught")

    /** The module of the exception Elixir's expansion raised, as atom text. */
    private fun OtpErlangObject.raised(): String? =
        (this as? OtpErlangTuple)
            ?.takeIf { it.arity() == 2 && it.elementAt(0) == OtpErlangAtom("raise") }
            ?.let { (it.elementAt(1) as OtpErlangAtom).atomValue() }

    private companion object {
        const val TOKEN = "{token}"
        const val ROW = "{row}"
        const val OPAQUE = "opaque"
        const val UNPORTED = "unported"
        const val FILE = "probe.ex"
        val DIRECTORIES = CompileDirectories { "/" }
        val COMPILE_TIMEOUT = 60.seconds

        /**
         * The rows compiled together for their tables: on 1.13.4, 230 rows from source compiled in 15 s on a
         * workstation and in over 40 s on CI, past the quoter's bound.
         */
        const val TABLES_BATCH = 40

        /**
         * The rows the oracle compiles together: on 1.13.4, 437 rows took 29 s in one compile on a workstation and over
         * 60 s on CI, past the quoter's bound, and under 3 s for each 100 on a workstation.
         */
        const val ORACLE_BATCH = 100
        val EVERY_LEG: (ElixirLanguageLevel) -> Boolean = { true }
        val UNPINNED: (Source, ElixirLanguageLevel) -> String? = { _, _ -> null }
        val DECLINED: (Source, ElixirLanguageLevel) -> String? = { _, _ -> OPAQUE }

        /** From source the expander declines where [OPAQUE] says; `Dbgi` holds what Elixir compiled. */
        val DECLINED_FROM_SOURCE: (Source, ElixirLanguageLevel) -> String? = { source, _ ->
            OPAQUE.takeIf { source == Source.SOURCE }
        }

        /**
         * [DECLINED_FROM_SOURCE] for a row whose module calls a function of a helper module that stopped. Before
         * [UNREQUIRED_MACRO_CALLED_AS_FUNCTION] that call needs the stopped module's macros, so from source the row's
         * module stops at it ([UNPORTED]).
         */
        val CALLS_A_STOPPED_MODULE: (Source, ElixirLanguageLevel) -> String? = { source, level ->
            DECLINED_FROM_SOURCE(source, level)?.let { if (UNREQUIRED_MACRO_CALLED_AS_FUNCTION.isSufficient(level)) it else UNPORTED }
        }

        /**
         * A `%module{}` that doesn't name every field stops its module where the leg's debug info doesn't record which
         * fields are enforced, as on 1.18.
         */
        fun buildsPartOf(module: String): (Source, ElixirLanguageLevel) -> String? = { source, _ ->
            UNPORTED.takeIf { source == Source.SOURCE && (legStructs.of(module) as? ModuleStruct.Present)?.enforced == Enforced.Unknown }
        }

        /** A macro body reading `key` of `1`, `nil` and `true`, each exception rescued. */
        val FIELD_ON_NON_MAPS = """
            defmacro m do
              for x <- [1, nil, true] do
                try do
                  x.key
                rescue
                  e -> e.__struct__
                end
              end
            end
        """.trimIndent()

        /** The OTP 25 `lists` functions check their fun first, so below it an error is the VM's to say. */
        val LISTS_ERROR_BELOW_OTP_25: (Source, ElixirLanguageLevel) -> String? = { _, level ->
            OPAQUE.takeUnless { LISTS_CHECK_THE_FUN_FIRST.isSufficient(level) }
        }

        /**
         * Below OTP 27, which of two keys `=:=` can't tell apart a map keeps depends on how the VM holds the map and on
         * what the compiler folded.
         */
        val KEY_KEPT_BELOW_OTP_27: (Source, ElixirLanguageLevel) -> String? = { _, level ->
            OPAQUE.takeUnless { NEGATIVE_ZERO_IS_DISTINCT.isSufficient(level) }
        }

        /** Below OTP 25, a fun with no clause matching raises as the compiler inlined it. */
        val FN_CLAUSE_BELOW_OTP_25: (Source, ElixirLanguageLevel) -> String? = { _, level ->
            OPAQUE.takeUnless { FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE.isSufficient(level) }
        }

        /** The test's `.beam`s are compiled in the quoter VM by the leg's Elixir, which holds no `Elixir.` prefix. */
        fun module(index: Int, token: String) = "$token.R$index"

        /** Each row's helper and module, in one file, or only those of the rows at [only]. */
        fun preamble(rows: List<Row>, token: String, only: List<Int>? = null): String =
            rows.withIndex().filter { (index, row) -> row.defs.isNotEmpty() && (only == null || index in only) }
                .joinToString("\n") { (index, row) ->
                    val module = module(index, token)

                    (if (row.helper.isEmpty()) "" else "${row.helper.replace(ROW, module)}\n") +
                        "defmodule $module do\n${row.defs.replace(ROW, module)}\nend"
                }

        /** A row whose case body binds the output of [call], a call of the row's module's. */
        fun macro(
            id: String,
            defs: String,
            call: String = "m()",
            source: SourceRun = SourceRun.ANSWERS,
            legs: (ElixirLanguageLevel) -> Boolean = EVERY_LEG,
            pins: (Source, ElixirLanguageLevel) -> String? = UNPINNED,
            stop: String = "$ROW.$call",
            unordered: Boolean = false,
            ownEnd: String? = null,
            helper: String = "",
            ends: (ElixirLanguageLevel) -> String? = { null },
        ) = Row(
            id,
            defs.trimIndent(),
            "$ROW.$call",
            "require $ROW\n_ = $ROW.$call",
            stop,
            listOf(ROW),
            source,
            legs,
            pins,
            null,
            unordered,
            ownEnd,
            helper.trimIndent(),
            ends,
        )

        /** A row whose case body `use`s the row's module with [options]. */
        fun use(
            id: String,
            defs: String,
            options: String = "",
            source: SourceRun = SourceRun.ANSWERS,
            stopsAfter: String? = null,
        ): Row {
            val use = "use $ROW" + if (options.isEmpty()) "" else ", $options"
            val call = "$ROW.__using__(${options.ifEmpty { "[]" }})"

            return Row(id, defs.trimIndent(), call, use, use, listOf(ROW), source, EVERY_LEG, UNPINNED, stopsAfter)
        }

        /** A row whose case body `use`s the leg's [module]. */
        fun leg(module: String, options: String = "", stopsAfter: String? = null): Row {
            val use = "use $module" + if (options.isEmpty()) "" else ", $options"
            val call = "$module.__using__(${options.ifEmpty { "[]" }})"

            return Row(
                "use:leg-" + module.lowercase(),
                "",
                call,
                use,
                use,
                listOf(module),
                SourceRun.NONE,
                EVERY_LEG,
                UNPINNED,
                stopsAfter,
            )
        }

        /** The legs where `Module.delete_definition/2` exists. */
        val DELETES_DEFINITIONS: (ElixirLanguageLevel) -> Boolean = { MODULE_DELETE_DEFINITION.isSufficient(it) }

        /** A helper module whose macro answers `Module.defines?/2` of its caller's module, where it expands. */
        val DEFINES = """
            defmodule {row}.D do
              defmacro d(name, arity), do: Module.defines?(__CALLER__.module, {name, arity})
            end
        """.trimIndent()

        /** A helper module whose `@before_compile` defines `r/0` as whether its module defines `f/0`. */
        val DEFINES_AT_CALLBACK = """
            defmodule {row}.CC do
              defmacro __before_compile__(env) do
                quote(do: def(r, do: unquote(Module.defines?(env.module, {:f, 0}))))
              end
            end
        """.trimIndent()

        /** [DEFINES], then a module for each of [bodies], each defining `f/0` before them and `g/0` after. */
        fun definesAround(vararg bodies: Pair<String, String>): String =
            DEFINES + bodies.joinToString("") { (module, body) ->
                "\n\ndefmodule {row}.$module do\n  require {row}.D\n  def f, do: 1\n$body\n  def g, do: {row}.D.d(:f, 0)\nend"
            }

        /** A helper module whose macro doubles its argument into a pair. */
        val TWICE = """
            defmodule {row}.H do
              defmacro twice(x), do: quote(do: {unquote(x), unquote(x)})
            end
        """.trimIndent()

        /** A helper module whose macro returns a pair of `quote`s. */
        val PAIR = """
            defmodule {row}.H do
              defmacro pair, do: quote(do: {quote(do: 1), quote(do: 2)})
            end
        """.trimIndent()

        /** A helper struct whose `Access` fetch answers `1`. */
        val FETCHES = """
            defmodule {row}.S do
              defstruct [:a]
              def fetch(_, _), do: {:ok, 1}
            end
        """.trimIndent()

        /** A helper defining the macro `h/0`, which Elixir exports as `MACRO-h/1`. */
        val DEFINES_A_MACRO = """
            defmodule {row}.H do
              defmacro h, do: 1
            end
        """.trimIndent()

        /** A macro defining `a/0` where its argument is marked `stop_generated`, else `b/0`. */
        val STOP_GENERATED = """
            defmacro m(x) do
              if Keyword.get(elem(x, 1), :stop_generated) do
                quote(do: def(a, do: 1))
              else
                quote(do: def(b, do: 1))
              end
            end
        """.trimIndent()

        /** A `def` whose body unquotes `Macro.escape(opts)`, a fragment the walk doesn't evaluate. */
        const val UNQUOTE_ESCAPED_OPTS = "{:unquote, [], [{{:., [], [{:__aliases__, [], [:Macro]}, :escape]}"

        val ROWS: List<Row> = listOf(
            // `elixir_erl_pass:translate/3`
            macro(
                "form:wildcard",
                """
                defmacro m do
                  _ = :a
                  :b
                end
                """,
            ),
            macro(
                "form:match",
                """
                defmacro m(x) do
                  {a, b} = x
                  [b, a]
                end
                """,
                "m({1, 2})",
            ),
            macro(
                "form:match-badmatch",
                """
                defmacro m(x) do
                  {a, _} = x
                  a
                end
                """,
                "m(3)",
            ),
            macro("form:tuple", "defmacro m(x), do: Macro.escape({x, 2, 3})", "m(1)"),
            macro("form:map", "defmacro m(x), do: Macro.escape(%{a: x, b: 2})", "m(1)", unordered = true),
            macro(
                "form:map-update",
                """
                defmacro m do
                  x = %{a: 1, b: 2}
                  Macro.escape(%{x | a: 3})
                end
                """,
                unordered = true,
            ),
            macro(
                "form:map-update-badkey",
                """
                defmacro m do
                  x = %{a: 1}
                  Macro.escape(%{x | b: 2})
                end
                """,
            ),
            macro(
                "form:struct",
                """defmacro m, do: Macro.escape(%URI{host: "h"})""",
                pins = buildsPartOf("Elixir.URI"),
                unordered = true,
            ),
            macro(
                "form:struct-update",
                """
                defmacro m do
                  u = %URI{host: "h"}
                  Macro.escape(%URI{u | port: 1})
                end
                """,
                pins = buildsPartOf("Elixir.URI"),
                unordered = true,
            ),
            macro(
                "form:struct-update-other",
                """
                defmacro m do
                  x = %{host: 1}
                  Macro.escape(%URI{x | host: "x"})
                end
                """,
            ),
            macro(
                "form:struct-pattern",
                """
                defmacro m do
                  %URI{host: h} = %URI{host: "h"}
                  h
                end
                """,
                pins = buildsPartOf("Elixir.URI"),
            ),
            macro(
                "form:struct-pattern-unknown-name",
                """
                @s :erlang.hd([URI])
                defmacro m do
                  case %{__struct__: @s} do
                    %name{} -> name
                    _ -> :none
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro("form:bitstring", """defmacro m(a), do: <<a::8, "b">>""", "m(97)"),
            macro("form:bitstring-utf8", "defmacro m(a), do: <<a::utf8>>", "m(233)"),
            macro(
                "form:bitstring-size",
                """
                defmacro m(a) do
                  n = 16
                  Macro.escape(<<a::size(n)>>)
                end
                """,
                "m(97)",
            ),
            macro(
                "form:bitstring-match-size-past-an-int",
                """
                defmacro m do
                  n = 4_294_967_296

                  case <<1>> do
                    <<x::size(n), _::bits>> -> {:matched, x}
                    _ -> :no_match
                  end
                end
                """,
            ),
            // A size that raises fails its pattern, as a guard that raises does, and the next clause is tried.
            macro(
                "form:bitstring-match-size-raises",
                """
                defmacro m do
                  n = 0

                  case <<1>> do
                    <<x::size(div(8, n))>> -> {:matched, x}
                    _ -> :no_match
                  end
                end
                """,
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            // From 1.14 a size reads a field as a guard does: `map_get/2`, which a module's name fails.
            macro(
                "form:bitstring-match-size-field",
                """
                defmacro m do
                  module = __MODULE__

                  case <<1>> do
                    <<x::size(module.k)>> -> {:matched, x}
                    _ -> :no_match
                  end
                end

                def k, do: 8
                """,
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            macro(
                "form:bitstring-binary-size-past-an-int",
                """
                defmacro m do
                  b = <<1>>
                  n = 4_294_967_296
                  <<b::binary-size(n)>>
                end
                """,
            ),
            macro(
                "form:bitstring-integer-size-past-a-long",
                """
                defmacro m do
                  n = 18_446_744_073_709_551_616
                  <<0::size(n)>>
                end
                """,
                pins = DECLINED,
            ),
            macro(
                "form:bitstring-binary-unit",
                """
                defmacro m do
                  b = <<1, 2>>
                  Macro.escape(<<b::binary-unit(16)>>)
                end
                """,
            ),
            macro(
                "form:bitstring-binary-unit-unaligned",
                """
                defmacro m do
                  b = <<1>>
                  <<b::binary-unit(16)>>
                end
                """,
            ),
            macro(
                "form:bitstring-match-binary-unit",
                """
                defmacro m do
                  case <<1>> do
                    <<x::binary-unit(16)>> -> {:matched, Macro.escape(x)}
                    _ -> :no_match
                  end
                end
                """,
            ),
            // A pinned segment followed by another takes its size from the pinned value.
            macro(
                "form:bitstring-match-pinned-binary",
                """
                defmacro m do
                  x = "cd"

                  case "cdef" do
                    <<^x::binary, rest::binary>> -> rest
                    _ -> :miss
                  end
                end
                """,
                legs = PINNED_BINARY_SEGMENT_INFERS_SIZE::isSufficient,
            ),
            macro(
                "form:bitstring-match-pinned-bitstring",
                """
                defmacro m do
                  x = <<1::3>>

                  case <<1::3, 5::5>> do
                    <<^x::bitstring, rest::bitstring>> -> Macro.escape(rest)
                    _ -> :miss
                  end
                end
                """,
                legs = PINNED_BINARY_SEGMENT_INFERS_SIZE::isSufficient,
            ),
            // A nested bitstring whose whole spec is `binary` or `bitstring` is spliced into the pattern it is in.
            macro(
                "form:bitstring-match-nested-binary",
                """
                defmacro m do
                  case <<1, 2>> do
                    <<(<<x::8>>)::binary, y::8>> -> {x, y}
                    _ -> :miss
                  end
                end
                """,
            ),
            macro(
                "form:bitstring-match-nested-bitstring",
                """
                defmacro m do
                  case <<1::3, 5::5>> do
                    <<(<<x::3>>)::bitstring, y::5>> -> {x, y}
                    _ -> :miss
                  end
                end
                """,
            ),
            macro(
                "form:bitstring-match-nested-empty",
                """
                defmacro m do
                  case <<1>> do
                    <<(<<>>)::binary, y::8>> -> y
                    _ -> :miss
                  end
                end
                """,
            ),
            // A nested bitstring a macro gives in a block of one expression is spliced in as that expression.
            macro(
                "form:bitstring-nested-block",
                """
                defmacro b, do: {:__block__, [], [quote(do: <<1>>)]}
                defmacro m, do: <<b()::binary, 2>>
                """,
            ),
            macro(
                "form:bitstring-match-nested-block",
                """
                defmacro bx, do: {:__block__, [], [quote(do: <<var!(x)::8>>)]}

                defmacro m do
                  case <<1, 2>> do
                    <<bx()::binary, y::8>> -> {x, y}
                    _ -> :miss
                  end
                end
                """,
            ),
            // A binary segment's unit, not a byte, is what its bits come in.
            macro(
                "form:bitstring-match-binary-sub-byte-unit",
                """
                defmacro m do
                  case <<15::4>> do
                    <<x::binary-size(4)-unit(1)>> -> bit_size(x)
                    _ -> :no_match
                  end
                end
                """,
            ),
            macro(
                "form:bitstring-match-binary-sub-byte-unit-tail",
                """
                defmacro m do
                  case <<15::4>> do
                    <<x::binary-unit(1)>> -> bit_size(x)
                    _ -> :no_match
                  end
                end
                """,
            ),
            macro(
                "form:block",
                """
                defmacro m do
                  :a
                  :b
                end
                """,
            ),
            macro("form:caller", "defmacro m, do: __CALLER__.module"),
            // A call another macro's `quote` returns has only the line linify gives it.
            macro(
                "form:caller-line-of-a-quoted-call",
                """
                defmacro l, do: quote(do: def(unquote(if(__CALLER__.line == 0, do: :zero, else: :line))(), do: 1))
                defmacro m, do: quote(do: unquote(__MODULE__).l())
                """,
            ),
            // A map whose `__exception__` isn't `true` is an exception from 1.20.0-rc.5 only.
            macro(
                "form:rescue-exception-key",
                """
                defmacro m do
                  try do
                    :erlang.error(%{__struct__: ArgumentError, __exception__: :yes, message: "m"})
                  rescue
                    ArgumentError -> :argument_error
                    e -> {:other, e.__struct__}
                  end
                end
                """,
            ),
            // Which Erlang error a record is, is its tag's to say.
            macro(
                "form:rescue-unknown-record-tag",
                """
                @r :erlang.hd([:badmatch])
                defmacro m do
                  try do
                    :erlang.error({@r, 1})
                  rescue
                    MatchError -> :match
                    _ -> :other
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // Which variables the caller has bound is the expansion's to say.
            macro("form:caller-vars", "defmacro m, do: Macro.Env.has_var?(__CALLER__, {:x, nil})", pins = DECLINED),
            macro(
                "form:stacktrace",
                """
                defmacro m do
                  try do
                    :erlang.error(:x)
                  rescue
                    _ -> :erlang.is_list(__STACKTRACE__)
                  end
                end
                """,
            ),
            // Before 1.14, `System.stacktrace()` in a `rescue` is `__STACKTRACE__`.
            macro(
                "form:system-stacktrace",
                """
                defmacro m do
                  try do
                    :erlang.error(:x)
                  rescue
                    _ -> :erlang.is_list(System.stacktrace())
                  end
                end
                """,
            ),
            macro(
                "form:system-stacktrace-imported",
                """
                import System, only: [stacktrace: 0]

                defmacro m do
                  try do
                    :erlang.error(:x)
                  rescue
                    _ -> :erlang.is_list(stacktrace())
                  end
                end
                """,
            ),
            macro("form:capture-remote", "defmacro m, do: &Enum.map/2"),
            macro(
                "form:capture-local",
                """
                defmacro m do
                  f = &h/1
                  f.(2)
                end

                def h(x), do: :erlang.*(x, 3)
                """,
            ),
            // Two captures of one local function are one map key.
            macro(
                "form:capture-local-map-key",
                """
                defmacro m do
                  a = &h/1
                  b = &h/1
                  :erlang.map_size(%{a => 1, b => 2})
                end

                def h(x), do: x
                """,
                pins = DECLINED,
            ),
            macro(
                "form:fn",
                """
                defmacro m do
                  f = fn x -> :erlang.+(x, 1) end
                  f.(1)
                end
                """,
            ),
            macro(
                "form:fn-clauses",
                """
                defmacro m do
                  f = fn
                    :a -> 1
                    _ -> 2
                  end

                  [f.(:a), f.(:b)]
                end
                """,
            ),
            macro(
                "form:fn-call",
                """
                defmacro m do
                  f = fn x, y -> [y, x] end
                  f.(1, 2)
                end
                """,
            ),
            macro(
                "form:fn-badarity",
                """
                defmacro m do
                  f = fn x -> x end
                  f.(1, 2)
                end
                """,
            ),
            macro(
                "form:fn-clause",
                "defmacro m(x), do: Enum.map([x], fn :a -> 1 end)",
                "m(:b)",
                pins = FN_CLAUSE_BELOW_OTP_25,
            ),
            macro(
                "form:fn-clause-bound",
                """
                defmacro m do
                  f = fn :a -> 1 end
                  f.(:b)
                end
                """,
                pins = FN_CLAUSE_BELOW_OTP_25,
            ),
            // A fun's name is the compiler's, from its fun counter and what it inlines.
            macro(
                "form:fn-clause-name",
                """
                defmacro m do
                  f = fn :a -> 1 end
                  g = fn :b -> f.(:a) end

                  try do
                    g.(:c)
                  rescue
                    e in FunctionClauseError -> e.function
                  end
                end
                """,
                pins = DECLINED,
            ),
            macro(
                "form:cond",
                """
                defmacro m(x) do
                  cond do
                    :erlang.==(x, 1) -> :one
                    true -> :other
                  end
                end
                """,
                "m(2)",
            ),
            macro(
                "form:cond-truthy",
                """
                defmacro m do
                  cond do
                    nil -> 1
                    false -> 2
                    :yes -> 3
                  end
                end
                """,
            ),
            macro(
                "form:cond-no-clause",
                """
                defmacro m(x) do
                  cond do
                    :erlang.==(x, 1) -> :one
                  end
                end
                """,
                "m(2)",
            ),
            macro(
                "form:case",
                """
                defmacro m(x) do
                  case x do
                    {:a, v} -> v
                    _ -> :none
                  end
                end
                """,
                "m({:a, 1})",
            ),
            macro(
                "form:case-guard",
                """
                defmacro m(x) do
                  case x do
                    v when :erlang.is_integer(v) -> :int
                    _ -> :other
                  end
                end
                """,
                "m(1)",
            ),
            macro(
                "form:case-clause",
                """
                defmacro m(x) do
                  case x do
                    :a -> 1
                  end
                end
                """,
                "m(:b)",
            ),
            macro(
                "form:case-attribute-pattern",
                """
                @tag {:ok, [1]}

                defmacro m(x) do
                  case x do
                    @tag -> :yes
                    _ -> :no
                  end
                end
                """,
                "m({:ok, [1]})",
            ),
            macro(
                "form:case-attribute-pattern-unmatched",
                """
                @tag {:ok, [1]}

                defmacro m(x) do
                  case x do
                    @tag -> :yes
                    _ -> :no
                  end
                end
                """,
                "m({:ok, [1.0]})",
            ),
            // `expand_remote/8` folds a signed number in a pattern into the number.
            macro("form:case-negative-integer-pattern", "defmacro m, do: (case -1 do -1 -> :yes; _ -> :no end)"),
            macro("form:case-negative-integer-pattern-unmatched", "defmacro m, do: (case 1 do -1 -> :yes; _ -> :no end)"),
            macro("form:case-negative-float-pattern", "defmacro m, do: (case -1.5 do -1.5 -> :yes; _ -> :no end)"),
            macro("form:case-positive-integer-pattern", "defmacro m, do: (case 2 do +2 -> :yes; _ -> :no end)"),
            macro("form:case-negative-zero-pattern", "defmacro m, do: (case 0.0 do -0.0 -> :yes; _ -> :no end)"),
            macro(
                "form:try-rescue",
                """
                defmacro m do
                  try do
                    :erlang.error(:badarg)
                  rescue
                    e in ArgumentError -> Macro.escape(e)
                  end
                end
                """,
                unordered = true,
            ),
            macro(
                "form:try-rescue-erlang",
                """
                defmacro m do
                  try do
                    :erlang.binary_to_atom(1, :utf8)
                  rescue
                    ArgumentError -> :badarg
                  end
                end
                """,
            ),
            macro(
                "form:try-rescue-keyerror",
                """
                defmacro m do
                  try do
                    Keyword.fetch!([], :a)
                  rescue
                    e in KeyError -> e.key
                  end
                end
                """,
            ),
            macro(
                "form:try-rescue-erlang-error",
                """
                defmacro m do
                  try do
                    :erlang.error(:oops)
                  rescue
                    e in ErlangError -> e.original
                  end
                end
                """,
            ),
            macro(
                "form:try-rescue-variable",
                """
                defmacro m do
                  try do
                    :erlang.error(:oops)
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
            ),
            macro(
                "form:try-rescue-unmatched",
                """
                defmacro m do
                  try do
                    :erlang.error(:oops)
                  rescue
                    KeyError -> :key
                  end
                end
                """,
            ),
            macro(
                "form:try-catch",
                """
                defmacro m do
                  try do
                    :erlang.throw(:t)
                  catch
                    :throw, v -> v
                  end
                end
                """,
            ),
            macro(
                "form:try-catch-value",
                """
                defmacro m do
                  try do
                    :erlang.throw(:t)
                  catch
                    v -> [v]
                  end
                end
                """,
            ),
            macro(
                "form:try-catch-error",
                """
                defmacro m do
                  try do
                    :erlang.error(:e)
                  catch
                    :error, v -> v
                  end
                end
                """,
            ),
            // `error/2` gives the raising function's frame the arguments it is given, where they are a list.
            macro(
                "pure:error-args",
                """
                def top(args) do
                  try do
                    :erlang.error(:e, args)
                  catch
                    :error, _ -> :erlang.element(3, :erlang.hd(__STACKTRACE__))
                  end
                end

                defmacro m, do: Macro.escape([top([:shown]), top([]), top([1 | 2]), top(:none), top(:bad)])
                """,
            ),
            // `error/3` also puts the last `error_info` in the frame's location, where every option is one with a map.
            macro(
                "pure:error-info",
                """
                def top(options) do
                  try do
                    :erlang.error(:e, [:shown], options)
                  catch
                    :error, _ ->
                      [frame | _] = __STACKTRACE__
                      {:erlang.element(3, frame), :lists.keyfind(:error_info, 1, :erlang.element(4, frame))}
                  end
                end

                defmacro m do
                  Macro.escape([
                    top(error_info: %{a: 1}),
                    top(error_info: %{a: 1}, error_info: %{b: 2}),
                    top([]),
                    top(error_info: :x),
                    top(error_info: %{a: 1}, other: 1),
                    top([{:error_info, %{a: 1}} | :t]),
                    top(:x)
                  ])
                end
                """,
            ),
            macro(
                "form:try-after",
                """
                defmacro m do
                  try do
                    :a
                  after
                    :b
                  end
                end
                """,
            ),
            macro(
                "form:try-after-raise",
                """
                defmacro m do
                  try do
                    :erlang.error(:x)
                  after
                    :b
                  end
                end
                """,
            ),
            macro(
                "form:try-else",
                """
                defmacro m do
                  try do
                    :a
                  rescue
                    _ -> :r
                  else
                    :a -> :else_a
                  end
                end
                """,
            ),
            macro(
                "form:try-else-clause",
                """
                defmacro m do
                  try do
                    :b
                  rescue
                    _ -> :r
                  else
                    :a -> 1
                  end
                end
                """,
            ),
            macro("form:throw", "defmacro m, do: :erlang.throw(:t)", pins = DECLINED),
            macro(
                "form:receive",
                """
                defmacro m do
                  receive do
                    x -> x
                  after
                    0 -> :none
                  end
                end
                """,
                pins = DECLINED,
            ),
            macro("form:for", "defmacro m, do: for(x <- [1, 2], do: :erlang.*(x, 2))"),
            macro("form:for-pattern", "defmacro m, do: for({:a, v} <- [{:a, 1}, :b, {:a, 2}], do: v)"),
            macro("form:for-filter", "defmacro m, do: for(x <- [1, 2, 3], :erlang.>(x, 1), do: x)"),
            macro("form:for-generators", "defmacro m, do: for(x <- [1, 2], y <- [:a], do: {x, y})"),
            macro("form:for-into", "defmacro m, do: Macro.escape(for(x <- [1, 2], into: %{}, do: {x, x}))"),
            macro("form:for-into-binary", """defmacro m, do: for(x <- ["a", "b"], into: "", do: x)"""),
            macro(
                "form:for-reduce",
                """
                defmacro m do
                  for x <- [1, 2], reduce: 0 do
                    acc -> :erlang.+(acc, x)
                  end
                end
                """,
            ),
            macro("form:for-uniq", "defmacro m, do: for(x <- [1, 1, 2], uniq: true, do: x)"),
            // `elixir_erl_for` reads a case as it expanded: from 1.18 a macro's `<-/2` is a generator.
            macro(
                "form:for-generated-generator",
                """
                defmacrop gen, do: quote(do: 1 <- [1, 2, 1])
                defmacro m, do: for(gen(), do: :ok)
                """,
                legs = POST_MODULE_LOCAL_CHECKS_TYPED::isSufficient,
            ),
            macro(
                "form:for-generated-filter",
                """
                defmacrop small(x), do: quote(do: :erlang.<(unquote(x), 2))
                defmacro m, do: for(x <- [1, 2, 3], small(x), do: x)
                """,
            ),
            // Not inline, as a list generator makes it, a bitstring generator over a non-bitstring raises `badarg`.
            macro(
                "form:for-generated-generator-bitstring-badarg",
                """
                defmacrop gen, do: quote(do: 1 <- [1])

                defmacro m do
                  b = :a

                  try do
                    for gen(), <<x <- b>>, into: "", do: <<x>>
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
                legs = POST_MODULE_LOCAL_CHECKS_TYPED::isSufficient,
            ),
            macro(
                "form:for-bitstring-generator",
                """defmacro m, do: for(<<c <- "ab">>, do: c)""",
            ),
            macro(
                "form:for-bitstring-skip",
                "defmacro m, do: for(<<x::8, 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, do: x)",
            ),
            // A reduce's skip clause, by the sizes each leg's `elixir_erl_for` keeps.
            macro(
                "form:for-bitstring-reduce-skip-integer",
                "defmacro m, do: for(<<x::8, 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, uniq: true, do: x)",
            ),
            macro(
                "form:for-bitstring-reduce-skip-variable",
                """
                defmacro m do
                  n = 8
                  for <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, uniq: true, do: x
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-rebound",
                """
                defmacro m do
                  n = 8
                  {n, for(<<n::8, x::size(n), 0::8 <- <<8, 1, 0, 8, 2, 1, 8, 3, 0>> >>, uniq: true, do: x)}
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-generator-rebound",
                """
                defmacro m do
                  n = 8
                  for n <- [8], <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, do: x
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-bitstring-generator-rebound",
                """
                defmacro m do
                  n = 8
                  for <<n::8 <- <<8>> >>, _ <- [1], <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, do: x
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-filter-rebound",
                """
                defmacro m do
                  n = 8
                  for _ <- [1], n = 8, <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, do: x
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-attribute",
                """
                @n 8
                defmacro m, do: for(<<0::size(@n), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x)
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-unit-attribute",
                """
                @u 8
                defmacro m, do: for(<<0::size(1)-unit(@u), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x)
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-attribute-operator",
                """
                @n 1
                defmacro m, do: for(<<0::size(@n * 8), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x)
                """,
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            macro(
                "form:for-bitstring-reduce-skip-macro",
                """
                defmacrop eight, do: 8
                defmacro m, do: for(<<0::size(eight()), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x)
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-macro-variable",
                """
                defmacrop sz(v), do: v
                defmacro m do
                  n = 8
                  for <<0::size(sz(n)), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x
                end
                """,
            ),
            macro(
                "form:bitstring-size-var-macro",
                """
                defmacro m do
                  n = 8
                  <<x::size(var!(n))>> = <<1>>
                  x
                end
                """,
            ),
            // A size is expanded once, in the context `expand_spec_arg/4` gives it, which its macro's `__CALLER__` sees.
            macro(
                "form:bitstring-size-macro-caller-context",
                """
                defmacrop sz(v), do: if(__CALLER__.context == :match, do: quote(do: m), else: v)

                defmacro m do
                  n = 8
                  <<x::size(sz(n))>> = <<1>>
                  x
                end
                """,
            ),
            // Elixir compares the two sizes as the floats they expand to; from source the attribute's float isn't held.
            macro(
                "form:bitstring-size-float-attribute",
                """
                @f 8.0

                defmacro m do
                  <<x::size(@f)-size(8.0)>> = <<1>>
                  x
                end
                """,
                pins = { source, _ -> UNPORTED.takeIf { source == Source.SOURCE } },
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            macro(
                "form:bitstring-size-repeated-attribute",
                """
                @n 8
                defmacro m do
                  <<x::size(@n)-size(8)>> = <<1>>
                  x
                end
                """,
            ),
            // A bare segment's value is evaluated as its expansion recorded it.
            macro(
                "form:bitstring-bare-attribute",
                """
                @n 65
                defmacro m, do: <<@n>>
                """,
            ),
            macro(
                "form:bitstring-bare-macro",
                """
                defmacrop n, do: 65
                defmacro m, do: <<n()>>
                """,
            ),
            // A UTF segment of a string literal is the string's code points, though an attribute spells it.
            macro(
                "form:bitstring-utf-attribute",
                """
                @text "ab"
                defmacro m, do: <<@text::utf8>>
                """,
            ),
            macro(
                "form:bitstring-match-utf-attribute",
                """
                @text "ab"
                defmacro m do
                  case "abc" do
                    <<@text::utf8, rest::binary>> -> rest
                    _ -> :miss
                  end
                end
                """,
            ),
            // A string literal's segment matches its own bytes, not the rest, though an attribute spells it.
            macro(
                "form:bitstring-match-binary-attribute",
                """
                @text "ab"
                defmacro m do
                  case "abc" do
                    <<@text, rest::binary>> -> rest
                    _ -> :miss
                  end
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-operator",
                """
                defmacro m do
                  s = 1
                  for <<0::size(s * 8), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x
                end
                """,
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            macro("form:for-bitstring-not-bitstring", "defmacro m(v), do: for(<<x <- v>>, do: x)", "m(1)"),
            macro(
                "form:for-bitstring-not-bitstring-into",
                """defmacro m(v), do: for(<<x <- v>>, into: "", do: <<x>>)""",
                "m(1)",
            ),
            macro(
                "form:for-bitstring-not-bitstring-into-bitstring",
                "defmacro m(v), do: for(<<x <- v>>, into: <<>>, do: <<x>>)",
                "m(1)",
            ),
            macro(
                "form:for-into-first",
                """
                defmacro m do
                  try do
                    for x <- [1], into: :erlang.error(:into), do: :erlang.error(x)
                  rescue
                    e in ErlangError -> e.original
                  end
                end
                """,
            ),
            // `Collectable.into/1` raises for `1` before the body runs.
            macro(
                "form:for-into-unsupported-first",
                """
                defmacro m do
                  try do
                    for _ <- [1], into: 1, do: :erlang.div(1, 0)
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
                pins = DECLINED,
            ),
            // The collector `Collectable.into/1` gives takes each result as its body gives it.
            macro(
                "form:for-into-collectable",
                """
                defmacro m do
                  out = %{}
                  Macro.escape(for(x <- [1, 2], into: out, do: {x, x}))
                end
                """,
            ),
            // So the first result it rejects raises before the next body runs.
            macro(
                "form:for-into-collectable-rejects-first",
                """
                defmacro m do
                  out = %{}

                  try do
                    for x <- [1, 2], into: out, do: if(x == 1, do: :bad, else: :erlang.div(1, 0))
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
                pins = DECLINED,
            ),
            // `build_inline_each/7` checks each result `into: ""` takes as its body gives it.
            macro(
                "form:for-into-binary-rejects-first",
                """
                defmacro m do
                  try do
                    for x <- [1, 2], into: "", do: if(x == 1, do: :bad, else: :erlang.div(1, 0))
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
            ),
            // `build_inline/7` reads `into:` as it expanded, so an attribute's `[]` is inline, where before 1.20.2 a
            // bitstring generator skips a run its pattern doesn't match.
            macro(
                "form:for-into-attribute-inline",
                """
                @out []
                defmacro m do
                  n = 8
                  for <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, into: @out, do: x
                end
                """,
            ),
            // And `uniq: true` isn't, as it expanded.
            macro(
                "form:for-uniq-attribute",
                """
                @u true
                defmacro m do
                  n = 8
                  for <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, uniq: @u, do: x
                end
                """,
            ),
            // `expand_for/4` expands the options first, in the scope the generators then start from, and from
            // `GENERATOR_RIGHT_SIDE_SCOPED` drops what a generator's right side binds.
            macro(
                "form:for-generator-right-side-scope",
                """
                defmacro m do
                  n = 8
                  for _ <- (n = 16; [1]), do: n
                end
                """,
            ),
            macro(
                "form:for-bitstring-generator-right-side-scope",
                """
                defmacro m do
                  n = 8
                  for _ <- [1], <<x::8 <- (n = 16; <<1>>)>>, do: {x, n}
                end
                """,
                // Before, Elixir doesn't compile it: `unbound variable '_n@2'`.
                legs = GENERATOR_RIGHT_SIDE_SCOPED::isSufficient,
            ),
            macro(
                "form:for-into-scope",
                """
                defmacro m do
                  n = 8
                  {for(_ <- [1], into: (n = 16; []), do: n), n}
                end
                """,
            ),
            macro(
                "form:for-reduce-scope",
                """
                defmacro m do
                  n = 8
                  {for(_ <- [1], reduce: (n = 16; 0), do: (acc -> acc + n)), n}
                end
                """,
            ),
            macro("form:with", "defmacro m(x), do: with({:ok, v} <- x, do: v)", "m({:ok, 1})"),
            macro("form:with-unmatched", "defmacro m(x), do: with({:ok, v} <- x, do: v)", "m(:error)"),
            macro("form:with-variable", "defmacro m(x), do: with(y <- x, do: [y])", "m(:a)"),
            macro(
                "form:with-else",
                """
                defmacro m(x) do
                  with {:ok, v} <- x do
                    v
                  else
                    :error -> :failed
                  end
                end
                """,
                "m(:error)",
            ),
            macro(
                "form:with-else-before-last",
                """
                defmacro m(x) do
                  with {:ok, v} <- x, else: (:error -> :failed) do
                    v
                  end
                end
                """,
                "m(:error)",
                legs = WITH_OPTIONS_BEFORE_LAST_ARGUMENT::isSufficient,
            ),
            macro(
                "form:with-clause-error",
                """
                defmacro m(x) do
                  with {:ok, v} <- x do
                    v
                  else
                    :error -> :failed
                  end
                end
                """,
                "m(:other)",
            ),
            macro(
                "form:pin",
                """
                defmacro m(x) do
                  y = 1

                  case x do
                    ^y -> :same
                    _ -> :different
                  end
                end
                """,
                "m(1)",
            ),
            macro("form:variable", "defmacro m(x), do: x", "m(:a)"),
            macro(
                "form:local",
                """
                defmacro m, do: h(2)

                defp h(x), do: [x]
                """,
            ),
            macro(
                "form:local-clauses",
                """
                defmacro m, do: [h(1), h(:a)]

                defp h(x) when :erlang.is_integer(x), do: :int
                defp h(_), do: :other
                """,
            ),
            macro(
                "form:guard-field",
                """
                defmacro m do
                  x = %{a: true}

                  case x do
                    v when v.a -> :yes
                    _ -> :no
                  end
                end
                """,
            ),
            macro(
                "form:guard-field-raises",
                """
                defmacro m do
                  x = 1

                  case x do
                    v when v.a -> :yes
                    _ -> :no
                  end
                end
                """,
            ),
            macro(
                "form:field",
                """
                defmacro m do
                  x = %{a: 1}
                  x.a
                end
                """,
            ),
            macro(
                "form:field-missing-key",
                """
                defmacro m do
                  x = %{a: 1}
                  x.b
                end
                """,
            ),
            // Before a non-map raises for its key, `1.key` calls `1:key()`.
            macro(
                "form:field-remote",
                """
                defmacro m do
                  x = 1
                  x.key
                end
                """,
                legs = { !FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(it) },
            ),
            // Before a non-map raises for its key, `nil.key` and `true.key` call a module no file defines.
            macro(
                "form:field-opaque",
                """
                defmacro m do
                  x = nil
                  x.key
                end
                """,
                legs = { !FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(it) },
                pins = DECLINED,
            ),
            macro(
                "form:field-opaque-true",
                """
                defmacro m do
                  x = true
                  x.key
                end
                """,
                legs = { !FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(it) },
                pins = DECLINED,
            ),
            macro(
                "form:field-keyerror",
                FIELD_ON_NON_MAPS,
                pins = { _, level -> OPAQUE.takeIf { KEY_ERROR_MESSAGE_INSPECTED_WITH_OPTIONS.isSufficient(level) } },
                legs = { FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(it) && !FIELD_OF_NON_MAP_RAISES_BADMAP.isSufficient(it) },
            ),
            macro("form:field-badmap", FIELD_ON_NON_MAPS, legs = FIELD_OF_NON_MAP_RAISES_BADMAP::isSufficient),
            macro("form:remote", "defmacro m, do: Keyword.keys([a: 1, b: 2])"),
            macro(
                "form:remote-own",
                """
                defmacro m, do: $ROW.h(1)

                def h(x), do: [x]
                """,
            ),
            macro("form:remote-unreadable", "defmacro m, do: :code.which(:absent_beam)", pins = DECLINED),
            // A module defined again, where the later definition stops, leaves none of the earlier one's clauses.
            macro(
                "form:remote-redefined-stopped",
                "defmacro m, do: $ROW.A.f()",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule $ROW.A do
                  def f, do: 1
                end

                defmodule $ROW.A do
                  def f, do: 2
                  defoverridable Application.get_env(:app, :ov, f: 0)
                end
                """,
            ),
            macro("form:pair", "defmacro m, do: {:a, :b}"),
            macro("form:list", "defmacro m, do: [1, :a]"),
            macro("form:improper-list", "defmacro m, do: Macro.escape([1 | 2])"),
            macro("form:literal", """defmacro m, do: [1.5, "b", :c]"""),
            // A module defining a guard stops at its `Kernel.Utils.defguard/2`, so it isn't loaded.
            macro(
                "form:defguard-source",
                "defguard is_even(x) when rem(x, 2) == 0",
                "is_even(4)",
                pins = { source, _ -> UNPORTED.takeIf { source == Source.SOURCE } },
                ownEnd = "opaque at Elixir.Kernel.Utils.defguard",
            ),
            Row(
                "form:defguard-in-guard",
                "defguard is_even(x) when rem(x, 2) == 0",
                "$ROW.is_even(4)",
                "require $ROW\n_ = $ROW.is_even(4)\ndef f(y) when $ROW.is_even(y), do: y",
                "$ROW.is_even(4)",
                listOf(ROW),
                SourceRun.NONE,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            // `elixir_bitstring:expand_each_spec/6` unpacks the specs a macro expands to again, ahead of the rest.
            Row(
                "form:bitstring-spec-macro",
                "defmacro my_size, do: quote(do: size(16))",
                "$ROW.my_size()",
                "import $ROW\ndef f(x), do: <<x::my_size()>>",
                "my_size()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            Row(
                "form:bitstring-spec-macro-specs",
                "defmacro u8, do: quote(do: unsigned - size(8))",
                "$ROW.u8()",
                "import $ROW\ndef f(x), do: <<x::u8()-little>>",
                "u8()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            Row(
                "form:bitstring-spec-macro-again",
                "defmacro s8, do: quote(do: b8() - signed)\ndefmacro b8, do: quote(do: size(8))",
                "$ROW.s8()",
                "import $ROW\ndef f(x), do: <<x::s8()>>",
                "s8()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            // A spec written as a name is expanded as a call of it on every version.
            Row(
                "form:bitstring-spec-macro-bare-name",
                "defmacro s8, do: quote(do: b8 - signed)\ndefmacro b8, do: quote(do: size(8))",
                "$ROW.s8()",
                "import $ROW\ndef f(x), do: <<x::s8()>>",
                "s8()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            Row(
                "form:bitstring-spec-bare-name",
                "defmacro b8, do: quote(do: size(8))",
                "$ROW.b8()",
                "import $ROW\ndef f(x), do: <<x::b8>>",
                "b8",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),

            // `elixir_quote`'s run-time helpers
            macro(
                "quote:runtime-option",
                """
                defmacro m do
                  l = 7
                  quote line: l, do: node()
                end
                """,
            ),
            macro(
                "quote:runtime-option-invalid",
                """
                defmacro m do
                  l = :bad
                  quote line: l, do: node()
                end
                """,
            ),
            macro(
                "quote:validate",
                """
                defmacro m do
                  x = 1
                  quote do: unquote(x)
                end
                """,
            ),
            macro(
                "quote:dot",
                """
                defmacro m do
                  name = :max
                  quote do: Kernel.unquote(name)(1, 2)
                end
                """,
            ),
            macro(
                "quote:dot-alias",
                """
                defmacro m do
                  name = String
                  quote do: Kernel.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-atom",
                """
                defmacro m do
                  name = :node
                  quote do: Kernel.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-aliases",
                """
                defmacro m do
                  name = quote(do: Foo.Bar)
                  quote do: Kernel.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-no-parens",
                """
                defmacro m do
                  name = quote(do: foo)
                  quote do: %{foo: 1}.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-call",
                """
                defmacro m do
                  name = quote(do: reverse([1]))
                  quote do: :lists.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-invalid",
                """
                defmacro m do
                  name = 1
                  quote do: Kernel.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-args",
                """
                defmacro m do
                  name = quote(do: max)
                  quote do: :erlang.unquote(name)(1, 2)
                end
                """,
            ),
            macro(
                "quote:dot-args-invalid",
                """
                defmacro m do
                  name = 1
                  quote do: Kernel.unquote(name)(1)
                end
                """,
            ),
            macro(
                "quote:splice",
                """
                defmacro m do
                  xs = [1, 2]
                  quote do: [0, unquote_splicing(xs), 3]
                end
                """,
            ),
            macro(
                "quote:splice-call",
                """
                defmacro m do
                  xs = [1, 2]
                  quote do: max(unquote_splicing(xs))
                end
                """,
            ),
            macro(
                "quote:splice-tail",
                """
                defmacro m do
                  xs = [1, 2]
                  quote do: [0, unquote_splicing(xs) | [3]]
                end
                """,
            ),
            macro(
                "quote:splice-empty-before-tail",
                """
                defmacro m do
                  xs = []
                  quote do: [unquote_splicing(xs) | [3]]
                end
                """,
            ),
            macro(
                "quote:splice-invalid",
                """
                defmacro m do
                  xs = 1
                  quote do: [unquote_splicing(xs)]
                end
                """,
            ),
            macro(
                "quote:splice-invalid-element",
                """
                defmacro m do
                  xs = [{1, 2, 3}]
                  quote do: [unquote_splicing(xs)]
                end
                """,
                legs = UNQUOTE_SHALLOW_VALIDATED::isSufficient,
            ),
            macro(
                "quote:unquote-invalid",
                """
                defmacro m do
                  x = {1, 2, 3}
                  quote do: f(unquote(x))
                end
                """,
                legs = UNQUOTE_SHALLOW_VALIDATED::isSufficient,
            ),
            macro(
                "quote:unquote-invalid-unknown-call",
                """
                @c :erlang.hd([:g])
                defmacro m do
                  x = {@c, [], 1}
                  quote do: f(unquote(x))
                end
                """,
                legs = UNQUOTE_SHALLOW_VALIDATED::isSufficient,
            ),
            macro(
                "quote:annotate-def",
                """
                defmacro m do
                  name = :def
                  quote do: Kernel.unquote(name)(f(x), do: x)
                end
                """,
            ),
            macro(
                "quote:annotate-def-when",
                """
                defmacro m do
                  name = :def
                  quote do: Kernel.unquote(name)(f(x) when x, do: x)
                end
                """,
            ),
            macro(
                "quote:annotate-lexical",
                """
                defmacro m do
                  name = :import
                  Macro.escape(quote do: Kernel.unquote(name)(List))
                end
                """,
            ),

            // `Macro.escape/2`
            macro("escape:prune", "defmacro m, do: Macro.escape({:a, [line: 1], nil}, prune_metadata: true)"),
            macro("escape:pair", "defmacro m, do: Macro.escape({:a, 1})"),
            macro("escape:tuple", "defmacro m, do: Macro.escape({:a, 1, 2})"),
            macro("escape:bitstring", "defmacro m, do: Macro.escape(<<1::3>>)"),
            macro("escape:map-order", "defmacro m, do: Macro.escape(%{b: 1, a: 2, c: 3})", unordered = true),
            macro("escape:map-nested", "defmacro m, do: Macro.escape(%{a: %{b: [1]}})"),
            // `1` and `1.0` tie as keys, so the sort compares their values, one of them a binary whose bytes aren't known.
            macro(
                "escape:map-unknown-value",
                "defmacro m, do: Macro.escape(%{1 => inspect(1), 1.0 => :ok})",
                pins = DECLINED,
                unordered = true,
            ),
            // In the window, Elixir lists atom keys in the order the VM made them, and the port keeps term order.
            macro(
                "escape:map-vm-order",
                "defmacro m, do: Macro.escape(%{zz_vm_first: 1, aa_vm_second: 2})",
                legs = ESCAPED_MAP_IN_VM_ORDER::isSufficient,
                pins = { _, _ -> "{:%{}, [], [aa_vm_second: 2, zz_vm_first: 1]}" },
            ),
            // `__escape__/1` returns code the escape doesn't run.
            macro(
                "escape:struct-escape",
                """
                defstruct [:ast]

                def __escape__(%{ast: ast}), do: ast

                defmacro m, do: Macro.escape(%{__struct__: __MODULE__, ast: :escaped})
                """,
                pins = { _, level -> OPAQUE.takeIf { REGEX_ESCAPED_IN_FIXED_FIELD_ORDER.isSufficient(level) } },
                unordered = true,
            ),
            macro("escape:struct", "defmacro m, do: Macro.escape(%URI{})", pins = buildsPartOf("Elixir.URI"), unordered = true),
            macro("escape:empty-list", "defmacro m, do: Macro.escape([])"),
            macro("escape:list-no-unquote", "defmacro m, do: Macro.escape([1, {:unquote, [], [:x]}])"),
            macro("escape:list", "defmacro m, do: Macro.escape([1, {:unquote, [], [:x]}], unquote: true)"),
            macro("escape:improper-list", "defmacro m, do: Macro.escape([1, 2 | 3])"),
            macro("escape:literal", """defmacro m, do: Macro.escape([1, 2.5, :a, "b"])"""),
            macro("escape:external", "defmacro m, do: Macro.escape(&Enum.map/2)"),
            macro("escape:closure", "defmacro m, do: Macro.escape(fn -> 1 end)"),

            // `elixir_dispatch`
            macro(
                "form:user-local",
                """
                defmacrop twice(x), do: quote(do: {unquote(x), unquote(x)})

                defmacro m, do: Macro.escape(twice(1))
                """,
            ),
            macro(
                "form:user-import",
                """
                import {row}.H

                defmacro m, do: Macro.escape(twice(1))
                """,
                helper = TWICE,
            ),
            macro(
                "form:user-remote",
                """
                require {row}.H

                defmacro m, do: Macro.escape({row}.H.twice(1))
                """,
                helper = TWICE,
            ),
            // The helper's output holds two `quote`s, which take its call's origin.
            macro(
                "form:quotes-sharing-an-origin",
                """
                require {row}.H

                defmacro m, do: {row}.H.pair()
                """,
                helper = PAIR,
            ),
            macro(
                "form:raise-in-macro",
                """defmacro m, do: raise(%ArgumentError{message: "no"})""",
                pins = buildsPartOf("Elixir.ArgumentError"),
            ),
            // `elixir_erl_pass:translate/3`'s `super`: the definition its `{super, {Kind, Name}}` names, a macro's
            // given `__CALLER__`.
            macro(
                "form:super-overridable",
                """
                defmacro m, do: Macro.escape({1, __CALLER__.module})
                defoverridable m: 0
                defmacro m, do: Macro.escape({super(), 2})
                """,
            ),
            macro(
                "form:super-overridable-function",
                """
                def f(x), do: x
                defoverridable f: 1
                def f(x), do: {super(x), 2}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            macro(
                "form:super-capture",
                """
                def f(x), do: x
                defoverridable f: 1
                def f(x), do: {(&super/1).(x), 2}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            macro(
                "form:super-capture-call",
                """
                def f(x), do: x
                defoverridable f: 1
                def f(x), do: {(&super(&1)).(x), 2}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            macro(
                "form:super-capture-expression",
                """
                def f(x), do: x
                defoverridable f: 1
                def f(x), do: {(&super({&1})).(x), 2}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            // `elixir_def:unpack_expanded/7`'s default arity calls the full one through `super`.
            macro("form:super-default", "defmacro m(x \\\\ 1), do: x"),
            macro(
                "form:super-default-function",
                """
                def f(x, y \\ 2), do: {x, y}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            // Before 1.14 a default's own variables take the default arity's first versions, so `y` is `a`.
            macro(
                "form:super-default-shared-version",
                """
                def f(a, b \\ (y = 1; y + 1)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            // A `case` clause matches the shared version, as Erlang's does; a fun head and a generator bind afresh.
            macro(
                "form:super-default-shared-version-case",
                """
                def f(a, b \\ (case 7 do y -> y end)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-fn",
                """
                def f(a, b \\ :lists.map(fn y -> y end, [1, 2])), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-for",
                """
                def f(a, b \\ (for y <- [1, 2], do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-for-bitstring",
                """
                def f(a, b \\ (for <<y <- <<1, 2>> >>, do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-for-reduce",
                """
                def f(a, b \\ (for _ <- [1, 2], reduce: 0 do y -> y + 1 end)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            // The clause a bitstring generator skips through binds afresh too, and a variable repeated in a fresh
            // pattern matches what the pattern itself bound.
            macro(
                "form:super-default-shared-version-for-bitstring-skip",
                """
                def f(a, b \\ (for <<y, 0 <- <<1, 1, 2, 0>> >>, do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-fn-repeated",
                """
                def f(a, b \\ :lists.map(fn {y, y} -> :same; _ -> :diff end, [{1, 1}, {1, 2}])), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-for-repeated",
                """
                def f(a, b \\ (for {y, y} <- [{1, 1}, {5, 5}, {1, 2}], do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            // A `rescue` variable is bound by a `=` that heads the clause's body, a lone variable `else` is the last
            // clause of the `case`, and before 1.13 `with y <- e` is `y = e`, so a clash raises as each of those does.
            macro(
                "form:super-default-shared-version-rescue",
                """
                def f(a, b \\ (try do raise "x" rescue y -> y.message end)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-with-else",
                """
                def f(a, b \\ (with :x <- 1 do 0 else y -> y end)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-with-variable",
                """
                def f(a, b \\ (with y <- 1, do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            // A macro's argument is marked `stop_generated` where an import or `Macro.expand_once/2` calls it, not a
            // remote call.
            Row(
                "quote:stop-generated",
                STOP_GENERATED,
                "$ROW.m(f())",
                "import $ROW\nm(f())",
                "m(f())",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            // `Macro.expand_once/2` gives back a node it can't expand as it was given, its mark kept.
            Row(
                "quote:stop-generated-expanded",
                """
                defmacro m(x) do
                  e = Macro.expand_once(x, __CALLER__)

                  if e == x and Keyword.get(elem(e, 1), :stop_generated, false) do
                    quote(do: def(a, do: 1))
                  else
                    quote(do: def(b, do: 1))
                  end
                end
                """.trimIndent(),
                "$ROW.m(f())",
                "import $ROW\nm(f())",
                "m(f())",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            Row(
                "quote:stop-generated-remote",
                STOP_GENERATED,
                "$ROW.m(f())",
                "require $ROW\n$ROW.m(f())",
                "$ROW.m(f())",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),

            // The natives: `erlang`
            macro("pure:append", "defmacro m, do: :erlang.++([1], [2])"),
            macro("pure:append-improper", "defmacro m, do: :erlang.++([1 | 2], [3])"),
            macro("pure:subtract-exact", "defmacro m, do: :erlang.--([1, 1.0], [1])"),
            macro(
                "pure:eq-int-float",
                """defmacro m, do: [:erlang.==(1, 1.0), :erlang."=:="(1, 1.0), :erlang."/="(1, 2), :erlang."=/="(1, 1.0)]""",
            ),
            macro(
                "pure:eq-nested",
                """defmacro m, do: [:erlang.==({1, [2]}, {1, [2.0]}), :erlang."=:="({1, [2]}, {1, [2.0]})]""",
            ),
            macro(
                "pure:compare-term-order",
                """
                defmacro m do
                  [
                    :erlang.<(1, :a),
                    :erlang.<(:a, {1}),
                    :erlang.<({1}, %{}),
                    :erlang.<(%{}, []),
                    :erlang.<([], [1]),
                    :erlang.<([1], "a"),
                    :erlang.<(1, 1.0),
                    :erlang.>=(1.0, 1),
                    :erlang.>({2}, {1, 1}),
                    :erlang."=<"(:b, :a)
                  ]
                end
                """,
            ),
            macro("pure:bool", "defmacro m, do: [:erlang.not(true), :erlang.and(true, false), :erlang.or(false, true)]"),
            macro("pure:bool-badarg", "defmacro m, do: :erlang.and(1, true)"),
            macro(
                "pure:is",
                """
                defmacro m do
                  [
                    :erlang.is_atom(nil),
                    :erlang.is_binary("a"),
                    :erlang.is_integer(1.0),
                    :erlang.is_number(1.0),
                    :erlang.is_tuple({}),
                    :erlang.is_map(%{}),
                    :erlang.is_boolean(nil)
                  ]
                end
                """,
            ),
            macro("pure:is-list-charlist", "defmacro m, do: [:erlang.is_list([97, 98]), :erlang.is_list([1 | 2])]"),
            macro(
                "pure:is-function-2",
                "defmacro m, do: [:erlang.is_function(&Enum.map/2, 2), :erlang.is_function(&Enum.map/2, 1)]",
            ),
            macro(
                "pure:is-function-arity-past-a-long",
                "defmacro m, do: :erlang.is_function(&Enum.map/2, 18_446_744_073_709_551_618)",
            ),
            macro(
                "pure:element",
                "defmacro m, do: [:erlang.element(1, {:a}), :erlang.tuple_size({1, 2}), Macro.escape(:erlang.setelement(1, {:a, :b}, :c))]",
            ),
            macro("pure:element-badarg", "defmacro m, do: :erlang.element(3, {1, 2})"),
            macro("pure:hd-tl", "defmacro m, do: [:erlang.hd([1, 2]), :erlang.tl([1, 2]), :erlang.length([1, 2])]"),
            macro("pure:tl-improper", "defmacro m, do: :erlang.tl([1 | 2])"),
            macro("pure:length-improper", "defmacro m, do: :erlang.length([1 | 2])"),
            macro(
                "pure:map",
                "defmacro m, do: [:erlang.map_size(%{a: 1}), :erlang.map_get(:a, %{a: 1}), :erlang.is_map_key(:b, %{a: 1})]",
            ),
            macro("pure:badmap", "defmacro m, do: :erlang.map_get(:a, 1)"),
            macro("pure:badkey", "defmacro m, do: :erlang.map_get(:b, %{a: 1})"),
            macro(
                "pure:arith",
                "defmacro m, do: [:erlang.rem(7, 2), :erlang.div(7, 2), :erlang.-(1, 3), :erlang.-(2), :erlang.+(2), :erlang.abs(:erlang.-(3))]",
            ),
            macro("pure:arith-float", "defmacro m, do: [:erlang./(4, 2), :erlang.+(1, 2.0), :erlang.*(2, 1.5)]"),
            macro("pure:badarith", "defmacro m, do: :erlang.+(1, :a)"),
            macro("pure:badarith-division", "defmacro m, do: :erlang.div(1, 0)"),
            // `/` converts each integer to a float, which raises for one past the float range.
            macro("pure:badarith-float-conversion", "defmacro m, do: :erlang./(1, 0x1${"0".repeat(275)})"),
            macro(
                "pure:atom-binary",
                """
                defmacro m do
                  [
                    :erlang.atom_to_binary(:a, :utf8),
                    :erlang.binary_to_atom("a", :utf8),
                    :erlang.binary_to_existing_atom("a", :utf8)
                  ]
                end
                """,
            ),
            macro("pure:binary-to-atom-invalid", "defmacro m, do: :erlang.binary_to_atom(1, :utf8)"),
            macro(
                "pure:atom-binary-encoding",
                """
                defmacro m do
                  [
                    :erlang.atom_to_binary(:"é", :latin1),
                    :erlang.binary_to_atom("\\xE9", :latin1),
                    :erlang.atom_to_binary(:"é", :unicode),
                    try do
                      :erlang.atom_to_binary(:a, :foo)
                    rescue
                      ArgumentError -> :badarg
                    end
                  ]
                end
                """,
            ),
            macro(
                "pure:type-tests",
                """
                defmacro m do
                  [
                    :erlang.xor(true, false),
                    :erlang.is_bitstring(<<1::1>>),
                    :erlang.is_float(1.0),
                    :erlang.is_pid(1),
                    :erlang.binary_to_existing_atom("true"),
                    :erlang.byte_size(<<1, 2::4>>),
                    :erlang.bit_size(<<1, 2::4>>)
                  ]
                end
                """,
            ),
            macro("form:exit", "defmacro m, do: :erlang.exit(:x)", pins = DECLINED),
            macro("pure:raise-badarg", "defmacro m, do: [:erlang.raise(:foo, :a, []), :erlang.raise(:error, :a, :bad)]"),
            // `raise_3` takes `{Fun, Args}` and `{M, F, A}` frames, and never reads a frame's arity or arguments.
            macro(
                "pure:raise-frames",
                """
                defmacro m do
                  try do
                    :erlang.raise(:error, :x, [{:m, :f, 1}, {:m, :f, :any, []}, {&List.first/1, [[1]]}])
                  rescue
                    e in ErlangError -> e.original
                  end
                end
                """,
            ),
            macro("pure:raise-bad-location", "defmacro m, do: :erlang.raise(:error, :x, [{:m, :f, 1, :nowhere}])"),
            // `raise_3` stores a `{Fun, Args}` or `{M, F, A}` frame with an empty location, and the first 8 frames.
            macro(
                "pure:raise-short-frames",
                """
                defmacro m do
                  frames = [{:m, :f, 1} | for(arity <- [1, 2, 3, 4, 5, 6, 7, 8], do: {:m, :g, arity, []})]

                  short =
                    try do
                      :erlang.raise(:error, :x, frames)
                    rescue
                      _ -> Macro.escape(__STACKTRACE__)
                    end

                  fun =
                    try do
                      :erlang.raise(:error, :x, [{&List.first/1, [[1]]}])
                    rescue
                      _ -> :erlang.tuple_size(:erlang.hd(__STACKTRACE__))
                    end

                  [short, fun]
                end
                """,
            ),
            // A `fn`'s `function_clause` frame names a function the evaluator doesn't know.
            macro(
                "pure:raise-unknown-frame",
                """
                defmacro m do
                  f = fn :a -> 1 end

                  try do
                    f.(:b)
                  rescue
                    e -> :erlang.raise(:error, e, __STACKTRACE__)
                  end
                end
                """,
                pins = DECLINED,
            ),
            macro(
                "pure:tuple-list",
                "defmacro m, do: [:erlang.tuple_to_list({1, 2}), Macro.escape(:erlang.list_to_tuple([1, 2, 3]))]",
            ),
            macro("pure:list-to-tuple-improper", "defmacro m, do: :erlang.list_to_tuple([1 | 2])"),
            macro(
                "pure:function-exported",
                "defmacro m, do: [:erlang.function_exported(Enum, :map, 2), :erlang.function_exported(Enum, :nope, 0)]",
            ),
            // The VM reads an arity by its low 32 bits, and one past a small integer is no arity.
            macro("pure:function-exported-arity-past-an-int", "defmacro m, do: :erlang.function_exported(Enum, :map, 4_294_967_298)"),
            macro(
                "pure:function-exported-arity-past-a-small-integer",
                "defmacro m, do: :erlang.function_exported(Enum, :map, 576_460_752_303_423_490)",
            ),
            // A macro is exported as `MACRO-<name>`, taking the caller first.
            macro(
                "pure:function-exported-macro",
                """
                defmacro m do
                  [
                    :erlang.function_exported({row}.H, :"MACRO-h", 1),
                    :erlang.function_exported({row}.H, :h, 0),
                    :erlang.function_exported(Kernel, :"MACRO-if", 2)
                  ]
                end
                """,
                helper = DEFINES_A_MACRO,
            ),
            // Which the evaluator doesn't run.
            macro(
                "pure:remote-macro-function",
                """defmacro m, do: :erlang.apply({row}.H, :"MACRO-h", [__CALLER__])""",
                pins = DECLINED,
                helper = DEFINES_A_MACRO,
            ),
            macro(
                "pure:apply",
                "defmacro m, do: [:erlang.apply(Keyword, :get, [[a: 1], :a]), :erlang.apply(fn x -> [x] end, [1])]",
            ),
            macro("pure:apply-badarity", "defmacro m, do: :erlang.apply(fn -> 1 end, [1])"),

            // The natives: `lists`
            macro("pure:reverse", "defmacro m, do: [:lists.reverse([1, 2]), :lists.reverse([1], [2])]"),
            macro("pure:reverse-improper", "defmacro m, do: :lists.reverse([1 | 2])"),
            macro(
                "pure:member",
                "defmacro m, do: [:lists.member(1, [1.0]), :lists.member(1, [1]), :lists.keymember(:a, 1, [{:a, 1}])]",
            ),
            macro("pure:keyfind-int-float", "defmacro m, do: :lists.keyfind(1, 1, [{1.0, :a}])"),
            macro("pure:keydelete", "defmacro m, do: :lists.keydelete(:a, 1, [{:a, 1}, {:b, 2}])"),
            macro("pure:keydelete-badarg", "defmacro m, do: :lists.keydelete(:a, 0, [])"),
            macro("pure:keyfind-position-past-an-int", "defmacro m, do: :lists.keyfind(:a, 4_294_967_297, [{:a, 1}])"),
            macro(
                "pure:keyfind-position-past-a-small-integer",
                "defmacro m, do: :lists.keyfind(:a, 576_460_752_303_423_488, [{:a, 1}])",
            ),
            macro("pure:keydelete-position-past-an-int", "defmacro m, do: :lists.keydelete(:a, 4_294_967_297, [{:a, 1}])"),
            macro(
                "pure:keydelete-position-past-a-long",
                "defmacro m, do: :lists.keydelete(:a, 18_446_744_073_709_551_617, [{:a, 1}])",
            ),
            // From source, an attribute the walk can't read is a value the evaluator doesn't know, which no native takes
            // for a known value of another type.
            macro(
                "pure:keyfind-unknown-element",
                """
                @x :erlang.hd([{:a, :first}])
                defmacro m, do: :lists.keyfind(:a, 1, [@x, {:a, :fallback}])
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:keydelete-unknown-element",
                """
                @x :erlang.hd([{:a, :first}])
                defmacro m, do: :erlang.length(:lists.keydelete(:a, 1, [@x, {:b, 1}]))
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:range-member-unknown",
                """
                @x :erlang.hd([2])
                defmacro m, do: Enum.member?(1..3, @x)
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:keyword-merge-unknown-pair",
                """
                @x :erlang.hd([{:a, 1}])
                defmacro m, do: Keyword.merge([@x], b: 2)
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:keyword-merge-unknown-key",
                """
                @k :erlang.hd([:a])
                defmacro m, do: Keyword.merge([{@k, 1}], b: 2)
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:escape-generated-unknown",
                """
                @g :erlang.hd([true])
                defmacro m, do: Macro.escape({:f, [], []}, generated: @g)
                """,
                pins = { source, level -> DECLINED_FROM_SOURCE(source, level)?.takeIf { MACRO_ESCAPE_MARKS_GENERATED.isSufficient(level) } },
            ),
            macro(
                "pure:env-in-match-unknown-struct",
                """
                @s :erlang.hd([Macro.Env])
                defmacro m, do: Macro.Env.in_match?(%{__struct__: @s, context: :match})
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:dot-unknown-name",
                """
                @n :erlang.hd([:b])
                defmacro m do
                  x = {@n, [], nil}
                  Macro.escape(quote(do: :lists.unquote(x)))
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:access-unknown-struct",
                """
                @s :erlang.hd([{row}.S])
                defmacro m, do: %{__struct__: @s, a: 2}[:a]
                """,
                pins = DECLINED_FROM_SOURCE,
                helper = FETCHES,
            ),
            // A list whose tail isn't known may be proper.
            macro(
                "pure:length-unknown-tail",
                """
                @t :erlang.hd([[2]])
                defmacro m do
                  try do
                    length([1 | @t])
                  rescue
                    ArgumentError -> :bad
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:match-unknown-tail",
                """
                @t :erlang.hd([[2]])
                defmacro m do
                  case [1 | @t] do
                    [_, b] -> b
                    _ -> :other
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // A segment whose value or size isn't known may raise.
            macro(
                "form:bitstring-unknown-segment",
                """
                @v :erlang.hd([:bad])
                defmacro m do
                  v = @v
                  _ = <<v::8>>
                  :ok
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "form:bitstring-unknown-size",
                """
                @s :erlang.hd([:bad])
                defmacro m do
                  s = @s
                  _ = <<1::size(s)>>
                  :ok
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // `String.Chars`' implementations for a tuple aren't read.
            macro(
                "pure:to-string-unknown",
                """
                defmacro m do
                  w = :erlang.hd([{:a}])
                  _ = "#{w}"
                  :ok
                end
                """,
                pins = DECLINED,
            ),
            macro("pure:append-lists", "defmacro m, do: [:lists.append([[1], [2]]), :lists.append([1], [2])]"),
            macro("pure:append-last-improper", "defmacro m, do: Macro.escape(:lists.append([[1], 2]))"),
            macro("pure:flatten-charlist", "defmacro m, do: :lists.flatten([[97], [98, [99]]])"),
            macro(
                "pure:lists-higher-order",
                """
                defmacro m do
                  [
                    :lists.map(fn x -> [x] end, [1, 2]),
                    :lists.filter(fn x -> :erlang.>(x, 1) end, [1, 2]),
                    :lists.foldl(fn x, acc -> [x | acc] end, [], [1, 2]),
                    Macro.escape(:lists.mapfoldl(fn x, acc -> {[x], :erlang.+(acc, x)} end, 0, [1, 2]))
                  ]
                end
                """,
            ),
            macro("pure:filter-bad-filter", "defmacro m, do: :lists.filter(fn _ -> 1 end, [1])"),
            macro(
                "pure:filter-improper",
                """
                defmacro m do
                  for keep <- [:x, true] do
                    try do
                      :lists.filter(fn _ -> keep end, [1 | 2])
                    rescue
                      e in ErlangError -> e.original
                    end
                  end
                end
                """,
            ),
            macro(
                "pure:maps-update",
                """
                defmacro m do
                  [
                    Macro.escape(:maps.update(:a, 2, %{a: 1})),
                    try do
                      :maps.update(:b, 2, %{a: 1})
                    rescue
                      e in KeyError -> e.key
                    end
                  ]
                end
                """,
            ),
            macro("pure:mapfoldl-badmatch", "defmacro m, do: :lists.mapfoldl(fn x, _ -> x end, 0, [1])", pins = LISTS_ERROR_BELOW_OTP_25),
            macro("pure:mapfoldl-not-list", "defmacro m, do: :lists.mapfoldl(fn x, a -> {x, a} end, 0, :x)", pins = LISTS_ERROR_BELOW_OTP_25),
            macro("pure:lists-not-fun", "defmacro m, do: :lists.map(:x, [1])", pins = LISTS_ERROR_BELOW_OTP_25),
            macro(
                "pure:lists-function-clause",
                """
                defmacro m do
                  try do
                    :lists.map(fn x -> x end, [1 | 2])
                  rescue
                    e in FunctionClauseError -> Macro.escape({e.module, e.function, e.arity})
                  end
                end
                """,
                pins = LISTS_ERROR_BELOW_OTP_25,
            ),
            macro(
                "pure:all",
                "defmacro m, do: [:lists.all(fn x -> :erlang.is_atom(x) end, [:a, :b]), :lists.all(fn x -> :erlang.is_atom(x) end, [:a, 1])]",
            ),
            macro("pure:all-case-clause", "defmacro m, do: :lists.all(fn _ -> 1 end, [1])", pins = LISTS_ERROR_BELOW_OTP_25),

            // The natives: `maps`
            macro(
                "pure:maps",
                """
                defmacro m do
                  [
                    :maps.get(:a, %{a: 1}),
                    :maps.get(:b, %{a: 1}, :d),
                    Macro.escape(:maps.find(:a, %{a: 1})),
                    :maps.find(:b, %{a: 1}),
                    :maps.is_key(:a, %{a: 1}),
                    Macro.escape(:maps.put(:b, 2, %{a: 1})),
                    Macro.escape(:maps.merge(%{a: 1}, %{a: 2, b: 3})),
                    Macro.escape(:maps.from_list([b: 1, a: 2])),
                    Macro.escape(:maps.to_list(%{2 => :b, 1 => :a}))
                  ]
                end
                """,
                unordered = true,
            ),
            macro(
                "pure:maps-to-list-atom-keys",
                "defmacro m, do: :maps.to_list(%{b: 1, a: 2})",
                pins = { _, level -> OPAQUE.takeIf { MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(level) } },
            ),
            macro("pure:maps-badmap", "defmacro m, do: :maps.get(:a, 1)"),
            macro("pure:maps-badkey", "defmacro m, do: :maps.get(:b, %{a: 1})"),
            macro("pure:maps-from-list-badarg", "defmacro m, do: :maps.from_list([1])"),

            // The natives: Elixir's functions
            macro(
                "pure:keyword",
                """
                defmacro m do
                  k = [a: 1, b: 2, a: 3]

                  [
                    Keyword.get(k, :a),
                    Keyword.get(k, :c, :d),
                    Macro.escape(Keyword.fetch(k, :b)),
                    Keyword.fetch(k, :c),
                    Keyword.fetch!(k, :b),
                    Keyword.has_key?(k, :a),
                    Keyword.keyword?(k),
                    Keyword.keyword?([1]),
                    Keyword.delete(k, :a),
                    Keyword.put(k, :a, 4),
                    Keyword.put_new(k, :a, 4),
                    Keyword.put_new(k, :c, 4),
                    Keyword.merge(k, a: 5, d: 6),
                    Keyword.take(k, [:a]),
                    Keyword.drop(k, [:a]),
                    Macro.escape(Keyword.pop(k, :a)),
                    Macro.escape(Keyword.pop(k, :c, :d))
                  ]
                end
                """,
            ),
            macro("pure:keyword-has-key-1-tuple", "defmacro m, do: Keyword.has_key?([{:a}], :a)"),
            macro("pure:keyword-fetch-bang-keyerror", "defmacro m, do: Keyword.fetch!([], :a)"),
            macro(
                "pure:enum",
                """
                defmacro m do
                  [
                    Enum.map([1, 2], fn x -> [x] end),
                    Enum.flat_map([1, 2], fn x -> [x, x] end),
                    Enum.filter([1, 2], fn x -> :erlang.>(x, 1) end),
                    Enum.reject([1, 2], fn x -> :erlang.>(x, 1) end),
                    Enum.reduce([1, 2], 0, fn x, acc -> :erlang.+(x, acc) end),
                    Enum.any?([false, nil]),
                    Enum.any?([1, 2], fn x -> :erlang.>(x, 1) end),
                    Enum.all?([1, nil]),
                    Enum.all?([1, 2], fn x -> :erlang.>(x, 0) end),
                    Enum.member?([1, 2], 2),
                    Enum.concat([1], [2]),
                    Enum.reverse([1, 2])
                  ]
                end
                """,
            ),
            macro(
                "pure:enum-range",
                "defmacro m, do: [Enum.map(1..3, fn x -> x end), Enum.reverse(1..3), Enum.member?(1..3, 2)]",
            ),
            macro(
                "pure:enum-range-lazy",
                "defmacro m, do: [Enum.member?(1..1_000_000_000, 1), Enum.any?(1..1_000_000_000), Enum.all?(1..3)]",
            ),
            macro("pure:enum-map", "defmacro m, do: Enum.map(%{a: 1}, fn {k, _} -> k end)", pins = DECLINED),
            macro(
                "pure:function-clause-arity",
                """
                defmacro m do
                  for f <- [fn -> Module.concat([1]) end, fn -> Keyword.get(1, :a) end] do
                    try do
                      f.()
                    rescue
                      e in FunctionClauseError -> Macro.escape({e.module, e.function, e.arity})
                    end
                  end
                end
                """,
            ),
            macro(
                "pure:access",
                """
                defmacro m do
                  [
                    Access.get([a: 1], :a),
                    Access.get(%{a: 1}, :a),
                    Access.get(%{a: 1}, :b, :d),
                    Access.get(nil, :a)
                  ]
                end
                """,
            ),
            macro("pure:access-integer-key", "defmacro m, do: Access.get([a: 1], 0)"),
            macro(
                "pure:env-context",
                "defmacro m, do: [Macro.Env.in_guard?(__CALLER__), Macro.Env.in_match?(__CALLER__)]",
            ),
            macro(
                "pure:concat",
                """defmacro m, do: [Module.concat(Foo, Bar), Module.concat([Foo, "Bar"]), Module.concat("Foo", :bar)]""",
            ),
            macro("pure:concat-empty", "defmacro m, do: Module.concat([])"),
            macro(
                "pure:concat-elixir",
                """defmacro m, do: [Module.concat(Elixir, Foo), Module.concat(["Elixir.Foo", Bar])]""",
            ),
            macro("pure:concat-leading-dot", """defmacro m, do: Module.concat(["Foo", ".Bar"])"""),
            macro("pure:concat-invalid-utf8", """defmacro m, do: Module.concat(["\\xFF"])"""),
            macro("pure:to-string", "defmacro m, do: [String.Chars.to_string(:a), String.Chars.to_string(1)]"),
            // `inspect`'s output is `Inspect`'s implementations', which aren't read.
            macro(
                "pure:inspect",
                """defmacro m, do: [Kernel.inspect(:a), Kernel.inspect([1, "b"]), Kernel.inspect(:c, [])]""",
                pins = DECLINED,
            ),
            // `inspect/2`'s options must be a list, and a list of them declines, as `Inspect.Opts` isn't read.
            macro("pure:inspect-options-not-a-list", "defmacro m, do: :erlang.element(2, {Kernel.inspect(:a, 1), :ok})"),
            macro(
                "pure:inspect-options",
                "defmacro m, do: :erlang.element(2, {Kernel.inspect(:a, pretty: 1), :ok})",
                pins = DECLINED,
            ),
            macro("pure:inspect-compared", "defmacro m, do: :erlang.==(Kernel.inspect(:a), Kernel.inspect(:b))", pins = DECLINED),
            // A segment with no size takes any binary whole, whatever its bytes; with a unit past a byte, its bytes decide.
            macro(
                "form:bitstring-unknown-binary",
                """
                defmacro m do
                  _ = <<Kernel.inspect(:a)::binary, "!">>
                  _ = <<Kernel.inspect(:a)::bitstring, 1::1>>
                  :ok
                end
                """,
            ),
            macro(
                "form:bitstring-unknown-binary-unit",
                """
                defmacro m do
                  _ = <<Kernel.inspect(:a)::binary-unit(16)>>
                  :ok
                end
                """,
                pins = DECLINED,
            ),
            macro("pure:to-string-interpolated", """defmacro m(name), do: :"#{name}?"""", "m(:a)"),
            macro(
                "pure:escape-generated",
                "defmacro m, do: [Macro.escape(1, generated: true), Kernel.elem(Macro.escape({:a, :b, :c}, generated: true), 1)]",
            ),
            macro("pure:atom-to-string", "defmacro m, do: [Atom.to_string(:a), Atom.to_string(Foo)]"),
            macro("pure:to-atom", """defmacro m, do: [String.to_atom("a"), String.to_existing_atom("a")]"""),
            // Whether an atom the evaluation hasn't seen exists is the VM's to say.
            macro(
                "pure:to-existing-atom",
                """defmacro m, do: String.to_existing_atom("interpreter_never_an_atom")""",
                pins = DECLINED,
            ),
            macro("pure:to-existing-atom-unseen", "defmacro m(s), do: String.to_existing_atom(s)", "m(\"ok\")", pins = DECLINED),
            macro(
                "pure:elem",
                "defmacro m, do: [Kernel.elem({:a, :b}, 1), Macro.escape(Kernel.put_elem({:a, :b}, 0, :c))]",
            ),
            macro("pure:elem-badarg", "defmacro m, do: Kernel.elem({1}, 1)"),
            macro("pure:info", "defmacro m, do: [String.__info__(:module), String.module_info(:module)]", pins = DECLINED),
            macro("pure:enum-info", "defmacro m, do: :erlang.length(Enum.module_info())", pins = DECLINED),
            macro(
                "pure:deep-recursion",
                """
                defmacro m, do: count(100_000)

                defp count(0), do: 0
                defp count(n), do: count(n - 1)
                """,
                pins = DECLINED,
            ),
            macro(
                "pure:binary-prefix",
                """
                defmacro m do
                  [
                    case Atom.to_string(Foo) do
                      "Elixir." <> rest -> rest
                      _ -> :no
                    end,
                    (<<"ab", r::binary>> = "abc"; r),
                    for(<<"a", c::8 <- "abac">>, do: c),
                    (<<"é"::utf8, x::8>> = <<195, 169, 1>>; x)
                  ]
                end
                """,
            ),
            macro("pure:utf-literal", """defmacro m, do: <<"é"::utf8, "a"::utf16>>"""),
            macro("pure:float-overflow", "defmacro m(x), do: <<x::32-float>>", "m(1.0e300)"),
            macro(
                "pure:float-overflow-bignum",
                "defmacro m(n), do: <<n::64-float>>",
                "m(${BigInteger.ONE.shiftLeft(1100)})",
            ),
            macro("pure:utf-overflow", "defmacro m(c), do: <<c::utf8>>", "m(4294967361)"),
            macro(
                "pure:bitstring-little-odd",
                "defmacro m, do: Macro.escape([<<0x123::12-little>>, (<<x::12-little, _::4>> = <<35, 16>>; x)])",
            ),
            // A fun in a macro's result is converted where expansion reaches it, so a macro it is passed to gets the fun.
            Row(
                "escape:fun-in-result",
                """
                defmacro inner(f) do
                  if is_function(f), do: quote(do: def(a, do: 1)), else: quote(do: def(b, do: 1))
                end

                defmacro outer, do: {{:., [], [__MODULE__, :inner]}, [], [&String.upcase/1]}
                """.trimIndent(),
                "$ROW.outer()",
                "require $ROW\n$ROW.outer()",
                "$ROW.outer()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            macro(
                "form:macro-expand",
                "defmacro m(x, y), do: {Macro.expand_once(x, __CALLER__), Macro.expand(y, __CALLER__)}",
                "m(unless(false, do: :x), __MODULE__)",
            ),
            macro(
                "form:macro-expand-env",
                "defmacro m(x), do: Macro.expand(x, %{__CALLER__ | line: 1})",
                "m(__MODULE__)",
                pins = DECLINED,
            ),
            // The read's line is the caller's, which differs between Elixir's call and the case's.
            macro(
                "form:macro-expand-attribute",
                """
                defmacro m do
                  {call, meta, [module, name, _line | rest]} = Macro.expand(quote(do: @a), __CALLER__)
                  {call, meta, [module, name, 0 | rest]}
                end
                """,
            ),
            // `do_expand_once/2` reads only the top node, so a term inside it that isn't AST doesn't stop the expansion.
            macro(
                "form:macro-expand-holding-a-term",
                "defmacro m, do: elem(Macro.expand(quote(do: unless(unquote(&List.first/1), do: 1)), __CALLER__), 0)",
            ),
            // Each call-back's evaluation makes references no other has made.
            macro(
                "form:macro-expand-fresh-reference",
                """
                defmacro r, do: make_ref()

                defmacro m do
                  r = quote(do: {row}.r())
                  Macro.expand_once(r, __CALLER__) == Macro.expand_once(r, __CALLER__)
                end
                """,
            ),
            // `alias`, `import` and `require` are their module, and a later node is dispatched as their expansion saw it.
            macro(
                "lexical:alias",
                """
                defmacro m do
                  x = alias(Enum, as: E)
                  {x, E}
                end
                """,
            ),
            macro(
                "lexical:import",
                """
                defmacro m do
                  x = import(Enum, only: [count: 1])
                  {x, count([1, 2])}
                end
                """,
            ),
            macro(
                "lexical:require",
                """
                defmacro m do
                  require Integer
                end
                """,
            ),
            macro(
                "lexical:multi-alias",
                """
                defmacro m do
                  alias Elixir.{Enum, List}
                end
                """,
            ),
            macro("form:env-field", "defmacro m, do: __ENV__.function"),
            // A key the env lacks is the call on the escaped env, which raises `KeyError` when it runs.
            macro(
                "form:env-field-missing",
                """
                defmacro m do
                  __ENV__.foo
                  1
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // `elixir_fn:capture/4` leaves a capture of a function, or else the `fn` it rewrites the capture to.
            macro("form:capture-remote-of-arguments", "defmacro m, do: (&length(&1)).([1, 2])"),
            macro(
                "form:capture-local-of-arguments",
                """
                def f(x), do: x
                defmacro m, do: (&f(&1)).(2)
                """,
            ),
            macro("form:capture-expression", "defmacro m, do: (&(&1 + 1)).(1)"),
            macro("form:capture-tuple", "defmacro m, do: (&{&1, 2}).(1)"),
            macro("form:capture-list", "defmacro m, do: (&[&1 | &2]).(1, [2])"),
            macro(
                "form:capture-variable-module",
                """
                defmacro m do
                  mod = :lists
                  {(&mod.reverse/1).([1, 2]), (&mod.reverse(&1)).([1, 2])}
                end
                """,
            ),
            // The helper places its argument in two scopes, which dispatch its call differently: each placement is
            // dispatched as its own scope's.
            macro(
                "form:argument-in-two-scopes",
                """
                require {row}.H

                defmacro m, do: {{row}.H.pick(true, length([1, 2])), {row}.H.pick(false, length([1, 2]))}
                """,
                helper = """
                    defmodule {row}.Foo do
                      def length(_), do: :foo
                    end

                    defmodule {row}.H do
                      defmacro pick(c, a) do
                        quote do
                          if unquote(c) do
                            import Kernel, except: [length: 1]
                            import {row}.Foo
                            unquote(a)
                          else
                            unquote(a)
                          end
                        end
                      end
                    end
                """,
            ),
            macro(
                "form:argument-in-an-uncalled-scope",
                """
                require {row}.H

                defmacro m, do: elem({row}.H.pair(length([1, 2])), 1)
                """,
                helper = """
                    defmodule {row}.Foo do
                      def length(_), do: :foo
                    end

                    defmodule {row}.H do
                      defmacro pair(a) do
                        quote do
                          {fn ->
                             import Kernel, except: [length: 1]
                             import {row}.Foo
                             unquote(a)
                           end, unquote(a)}
                        end
                      end
                    end
                """,
            ),
            macro(
                "try:rescue-badarg-message",
                """
                defmacro m do
                  try do
                    :erlang.binary_to_atom(1, :utf8)
                  rescue
                    e -> Exception.message(e)
                  end
                end
                """,
                pins = { _, level ->
                    "\"argument error\"".takeIf { RESCUED_ERROR_MESSAGE_FROM_ERROR_INFO.isSufficient(level) }
                },
            ),
            macro(
                "try:rescue-list-stacktrace",
                """
                defmacro m do
                  try do
                    Access.get("foo", 0)
                  rescue
                    x in [FunctionClauseError, CaseClauseError] -> [x.module, x.function, x.arity]
                  end
                end
                """,
            ),

            // `use` of library macros
            use(
                "use:bind-quoted",
                """
                @callback validate_config(keyword) :: :ok
                def validate_config(_required, _config), do: :ok
                def validate_dependency(_deps), do: :ok

                defmacro __using__(opts) do
                  quote bind_quoted: [opts: opts] do
                    @required_config opts[:required_config] || []
                    @required_deps opts[:required_deps] || []

                    @behaviour {row}

                    def validate_config(config) do
                      {row}.validate_config(@required_config, config)
                    end

                    def validate_dependency do
                      {row}.validate_dependency(@required_deps)
                    end
                  end
                end
                """,
                "required_config: [:api_key]",
            ),
            use(
                "use:keep",
                """
                defmacro __using__(_) do
                  quote location: :keep do
                    @behaviour Application

                    @doc false
                    def stop(_state) do
                      :ok
                    end

                    defoverridable Application
                  end
                end
                """,
            ),
            use(
                "use:cond",
                """
                defmacro __using__(options) do
                  except =
                    cond do
                      Keyword.get(options, :only_operators) ->
                        [bnot: 1, band: 2, bor: 2, bxor: 2, bsl: 2, bsr: 2]

                      Keyword.get(options, :skip_operators) ->
                        ["~~~": 1, &&&: 2, |||: 2, "^^^": 2, <<<: 2, >>>: 2]

                      true ->
                        []
                    end

                  quote do
                    import Bitwise, except: unquote(except)
                  end
                end
                """,
                "only_operators: true",
            ),
            use(
                "use:apply",
                """
                def static_paths, do: ~w(assets fonts)

                def controller do
                  quote do
                    import Kernel, except: [to_string: 1]
                    unquote(verified_routes())
                  end
                end

                def html do
                  quote do
                    import String, only: [upcase: 1]
                    unquote(html_helpers())
                  end
                end

                defp html_helpers do
                  quote do
                    alias String.Chars
                    unquote(verified_routes())
                  end
                end

                def verified_routes do
                  quote do
                    @statics {row}.static_paths()
                  end
                end

                defmacro __using__(which) when is_atom(which) do
                  apply(__MODULE__, which, [])
                end
                """,
                ":html",
            ),
            use(
                "use:caller",
                """
                def __setup__(_module, opts), do: [{"phx-", Keyword.get(opts, :global, true)}]

                defmacro __using__(opts \\ []) do
                  conditional =
                    if __CALLER__.module != {row}.Helpers do
                      quote do: import({row}, only: [__setup__: 2])
                    end

                  imports =
                    quote bind_quoted: [opts: opts] do
                      import Kernel, except: [to_string: 1]

                      for {prefix_match, value} <- {row}.__setup__(__MODULE__, opts) do
                        @doc false
                        def __global__?(unquote(prefix_match)), do: unquote(value)
                      end
                    end

                  [conditional, imports]
                end
                """,
                stopsAfter = "{:unquote, [], [{:prefix_match",
            ),
            use(
                "use:helpers",
                """
                @callback config(atom) :: term

                defmacro __using__(opts) do
                  quote do
                    @behaviour {row}

                    unquote(config(opts))
                    unquote(pubsub())
                    unquote(server())
                  end
                end

                defp config(opts) do
                  quote do
                    @otp_app unquote(opts)[:otp_app] || raise("endpoint expects :otp_app to be given")
                    var!(code_reloading?) = false
                    _ = var!(code_reloading?)
                  end
                end

                defp pubsub() do
                  quote generated: true do
                    def subscribe(topic, opts \\ []) when is_binary(topic) do
                      {topic, opts}
                    end
                  end
                end

                defp server() do
                  quote location: :keep, unquote: false do
                    def child_spec(opts) do
                      %{id: __MODULE__, start: {__MODULE__, :start_link, [opts]}, type: :supervisor}
                    end

                    def config(key), do: key
                  end
                end
                """,
                "otp_app: :demo",
            ),
            use(
                "use:genserver",
                """
                defmacro __using__(opts) do
                  quote location: :keep, bind_quoted: [opts: opts] do
                    @behaviour GenServer

                    if not Module.has_attribute?(__MODULE__, :doc) do
                      @doc ${"\"\"\""}
                      Returns a specification to start this module under a supervisor.
                      ${"\"\"\""}
                    end

                    def child_spec(init_arg) do
                      default = %{
                        id: __MODULE__,
                        start: {__MODULE__, :start_link, [init_arg]}
                      }

                      Supervisor.child_spec(default, unquote(Macro.escape(opts)))
                    end

                    defoverridable child_spec: 1

                    @before_compile GenServer

                    @doc false
                    def terminate(_reason, _state) do
                      :ok
                    end

                    defoverridable terminate: 2
                  end
                end
                """,
                "restart: :transient",
                stopsAfter = UNQUOTE_ESCAPED_OPTS,
            ),
            use(
                "use:backend",
                """
                defmacro __using__(opts) do
                  env_fun = if function_exported?(Module, :attributes_in, 1), do: :compile_env, else: :get_env

                  quote do
                    opts = unquote(opts)
                    otp_app = Keyword.fetch!(opts, :otp_app)
                    @gettext_opts Keyword.merge(opts, Application.unquote(env_fun)(otp_app, __MODULE__, []))
                  end
                end
                """,
                "otp_app: :demo",
            ),
            leg("GenServer", stopsAfter = UNQUOTE_ESCAPED_OPTS),
            leg("Supervisor", stopsAfter = UNQUOTE_ESCAPED_OPTS),
            leg("Agent", stopsAfter = UNQUOTE_ESCAPED_OPTS),
            leg("Task", stopsAfter = UNQUOTE_ESCAPED_OPTS),
            leg("DynamicSupervisor", stopsAfter = UNQUOTE_ESCAPED_OPTS),
            leg("Application"),
            leg("Bitwise", "only_operators: true"),
            // A result's term that isn't AST reaches `elixir_expand`'s value clauses where expansion meets it.
            macro("head:fun-anonymous", "defmacro m, do: fn -> 1 end", ends = { "raised invalid_quoted_expr" }),
            macro("head:pid-module-body", "defmacro m, do: self()"),
            Row(
                "head:pid-def-body",
                "defmacro m, do: self()",
                "$ROW.m()",
                "require $ROW\ndef f, do: $ROW.m()",
                "$ROW.m()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { level ->
                    "crashed $MATCH_ERROR".takeIf { PID_IN_FUNCTION_RAISES_MATCH_ERROR.isSufficient(level) }
                },
            ),
            // An argument's pid takes `expand_arg`'s fast path, so it never reaches the pid head.
            Row(
                "head:pid-in-list-def-body",
                "defmacro m, do: [self()]",
                "$ROW.m()",
                "require $ROW\ndef f, do: $ROW.m()",
                "$ROW.m()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            macro("head:reference", "defmacro m, do: make_ref()", ends = { "raised invalid_quoted_expr" }),
            // A quote puts a term that isn't AST in its output as it is, which expanding then raises at.
            macro("head:empty-tuple-in-quote", "defmacro m, do: {:quote, [], [[do: {}]]}", ends = { "raised invalid_quoted_expr" }),
            macro("head:map-in-quote", "defmacro m, do: {:quote, [], [[do: %{a: 1}]]}", ends = { "raised invalid_quoted_expr" }),
            macro(
                "head:four-tuple-in-quote",
                "defmacro m, do: {:quote, [], [[do: {1, 2, 3, 4}]]}",
                ends = { "raised invalid_quoted_expr" },
            ),
            // `Macro.pipe/3` builds a call of any 3-tuple with list arguments, and expanding it raises.
            macro("head:pipe-into-value", "defmacro m, do: {:|>, [], [1, {1, 2, [3]}]}", ends = { "raised invalid_quoted_expr" }),
            // Jinterface reads a list of bytes as a string.
            macro(
                "head:pipe-into-value-of-bytes",
                "defmacro m(x), do: {:|>, [], [1, {1, 2, x}]}",
                "m([3])",
                ends = { "raised invalid_quoted_expr" },
            ),
            macro("head:bad-tuple", "defmacro m, do: {1, 2, 3}", ends = { "raised invalid_quoted_expr" }),
            macro("head:legacy", "defmacro m, do: {1, [], nil}", ends = { "raised invalid_quoted_expr" }),
            macro("head:improper-list-in-map", "defmacro m, do: %{a: [1 | 2]}", ends = { "raised invalid_quoted_expr" }),
            // Elixir takes each for a form or a call, whose metadata can't be read.
            macro("head:unreadable-meta", "defmacro m, do: {:+, [x: 1.0], [1, 2]}", pins = DECLINED),
            macro("head:non-list-meta-form", "defmacro m, do: {:__block__, :x, [1]}", pins = DECLINED),
            macro("head:list-meta-call", "defmacro m, do: {1, [1], []}", pins = DECLINED),
            // Linifying the result raises before anything expands.
            macro("head:improper-list", "defmacro m, do: [1 | 2]"),
            macro("head:linify-lexical-meta", "defmacro m, do: {:alias, :x, [1]}"),
            // `Macro.expand_once/2`'s call has no line, so before 1.20 Elixir's linify doesn't read the meta there.
            macro(
                "head:improper-meta",
                "defmacro m, do: {:foo, [1 | 2], []}",
                pins = { _, level ->
                    "{:raise, ArgumentError}".takeUnless { MACRO_ARGUMENTS_STOP_GENERATED.isSufficient(level) }
                },
                ends = { "raised Elixir.ArgumentError" },
            ),
            // The `def` before the reference is stored, though the module raises before `@before_compile` runs.
            Row(
                "head:reference-after-def",
                "defmacro m, do: {:__block__, [], [quote(do: def(f, do: 1)), make_ref()]}",
                "$ROW.m()",
                "require $ROW\n$ROW.m()",
                "$ROW.m()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { "raised invalid_quoted_expr" },
                table = "[f/0 def]",
            ),
            // Two bitstrings matched in parallel raise, but not the one side of a match against itself.
            Row(
                "head:duplicate-match",
                """
                defmacro m do
                  b = quote(do: <<a>>)
                  quote(do: unquote(b) = unquote(b))
                end
                """.trimIndent(),
                "$ROW.m()",
                "require $ROW\ndef f($ROW.m()), do: :ok",
                "$ROW.m()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { level ->
                    "raised parallel_bitstring_match".takeUnless { DUPLICATE_MATCH_UNPACKED_ONCE.isSufficient(level) }
                },
            ),
            // An unknown that decides whether a 3-tuple is a form declines, since Elixir decides on the real value.
            macro("head:unknown-meta", "defmacro m, do: {1, __CALLER__.aliases, []}", pins = DECLINED),
            macro("head:unknown-head-and-meta", "defmacro m, do: {__CALLER__.lexical_tracker, __CALLER__.tracers, []}", pins = DECLINED),
            // `Macro.expand/2` reads each as the expander does, before any result is linified.
            macro("head:unknown-meta-expanded", "defmacro m, do: (_ = Macro.expand({1, __CALLER__.aliases, []}, __CALLER__); :ok)", pins = DECLINED),
            macro(
                "head:unknown-head-and-meta-expanded",
                "defmacro m, do: (_ = Macro.expand({__CALLER__.lexical_tracker, __CALLER__.tracers, []}, __CALLER__); :ok)",
                pins = DECLINED,
            ),
            macro(
                "head:pipe-into-unknown",
                """
                defmacro m do
                  t = {__CALLER__.lexical_tracker, __CALLER__.lexical_tracker, __CALLER__.tracers}
                  {:|>, [], [1, t]}
                end
                """,
                pins = DECLINED,
            ),
            // A rescue pattern that is a 3-tuple with metadata that isn't a list.
            macro(
                "rescue:value-pattern",
                "defmacro m, do: {:try, [], [[do: :ok, rescue: [{:->, [], [[{1, :x, 2}], :ok]}]]]}",
                ends = { level ->
                    if (RESCUE_CALL_EXPANDED_AS_MACRO.isSufficient(level)) "crashed Elixir.FunctionClauseError" else "raised invalid_quoted_expr"
                },
            ),
            // Linifying reads an unknown metadata as Elixir reads the list it may be.
            macro(
                "head:linify-unknown-meta",
                "defmacro m, do: [{:a, __CALLER__.aliases, [1 | 2]}, {:b, [{:line, 1} | 3], []}]",
                pins = DECLINED,
            ),
            // `rescue x in Alias` before Elixir reads only the key's presence.
            macro(
                "rescue:unknown-exception-flag",
                """
                @flag Enum.member?([1], 1)
                defmacro m do
                  try do
                    :erlang.error(%{__struct__: ArgumentError, __exception__: @flag, message: "x"})
                  rescue
                    ArgumentError -> :a
                    _ -> :b
                  end
                end
                """,
                pins = { source, level -> DECLINED_FROM_SOURCE(source, level)?.takeUnless { RESCUE_EXCEPTION_KEY_PRESENT.isSufficient(level) } },
            ),
            // `-0.0` and `0.0` are two terms from OTP 27.
            macro("pure:negative-zero-exact", "defmacro m, do: (if -0.0 === 0.0, do: :same, else: :different)"),
            // A map's keys compare as `=:=` does, under `==` and `<` too.
            macro("pure:negative-zero-map-key", "defmacro m, do: {%{-0.0 => 1} == %{0.0 => 1}, %{-0.0 => 1} < %{0.0 => 1}}"),
            // A map holds one key for each term `=:=` tells apart, however it is built or read.
            macro("pure:negative-zero-map-put", "defmacro m, do: map_size(Map.put(%{0.0 => 1}, -0.0, 2))", pins = KEY_KEPT_BELOW_OTP_27),
            macro("pure:negative-zero-map-literal", "defmacro m, do: map_size(%{0.0 => 1, -0.0 => 2})", pins = KEY_KEPT_BELOW_OTP_27),
            macro("pure:negative-zero-map-get", "defmacro m, do: Map.get(%{0.0 => :a}, -0.0)"),
            macro("pure:negative-zero-map-pattern", "defmacro m, do: (case %{0.0 => :a} do %{-0.0 => v} -> v; _ -> :none end)"),
            macro(
                "pure:negative-zero-map-merge",
                "defmacro m, do: map_size(Map.merge(%{0.0 => :a}, %{-0.0 => :b}))",
                pins = KEY_KEPT_BELOW_OTP_27,
            ),
            macro(
                "pure:negative-zero-map-from-list",
                "defmacro m, do: map_size(:maps.from_list([{0.0, :a}, {-0.0, :b}]))",
                pins = KEY_KEPT_BELOW_OTP_27,
            ),
            // A repeated key is one key, though only the VM orders the references it holds.
            macro(
                "pure:map-key-repeated-holding-references",
                """
                defmacro m do
                  k = %{make_ref() => 1, make_ref() => 2}
                  map_size(%{k => :first, k => :second})
                end
                """,
            ),
            macro(
                "pure:map-key-rebuilt-holding-references",
                """
                defmacro m do
                  a = make_ref()
                  b = make_ref()
                  map_size(%{%{a => 1, b => 2} => :first, %{a => 1, b => 2} => :second})
                end
                """,
            ),
            macro(
                "pure:map-key-repeated-holding-references-and-a-float",
                """
                defmacro m do
                  k = %{make_ref() => 1.0, make_ref() => 2}
                  map_size(%{k => :first, k => :second})
                end
                """,
            ),
            macro(
                "pure:access-fetch-unknown-ok",
                """
                defmacro m do
                  try do
                    Access.get(%{__struct__: {row}.S}, :a)
                  catch
                    :error, reason -> elem(reason, 0)
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
                helper = """
                defmodule {row}.S do
                  @ok String.to_atom("ok")
                  def fetch(_, _), do: {@ok, 1}
                end
                """,
            ),
            // `elixir_quote:annotate/2` replaces a definition head's `context`, wherever it is in the metadata.
            macro(
                "quote:def-head-unknown-context",
                """
                @c List.to_tuple([:context, Foo])
                defmacro m do
                  {_, _, [{:f, meta, _} | _]} =
                    quote context: Bar, do: unquote(Kernel).unquote(:def)(unquote({:f, [@c], []}), do: 1)
                  length(meta)
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // `lists:filter/2` calls its fun on each element before it reaches an improper tail.
            macro(
                "pure:keyword-take-improper",
                """
                defmacro m do
                  try do
                    Keyword.take([1 | :x], [:a])
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
            ),
            macro(
                "pure:keyword-merge-improper",
                """
                defmacro m do
                  try do
                    Keyword.merge([1 | :x], [b: 1])
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
            ),
            macro("pure:escape-unquote-not-true", "defmacro m, do: Macro.escape({:unquote, [], [{:x, [], nil}]}, unquote: :yes)"),
            // The VM's largest integer is 65,535 words; before OTP 26.0.2 it depends on the patch release.
            macro(
                "pure:integer-past-system-limit",
                """
                defmacro m do
                  _ = Enum.reduce(1..22, 2, fn _, a -> a * a end)
                  :ok
                end
                """,
                pins = { _, level -> OPAQUE.takeUnless { INTEGERS_UP_TO_4_MBIT.isSufficient(level) } },
            ),
            // Every segment's value is evaluated before any is written.
            macro(
                "bits:segment-values-first",
                """
                defmacro m do
                  a = __CALLER__.module
                  <<a, Keyword.fetch!([], :missing)>>
                end
                """,
            ),
            macro(
                "pure:flatten-improper-args",
                """
                defmacro m do
                  try do
                    :lists.flatten([[1 | :a], 2])
                  rescue
                    _ ->
                      [{m, f, args, _} | _] = __STACKTRACE__
                      Macro.escape({m, f, args})
                  end
                end
                """,
            ),
            macro(
                "pure:keyword-delete-args",
                """
                defmacro m do
                  try do
                    Keyword.delete([{:a, 1}, 3], :a)
                  rescue
                    _ ->
                      [{m, f, args, _} | _] = __STACKTRACE__
                      Macro.escape({m, f, args})
                  end
                end
                """,
            ),
            // A struct whose module isn't known may escape itself, so its module is needed before `A:`, which sorts
            // first. From `Dbgi` its module is known. Before entries are quoted apart, the last is quoted first.
            macro(
                "escape:unknown-struct-escapes-itself",
                """
                @mod Application.compile_env(:app, :mod, {row}.W)
                defmacro m, do: Macro.escape(%{A: fn -> :ok end, __struct__: @mod})
                """,
                pins = { source, level ->
                    OPAQUE.takeIf {
                        REGEX_ESCAPED_IN_FIXED_FIELD_ORDER.isSufficient(level) ||
                            source == Source.SOURCE && !ESCAPED_MAP_ENTRIES_QUOTED_APART.isSufficient(level)
                    }
                },
                helper = """
                defmodule {row}.W do
                  defstruct [:f]
                  def __escape__(_), do: :escaped
                end
                """,
            ),
            // Before Elixir sorts them, a large map's entries are escaped in the order of their hashes.
            macro(
                "escape:large-map-raises-in-hash-order",
                """
                @v [{:unquote_splicing, [], [[1]]} | :t]
                defmacro m do
                  Macro.escape(
                    %{
                      a1: 1, a2: 2, a3: 3, a4: 4, a5: 5, a6: 6, a7: 7, a8: 8, a9: 9, a10: 10, a11: 11, a12: 12, a13: 13,
                      a14: 14, a15: 15, a16: 16, a17: 17, a18: 18, a19: 19, a20: 20, a21: 21, a22: 22, a23: 23, a24: 24,
                      a25: 25, a26: 26, a27: 27, a28: 28, a29: 29, a30: 30, a31: 31, f: fn -> :ok end, v: @v
                    },
                    unquote: true
                  )
                end
                """,
                legs = { !ESCAPED_MAP_ENTRIES_SORTED.isSufficient(it) },
                pins = DECLINED,
            ),
            // An entry that isn't known may raise before the one that does, in an order no term decides. From `Dbgi`
            // it is known.
            macro(
                "escape:large-map-unknown-entry",
                """
                @u Application.compile_env(:app, :u, 1)
                defmacro m do
                  Macro.escape(%{
                    a1: 1, a2: 2, a3: 3, a4: 4, a5: 5, a6: 6, a7: 7, a8: 8, a9: 9, a10: 10, a11: 11, a12: 12, a13: 13,
                    a14: 14, a15: 15, a16: 16, a17: 17, a18: 18, a19: 19, a20: 20, a21: 21, a22: 22, a23: 23, a24: 24,
                    a25: 25, a26: 26, a27: 27, a28: 28, a29: 29, a30: 30, a31: 31, f: fn -> :ok end, u: @u
                  })
                end
                """,
                legs = { !ESCAPED_MAP_ENTRIES_SORTED.isSufficient(it) },
                pins = DECLINED_FROM_SOURCE,
            ),
            // A quote keeps the imports a node of a macro's result already holds.
            macro(
                "quote:kept-imports",
                """
                import {row}.U, only: [upcase: 2]
                require {row}.H
                defmacro n, do: {row}.H.h()
                """,
                "n()",
                helper = """
                defmodule {row}.U do
                  def upcase(a, b), do: {a, b}
                end

                defmodule {row}.H do
                  defmacro h, do: {:quote, [], [[do: {:upcase, [imports: [{1, String}], context: String], ["a"]}]]}
                end
                """,
                // Before 1.17 a quote reads `import:`, so `upcase/1` is a function the module doesn't define.
                ends = { level -> "raised undefined_function".takeUnless { QUOTE_KEEPS_IMPORTS_META.isSufficient(level) } },
            ),
            // From 1.14 to 1.16 a quote writes `imports: false` beside a node's `import:`, and its dispatch raises.
            macro(
                "quote:kept-import",
                """
                require {row}.H
                defmacro n, do: {row}.H.h()
                """,
                "n()",
                helper = """
                defmodule {row}.H do
                  defmacro h, do: {:quote, [], [[do: {:upcase, [import: String], ["a"]}]]}
                end
                """,
                legs = { QUOTE_IMPORTS_EVERY_ARITY.isSufficient(it) && !QUOTE_KEEPS_IMPORTS_META.isSufficient(it) },
                ends = { "stopped at `$ROW.n()`" },
            ),
            // A node's line is its first `line` entry, an integer of any size, or 0.
            Row(
                "meta:repeated-line-kept",
                """
                require {row}.G
                defmacro n do
                  ast = Macro.expand(quote(do: {row}.G.m()), __CALLER__)
                  Macro.escape(Keyword.get_values(elem(ast, 1), :line))
                end
                """.trimIndent(),
                "$ROW.n()",
                "require $ROW\nrequire $ROW.G\n_ = $ROW.n()",
                "$ROW.n()",
                listOf(ROW, "$ROW.G"),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                helper = """
                defmodule {row}.G do
                  defmacro m, do: {:foo, [line: 1, line: :x], []}
                end
                """.trimIndent(),
            ),
            macro(
                "meta:line-past-int",
                """
                require {row}.G
                defmacro n, do: {row}.G.m()
                """,
                "n()",
                helper = """
                defmodule {row}.G do
                  defmacro m, do: {{:., [], [{:__ENV__, [line: 3_000_000_000], nil}, :line]}, [], []}
                end
                """,
                // A node's line is an `Int`, so from source the module stops at `n`'s body; `Dbgi` holds the line it expanded to.
                pins = { source, _ -> UNPORTED.takeIf { source == Source.SOURCE } },
            ),
            macro(
                "meta:line-not-an-integer",
                """
                require {row}.G
                defmacro n, do: {row}.G.m()
                """,
                "n()",
                helper = """
                defmodule {row}.G do
                  defmacro m, do: {{:., [], [{:__ENV__, [line: :x, line: 5], nil}, :line]}, [], []}
                end
                """,
            ),
            // An alias of no segments has no clause in `elixir_aliases:expand`.
            macro("head:empty-alias", "defmacro m, do: {:__aliases__, [], []}", pins = DECLINED),
            macro("form:macro-expand-empty-alias", "defmacro m, do: Macro.expand({:__aliases__, [], []}, __CALLER__)", pins = DECLINED),
            // `elixir_aliases:expand/4` raises `system_limit` on an alias past an atom's length.
            macro(
                "form:macro-expand-alias-past-an-atom",
                "defmacro m, do: (try do Macro.expand({:__aliases__, [], [:${"A".repeat(250)}]}, __CALLER__) rescue e -> e.__struct__ end)",
                pins = DECLINED,
            ),
            macro(
                "head:defimpl-empty-alias",
                "defmacro m, do: {:defimpl, [], [{:__aliases__, [], []}, [for: Integer], [do: nil]]}",
                pins = DECLINED,
            ),
            macro(
                "in:unescaped-map",
                "defmacro m(x), do: {:in, [], [x, %{a: 1}]}",
                "m(1)",
                ends = { level ->
                    if (IN_REFUSES_UNESCAPED_RIGHT_SIDE.isSufficient(level)) "raised Elixir.ArgumentError" else "raised invalid_quoted_expr"
                },
            ),
            // `for` checks its first case after expanding it. From 1.18 the type checker reads the `<-/2` it expands to
            // as a generator; before, it is a call of a local function the module doesn't define.
            Row(
                "for:generator-from-macro",
                "defmacro gen, do: quote(do: 1 <- [1])",
                "$ROW.gen()",
                "require $ROW\ndef f, do: for($ROW.gen(), do: 1)",
                "$ROW.gen()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { level ->
                    when {
                        POST_MODULE_LOCAL_CHECKS_TYPED.isSufficient(level) -> null
                        FUNCTION_ERRORS_CONTINUE.isSufficient(level) -> "tainted"
                        else -> "raised undefined_function"
                    }
                },
            ),
            // An expanded bitstring's segments are each typed, so it isn't a generator.
            Row(
                "for:bitstring-generator-from-macro",
                "defmacro gen, do: quote(do: <<1 <- \"a\">>)",
                "$ROW.gen()",
                "require $ROW\ndef f, do: for($ROW.gen(), do: 1)",
                "$ROW.gen()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { "raised for_generator_start" },
            ),
            // What a `@before_compile` callback defines is the module's, and `Module.defines?/2,3` answers from the
            // table wherever the module compiles, declining where Elixir's table may differ.
            macro(
                "before_compile:defines",
                "defmacro m, do: Macro.escape({row}.U.f())",
                helper = """
                defmodule {row}.CB do
                  defmacro __before_compile__(_env), do: quote(do: def(f, do: 1))
                end

                defmodule {row}.U do
                  @before_compile {row}.CB
                end
                """,
            ),
            // The callback's output is a statement of the module body, so its attribute writes are the module's.
            macro(
                "before_compile:attribute",
                "defmacro m, do: Macro.escape({row}.UA.r())",
                helper = """
                defmodule {row}.CA do
                  defmacro __before_compile__(_env) do
                    quote do
                      @x 1
                      def r, do: @x
                    end
                  end
                end

                defmodule {row}.UA do
                  @before_compile {row}.CA
                end
                """,
            ),
            macro(
                "before_compile:defines-kind",
                "defmacro m, do: Macro.escape({row}.K.r())",
                helper = """
                defmodule {row}.CK do
                  defmacro __before_compile__(env) do
                    t = {
                      Module.defines?(env.module, {:f, 0}, :def),
                      Module.defines?(env.module, {:f, 0}, :defp),
                      Module.defines?(env.module, {:g, 0}, :def)
                    }

                    quote(do: def(r, do: unquote(Macro.escape(t))))
                  end
                end

                defmodule {row}.K do
                  @before_compile {row}.CK
                  def f, do: 1
                end
                """,
            ),
            // The first callback's env is its caller's, so `Macro.expand/2` expands in it.
            macro(
                "before_compile:expands-in-env",
                "defmacro m, do: Macro.escape({row}.UE.r())",
                helper = """
                defmodule {row}.CE do
                  defmacro __before_compile__(env) do
                    expanded = Macro.expand(quote(do: __MODULE__), env)

                    quote(do: def(r, do: unquote(expanded)))
                  end
                end

                defmodule {row}.UE do
                  @before_compile {row}.CE
                end
                """,
            ),
            // A body's macro sees what was stored before the body; one at the module body's top level, an empty table.
            macro(
                "before_compile:defines-at-expansion",
                "defmacro m, do: Macro.escape({{row}.X.in_def(), {row}.XB.at_body()})",
                helper = DEFINES + "\n\n" + """
                defmodule {row}.X do
                  require {row}.D
                  def f, do: 1
                  def in_def, do: {row}.D.d(:f, 0)
                end

                defmodule {row}.XB do
                  require {row}.D
                  def f, do: 1
                  @at_body {row}.D.d(:f, 0)
                  def at_body, do: @at_body
                end
                """.trimIndent(),
            ),
            // Both entries are unordered, so either may be absent in Elixir.
            macro(
                "before_compile:defines-branch",
                "defmacro m, do: Macro.escape({{row}.N.r(), {row}.T.r()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = DEFINES_AT_CALLBACK + "\n\n" + """
                defmodule {row}.N do
                  @before_compile {row}.CC
                  if false do
                    def f, do: 1
                  end
                end

                defmodule {row}.T do
                  @before_compile {row}.CC
                  if true do
                    def f, do: 1
                  end
                end
                """.trimIndent(),
            ),
            // A stop can hide a removal, so after one nothing is answered.
            macro(
                "before_compile:defines-after-stop",
                "defmacro m, do: Macro.escape({{row}.Y.g(), {row}.Y.in_def()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = DEFINES + "\n\n" + """
                defmodule {row}.Y do
                  require {row}.D
                  def unquote(Application.get_env(:app, :name, :g))(), do: 1
                  def in_def, do: {row}.D.d(:f, 0)
                end
                """.trimIndent(),
            ),
            macro(
                "before_compile:defines-overridable-branch",
                "defmacro m, do: Macro.escape({{row}.O.g(), {row}.O2.g()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "O" to "  if false do\n    defoverridable f: 0\n  end",
                    "O2" to "  if true do\n    defoverridable f: 0\n  end",
                ),
            ),
            macro(
                "before_compile:defines-after-overridable",
                "defmacro m, do: Macro.escape({row}.OV.g())",
                helper = definesAround("OV" to "  defoverridable f: 0"),
            ),
            macro(
                "before_compile:defines-after-overridable-stop",
                "defmacro m, do: Macro.escape({row}.V.g())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround("V" to "  defoverridable Application.get_env(:app, :ov, f: 0)"),
            ),
            // Each call of a remover by name reaches the table or stops the module.
            macro(
                "before_compile:defines-after-delete",
                "defmacro m, do: Macro.escape({row}.DD.g())",
                legs = DELETES_DEFINITIONS,
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround("DD" to "  Module.delete_definition(__MODULE__, {:f, 0})"),
            ),
            macro(
                "before_compile:defines-after-nested-delete",
                "defmacro m, do: Macro.escape({{row}.ND.g(), {row}.NO.g()})",
                legs = DELETES_DEFINITIONS,
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "ND" to "  defmodule Inner do\n    Module.delete_definition({row}.ND, {:f, 0})\n  end",
                    "NO" to "  defmodule Inner do\n    Module.make_overridable({row}.NO, f: 0)\n  end",
                ),
            ),
            // A nested module's stop is its enclosing module's, so its definitions asking after the enclosing module
            // decline too.
            macro(
                "before_compile:defines-in-nested-after-stop",
                "defmacro m, do: Macro.escape({row}.NS.Inner.g())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.DM do
                  defmacro dm(module, name, arity), do: Module.defines?(Macro.expand(module, __CALLER__), {name, arity})
                end

                defmodule {row}.NS do
                  def f, do: 1

                  defmodule Inner do
                    require {row}.DM
                    Module.make_overridable({row}.NS, f: 0)
                    def g, do: {row}.DM.dm({row}.NS, :f, 0)
                  end
                end
                """.trimIndent(),
            ),
            macro(
                "before_compile:defines-after-unknown-delete",
                "defmacro m, do: Macro.escape({{row}.UD.g(), {row}.UT.g()})",
                legs = DELETES_DEFINITIONS,
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "UD" to "  m = Application.get_env(:app, :mod, Module)\n  m.delete_definition(__MODULE__, {:f, 0})",
                    "UT" to "  Module.delete_definition(Application.get_env(:app, :t, __MODULE__), {:f, 0})",
                ),
            ),
            macro(
                "before_compile:defines-after-unknown-overridable",
                "defmacro m, do: Macro.escape({{row}.UO.g(), {row}.UM.g()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "UO" to "  Module.make_overridable(Application.get_env(:app, :t, __MODULE__), f: 0)",
                    "UM" to "  m = Application.get_env(:app, :mod, Module)\n  m.make_overridable(__MODULE__, f: 0)",
                ),
            ),
            macro(
                "before_compile:defines-after-take",
                "defmacro m, do: Macro.escape({{row}.ED.g(), {row}.TU.g()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "ED" to "  :elixir_def.take_definition(__MODULE__, {:f, 0})",
                    "TU" to "  m = Application.get_env(:app, :mod, :elixir_def)\n  m.take_definition(__MODULE__, {:f, 0})",
                ),
            ),
            // `GenServer.__before_compile__` asks `Module.defines?/2` whether the module defines `init/1`. It is named
            // without `use GenServer`, whose fragments stop from source before the callback.
            macro(
                "before_compile:genserver",
                "defmacro m, do: Macro.escape({row}.B.init(1))",
                helper = """
                defmodule {row}.B do
                  @before_compile GenServer
                  def init(x), do: {:ok, {:mine, x}}
                end
                """,
            ),
            // Without `init/1` the callback warns through functions the evaluator doesn't port.
            macro(
                "before_compile:genserver-without-init",
                "defmacro m, do: Macro.escape(function_exported?({row}.A, :init, 1))",
                pins = DECLINED_FROM_SOURCE,
                helper = """
                defmodule {row}.A do
                  @before_compile GenServer
                end
                """,
            ),
        )
    }
}
