package org.elixir_lang.snapshot

import org.elixir_lang.coverage.FactsReport
import org.elixir_lang.junit.UnitTestCase
import java.io.File
import kotlin.io.path.createTempDirectory

class CompilerCorpusJoinGoldenTest : UnitTestCase() {
    fun testRowsSharingAKeyAreRefused() {
        val golden = CompilerCorpusJoinTestCase.Golden(FactsReport.ROOT, NAME, FactsReport::key)

        val refused = runCatching { golden.rows("$HEADER$ROW${ROW.replace("\t3", "\t4")}") }.exceptionOrNull()

        assertNotNull("Two rows keyed `ref local matched def` became one", refused)
    }

    fun testAFinalNewlineTheGoldenHasIsPartOfItsLayout() {
        val root = createTempDirectory().toFile()

        try {
            File(root, "$VERSION/$NAME").apply { parentFile.mkdirs() }.writeText("$HEADER$ROW")
            val golden = CompilerCorpusJoinTestCase.Golden(root.path, NAME, FactsReport::key)
            val actual = "$HEADER$ROW".removeSuffix("\n")

            val failed = runCatching { golden.assertSameLayout(VERSION, actual, golden.rows(actual)) }.exceptionOrNull()

            assertNotNull("A join without the golden's final newline has the same layout", failed)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val VERSION = "1.20.4"
        const val NAME = "corpus-0.report"
        const val HEADER = "# Elixir $VERSION\tshard 0\n${FactsReport.COLUMNS}\n"
        const val ROW = "ref\tlocal\tmatched\tdef\t3\n"
    }
}
