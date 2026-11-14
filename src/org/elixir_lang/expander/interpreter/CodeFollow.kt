package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.expander.Expansion
import org.elixir_lang.expander.Followed
import org.elixir_lang.expander.TermEscapeView
import org.elixir_lang.expander.VARIABLE_ORDER
import org.elixir_lang.expander.Variable
import org.elixir_lang.expander.blockExpressions
import org.elixir_lang.expander.children
import org.elixir_lang.expander.escaped
import org.elixir_lang.expander.interpreter.Evaluation.EvalEnv
import org.elixir_lang.expander.isSignedNumber
import org.elixir_lang.language_level.ElixirLanguageFeature.EVAL_ATOM_GIVES_ITS_BINDING
import org.elixir_lang.language_level.ElixirLanguageFeature.EVAL_BINDING_DESCENDS
import org.elixir_lang.language_level.ElixirLanguageFeature.EVAL_NUMBER_GIVES_ITS_BINDING
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * `Code.require_file/1,2`, `eval_file/1,2`, `compile_file/1,2`, `eval_string/1,2,3` and `eval_quoted/1,2,3`: the one
 * site that runs the code those forms run, the file forms in a config read. A form that isn't one of those, or an
 * argument whose error Elixir's clauses give, returns `null`, so `Code`'s clauses run.
 */
internal class CodeFollow(private val evaluation: Evaluation) {
    private val run get() = evaluation.run

    private val level get() = run.level

    fun call(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "eval_string/1", "eval_string/2", "eval_string/3" -> evalString(args)
            "eval_quoted/1", "eval_quoted/2", "eval_quoted/3" -> evalQuoted(args)
            else -> if (run.config == null) null else fileForm(key, args)
        }

    private fun fileForm(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "require_file/1", "require_file/2" -> path(args)?.let(::requireFile)
            "compile_file/1", "compile_file/2" -> path(args)?.let(::compileFile)
            "eval_file/1", "eval_file/2" -> path(args)?.let(::evalFile)
            else -> null
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

    /** `elixir_code_server`'s require-once: `nil` for a path the read already required, else the modules it loaded. */
    private fun requireFile(path: String): OtpErlangObject {
        if (!run.required.add(path)) return NIL

        return modules(follow(path, evaluates = false))
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
        val text = when (val string = seen(args[0])) {
            is OtpErlangBinary -> evaluation.natives.utf8(string) ?: return null
            is OtpErlangString -> string.stringValue()
            else -> return null
        }
        val binding = EvalBinding.of(args.getOrNull(1)) ?: return null
        val env = evalEnv(args.getOrNull(2)) ?: return null
        val line = env.line ?: 1
        if (line.toLong() + text.count { it == '\n' } > Int.MAX_VALUE) decline("eval_string/3, past the last line counted")
        val forms = run.files.parsed(text, line) ?: decline("eval_string/3, with no project to parse in")

        if (holdsBroken(forms)) {
            evaluation.mayRaise()
            evaluation.raiseAs("error", Unknown)
        }

        return evaluate(forms, binding, env)
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
