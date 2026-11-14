package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.expander.Expansion
import org.elixir_lang.expander.Followed

/**
 * `Code.require_file/1,2`, `eval_file/1,2` and `compile_file/1,2`: the one site that runs the code those forms run, in a
 * config read. A form that isn't one of those, or an argument whose error Elixir's clauses give, returns `null`, so
 * `Code`'s clauses run.
 */
internal class CodeFollow(private val evaluation: Evaluation) {
    private val run get() = evaluation.run

    fun call(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        if (run.config == null) return null

        return when (key) {
            "require_file/1", "require_file/2" -> path(args)?.let(::requireFile)
            "compile_file/1", "compile_file/2" -> path(args)?.let(::compileFile)
            "eval_file/1", "eval_file/2" -> path(args)?.let(::evalFile)
            else -> null
        }
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

        return tuple(followed.value, followed.binding)
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
}
