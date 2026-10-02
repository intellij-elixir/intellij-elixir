package org.elixir_lang.lowering

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LoggedErrorProcessor
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.parser_definition.ElixirLangElixirParsingTestCase
import org.elixir_lang.psi.ElixirFile
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** No shape in the corpus makes the lowering log, whether a whole file or each element the readers ask about. */
class UnknownShapesTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()

        ElixirLanguageLevelResolver.overrideLanguageLevel(
            project,
            ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION"), System.getenv("ERLANG_VERSION"))
        )
    }

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testNoCorpusShapeLogs() {
        val root = Path.of(System.getenv(ElixirLangElixirParsingTestCase.CORPUS_ENVIRONMENT_VARIABLE)!!)
        val logged = CopyOnWriteArrayList<String>()
        var path = ""
        val recording = object : GuardedLoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?) =
                if (category == "#${Lowering::class.java.name}") {
                    logged += "$path: $message"
                    Action.NONE
                } else {
                    super.processError(category, message, details, t)
                }
        }

        LoggedErrorProcessor.executeWith(recording).use {
            for (sourcePath in ElixirLangElixirParsingTestCase.sourcePaths(root)) {
                path = sourcePath
                val text = FileUtil.loadFile(root.resolve(sourcePath).toFile(), Charsets.UTF_8.name(), true).trim()
                val file = PsiFileFactory.getInstance(project)
                    .createFileFromText(sourcePath.substringAfterLast('/'), ElixirLanguage, text) as ElixirFile

                ReadAction.computeBlocking<Unit, Throwable> {
                    Lowering.lower(file, ElixirLanguageLevelResolver.languageLevelFor(file))
                    quotedElements(file).forEach { (_, element) -> ElementLowering.lower(element) }
                }
            }
        }

        assertEmpty(logged.take(20).joinToString("\n"), logged)
    }
}
