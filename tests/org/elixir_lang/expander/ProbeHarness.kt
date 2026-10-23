package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.lowering.Meta
import org.elixir_lang.lowering.expressionNodes
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Compiles case module bodies through [Quoter.compile], one batch per call, with a probe macro after each statement
 * that sends its `__CALLER__` back, and an identity probe macro around each of a case's [Case.identities], which sends
 * its `__CALLER__` and returns its argument. Each probe is a macro expansion, so its module's hygiene counter advances
 * once it returns; each also sends the counter as it was before that, as `counter_before`.
 * Probes are inserted on their statement's own line, so lines are kept and columns are not.
 *
 * The harness's modules carry a token unique to the compile: test forks share one quoter node, and two compiles
 * defining the same module at once would make Elixir raise. So a case body may define only modules nested in its case
 * module.
 *
 * With a [Hook], the harness hooks the case module and every `defmodule` a case body holds, at any depth, outside the
 * quoted code of a `quote`, by one call to the probe module's `hook/0` as the first thing in the module's body. A case
 * never names the hook.
 *
 * @param parse parses source text into a file, whose top-level expressions are the statements
 */
class ProbeHarness(private val parse: (String) -> ElixirFile) {
    /**
     * `{case, block, statement}`: statement 0 is the start of the block, statement `n` follows its `n`th statement.
     * Block 0 is the case body, and block `n` the `n`th of [Case.bodies]. An identity probe has [identity], its
     * 1-based index in [Case.identities], and the top-level statement it is in.
     */
    data class Tag(val case: Int, val block: Int, val statement: Int, val identity: Int = 0) {
        override fun toString() = listOfNotNull(case, block, statement, identity.takeIf { it > 0 }).joinToString(".")
    }

    /**
     * A case body, the ranges of it to wrap in an identity probe, which must not overlap, and the statements of each
     * body nested in it, such as a `->` clause's.
     *
     * @property value the 1-based top-level statement after which the run-time value of the case's variable `q` is
     *   sent, as a module-body statement of its own with no probe after it
     */
    class Case(
        val body: String,
        val identities: List<TextRange> = emptyList(),
        val bodies: List<List<TextRange>> = emptyList(),
        val value: Int? = null,
    )

    /**
     * What the probe at [tag] saw: `__CALLER__` as a map, and its module's hygiene counter before it took one, or
     * `null` outside any module.
     */
    data class Observation(val tag: Tag, val env: OtpErlangMap, val counterBefore: Long?)

    /**
     * [probeModule] and [caseModule] are atom text, as [Env] holds modules.
     *
     * @property values each case's [Case.value] as it was sent, by case
     * @property hooks what the hook saw of each module it ran in, by module
     * @property finals the value of each of [Hook.attributes] when each module's body ended, by module
     * @property definitionAttributes what each definition's clauses saw of [DEFINITION_ATTRIBUTES], by module
     * @property reads what each read function returned once its module compiled, by module and then by name
     * @property docs each compiled module's Docs chunk, by module
     */
    class Batch(
        private val token: String,
        val observations: List<Observation>,
        val values: Map<Int, OtpErlangObject> = emptyMap(),
        val hooks: Map<String, List<HookEntry>> = emptyMap(),
        val finals: Map<String, Map<String, OtpErlangObject>> = emptyMap(),
        val definitionAttributes: Map<String, List<ClauseAttributes>> = emptyMap(),
        val reads: Map<String, Map<String, OtpErlangObject>> = emptyMap(),
        val docs: Map<String, Docs> = emptyMap(),
    ) {
        val probeModule = "Elixir." + probeModule(token)

        fun caseModule(case: Int) = "Elixir." + caseModule(token, case)
    }

    /**
     * A compile that may have failed.
     *
     * @property tags every probe inserted
     * @property bodyLines the source line of each case body's first line
     * @property source the probed source compiled
     */
    class Attempt(
        val compiled: Quoter.Compiled,
        val tags: List<Tag>,
        val bodyLines: List<Int>,
        val batch: Batch,
        val source: String,
        val layout: Layout,
    )

    /**
     * A batch laid out for one compile: [cases], whose bodies already have `{token}` replaced by [token], after the
     * probe module, the hook module if there is a [hook], and [preamble]'s modules, and the top-level probe at the end
     * of the file if [top].
     *
     * The expander reads [plain]: the source without the harness's own text. The probes are left out, and the probe
     * module's and the hook's bodies, the hook's header, the `require` of the probe module and the step-0 probe are
     * blanked to their newlines, so lines are kept. The hook calls in nested modules are left out as the probes are. The
     * top-level probe is `:ok` on its line, so the file still holds more than modules.
     *
     * @property bodyStarts where each case body starts in [plain]
     * @property bodyLines the line each case body starts on, in [plain] and in the compiled source alike
     * @property topLine the top-level probe's line, if there is one
     */
    class Layout(
        val token: String,
        val cases: List<Case>,
        val preamble: String = "",
        val hook: Hook? = null,
        val top: Boolean = false,
    ) {
        val probeModule = "Elixir." + probeModule(token)
        val hookModule = "Elixir." + hookModule(token)
        val plain: String
        val bodyStarts: List<Int>
        val bodyLines: List<Int>
        val topLine: Int?

        init {
            val written = write(this, null) { _, case -> case.body }

            plain = written.source
            bodyStarts = written.bodyStarts
            bodyLines = written.bodyLines
            topLine = written.topLine
        }

        fun caseModule(case: Int) = "Elixir." + caseModule(token, case)
    }

    /**
     * The hook a case module names in its header, which reports to the harness:
     * - `__before_compile__/1`: each definition, and the value of each of [attributes];
     * - `on_def/6`, an `@on_definition` callback: the [DEFINITION_ATTRIBUTES] each clause is defined with, which
     *   Elixir's own callback, run after it, takes, and the `@on_definition` callbacks it is defined with;
     * - `__after_compile__/2`, once the module has compiled and loaded: the value each of its read functions returns,
     *   the public zero-arity functions whose names start `r_`, and each function's and macro's doc and deprecation
     *   as the Docs chunk of its bytecode holds them, which is where the compiler merges its clauses'.
     *
     * @property attributes names of the attributes whose values at the end of the module body are sent
     */
    data class Hook(val attributes: List<String> = emptyList())

    /**
     * A function's or macro's entry in a module's Docs chunk.
     *
     * @property doc its doc's text, `:hidden` for `@doc false`, or `:none` where it has none
     * @property deprecated its `deprecated` metadata, or `nil`
     * @property guard whether its metadata holds `guard: true`, which `@doc guard: true` puts there
     */
    data class DocEntry(
        val kind: String,
        val name: String,
        val arity: Int,
        val doc: OtpErlangObject,
        val deprecated: OtpErlangObject,
        val guard: Boolean = false,
    )

    /**
     * A module's Docs chunk as `__after_compile__/2` read it.
     *
     * @property enabled the `:docs` compiler option the module compiled with
     * @property entries its functions and macros, or `null` where its bytecode has no Docs chunk
     */
    data class Docs(val enabled: Boolean, val entries: List<DocEntry>?)

    /**
     * What a clause of [name]/[arity] saw of [DEFINITION_ATTRIBUTES] as it was defined, each `nil` where unset.
     *
     * @property line the line of the definition, which is its `env.line`
     * @property callbacks the `{module, function}` of each `@on_definition` callback Elixir runs for the clause
     */
    data class ClauseAttributes(
        val kind: String,
        val name: String,
        val arity: Int,
        val line: Int,
        val values: Map<String, OtpErlangObject>,
        val callbacks: List<Pair<String, String>>,
    )

    /**
     * A definition as the hook saw it before its module compiled. Before `Module.get_definition/2` (1.12) only the
     * name, arity and kind are known.
     *
     * @property default whether the definition's meta holds `:context`, which from 1.20 marks a default arity's
     */
    data class HookEntry(
        val name: String,
        val arity: Int,
        val kind: String,
        val line: Int?,
        val clauses: Int?,
        val default: Boolean?,
    )

    /** Compiles each of [bodies] as the body of a module of its own. */
    fun compile(bodies: List<String>): Batch = compileCases(bodies.map { Case(it) })

    /** Compiles each of [cases] as the body of a module of its own, which must compile and deliver every probe. */
    fun compileCases(cases: List<Case>): Batch {
        val attempt = attempt(cases)

        assertTrue(
            "compile failed: ${inspect(attempt.compiled.status)} ${attempt.compiled.diagnostics.map(::inspect)}\n" +
                attempt.source,
            attempt.compiled.status == OtpErlangAtom("ok")
        )
        assertEquals("probes that reported", attempt.tags.sorted(), attempt.batch.observations.map { it.tag }.sorted())

        return attempt.batch
    }

    /**
     * Compiles each of [cases] as the body of a module of its own, after [preamble]'s modules, and returns what
     * happened, however it ended.
     */
    fun attempt(cases: List<Case>, preamble: String = ""): Attempt = attempt(Layout(token(), cases, preamble))

    /** Compiles [layout], and returns what happened, however it ended. */
    fun attempt(layout: Layout): Attempt {
        val tags = mutableListOf<Tag>()
        val written = write(layout, tags) { index, case -> probed(probeModule(layout.token), index, case, tags, layout.hook != null) }
        val compiled = Quoter.compile(written.source, COMPILE_TIMEOUT)
        val values = mutableMapOf<Int, OtpErlangObject>()
        val hooks = mutableMapOf<String, List<HookEntry>>()
        val finals = mutableMapOf<String, Map<String, OtpErlangObject>>()
        val definitionAttributes = mutableMapOf<String, MutableList<ClauseAttributes>>()
        val reads = mutableMapOf<String, Map<String, OtpErlangObject>>()
        val docs = mutableMapOf<String, Docs>()
        val observations = mutableListOf<Observation>()

        for (message in compiled.messages) {
            val elements = (message as OtpErlangTuple).elements()

            when (elements[0]) {
                OtpErlangAtom("value") -> values[(elements[1] as OtpErlangLong).intValue()] = elements[2]
                OtpErlangAtom("hook") -> hooks[atom(elements[1])] = hookEntries(elements[2])
                OtpErlangAtom("final") -> finals[atom(elements[1])] = named(elements[2])
                OtpErlangAtom("on_def") ->
                    definitionAttributes.getOrPut(atom(elements[1])) { mutableListOf() } += clauseAttributes(elements)
                OtpErlangAtom("reads") -> reads[atom(elements[1])] = named(elements[2])
                OtpErlangAtom("docs") -> docs[atom(elements[1])] = docs(elements[2], elements[3])
                else -> observations += observation(message)
            }
        }

        return Attempt(
            compiled,
            tags,
            written.bodyLines,
            Batch(layout.token, observations, values, hooks, finals, definitionAttributes, reads, docs),
            written.source,
            layout,
        )
    }

    /** Compiles [source] as it is, with no probes. */
    fun compileSource(source: String): Quoter.Compiled = Quoter.compile(source, COMPILE_TIMEOUT)

    /** A token unique to one compile, which names its modules. */
    fun token(): String = TOKEN_PREFIX + UUID.randomUUID().toString().replace("-", "")

    /**
     * Compiles a module whose body reports `Code.get_compiler_option([name])` as its compile sees it.
     */
    fun compilerOption(name: String): OtpErlangObject {
        val module = probeModule("ProbeOption" + UUID.randomUUID().toString().replace("-", ""))
        val compiled = Quoter.compile(
            """
            defmodule $module do
              defmacro o do
                IntellijElixir.Quoter.Probe.send(__CALLER__, Code.get_compiler_option(:$name))
                nil
              end
            end

            defmodule $module.Case do
              require $module
              $module.o()
            end
            """.trimIndent(),
            COMPILE_TIMEOUT
        )

        assertEquals("compile status", OtpErlangAtom("ok"), compiled.status)

        return compiled.messages.single()
    }

    /**
     * [case]'s body with a probe after each statement, top-level or nested, on the statement's own line, and each
     * identity range wrapped; each probe's tag is added to [tags]. A keyword value such as `do: x` takes parentheses
     * around it and its probe. With [hooked], each module [case] defines starts with a call to the probe module's hook.
     */
    private fun probed(probeModule: String, index: Int, case: Case, tags: MutableList<Tag>, hooked: Boolean): String {
        val file = parse(case.body)
        val ends = statementEnds(file)
        val insertions = mutableListOf<Insertion>()

        val hooks = if (hooked) hookCalls(probeModule, file) else Hooks(emptyList(), emptyList())

        insertions += hooks.insertions

        ends.forEachIndexed { statement, end ->
            val tag = Tag(index, 0, statement + 1)
            tags.add(tag)
            insertions.add(Insertion(end, 3, "; " + probe(probeModule, tag)))

            if (case.value == statement + 1) {
                insertions.add(Insertion(end, 4, "; IntellijElixir.Quoter.Probe.send(__ENV__, {:value, $index, q})"))
            }
        }
        case.bodies.forEachIndexed { block, statements ->
            val start = statements.first().startOffset
            val keywordValue = isKeywordValue(file, start)

            if (keywordValue) insertions.add(Insertion(start, 5, "("))

            statements.forEachIndexed { statement, range ->
                val tag = Tag(index, block + 1, statement + 1)
                val close = if (keywordValue && statement == statements.lastIndex) ")" else ""
                // A statement that holds a module's wrapped value is probed after the wrapper closes.
                val encloses = hooks.wrapped.any { range.startOffset < it.startOffset && range.endOffset == it.endOffset }
                tags.add(tag)
                insertions.add(Insertion(range.endOffset, if (encloses) 3 else 1, "; " + probe(probeModule, tag) + close))
            }
        }
        case.identities.forEachIndexed { identity, range ->
            val statement = ends.indexOfFirst { range.endOffset <= it } + 1
            val tag = Tag(index, 0, statement, identity + 1)
            tags.add(tag)
            insertions.add(Insertion(range.startOffset, 6, "$probeModule.i(${tagList(tag)}, "))
            insertions.add(Insertion(range.endOffset, 0, ")"))
        }

        val probedBody = StringBuilder(case.body)

        // Each insertion at an offset lands left of those already there, so at one offset the result reads: a module's
        // hook call, the end of a wrapped range, then the probe of a nested statement, then the close of a module's
        // keyword value, then the probe of a statement that holds the nested ones or a wrapped range (a top-level
        // statement always does), then a value send, then a keyword value's opening parenthesis, then the start of
        // the next range.
        insertions.sortedWith(compareByDescending<Insertion> { it.offset }.thenByDescending { it.order }).forEach {
            probedBody.insert(it.offset, it.text)
        }

        return probedBody.toString()
    }

    private class Insertion(val offset: Int, val order: Int, val text: String)

    /** Whether the code at [offset] in [file] is a keyword's value, which a `:` precedes, comments aside. */
    private fun isKeywordValue(file: ElixirFile, offset: Int): Boolean =
        ReadAction.computeBlocking<Boolean, Throwable> {
            var previous = file.findElementAt(offset)?.let(PsiTreeUtil::prevLeaf)

            while (previous is PsiWhiteSpace || previous is PsiComment) previous = PsiTreeUtil.prevLeaf(previous)

            previous?.text?.endsWith(":") == true
        }

    /**
     * The call to [probeModule]'s hook for each module [file] defines where a `quote` evaluates it or outside one: first
     * in a `do` block, which has `do` and `end` tokens, and around any other `do` value, such as a keyword `do:`'s or a
     * tuple's, which the call and the value would otherwise not share.
     */
    private fun hookCalls(probeModule: String, file: ElixirFile): Hooks {
        val statements = ReadAction.computeBlocking<List<ElixirAst>, Throwable> {
            val lowering = Lowering.of(file, legLevel())

            expressionNodes(file).map { lowering.lower(it.psi) }
        }
        val call = "$probeModule.hook()"
        val insertions = mutableListOf<Insertion>()
        val wrapped = mutableListOf<TextRange>()

        statements.flatMap { modules(it) }.forEach { module ->
            val value = (module.arguments!!.last() as? ElixirAst.ListNode)?.elements
                ?.firstOrNull { keyOf(it) == "do" }?.let(::valueOf)

            when {
                value == null -> Unit
                !hasDoBlock(module) -> {
                    insertions += Insertion(value.meta.origin.startOffset, -1, "($call; ")
                    insertions += Insertion(value.meta.origin.endOffset, 2, ")")
                    wrapped += value.meta.origin
                }
                value is ElixirAst.Block && value.expressions.isEmpty() ->
                    insertions += Insertion(module.meta.origin.endOffset - "end".length, -1, "$call ")
                else -> {
                    val first = (value as? ElixirAst.Block)?.expressions?.first() ?: value

                    insertions += Insertion(first.meta.origin.startOffset, -1, "$call; ")
                }
            }
        }

        return Hooks(insertions, wrapped)
    }

    /** The hook calls to insert, and the keyword values they wrap in parentheses. */
    private class Hooks(val insertions: List<Insertion>, val wrapped: List<TextRange>)

    /**
     * Where each statement ends, as the lowering splits a file into statements: a parenthesised block is one, though
     * it quotes as several do.
     */
    private fun statementEnds(file: ElixirFile): List<Int> =
        ReadAction.computeBlocking<List<Int>, Throwable> {
            check(!PsiTreeUtil.hasErrorElements(file)) { "a case body must parse without errors" }

            expressionNodes(file).map { it.textRange.endOffset }
        }

    private fun observation(message: OtpErlangObject): Observation {
        val (tag, env, counterBefore) = (message as OtpErlangTuple).elements()
        val numbers = (tag as OtpErlangTuple).elements().map { (it as OtpErlangLong).intValue() }

        return Observation(
            Tag(numbers[0], numbers[1], numbers[2], numbers.getOrElse(3) { 0 }),
            env as OtpErlangMap,
            (counterBefore as? OtpErlangLong)?.longValue(),
        )
    }

    /** A layout's source, where and on which line each case body starts in it, and the top-level probe's line. */
    private class Written(val source: String, val bodyStarts: List<Int>, val bodyLines: List<Int>, val topLine: Int?)

    private companion object {
        val COMPILE_TIMEOUT = 30.seconds

        val PROBE_BODY = """
            |  defmacro p(tag) do
            |    IntellijElixir.Quoter.Probe.send(__CALLER__, observation(tag, __CALLER__))
            |    # A body's last statement is a probe, so an `@on_load` function returns this.
            |    :ok
            |  end
            |
            |  defmacro i(tag, expr) do
            |    IntellijElixir.Quoter.Probe.send(__CALLER__, observation(tag, __CALLER__))
            |    expr
            |  end
            |
            |  defp observation(tag, caller) do
            |    {data, _} = :elixir_module.data_tables(caller.module)
            |
            |    {List.to_tuple(tag), Map.from_struct(caller), :ets.lookup_element(data, {:elixir, :counter}, 2)}
            |  end
            |""".trimMargin()

        /**
         * The names `on_def/6` reads, which `Module.compile_definition_attributes/6` takes once it has run. A clause's
         * `@doc` and `@deprecated` are compared as the Docs chunk merges them instead.
         */
        val DEFINITION_ATTRIBUTES = listOf("impl")

        /**
         * The probe module's `hook/0`, which puts the callbacks of [hookModule] on the module calling it as its body
         * expands.
         */
        fun hookMacro(hookModule: String): String = """
            |
            |  defmacro hook do
            |    Module.put_attribute(__CALLER__.module, :before_compile, $hookModule)
            |    Module.put_attribute(__CALLER__.module, :on_definition, {$hookModule, :on_def})
            |    Module.put_attribute(__CALLER__.module, :after_compile, $hookModule)
            |    :ok
            |  end
            |""".trimMargin()

        /**
         * The `defmodule` calls in [node], outermost first, outside the quoted code of any `quote` and any definition's
         * head, where `defmodule` is a name being defined, except in what a head or a `quote` evaluates. `defmodule` and
         * the definers are written local or as `Kernel.<name>`.
         */
        fun modules(node: ElixirAst): List<ElixirAst.Call> {
            if (isNamedCall(node, "quote")) return evaluatedInQuote(node as ElixirAst.Call).flatMap { modules(it) }

            val call = node as? ElixirAst.Call ?: return children(node).flatMap { modules(it) }
            val parts = children(call)
            val inside =
                if (isDefiner(call)) parts.take(1).flatMap { unquoted(it) }.flatMap { modules(it) } + parts.drop(1).flatMap { modules(it) }
                else parts.flatMap { modules(it) }

            return listOfNotNull(call.takeIf { it.arguments?.size == 2 && isKernelCall(it, "defmodule") }) + inside
        }

        /** [hook]'s module body. */
        fun hookBody(hook: Hook): String = """
            |  defmacro __before_compile__(env) do
            |    defs =
            |      if function_exported?(Module, :get_definition, 2) do
            |        for {name, arity} <- Module.definitions_in(env.module) do
            |          {:v1, kind, meta, clauses} = apply(Module, :get_definition, [env.module, {name, arity}])
            |          {name, arity, kind, meta[:line], length(clauses), Keyword.has_key?(meta, :context)}
            |        end
            |      else
            |        for kind <- [:def, :defp, :defmacro, :defmacrop],
            |            {name, arity} <- Module.definitions_in(env.module, kind),
            |            do: {name, arity, kind}
            |      end
            |
            |    IntellijElixir.Quoter.Probe.send(env, {:hook, env.module, defs})
            |    finals = for name <- ${atoms(hook.attributes)}, do: {name, Module.get_attribute(env.module, name)}
            |    IntellijElixir.Quoter.Probe.send(env, {:final, env.module, finals})
            |    nil
            |  end
            |
            |  def on_def(env, kind, name, args, _guards, _body) do
            |    values = for key <- ${atoms(DEFINITION_ATTRIBUTES)}, do: {key, Module.get_attribute(env.module, key)}
            |    callbacks = Module.get_attribute(env.module, :on_definition)
            |
            |    IntellijElixir.Quoter.Probe.send(
            |      env,
            |      {:on_def, env.module, kind, name, length(args), env.line, values, callbacks}
            |    )
            |  end
            |
            |  def __after_compile__(env, bytecode) do
            |    reads =
            |      for {name, 0} <- env.module.__info__(:functions), match?("r_" <> _, Atom.to_string(name)),
            |          do: {name, apply(env.module, name, [])}
            |
            |    IntellijElixir.Quoter.Probe.send(env, {:reads, env.module, reads})
            |
            |    docs =
            |      case :beam_lib.chunks(bytecode, [~c"Docs"]) do
            |        {:ok, {_, [{_, chunk}]}} ->
            |          {:docs_v1, _, _, _, _, _, entries} = :erlang.binary_to_term(chunk)
            |
            |          for {{kind, name, arity}, _, _, doc, metadata} <- entries, kind in [:function, :macro] do
            |            # 1.12 writes a public function's missing doc as an empty map, where the other releases write :none.
            |            text =
            |              case doc do
            |                %{"en" => text} -> text
            |                empty when empty == %{} -> :none
            |                other -> other
            |              end
            |
            |            {kind, name, arity, text, Map.get(metadata, :deprecated), Map.get(metadata, :guard, false)}
            |          end
            |
            |        _ ->
            |          nil
            |      end
            |
            |    IntellijElixir.Quoter.Probe.send(env, {:docs, env.module, Code.get_compiler_option(:docs), docs})
            |  end
            |""".trimMargin()

        fun atoms(names: List<String>) = names.joinToString(", ", "[", "]") { ":$it" }

        const val TOP_PROBE =
            "IntellijElixir.Quoter.Probe.send(__ENV__, {List.to_tuple([-1, 0, 0]), Map.from_struct(__ENV__), nil})"

        /**
         * [layout]'s source, each case body written by [body]: the probed source when [tags] collects the probes'
         * tags, and [Layout.plain] when it is `null`.
         */
        fun write(layout: Layout, tags: MutableList<Tag>?, body: (Int, Case) -> String): Written {
            val probeModule = probeModule(layout.token)
            val hookModule = hookModule(layout.token)
            val bodyStarts = mutableListOf<Int>()
            val bodyLines = mutableListOf<Int>()
            var topLine: Int? = null

            fun StringBuilder.harness(text: String) {
                append(if (tags == null) text.filter { it == '\n' } else text)
            }

            val source = buildString {
                append("defmodule $probeModule do\n")
                harness(PROBE_BODY)

                if (layout.hook != null) harness(hookMacro(hookModule))

                append("end\n")

                layout.hook?.let { hook ->
                    append("\ndefmodule $hookModule do\n")
                    harness(hookBody(hook))
                    append("end\n")
                }

                append(layout.preamble)

                layout.cases.forEachIndexed { index, case ->
                    append("\ndefmodule ${caseModule(layout.token, index)} do\n")
                    harness("require $probeModule\n")

                    if (layout.hook != null) harness("$probeModule.hook()\n")

                    harness(probe(probeModule, Tag(index, 0, 0).also { tags?.add(it) }) + "\n")
                    bodyStarts.add(length)
                    bodyLines.add(count { it == '\n' } + 1)
                    append(body(index, case))
                    append("\nend\n")
                }

                if (layout.top) {
                    append("\n")
                    topLine = count { it == '\n' } + 1

                    if (tags == null) {
                        append(":ok")
                    } else {
                        append(TOP_PROBE)
                        tags.add(Tag(-1, 0, 0))
                    }

                    append("\n")
                }
            }

            return Written(source, bodyStarts, bodyLines, topLine)
        }

        fun probe(probeModule: String, tag: Tag): String = "$probeModule.p(${tagList(tag)})"

        fun tagList(tag: Tag) =
            listOfNotNull(tag.case, tag.block, tag.statement, tag.identity.takeIf { it > 0 })
                .joinToString(", ", "[", "]")

        fun probeModule(token: String) = "$token.Probe"

        fun hookModule(token: String) = "$token.Hook"

        fun caseModule(token: String, case: Int) = "$token.$CASE_SEGMENT$case"

        /** The definitions a hook sent: 6-tuples from 1.12, and before it `{name, arity, kind}`. */
        fun hookEntries(term: OtpErlangObject): List<HookEntry> =
            (term as OtpErlangList).elements().map { entry ->
                val elements = (entry as OtpErlangTuple).elements()
                val name = (elements[0] as OtpErlangAtom).atomValue()
                val arity = (elements[1] as OtpErlangLong).intValue()
                val kind = (elements[2] as OtpErlangAtom).atomValue()

                if (elements.size == 3) {
                    HookEntry(name, arity, kind, null, null, null)
                } else {
                    HookEntry(
                        name,
                        arity,
                        kind,
                        (elements[3] as? OtpErlangLong)?.intValue(),
                        (elements[4] as OtpErlangLong).intValue(),
                        (elements[5] as OtpErlangAtom).booleanValue(),
                    )
                }
            }

        /** `[{name, value}]`, by name. */
        fun named(term: OtpErlangObject): Map<String, OtpErlangObject> =
            (term as OtpErlangList).elements().associate { pair ->
                val (name, value) = (pair as OtpErlangTuple).elements()

                atom(name) to value
            }

        /** `{:on_def, module, kind, name, arity, line, [{key, value}], [{module, function}]}`'s elements. */
        fun clauseAttributes(elements: Array<OtpErlangObject>): ClauseAttributes =
            ClauseAttributes(
                atom(elements[2]),
                atom(elements[3]),
                (elements[4] as OtpErlangLong).intValue(),
                (elements[5] as OtpErlangLong).intValue(),
                named(elements[6]),
                (elements[7] as OtpErlangList).elements().map { callback ->
                    val (module, function) = (callback as OtpErlangTuple).elements()

                    atom(module) to atom(function)
                },
            )

        /** `{:docs, module, enabled, [{kind, name, arity, doc, deprecated, guard}] | nil}`'s last two elements. */
        fun docs(enabled: OtpErlangObject, entries: OtpErlangObject): Docs =
            Docs(
                (enabled as OtpErlangAtom).booleanValue(),
                (entries as? OtpErlangList)?.elements()?.map { entry ->
                    val elements = (entry as OtpErlangTuple).elements()

                    DocEntry(
                        atom(elements[0]),
                        atom(elements[1]),
                        (elements[2] as OtpErlangLong).intValue(),
                        elements[3],
                        elements[4],
                        elements[5] == OtpErlangAtom("true"),
                    )
                },
            )

        fun atom(term: OtpErlangObject): String = (term as OtpErlangAtom).atomValue()

        fun List<Tag>.sorted() = sortedWith(compareBy({ it.case }, { it.block }, { it.statement }, { it.identity }))
    }
}

/**
 * The elements of a `{severity, line, column, message}` diagnostic, as the quoter reports one.
 *
 * @property line `null` where Elixir gives none, or line 0, as for `invalid_quoted_expr`
 */
internal class Diagnostic(val severity: String, val line: Int?, val column: Int?, val message: String) {
    companion object {
        fun of(term: OtpErlangObject): Diagnostic {
            val (severity, line, column, message) = (term as OtpErlangTuple).elements()

            return Diagnostic(
                (severity as OtpErlangAtom).atomValue(),
                (line as? OtpErlangLong)?.intValue()?.takeIf { it != 0 },
                (column as? OtpErlangLong)?.intValue(),
                utf8(message)
            )
        }
    }
}

private val DEFINERS = setOf("def", "defp", "defmacro", "defmacrop")

/** Whether [call] is written [name], or `Kernel.[name]`. */
private fun isKernelCall(call: ElixirAst.Call, name: String): Boolean {
    val remote = dotArguments(call.callee)?.takeIf { it.size == 2 }?.let { (receiver, function) ->
        (receiver as? ElixirAst.Alias)?.segments?.map { (it as? ElixirAst.Literal.Atom)?.name } == listOf("Kernel") &&
            (function as? ElixirAst.Literal.Atom)?.name == name
    }

    return remote == true || (call.callee as? ElixirAst.Literal.Atom)?.name == name
}

/** Whether [node] is a call of `def`, `defp`, `defmacro` or `defmacrop`, written local or as `Kernel.<name>`. */
internal fun isDefiner(node: ElixirAst): Boolean = node is ElixirAst.Call && DEFINERS.any { isKernelCall(node, it) }

/** Whether [call] has a `do` block, whose `do` and `end` tokens its metadata records. */
private fun hasDoBlock(call: ElixirAst.Call): Boolean =
    call.meta.keys.any { it is Meta.Key.Entry && it.name == "do" }

/**
 * The expressions of [quote] that run where it is written, as Elixir reads them: its options other than `do`, and the
 * expressions its `do` body unquotes, when `unquote:` is true, or is absent and there is no `bind_quoted`.
 */
internal fun evaluatedInQuote(quote: ElixirAst.Call): List<ElixirAst> {
    val options = quote.arguments.orEmpty().flatMap { (it as? ElixirAst.ListNode)?.elements.orEmpty() }
    val body = options.firstOrNull { keyOf(it) == "do" }?.let(::valueOf)
    val unquote = options.firstOrNull { keyOf(it) == "unquote" }?.let(::valueOf)
    val unquotes =
        if (unquote != null) (unquote as? ElixirAst.Literal.Atom)?.name != "false"
        else options.none { keyOf(it) == "bind_quoted" }

    return options.filter { keyOf(it) != null && keyOf(it) != "do" }.map(::valueOf) +
        body?.takeIf { unquotes }?.let(::unquoted).orEmpty()
}

/**
 * The expressions [node] unquotes, in a call's name too. A `quote` nested in it unquotes nothing but the options it is
 * given as a separate argument.
 */
internal fun unquoted(node: ElixirAst): List<ElixirAst> {
    val receiver = (node as? ElixirAst.Call)?.takeIf { it.arguments?.size == 1 }?.let { call ->
        dotArguments(call.callee)?.takeIf { it.size == 2 && (it[1] as? ElixirAst.Literal.Atom)?.name == "unquote" }?.first()
    }

    return when {
        isNamedCall(node, "quote") ->
            (node as ElixirAst.Call).arguments?.takeIf { it.size == 2 }?.let { unquoted(it.first()) }.orEmpty()
        isCall(node, "unquote", 1) || isCall(node, "unquote_splicing", 1) -> (node as ElixirAst.Call).arguments!!
        // `Left.unquote(expr)`, whose `expr` names the call.
        receiver != null -> (node as ElixirAst.Call).arguments!! + unquoted(receiver)
        else -> (listOfNotNull((node as? ElixirAst.Call)?.callee) + children(node)).flatMap(::unquoted)
    }
}

private const val TOKEN_PREFIX = "ProbeCase"
private const val CASE_SEGMENT = "Case"

/** A compile's token, as [ProbeHarness.token] makes it. */
internal val TOKEN_PATTERN = Regex("$TOKEN_PREFIX[0-9a-f]{32}")

/** A case module's name after the `Elixir.` prefix, for any case. */
internal val CASE_MODULE_PATTERN = Regex("${TOKEN_PATTERN.pattern}\\.$CASE_SEGMENT\\d+")

/** The nodes directly under [node]. */
internal fun children(node: ElixirAst): List<ElixirAst> =
    when (node) {
        is ElixirAst.Call -> node.arguments.orEmpty()
        is ElixirAst.Tuple -> node.elements
        is ElixirAst.ListNode -> node.elements
        is ElixirAst.Block -> node.expressions
        is ElixirAst.Alias, is ElixirAst.Literal, is ElixirAst.Placeholder, is ElixirAst.Value -> emptyList()
    }

internal fun utf8(term: OtpErlangObject): String =
    when (term) {
        is com.ericsson.otp.erlang.OtpErlangBinary -> String(term.binaryValue(), Charsets.UTF_8)
        is com.ericsson.otp.erlang.OtpErlangString -> term.stringValue()
        is OtpErlangList -> term.stringValue()
        else -> throw AssertionError("not text: ${inspect(term)}")
    }
