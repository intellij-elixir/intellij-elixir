package org.elixir_lang.snapshot

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFileFactory
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Tokenization
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.snapshot.CompilerFacts.Fact
import org.elixir_lang.snapshot.CompilerFacts.INPUTS
import org.elixir_lang.snapshot.CompilerFacts.Position
import org.elixir_lang.snapshot.CompilerFacts.committed
import java.io.File

/** Fails when a committed fact's position is not one the plugin places at an offset in its file for that version. */
class CompilerJoinTest : SnapshotTestCase() {
    fun testEveryCommittedPositionIsInItsFile() {
        val offsets = mutableMapOf<Pair<File, String>, Map<Pair<Int, Int>, Int>>()
        val misplaced = committed().flatMap { (version, facts) ->
            val input = File(INPUTS.getValue(facts.nameWithoutExtension))

            facts.readLines().withIndex().mapNotNull { (index, line) ->
                val position = position(CompilerFacts.parse(line)) ?: return@mapNotNull null
                val file = File(input, position.path)
                val placed = file.isFile &&
                    offsets.getOrPut(file to version.name) { offsets(file, version.name) }
                        .containsKey(position.line to position.column)

                if (placed) null else "$facts:${index + 1}: $line"
            }
        }

        assertTrue("Facts at positions outside their files:\n${misplaced.joinToString("\n")}", misplaced.isEmpty())
    }

    private fun position(fact: Fact?): Position? =
        when (fact) {
            is Fact.Ref -> fact.position
            is Fact.Alias -> fact.position
            else -> null
        }

    /** The first offset in [file] at each (line, column) [elixirVersion]'s tokenizer counts. */
    private fun offsets(file: File, elixirVersion: String): Map<Pair<Int, Int>, Int> {
        val text = file.readText()
        val psiFile =
            PsiFileFactory.getInstance(project).createFileFromText(file.name, ElixirLanguage, text) as ElixirFile

        return ReadAction.computeBlocking<Map<Pair<Int, Int>, Int>, Throwable> {
            val lines = Tokenization.lines(psiFile, text, ElixirLanguageLevel.of(elixirVersion))
            val offsets = mutableMapOf<Pair<Int, Int>, Int>()

            for (offset in 0..text.length) {
                val position = lines.position(offset)
                offsets.putIfAbsent(position.line to position.column, offset)
            }

            offsets
        }
    }
}
