package org.elixir_lang.snapshot

import com.intellij.util.system.OS
import org.elixir_lang.elixir_surface.LegManifest.environment
import java.io.File

/** The facts `generate.exs` commits under [ROOT]: `<version>/<input>.facts`, one tab-separated fact per line. */
object CompilerFacts {
    const val ROOT = "testData/org/elixir_lang/snapshot/compiler"

    /** Each input's facts file name to the directory `generate.exs` compiles for it. */
    val INPUTS = mapOf(
        "callable_declaration" to "testData/org/elixir_lang/psi/callable_declaration",
        "columns" to "$ROOT/columns",
        "inputs" to "testData/org/elixir_lang/snapshot/inputs",
    )

    /** The Elixir version a facts file's first line names. */
    val HEADER = Regex("""^# Elixir (\S+)\.""")

    /** The leg's `elixir`, which runs `generate.exs`. */
    fun elixir(): File =
        File(environment("ELIXIR_LANG_ELIXIR_PATH"), if (OS.CURRENT == OS.Windows) "bin/elixir.bat" else "bin/elixir")

    /** Each version directory with each of its facts files. */
    fun committed(): List<Pair<File, File>> =
        File(ROOT).listFiles(File::isDirectory).orEmpty().sortedBy { it.name }.flatMap { version ->
            version.listFiles { file -> file.extension == "facts" }.orEmpty().sortedBy { it.name }.map { version to it }
        }

    /** Where Elixir placed a fact: [column] is Elixir's own, counted as the leg's tokenizer counts it. */
    data class Position(val path: String, val line: Int, val column: Int)

    sealed interface Fact {
        /** A call the compiler traced, as [kind], to [module]'s [name] of [arity]. */
        data class Ref(val position: Position, val kind: String, val module: String, val name: String, val arity: Int) :
            Fact

        /** An alias the compiler expanded to [module]. */
        data class Alias(val position: Position, val module: String) : Fact

        /** A function or macro [module] defines, as [kind], at [line]. */
        data class Def(val module: String, val kind: String, val name: String, val arity: Int, val line: Int) : Fact

        /** An error the compiler reported in a file that did not compile, as [kind] and what it names. */
        data class Diag(val path: String, val line: Int, val kind: String, val subject: List<String>) : Fact
    }

    /** [line] as a fact, or `null` when it is not one. */
    fun parse(line: String): Fact? {
        val fields = line.split('\t')

        return when (fields.first()) {
            "ref" -> fields.takeIf { it.size == 6 && it[2] in REF_KINDS && module(it[3]) && it[4].isNotEmpty() }?.let {
                Fact.Ref(position(it[1]) ?: return null, it[2], it[3], it[4], it[5].toIntOrNull() ?: return null)
            }
            "alias" -> fields.takeIf { it.size == 3 && module(it[2]) }?.let {
                Fact.Alias(position(it[1]) ?: return null, it[2])
            }
            "def" -> fields.takeIf { it.size == 6 && module(it[1]) && it[2] in DEF_KINDS && it[3].isNotEmpty() }?.let {
                Fact.Def(it[1], it[2], it[3], it[4].toIntOrNull() ?: return null, it[5].toIntOrNull() ?: return null)
            }
            "diag" -> fields.takeIf { it.size >= 3 }?.let {
                val (path, lineNumber) = DIAG_POSITION.matchEntire(it[1])?.destructured ?: return null
                val subject = it.drop(3)
                val named = when (it[2]) {
                    "undefined_function", "undefined_type" ->
                        subject.size == 2 && subject[0].isNotEmpty() && subject[1].toIntOrNull() != null
                    "module_not_available" -> subject.size == 1 && module(subject[0])
                    else -> false
                }

                if (named) Fact.Diag(path, lineNumber.toInt(), it[2], subject) else null
            }
            else -> null
        }
    }

    private fun position(field: String): Position? =
        POSITION.matchEntire(field)?.destructured?.let { (path, line, column) ->
            Position(path, line.toInt(), column.toInt())
        }

    private fun module(field: String): Boolean = MODULE.matches(field)

    private val REF_KINDS = setOf(
        "remote_function",
        "remote_macro",
        "imported_function",
        "imported_macro",
        "imported_quoted",
        "local_function",
        "local_macro",
    )
    /** The `def*` keywords a `def` fact names. */
    val DEF_KINDS = setOf("def", "defp", "defmacro", "defmacrop")
    /** A module as `inspect/1` writes its atom: quoted when it is neither an alias nor a plain atom. */
    private val MODULE = Regex("""[A-Z]\w*(?:\.[A-Z]\w*)*|:[\w@]+|:"(?:[^"\\]|\\.)*"""")
    private val POSITION = Regex("""(.+):(\d+):(\d+)""")
    private val DIAG_POSITION = Regex("""(.+):(\d+)""")
}
