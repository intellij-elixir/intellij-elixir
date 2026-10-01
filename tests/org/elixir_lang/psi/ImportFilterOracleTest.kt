package org.elixir_lang.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.Env
import org.elixir_lang.expander.KernelImports
import org.elixir_lang.expander.directiveValue
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.call.Call
import java.io.File

/**
 * Holds [Import.Filter] to what the compiler of the Elixir under test brings in. `oracle/generate.exs` writes each
 * case's `import` lines and, per Elixir version, the functions and macros they bring in from `m.ex` or the error the
 * compiler gives, and what `m.ex` exports.
 */
class ImportFilterOracleTest : PlatformTestCase() {
    fun testEveryCaseAgreesWithTheCompiler() {
        val languageLevel = ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION"))
        val goldens = File(testDataPath, languageLevel.elixirVersion)
        assertTrue(
            "No import oracle for Elixir ${languageLevel.elixirVersion}: in $testDataPath, run " +
                "`mise exec elixir@${languageLevel.elixirVersion}-otp-<otp> erlang@<otp> -- elixir generate.exs`",
            goldens.isDirectory
        )
        val exports = imports(goldens.resolve("exports.golden").readLines())
        val cases = File(testDataPath, "cases").listFiles { file -> file.extension == "ex" }!!.sortedBy { it.name }

        val expected = cases.joinToString("\n") { case ->
            section(case, goldens.resolve("${case.nameWithoutExtension}.golden").readLines().filterNot(::isComment))
        }
        val actual = cases.joinToString("\n") { case -> section(case, imported(case, exports, languageLevel)) }

        assertEquals(expected, actual)
    }

    /**
     * The same cases through [Import.Filter.of] over the options' expanded term, as the expander reads them. A case
     * whose options don't expand to a known term is skipped.
     */
    fun testEveryCaseAgreesThroughTheExpandedOptions() {
        val languageLevel = ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION"))
        val goldens = File(testDataPath, languageLevel.elixirVersion)
        val exports = imports(goldens.resolve("exports.golden").readLines())
        val cases = File(testDataPath, "cases").listFiles { file -> file.extension == "ex" }!!.sortedBy { it.name }
        val compared = cases.associateWith { case -> expandedImported(case, exports, languageLevel) }
            .filterValues { it != null }

        assertEquals(listOf("except_macro_call"), (cases - compared.keys).map { it.nameWithoutExtension })
        assertEquals(
            compared.keys.joinToString("\n") { case ->
                section(case, goldens.resolve("${case.nameWithoutExtension}.golden").readLines().filterNot(::isComment))
            },
            compared.entries.joinToString("\n") { (case, lines) -> section(case, lines!!) },
        )
    }

    /** `null` when an `import`'s options have no known expanded term. */
    private fun expandedImported(case: File, exports: Import.Imports, languageLevel: ElixirLanguageLevel): List<String>? {
        val file = myFixture.configureByText("case.ex", case.readText()) as ElixirFile
        val root = ReadAction.computeBlocking<ElixirAst, Throwable> { Lowering.lower(file, languageLevel) }
        val statements = (root as? ElixirAst.Block)?.expressions ?: listOf(root)
        val env = Env.empty(languageLevel, KernelImports(emptyList(), emptyList()))
        var imports: Import.Imports? = null

        for (statement in statements) {
            val arguments = (statement as? ElixirAst.Call)
                ?.takeIf { (it.callee as? ElixirAst.Literal.Atom)?.name == "import" }
                ?.arguments
                ?: continue
            val options = arguments.getOrNull(1)?.let { directiveValue(it, env, languageLevel) ?: return null }
                ?: Import.Term.List(emptyList())

            when (val filter = Import.Filter.of(options as Import.Term.List, languageLevel, imports).filter) {
                is Import.Filter.Invalid -> return listOf("error: ${filter.error.kind}")
                else -> imports = filter.imports(exports)
            }
        }

        return lines(checkNotNull(imports) { "${case.name} has no `import`" })
    }

    private fun imported(case: File, exports: Import.Imports, languageLevel: ElixirLanguageLevel): List<String> {
        val file = myFixture.configureByText("case.ex", case.readText())
        val importCalls = PsiTreeUtil.findChildrenOfType(file, Call::class.java).filter { it.functionName() == "import" }
        var imports: Import.Imports? = null

        for (importCall in importCalls) {
            when (val filter = Import.Filter.of(importCall, languageLevel, imports)) {
                is Import.Filter.Invalid -> return listOf("error: ${filter.error.kind}")
                else -> imports = filter.imports(exports)
            }
        }

        return lines(checkNotNull(imports) { "${case.name} has no `import`" })
    }

    private fun section(case: File, lines: List<String>): String =
        (listOf("# ${case.nameWithoutExtension}: ${case.readText().trim().replace("\n", "; ")}") + lines)
            .joinToString("\n")

    private fun lines(imports: Import.Imports): List<String> =
        listOf("functions") + lines(imports.functions) + listOf("macros") + lines(imports.macros)

    private fun lines(nameArities: Set<NameArity>): List<String> =
        nameArities.sortedWith(compareBy({ it.name }, { it.arity })).map { "  ${it.name}/${it.arity}" }

    private fun imports(golden: List<String>): Import.Imports {
        val body = golden.filterNot(::isComment)
        val macrosAt = body.indexOf("macros")

        fun nameArities(lines: List<String>): Set<NameArity> =
            lines.map { line ->
                val (name, arity) = line.trim().split("/")
                NameArity(name, arity.toInt())
            }.toSet()

        return Import.Imports(nameArities(body.subList(1, macrosAt)), nameArities(body.subList(macrosAt + 1, body.size)))
    }

    private fun isComment(line: String) = line.startsWith("#")

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/import/oracle"
}
