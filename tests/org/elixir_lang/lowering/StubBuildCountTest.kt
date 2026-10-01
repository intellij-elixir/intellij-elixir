package org.elixir_lang.lowering

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFileFactory
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.parser_definition.ElixirLangElixirParsingTestCase
import org.elixir_lang.psi.ElixirFile
import java.nio.file.Path

/** What building every corpus file's stubs costs in lowering work, which wall time is too noisy to bound. */
class StubBuildCountTest : PlatformTestCase() {
    fun testStubBuildingLowersEachRequestAloneOverOneLineIndexPerFile() {
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
        val root = Path.of(System.getenv(ElixirLangElixirParsingTestCase.CORPUS_ENVIRONMENT_VARIABLE)!!)
        val overLowered = mutableListOf<String>()
        var requested = 0

        LoweringCounters.reset()
        LoweringCounters.counting = true

        try {
            for (path in ElixirLangElixirParsingTestCase.sourcePaths(root)) {
                val text = FileUtil.loadFile(root.resolve(path).toFile(), Charsets.UTF_8.name(), true)
                val file = PsiFileFactory.getInstance(project)
                    .createFileFromText(path.substringAfterLast('/'), ElixirLanguage, text) as ElixirFile
                val before = counts()
                ReadAction.computeBlocking<Unit, Throwable> {
                    org.elixir_lang.psi.stub.type.File.INSTANCE.builder.buildStubTree(file)
                }
                val (requests, foreign, tokenizations, lineIndexes) = counts().zip(before) { after, prior -> after - prior }

                if (requests > 0) requested++
                if (foreign != 0L || tokenizations != 0L || lineIndexes != (if (requests > 0) 1L else 0L)) {
                    overLowered += "$path: requests=$requests foreignLowerings=$foreign tokenizations=$tokenizations lineIndexes=$lineIndexes"
                }
            }
        } finally {
            LoweringCounters.counting = false
        }

        assertTrue("no stub build lowered anything", requested > 0)
        assertEmpty(overLowered.take(20).joinToString("\n"), overLowered)
    }

    private fun counts(): List<Long> =
        with(LoweringCounters) { listOf(requests, foreignLowerings, tokenizations, lineIndexes).map { it.sum() } }
}
