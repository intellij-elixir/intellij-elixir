package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.*
import org.elixir_lang.psi.ElixirFile
import java.io.File
import java.util.concurrent.Callable

/**
 * Each project under `testData/org/elixir_lang/expander/code_follow/rows`, whose `.row` names the command Elixir runs it
 * with (`judge.sh` runs each) and the attributes its answer reads. The plugin's answer is Elixir's, or, where the plugin
 * can't know it, the one it gives, with Elixir's beside it.
 */
class CodeFollowRowsHeavyTest : ConfigHeavyTestCase() {
    /** A row's plugin [answer] and [elixir]'s, and what each was [before] a feature that changed it. */
    private class Row(val name: String, val answer: String, val elixir: String, vararg val before: Before)

    fun testRows() {
        val base = File("testData/org/elixir_lang/expander/code_follow/rows")
        // Elixir's answer stands beside each row's, so a failure shows it.
        fun elixir(row: Row) = onLeg(row.elixir, row.before, Before::elixir)
        val expected = ROWS.joinToString("\n") { row -> "${row.name}\t${onLeg(row.answer, row.before)}\t(Elixir ${elixir(row)})" }
        val actual = ROWS.joinToString("\n") { row -> "${row.name}\t${answer(File(base, row.name))}\t(Elixir ${elixir(row)})" }

        assertEquals(expected, actual)
    }

    /** The row's answer: each probed attribute's value, one alone or as a tuple, as `judge.sh` prints Elixir's. */
    private fun answer(source: File): String {
        val spec = File(source, ".row").readLines().associate { it.substringBefore(": ") to it.substringAfter(": ", "") }
        val directory = createTempDir(source.name)
        FileUtil.copyDir(source, directory)
        val root = rooted(directory)
        val hasDep = root.findFileByRelativePath("deps_src/dep") != null
        if (hasDep) {
            val ebin = createTempDir("dep-ebin")
            File(directory, "deps_src/dep/ebin").listFiles()?.forEach { FileUtil.copy(it, File(ebin, it.name)) }
            FileUtil.writeToFile(File(ebin, "dep.app"), "{application, dep, [{vsn, \"0.1.0\"}, {modules, []}, {env, [{key, value}]}]}.\n")
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebin)!!
            ModuleRootModificationUtil.addModuleLibrary(module, "dep", listOf(virtual.url), emptyList())
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        try {
            return probed(root, spec.getValue("probe"))
        } finally {
            PsiTestUtil.removeContentEntry(module, root)
            if (hasDep) {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.moduleLibraryTable.libraries.filter { it.name == "dep" }.forEach(model.moduleLibraryTable::removeLibrary)
                }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
    }

    private fun probed(root: VirtualFile, probes: String): String =
        ReadAction.nonBlocking(Callable {
            val expansions = HashMap<String, FileExpansion>()
            val values = probes.split(' ').filter { it.isNotEmpty() }.map { probe ->
                val path = probe.substringBefore(':')
                val expansion = expansions.getOrPut(path) { holder.expansion(psi(root, path) as ElixirFile, legInputs) }

                value(probe.substringAfter(':'), expansion)
            }

            values.singleOrNull() ?: "{${values.joinToString(", ")}}"
        }).executeSynchronously()

    /**
     * `Module.attr`, the attribute's final value; `Module#name/arity`, whether the module defines the function; nothing,
     * that the file's expansion ended.
     */
    private fun value(probe: String, expansion: FileExpansion): String {
        if (probe.isEmpty()) return ":ended"
        val hash = probe.indexOf('#')
        val dot = if (hash >= 0) hash else probe.lastIndexOf('.')
        val module = probe.substring(0, dot)
        val result = ProjectModules.results(expansion).firstOrNull { it.module == "Elixir.$module" }
            ?: return raised(expansion) ?: "no-module"

        raised(result)?.let { return it }

        return if (hash >= 0) {
            val (name, arity) = probe.substring(hash + 1).split('/')

            (NameArity(name, arity.toInt()) in result.table.entries).toString()
        } else {
            rendered(result.attributes.final[probe.substring(dot + 1)])
        }
    }

    private fun raised(expansion: FileExpansion): String? = (expansion.top as? Expansion.Error)?.let { "raises ${it.kind}" }

    private fun raised(result: ExpansionResult): String? =
        when (val ended = result.ended) {
            is ExpansionResult.Ended.Raised -> "raises ${ended.error.kind}"
            is ExpansionResult.Ended.Crashed -> "raises ${ended.exception}"
            ExpansionResult.Ended.Tainted -> "raises tainted"
            else -> null
        }

    private companion object {
        val ROWS = listOf(
            Row("b3", "raises unloaded_module", elixir = "exit 1 raises CompileError"),
            Row("b4", "raises unloaded_module", elixir = "exit 1 raises CompileError"),
            // Which of Elixir's runs comes first is a race.
            Row("b5", "{unknown, unknown}", elixir = "{true, false} / {false, true}"),
            Row("b10", "{2, {2, [x: 2, y: 1]}, 4}", elixir = "{2, {2, [x: 2, y: 1]}, 4}"),
            Row("b11", "{nil, A, \"/x\", \"nofile\"}", elixir = "{nil, A, \"/x\", \"nofile\"}"),
            Row("b12", ":from_s", elixir = ":from_s"),
            Row("b13", "false", elixir = "1"),
            Row("b15", "true", elixir = ":from_macro"),
            Row("b19", ":ended", elixir = "exit 124"),
            Row("b20", ":ended", elixir = "exit 124"),
            Row("b21", "{unknown, unknown}", elixir = "{true, false} / {false, true}"),
            Row("b22", "true", elixir = "true"),
            Row("b26", "{2, [x: 1]}", elixir = "{2, [x: 1]}"),
            Row("b27", "{4, []}", elixir = "{4, []}"),
            Row("b29", "unknown", elixir = "exit 1 raises UndefinedFunctionError"),
            Row("b30", "unknown", elixir = "exit 1 raises UndefinedFunctionError"),
            Row("b31", "[A]", elixir = "[A]", Before(EVAL_ENV_KEEPS_CONTEXT_MODULES, "[]")),
            Row("b32", "{:f, 0}", elixir = "{:f, 0}"),
            // A string evaluated in a module's env evaluates another from `elixir_env:new()`'s env.
            Row("b33", "{nil, Bar}", elixir = "{nil, Bar}"),
            Row("c1", "{:value, nil, :from_file}", elixir = "{:value, nil, :from_file}"),
            Row("c2", "{:between, nil}", elixir = "{:between, nil}"),
            Row("c3c", "{:value, nil, :from_file}", elixir = "{:value, nil, :from_file}"),
            Row("c3e", "{:value, nil, :from_file}", elixir = "{:value, nil, :from_file}"),
            Row("c4", "{:value, nil, :from_file}", elixir = "{:value, nil, :from_file}"),
            Row("c5a", "{:value, nil, :from_file}", elixir = "{:value, nil, :from_file}"),
            Row("c5b", "{:value, nil, :from_file}", elixir = "{:value, nil, :from_file}"),
            Row("c6", "{:value, nil, :from_file}", elixir = "{:value, nil, :from_file}"),
            Row("c7", "{unknown, unknown, unknown}", elixir = "exit 1 raises Code.LoadError"),
            Row("c8", "{:value, nil, :sub}", elixir = "{:value, nil, :sub}"),
            Row("c9", "{:value, nil, :two}", elixir = "{:value, nil, :two}"),
            Row("c10c", "{:value, nil, :from_helper}", elixir = "{:value, nil, :from_helper}"),
            Row("c10e", "{:value, nil, :from_helper}", elixir = "{:value, nil, :from_helper}"),
            Row("c10r", "{:value, nil, :from_helper}", elixir = "{:value, nil, :from_helper}"),
            Row("c11", "{unknown, unknown, unknown}", elixir = "exit 1 raises CompileError"),
            Row("c12", "{unknown, unknown, unknown}", elixir = "exit 1 raises CompileError"),
            Row("c13", "{:value, nil, :legacy}", elixir = "{:value, nil, :legacy}"),
            Row("c14", "{:value, nil, :from_file_value}", elixir = "{:value, nil, :from_file_value}"),
            Row(
                "c15",
                "{:value, unknown, :earlier}",
                elixir = "{:value, nil, :earlier}",
                Before(UNREQUIRED_MACRO_CALLED_AS_FUNCTION, "{:value, unknown, unknown}"),
            ),
            Row("c16", "{unknown, unknown, unknown}", elixir = "{:value, nil, nil}"),
            Row("c17", "{unknown, unknown, unknown}", elixir = "{:other, nil, nil}"),
            Row("c18", "{:value, nil, :from_string}", elixir = "{:value, nil, :from_string}"),
            Row("c19", "{:value, nil, :from_string}", elixir = "{:value, nil, :from_string}"),
            Row("c20", "{unknown, unknown, unknown}", elixir = "exit 1 raises CompileError"),
            Row("c21", "{:value, nil, 2}", elixir = "{:value, nil, 2}"),
            Row("c22", "{:value, nil, :from_quoted}", elixir = "{:value, nil, :from_quoted}"),
            Row("c23", "{:value, nil, 2}", elixir = "{:value, nil, 2}"),
            Row("c24", "{unknown, unknown, unknown}", elixir = "{:value, nil, :computed}"),
            Row("c25a", "{unknown, unknown, unknown}", elixir = "{:value, :other, nil}"),
            Row("c25b", "{unknown, unknown, unknown}", elixir = "{:value, :other, nil}"),
            Row("c26", "{unknown, unknown, unknown}", elixir = "exit 1 raises ArgumentError"),
            Row("c27", "{:value, nil, 1}", elixir = "{:value, nil, 1}"),
            Row("c28", "{:value, nil, unknown}", elixir = "{:value, nil, nil}"),
            Row("c29", "{:value, nil, unknown}", elixir = "exit 1 raises CompileError"),
            Row("c32", "unknown", elixir = ":from_required"),
            // The binding an evaluation gives: its variables by name, descending since 1.12, ascending before.
            Row(
                "c33",
                "{:value, nil, [y: 1, x: 2, a: 3]}",
                elixir = "{:value, nil, [y: 1, x: 2, a: 3]}",
                Before(EVAL_BINDING_DESCENDS, "{:value, nil, [a: 3, x: 2, y: 1]}"),
            ),
            // An atom's evaluation gives the binding it was given since 1.12.3, reversed with each name's context before.
            Row(
                "c34",
                "{:value, nil, [x: 1]}",
                elixir = "{:value, nil, [x: 1]}",
                Before(EVAL_ATOM_GIVES_ITS_BINDING, "{:value, nil, [{{:x, nil}, 1}]}"),
            ),
            Row(
                "c35",
                "{:value, nil, [x: 2, y: 1, a: 3]}",
                elixir = "{:value, nil, [x: 2, y: 1, a: 3]}",
                Before(EVAL_ATOM_GIVES_ITS_BINDING, "{:value, nil, [{{:a, nil}, 3}, {{:y, nil}, 1}, {{:x, nil}, 2}]}"),
            ),
            // An integer's or a float's gives it as an atom's does since 1.14.2.
            Row(
                "c36",
                "{:value, nil, [x: 2, y: 1, a: 3]}",
                elixir = "{:value, nil, [x: 2, y: 1, a: 3]}",
                Before(EVAL_BINDING_DESCENDS, "{:value, nil, [a: 3, x: 2, y: 1]}"),
                Before(EVAL_NUMBER_GIVES_ITS_BINDING, "{:value, nil, [y: 1, x: 2, a: 3]}"),
            ),
            // A name given twice: the last value since 1.12, the first before.
            Row(
                "c37",
                "{:value, nil, {2, [x: 2]}}",
                elixir = "{:value, nil, {2, [x: 2]}}",
                Before(EVAL_BINDING_DESCENDS, "{:value, nil, {1, [x: 1]}}"),
            ),
            // A variable of a context is named with it, after every name without one before 1.12.
            Row(
                "c38",
                "{:value, nil, [{{:z, Foo}, 1}, {:y, 2}]}",
                elixir = "{:value, nil, [{{:z, Foo}, 1}, {:y, 2}]}",
                Before(EVAL_BINDING_DESCENDS, "{:value, nil, [{:y, 2}, {{:z, Foo}, 1}]}"),
            ),
            // Past 32 variables the binding is in a map's order, which isn't known.
            Row(
                "c39",
                "{:value, nil, unknown}",
                elixir = "{:value, nil, [v28: 28, ...]}",
                Before(
                    EVAL_BINDING_DESCENDS,
                    "{:value, nil, [v1: 1, v10: 10, v11: 11, v12: 12, v13: 13, v14: 14, v15: 15, v16: 16, v17: 17, v18: 18, v19: 19, v2: 2, v20: 20, v21: 21, v22: 22, v23: 23, v24: 24, v25: 25, v26: 26, v27: 27, v28: 28, v29: 29, v3: 3, v30: 30, v31: 31, v32: 32, v33: 33, v4: 4, v5: 5, v6: 6, v7: 7, v8: 8, v9: 9]}",
                    "{:value, nil, [v1: 1, v10: 10, ...]}",
                ),
            ),
            // A line not known declines the evaluation, which may write the read.
            Row("c40", "{unknown, unknown, unknown}", elixir = "{:value, nil, :computed}"),
            Row("c41", "0", elixir = "0"),
            // A line past the last one the plugin counts.
            Row("c42", "unknown", elixir = "2147483648"),
            // Each file a read imports makes references none of the others made.
            Row("c43", "false", elixir = "false"),
            Row("e1", ":ended", elixir = "exit 124"),
            Row("e2", ":ended", elixir = "exit 124"),
            Row("e6", "{unknown, unknown, unknown}", elixir = "exit 1 raises RuntimeError"),
            Row("e7", "raises Elixir.FunctionClauseError", elixir = "exit 1 raises FunctionClauseError"),
            Row("e8", "raises null", elixir = "exit 1 raises TokenMissingError"),
            // Before `Code.eval_string/2` checked its binding, the check was in Erlang's `elixir`, which the plugin can't read.
            Row(
                "e9",
                "raises Elixir.ArgumentError",
                elixir = "exit 1 raises ArgumentError",
                Before(EVAL_STRING_CHECKS_THE_BINDING, "unknown", "exit 1 raises FunctionClauseError"),
            ),
            Row("e10", "unknown", elixir = "[Sub.Inner, Sub]"),
            Row("i4", "true", elixir = ":from_h"),
            Row("i5", "{:value, nil, unknown}", elixir = "{:value, nil, nil}"),
            Row("i6", "{41, \"/x/y.ex\"}", elixir = "{41, \"/x/y.ex\"}"),
            Row("i9", "{:value, nil, unknown}", elixir = "{:value, nil, {:error, :nofile}}"),
            Row("i12", "unknown", elixir = "true"),
            Row("i13", "unknown", elixir = "false"),
            Row("i14", "unknown", elixir = "{:raised, :raised}", Before(REQUIRE_FILE_RELEASES_A_RAISED_FILE, elixir = "exit 124")),
            Row("i16", ":from_c", elixir = ":from_c"),
            Row("i17", ":from_first", elixir = ":from_first"),
            // Each evaluation's `make_ref/0` makes a reference no other has made.
            Row("i36", "{false, false}", elixir = "{false, false}"),
            // A string evaluated after the body wrote an app's env reads it as the body does.
            Row("i37", "unknown", elixir = ":other"),
            Row("i37b", "unknown", elixir = ":other"),
            Row("i37c", "unknown", elixir = ":other"),
            // A string evaluated inside a `try` runs inside it, which may catch its raise.
            Row("i38", "unknown", elixir = ":rescued"),
            // A string a body evaluates reads the module the body compiles, which its run doesn't see.
            Row("i41", "unknown", elixir = "1"),
            // A follow of a path not known writes what it may: the read declines.
            Row("w1", "{unknown, nil, unknown}", elixir = "{:computed, nil, :computed}"),
            // A follow in code that may not run declines: what it writes and loads would join nothing.
            Row("w3", "{:value, nil, unknown}", elixir = "{:value, nil, false}"),
        )
    }
}
