package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.expander.Expansion
import org.elixir_lang.expander.Followed
import org.elixir_lang.expander.Parsed
import org.elixir_lang.expander.Require
import org.elixir_lang.expander.TermEscapeView
import org.elixir_lang.expander.VARIABLE_ORDER
import org.elixir_lang.expander.Variable
import org.elixir_lang.expander.blockExpressions
import org.elixir_lang.expander.children
import org.elixir_lang.expander.escaped
import org.elixir_lang.expander.interpreter.Evaluation.EvalEnv
import org.elixir_lang.expander.isSignedNumber
import org.elixir_lang.language_level.ElixirLanguageFeature.CODE_INDENTATION_STARTS_LATER_LINES
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_PARSES_COLUMNS
import org.elixir_lang.language_level.ElixirLanguageFeature.EVAL_ATOM_GIVES_ITS_BINDING
import org.elixir_lang.language_level.ElixirLanguageFeature.EVAL_BINDING_DESCENDS
import org.elixir_lang.language_level.ElixirLanguageFeature.EVAL_NUMBER_GIVES_ITS_BINDING
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.ParserOptions
import org.elixir_lang.mix.ProjectKeywords.Reading
import org.elixir_lang.psi.Import.Term

/** The functions of `Code` that [CodeFollow.call] answers by only reading: they parse, or read a compiler option. */
internal val CODE_READS = setOf("string_to_quoted!", "get_compiler_option")

/**
 * `Code.require_file/1,2`, `eval_file/1,2`, `compile_file/1,2`, `eval_string/1,2,3` and `eval_quoted/1,2,3`: the one
 * site that runs the code those forms run, and what `ensure_compiled/1,!` reach of it; `string_to_quoted!/1,2`, which
 * parses as `eval_string` does; and `get_compiler_option(:parser_options)`. A form that isn't one of those, or an
 * argument whose error Elixir's clauses give, returns `null`, so `Code`'s clauses run.
 */
internal class CodeFollow(private val evaluation: Evaluation) {
    private val run get() = evaluation.run

    private val level get() = run.level

    /** [key]'s form of `Code` answered. Each that only reads, writing nothing later code reads, is in [CODE_READS]. */
    fun call(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "require_file/1", "require_file/2" -> path(args)?.let(::requireFile)
            "compile_file/1", "compile_file/2" -> path(args)?.let(::compileFile)
            "eval_file/1", "eval_file/2" -> path(args)?.let(::evalFile)
            "eval_string/1", "eval_string/2", "eval_string/3" -> evalString(args)
            "eval_quoted/1", "eval_quoted/2", "eval_quoted/3" -> evalQuoted(args)
            "string_to_quoted!/1", "string_to_quoted!/2" -> stringToQuoted(args)
            "get_compiler_option/1" -> compilerOption(args[0])
            "put_compiler_option/2", "compiler_options/1" -> {
                run.shared.compilerOptionsWritten = true
                null
            }
            "ensure_compiled/1", "ensure_compiled!/1" -> {
                reachFollowsOf(args[0])
                null
            }
            else -> null
        }

    /**
     * What [module]'s file's own follows loaded comes with it
     * ([loadedWith][org.elixir_lang.expander.RunFiles.loadedWith]): they ran as the file compiled, whether or not the
     * module then loads. The answer is `Code`'s clauses', through `code:ensure_loaded/1`.
     */
    private fun reachFollowsOf(module: OtpErlangObject) {
        val name = (module as? OtpErlangAtom)?.atomValue() ?: return

        run.files.loadedWith(name).forEach(run::ensuredLoaded)
    }

    /** `find_file!/2`: the file's absolute path, or `null` where the file argument isn't a binary, whose error is the clauses'. */
    private fun path(args: List<OtpErlangObject>): String? {
        val file = seen(args[0]) as? OtpErlangBinary ?: return null
        val relativeTo = args.getOrNull(1)?.let(::seen)?.takeUnless { it == NIL }
        val expanded = evaluation.remote(atom("Elixir.Path"), "expand", listOfNotNull(file, relativeTo))
        val known = seen(expanded) as? OtpErlangBinary ?: decline("Code's follow of a path that isn't known")
        val path = evaluation.natives.utf8(known) ?: decline("Code's follow of a path that isn't UTF-8 %s", known)

        when (run.files.exists(path)) {
            null -> decline("Code's follow of %s, whose file isn't known", expanded)
            false -> {
                evaluation.mayRaise()
                evaluation.raise("Elixir.Code.LoadError", "file" to expanded)
            }
            true -> {}
        }

        return path
    }

    /**
     * `elixir_code_server`'s require-once: `nil` for a path the run, or the config read its build follows, already
     * required, else the modules it loaded. Where code that declined may have required it
     * ([mayHaveRunUnfollowed][org.elixir_lang.expander.Run.mayHaveRunUnfollowed]), or in a run beside others
     * ([sequential][org.elixir_lang.expander.Run.sequential]), where which of them requires the path first is a race,
     * the modules are loaded but what the call gives isn't known.
     */
    private fun requireFile(path: String): OtpErlangObject {
        val earlier = if (path in run.shared.required) Require.REQUIRED else run.files.requiredByRead(path)
        if (earlier == Require.REQUIRED) return NIL
        val maybe = earlier == Require.MAYBE || run.mayHaveRunUnfollowed

        val modules = modules(follow(path, evaluates = false))
        run.shared.required += path

        return if (run.sequential && !maybe) modules else Unknown
    }

    private fun compileFile(path: String): OtpErlangObject = modules(follow(path, evaluates = false))

    private fun evalFile(path: String): OtpErlangObject {
        val followed = follow(path, evaluates = true)

        return tuple(followed.value, dumped(followed.bound))
    }

    /** `{module, binary}` of each of [followed]'s modules. */
    private fun modules(followed: Followed): OtpErlangObject = list(followed.modules.map { tuple(atom(it), UnknownBinary) })

    private fun follow(path: String, evaluates: Boolean): Followed {
        if (path in run.following) decline("Code's follow of %s, a cycle", binary(path))

        val file = run.files.followed(path) ?: decline("Code's follow of %s, which can't be read", binary(path))
        val env = run.emptyEnv ?: decline("Code's follow outside a file's expansion")

        return settled(run.follow(file.forms, env, path, file.virtualFile, evaluates))
    }

    /** What the followed code left, for the caller: a raise raises here, and a decline in it is the caller's. */
    private fun settled(followed: Followed): Followed {
        when (val top = followed.top) {
            is Expansion.Error -> {
                val kind = top.kind ?: decline("Code's follow, whose raise isn't known")

                evaluation.mayRaise()
                evaluation.raise(if (kind.startsWith("Elixir.")) kind else "Elixir.CompileError")
            }
            is Expansion.Expanded -> if (!followed.certain) decline("Code's follow, part of which isn't known")
            else -> decline("Code's follow, which stopped")
        }

        return followed
    }

    /** `eval_string/3`: the string parsed at the env's line, then evaluated as [evaluate] does. A string that doesn't parse raises. */
    private fun evalString(args: List<OtpErlangObject>): OtpErlangObject? {
        val text = textOf(args[0]) ?: return null
        val binding = EvalBinding.of(args.getOrNull(1)) ?: return null
        val env = evalEnv(args.getOrNull(2)) ?: return null

        return evaluate(parsed(text, env.line ?: 1, 1, 0, "eval_string/3"), binding, env)
    }

    /**
     * `string_to_quoted!/1,2`: the string parsed as [evalString] parses it, from the options' `line:`, `column:` and
     * `indentation:`, and quoted with columns where `columns: true`. `file:`, `emit_warnings:`, `warnings:` and
     * `token_metadata: false` change nothing it gives; any other option declines, as does text holding a shape the
     * lowering doesn't lower, whose AST isn't known. `null`, so `Code`'s clauses run, where the string isn't a binary or
     * a list of code points, or the options aren't a list.
     */
    private fun stringToQuoted(args: List<OtpErlangObject>): OtpErlangObject? {
        val text = textOf(args[0]) ?: return null
        val options = properElements(seen(args.getOrElse(1) { EMPTY })) ?: return null
        // As `Keyword.get/3` and `lists:keyfind/3` read them, the first of a key; the tokenizer folds every `indentation:`.
        val read = LinkedHashMap<String, OtpErlangObject>()
        val indentations = mutableListOf<OtpErlangObject>()

        for (option in options) {
            val (key, value) = keywordPair(option) ?: decline("string_to_quoted! given the option %s", option)

            if (key == "indentation") indentations += value else read.putIfAbsent(key, value)
        }

        for ((key, value) in read) {
            val ported = when (key) {
                "line", "column", "columns", "file", "emit_warnings", "warnings" -> true
                "token_metadata" -> seen(value) != TRUE
                else -> false
            }

            if (!ported) decline("string_to_quoted! given the option %s", tuple(atom(key), value))
        }

        val line = read["line"]?.let { smallInt(seen(it))?.takeIf { n -> n >= 0 } ?: decline("string_to_quoted! from the line %s", it) } ?: 1
        val column = read["column"]?.let { smallInt(seen(it))?.takeIf { n -> n >= 1 } ?: decline("string_to_quoted! from the column %s", it) } ?: 1
        read["file"]?.let { if (seen(it) !is OtpErlangBinary) decline("string_to_quoted! in the file %s", it) }
        // From when it starts the lines after the first, each in turn: one at least 0 is the indentation, a negative one
        // is passed over, and Elixir adds 1 to any other term at least 0, which raises or isn't a column. Before, none is read.
        val indentation = if (CODE_INDENTATION_STARTS_LATER_LINES.isSufficient(level)) {
            indentations.fold(0) { indentation, term ->
                val n = smallInt(seen(term)) ?: decline("string_to_quoted! given the indentation %s", term)

                if (n >= 0) n else indentation
            }
        } else {
            0
        }

        val parserOptions = ParserOptions(columns = read["columns"]?.let(::seen) == TRUE)

        return parsed(text, line, column, indentation, "string_to_quoted!").toOtp(parserOptions) { node, _ ->
            if (node is ElixirAst.Placeholder && node.stopsExpansion) decline("string_to_quoted! of a shape with no lowering")
        }
    }

    /** The text of [term], a UTF-8 binary or a proper list of code points; `null` where it is neither. */
    private fun textOf(term: OtpErlangObject): String? =
        when (val known = seen(term)) {
            is OtpErlangBinary -> evaluation.natives.utf8(known)
            else -> charlistText(known)
        }

    /**
     * [text] parsed, for [caller], as if it began on [line] at [column], with `Code`'s [indentation]: text that doesn't
     * parse raises, as Elixir's parser does, its exception not known.
     */
    private fun parsed(text: String, line: Int, column: Int, indentation: Int, caller: String): ElixirAst {
        if (line.toLong() + text.count { it == '\n' } > Int.MAX_VALUE) decline("$caller, past the last line counted")
        if (maxOf(column.toLong(), indentation + 1L) + text.length > Int.MAX_VALUE) decline("$caller, past the last column counted")
        val forms = when (val parsed = run.files.parsed(text, line, column, indentation)) {
            is Parsed.Forms -> parsed.forms
            Parsed.NoProject -> decline("$caller, with no project to parse in")
            is Parsed.UnplacedHeredoc -> decline("$caller of a heredoc, its later lines from the column ${parsed.laterColumn}")
        }

        if (holdsBroken(forms)) {
            evaluation.mayRaise()
            evaluation.raiseAs("error", Unknown)
        }

        return forms
    }

    /**
     * `get_compiler_option/1` of `:parser_options`: what the project's `elixirc_options` give the compile
     * ([parserOptions][org.elixir_lang.expander.RunFiles.parserOptions]), where they give none from
     * [COMPILER_PARSES_COLUMNS] `[columns: true]`, before `[]`, and not known once a write of the options was reached or
     * code the expander doesn't follow may have run, which may have written them
     * ([mayHaveRunUnfollowed][org.elixir_lang.expander.Run.mayHaveRunUnfollowed]). `null` for any other option, which
     * varies with how Elixir runs.
     */
    private fun compilerOption(key: OtpErlangObject): OtpErlangObject? {
        if (seen(key) != atom("parser_options")) return null
        if (run.shared.compilerOptionsWritten || run.mayHaveRunUnfollowed) return Unknown

        return when (val project = run.files.parserOptions()) {
            is Reading.Written -> project.value
            Reading.Unknown -> Unknown
            Reading.Absent -> if (COMPILER_PARSES_COLUMNS.isSufficient(level)) list(listOf(tuple(atom("columns"), TRUE))) else EMPTY
        }
    }

    /** `eval_quoted/3`: the quoted code, evaluated as [evaluate] does. */
    private fun evalQuoted(args: List<OtpErlangObject>): OtpErlangObject? {
        val binding = EvalBinding.of(args.getOrNull(1)) ?: return null
        val env = evalEnv(args.getOrNull(2)) ?: return null
        val forms = evaluation.code(args[0]) ?: decline("eval_quoted/3 of what isn't quoted, or is too large to copy")

        return evaluate(forms, binding, env)
    }

    /**
     * `elixir:eval_forms/4` of [forms] in [env] with [binding], each variable assigned before them: `{value, binding}`,
     * the binding as [bindingOut] gives it.
     */
    private fun evaluate(forms: ElixirAst, binding: EvalBinding, env: EvalEnv): OtpErlangObject {
        val pairs = if (EVAL_BINDING_DESCENDS.isSufficient(level)) binding.pairs else binding.pairs.distinctBy { it.first }
        val prefixed =
            if (pairs.isEmpty()) forms else ElixirAst.Block(forms.meta, pairs.map { (name, value) -> assignment(name, value) } + forms)
        val evaluated = unwrapped(forms)
        val followed = settled(run.follow(prefixed, env.env, env.env.file, null, evaluates = true, observed = evaluated))

        return tuple(followed.value, bindingOut(evaluated, followed, binding))
    }

    /** `name = value`, the value escaped. */
    private fun assignment(name: String, value: OtpErlangObject): ElixirAst {
        val escaped = escaped(TermEscapeView, value, evaluation.call, run) { null } ?: decline("a binding the escape can't read")
        val code = tuple(atom("="), EMPTY, list(listOf(tuple(atom(name), EMPTY, NIL), value)))

        return codeOf(code, evaluation.call.meta.origin, values = true, known = { if (it === value) escaped else null })
            ?: decline("a binding too large to copy")
    }

    /**
     * The binding `eval_forms/4` gives back for [evaluated], the code it was given, which [followed] ran: where that
     * compiles to an atom, or from [EVAL_NUMBER_GIVES_ITS_BINDING] a number, the [given] binding, before
     * [EVAL_ATOM_GIVES_ITS_BINDING] an atom's as it was loaded; otherwise the variables left bound ([dumped]). Unknown
     * where which isn't known.
     */
    private fun bindingOut(evaluated: ElixirAst, followed: Followed, given: EvalBinding): OtpErlangObject =
        when (compiled(evaluated, followed.observed)) {
            Compiled.ATOM -> if (EVAL_ATOM_GIVES_ITS_BINDING.isSufficient(level)) given.term else given.loaded()
            Compiled.NUMBER -> if (EVAL_NUMBER_GIVES_ITS_BINDING.isSufficient(level)) given.term else dumped(followed.bound)
            Compiled.OTHER -> dumped(followed.bound)
            Compiled.UNKNOWN -> Unknown
        }

    /** What [evaluated], which expanded to [expansion], compiles to: a literal atom or number, or other code. */
    private fun compiled(evaluated: ElixirAst, expansion: Expansion?): Compiled {
        blockExpressions(evaluated)?.let { return if (it.isEmpty()) Compiled.ATOM else Compiled.OTHER }

        return when ((expansion as? Expansion.Expanded)?.value) {
            null -> Compiled.UNKNOWN
            is Term.Atom -> Compiled.ATOM
            is Term.Integer -> Compiled.NUMBER
            // A float, whose value the expander doesn't hold: `0.0` compiles to `+0.0` from Elixir 1.16.
            Term.NonTuple -> if (floatLiteral(evaluated)?.let { it != 0.0 } == true) Compiled.NUMBER else Compiled.UNKNOWN
            else -> Compiled.OTHER
        }
    }

    private enum class Compiled { ATOM, NUMBER, OTHER, UNKNOWN }

    /** The float [node] is written as, signed or not, `null` where it isn't one. */
    private fun floatLiteral(node: ElixirAst): Double? =
        when (node) {
            is ElixirAst.Literal.Float -> node.value
            is ElixirAst.Call if
                isSignedNumber((node.callee as? ElixirAst.Literal.Atom)?.name, node.arguments.orEmpty()) ->
                (node.arguments!!.single() as? ElixirAst.Literal.Float)?.value
            else -> null
        }

    /**
     * `elixir_erl_var:dump_binding/4` of the variables [bound], those with an atom context: from [EVAL_BINDING_DESCENDS]
     * in the order a map of `{name, context}` keys folds them, descending, which isn't known past [FLATMAP_KEYS]; before
     * it, sorted by key, a name without a context before `{name, context}`.
     */
    private fun dumped(bound: List<Pair<Variable, OtpErlangObject>>): OtpErlangObject {
        val named = bound.filter { (variable, _) -> variable.context is Variable.Context.Atom }
        val sorted = if (EVAL_BINDING_DESCENDS.isSufficient(level)) {
            if (bound.size > FLATMAP_KEYS) return Unknown

            named.sortedWith(compareBy(VARIABLE_ORDER.reversed()) { it.first })
        } else {
            named.sortedWith(
                compareBy<Pair<Variable, OtpErlangObject>> { it.first.context != Variable.NIL }.thenBy(VARIABLE_ORDER) { it.first },
            )
        }

        return list(
            sorted.map { (variable, value) ->
                val context = variable.context as Variable.Context.Atom
                val key =
                    if (variable.context == Variable.NIL) atom(variable.name) else tuple(atom(variable.name), atom(context.text))

                tuple(key, value)
            },
        )
    }

    /**
     * A binding `eval_string/3` and `eval_quoted/3` take: the [term] as given, and its [pairs], each name with its value.
     */
    private class EvalBinding(val term: OtpErlangObject, val pairs: List<Pair<String, OtpErlangObject>>) {
        /** The binding as `elixir_erl_var:load_binding/2` loads it: reversed, each name as `{name, nil}`. */
        fun loaded(): OtpErlangObject = list(pairs.asReversed().map { (name, value) -> tuple(tuple(atom(name), NIL), value) })

        companion object {
            /**
             * [term], the binding: a list of names, each an atom or `{name, nil}`, with their values; empty where there
             * is none. `null` where it isn't a list, whose error the clauses give.
             */
            fun of(term: OtpErlangObject?): EvalBinding? {
                if (term == null) return EvalBinding(EMPTY, emptyList())
                val elements = properElements(seen(term)) ?: return null

                return EvalBinding(
                    term,
                    elements.map { element ->
                        val pair = (seen(element) as? OtpErlangTuple)?.takeIf { it.arity() == 2 }
                            ?: decline("a binding entry that isn't a name and a value %s", element)
                        val name = when (val key = seen(pair.elementAt(0))) {
                            is OtpErlangAtom -> key
                            is OtpErlangTuple if key.arity() == 2 && seen(key.elementAt(1)) == NIL -> seen(key.elementAt(0)) as? OtpErlangAtom
                            else -> null
                        } ?: decline("a binding name that isn't a variable without a context %s", pair.elementAt(0))

                        name.atomValue() to pair.elementAt(1)
                    },
                )
            }
        }
    }

    /**
     * `elixir:env_for_eval/1` of [term], the eval's options: none, or a list of only `:file` and `:line`, gives a new
     * env; a `Macro.Env` keeps its own, its module and function too. Other options decline; any other term is `null`,
     * for the clauses.
     */
    private fun evalEnv(term: OtpErlangObject?): EvalEnv? {
        val seen = term?.let(::seen) ?: return evaluation.evalEnv(emptyList())

        macroEnvOf(seen)?.let { macroEnv ->
            val eval = evaluation.evalEnv(macroEnv)

            val module = moduleOf(macroEnvField(macroEnv, "module"))
            val function = functionOf(macroEnvField(macroEnv, "function"))

            return EvalEnv(eval.env.copy(module = module, function = function), eval.line)
        }
        if (seen is OtpErlangMap) return null

        val options = properElements(seen) ?: return null
        options.forEach { option ->
            val key = pairKey(seen(option))?.let(::seen)

            if (key != atom("file") && key != atom("line")) decline("an eval option other than file: and line: %s", option)
        }

        return evaluation.evalEnv(options)
    }

    private fun moduleOf(term: OtpErlangObject): String? =
        (term as? OtpErlangAtom ?: decline("a Macro.Env's module %s", term)).takeUnless { it == NIL }?.atomValue()

    private fun functionOf(term: OtpErlangObject): NameArity? {
        if (term == NIL) return null
        val pair = (term as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elements()?.map(::seen)
        val name = pair?.get(0) as? OtpErlangAtom
        val arity = pair?.get(1)?.let(::smallInt)

        return if (name != null && arity != null) NameArity(name.atomValue(), arity) else decline("a Macro.Env's function %s", term)
    }

    /** Whether [node] holds code that didn't parse. */
    private fun holdsBroken(node: ElixirAst): Boolean {
        val pending = ArrayDeque(listOf(node))

        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()

            val next = pending.removeLast()
            if (next is ElixirAst.Placeholder && next.reason == ElixirAst.Placeholder.Reason.Error) return true
            pending.addAll(children(next))
        }

        return false
    }

    private companion object {
        /** The most keys an Erlang map folds in term order. */
        const val FLATMAP_KEYS = 32

        /** [node] inside any blocks of one expression, which expand to that expression. */
        fun unwrapped(node: ElixirAst): ElixirAst = blockExpressions(node)?.singleOrNull()?.let(::unwrapped) ?: node
    }
}
