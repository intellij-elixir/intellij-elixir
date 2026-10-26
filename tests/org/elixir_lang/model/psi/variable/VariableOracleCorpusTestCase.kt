package org.elixir_lang.model.psi.variable

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.refactoring.rename.api.RenameTarget
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetDirectly
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.psi.UnqualifiedBracketOperation
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import java.io.File

/**
 * Holds the plugin's idea of "the same variable" against the compiler's, over every binding shape crossed with
 * every read shape in each context. `oracle/generate.exs` writes a fixture per combination and a golden beside it
 * that lists each variable occurrence with the compiler's version and the rebinding class it belongs to; two
 * occurrences are one variable exactly when their classes agree. Every path is asked the same question for every
 * occurrence, resolution, classification, the chain root, Find Usages, the rename target and both scopes, and
 * the rename itself is run once per class, since carets with one target run one search. Every disagreement is
 * collected, so a change that moves one path away from the others fails here by path, fixture and position.
 *
 * The fixtures are split over [SHARDS] classes by id, so parallel test JVMs share the corpus between them.
 */
@Suppress("UnstableApiUsage")
abstract class VariableOracleCorpusTestCase(private val shard: Int) : PlatformTestCase() {
    private class Occurrence(
        val name: String,
        val line: Int,
        val column: Int,
        val version: Int,
        val group: Int,
        val binds: Boolean
    ) {
        val position get() = line to column
        val label get() = "$name@$line:$column"
    }

    private class Case(val id: String, val fileName: String, val text: String, val occurrences: List<Occurrence>) {
        val watched get() = occurrences.filter { it.name == WATCHED }
        private val occurrencesByGroup = occurrences.groupBy { it.group }
        fun group(occurrence: Occurrence) = occurrencesByGroup.getValue(occurrence.group)
        fun root(occurrence: Occurrence) = group(occurrence).first { it.binds && it.version == it.group }
    }

    private val disagreements = PATHS.associateWith { mutableListOf<String>() }
    /** The rename targets at each class's root binding: what every caret is held to, and what is renamed. */
    private val rootTargets = mutableMapOf<Pair<String, Int>, List<RenameTarget>>()
    private var checked = 0

    fun testEveryPathAgreesWithTheCompiler() {
        val cases = cases()
        println("variable oracle corpus shard $shard: ${cases.size} fixtures")

        for (case in cases) {
            load(case)
            for (occurrence in case.occurrences) checkIdentity(case, occurrence)
            for (occurrence in case.watched) checkSearch(case, occurrence)

            for (root in case.watched.filter { it.binds && it.version == it.group }) {
                load(case)
                check(case, root, "rename") { _ ->
                    val targets = rootTargets.getValue(case.id to root.group)
                    val target = targets.singleOrNull()
                        ?: return@check "expected exactly one rename target at the root, got ${targets.size}: $targets"
                    myFixture.renameTargetDirectly(target, RENAMED)
                    val actual = myFixture.file.text
                    val expected = renamed(case, case.group(root))
                    if (actual == expected) null else "renamed to:\n$actual"
                }
            }
        }

        report()
    }

    private val opened = mutableMapOf<String, VirtualFile>()

    /** Puts [case]'s text in one editor per extension, opened once: an editor costs far more than a document. */
    private fun load(case: Case) {
        val extension = case.fileName.substringAfterLast('.')
        val file = opened.getOrPut(extension) { myFixture.configureByText("fixture.$extension", "").virtualFile }
        if (myFixture.file?.virtualFile != file) myFixture.openFileInEditor(file)
        // a rename is the only edit, so a document still holding the text has nothing to reparse
        if (StringUtil.equals(myFixture.editor.document.immutableCharSequence, case.text)) return
        WriteCommandAction.runWriteCommandAction(project) {
            @Suppress("UsePropertyAccessSyntax") // `Document.text` has no setter
            myFixture.editor.document.setText(case.text)
            // committing under the same write lock forestalls the background commit the change would otherwise queue
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    private fun checkIdentity(case: Case, occurrence: Occurrence) {
        check(case, occurrence, "declaration") { element ->
            val declares = runReadActionBlocking { VariableSymbol.isDeclaration(element) }
            if (declares == occurrence.binds) {
                null
            } else {
                "is a ${if (declares) "declaration" else "read"} to the plugin, " +
                    "a ${if (occurrence.binds) "binding" else "read"} to the compiler"
            }
        }

        if (occurrence.binds) {
            check(case, occurrence, "chain root") { element ->
                val root = case.root(occurrence).position
                val chained = runReadActionBlocking { VariableSymbol.fromDeclaration(element)?.chainRootSymbol() }
                    ?.let { position(it.range.startOffset) }
                if (chained == root) null else "chains to $chained, not to $root"
            }
        } else {
            check(case, occurrence, "resolution") { element ->
                val resolved = runReadActionBlocking { VariableReference.resolveSymbols(element) }
                    .map { position(it.range.startOffset) }
                val group = case.group(occurrence).map { it.position }

                when {
                    resolved.isEmpty() -> "resolves to nothing"
                    resolved.any { it !in group } ->
                        "resolves to ${resolved.filter { it !in group }}, outside its binding"
                    else -> null
                }
            }
        }
    }

    private fun checkSearch(case: Case, occurrence: Occurrence) {
        check(case, occurrence, "usages") { element ->
            myFixture.editor.caretModel.moveToOffset(element.textRange.startOffset)
            val found = myFixture.psiUsagesAtCaret(project).map { position(it.range.startOffset) }.toSet()
            val group = case.group(occurrence).map { it.position }.toSet()
            if (found == group) null else "finds ${found.ordered()}, expected ${group.ordered()}"
        }

        // carets with one target run one search, so the rename itself runs once per class, from its root
        check(case, occurrence, "rename target") { element ->
            myFixture.editor.caretModel.moveToOffset(element.textRange.startOffset)
            val targets = myFixture.renameTargetsAtCaret()
            val root = case.root(occurrence)
            val expected = rootTargets.getOrPut(case.id to occurrence.group) {
                // the root binding comes first in its class, so this caret is the root's own on the first visit
                if (occurrence === root) targets else {
                    myFixture.editor.caretModel.moveToOffset(elementAt(root).textRange.startOffset)
                    myFixture.renameTargetsAtCaret()
                }
            }
            val actual = targets.map { it.chainRoot() }
            val held = expected.map { it.chainRoot() }
            if (actual == held) null else "rename target $actual, from the binding $held"
        }

        check(case, occurrence, "scope") { element ->
            val classmates = case.group(occurrence).map { elementAt(it) }
            val useScope = runReadActionBlocking { element.useScope } as? LocalSearchScope
            val symbolScope =
                runReadActionBlocking { VariableSymbol.fromElement(element)?.maximalSearchScope } as? LocalSearchScope

            listOfNotNull(
                useScope?.leftOut(classmates)?.let { "use scope leaves out $it" },
                symbolScope?.leftOut(classmates)?.let { "symbol scope leaves out $it" }
            ).takeIf { it.isNotEmpty() }?.joinToString("; ")
        }
    }

    /** Runs [ask] on [occurrence]'s element and files a non-null answer under [path]. */
    private fun check(case: Case, occurrence: Occurrence, path: String, ask: (PsiElement) -> String?) {
        checked++
        val element = elementAtOrNull(occurrence)
        val answer = if (element == null) {
            "no variable element"
        } else {
            try {
                ask(element)
            } catch (e: Throwable) {
                "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
            }
        } ?: return

        disagreements.getValue(path) += "${case.id} ${occurrence.label}: $answer"
    }

    private fun report() {
        // the assertion shows the first few per path; the whole lists go under build/ for triage
        val directory = File("build/oracle-corpus/shard-$shard").apply { mkdirs() }
        val sections = PATHS.mapNotNull { path ->
            val failures = disagreements.getValue(path)
            directory.resolve("$path.txt").writeText(failures.joinToString("\n"))
            failures.takeIf { it.isNotEmpty() }?.let {
                val more = if (it.size > REPORTED) "\n  ... and ${it.size - REPORTED} more" else ""
                "$path: ${it.size} disagree:\n  " + it.take(REPORTED).joinToString("\n  ") + more
            }
        }
        println("variable oracle corpus shard $shard: $checked checks")
        assertTrue(
            "$checked checks; disagreements with the compiler:\n" + sections.joinToString("\n"),
            sections.isEmpty()
        )
    }

    private fun RenameTarget.chainRoot(): Any =
        (this as? VariableSymbol)?.let { runReadActionBlocking { it.chainRootSymbol() } } ?: this

    private fun LocalSearchScope.leftOut(elements: List<PsiElement>): List<Pair<Int, Int>>? =
        elements.filterNot { PsiSearchScopeUtil.isInScope(this, it) }
            .map { position(it.textRange.startOffset) }
            .takeIf { it.isNotEmpty() }

    private fun renamed(case: Case, group: List<Occurrence>): String {
        val lines = case.text.split("\n").toMutableList()
        for (occurrence in group.sortedWith(compareBy({ it.line }, { -it.column }))) {
            val line = lines[occurrence.line - 1]
            lines[occurrence.line - 1] = line.substring(0, occurrence.column - 1) + RENAMED +
                line.substring(occurrence.column - 1 + WATCHED.length)
        }
        return lines.joinToString("\n")
    }

    private fun elementAt(occurrence: Occurrence): PsiElement =
        elementAtOrNull(occurrence) ?: error("no variable element at ${occurrence.label}")

    private fun elementAtOrNull(occurrence: Occurrence): PsiElement? {
        val offset = myFixture.editor.document.getLineStartOffset(occurrence.line - 1) + occurrence.column - 1
        return generateSequence(myFixture.file.findElementAt(offset)) { it.parent }
            .takeWhile { it.textRange.startOffset == offset }
            .firstOrNull {
                (it is UnqualifiedNoArgumentsCall<*> || it is ElixirVariable || it is UnqualifiedBracketOperation) &&
                    VariableSymbol.variableName(it) == occurrence.name
            }
    }

    private fun position(offset: Int): Pair<Int, Int> {
        val document = myFixture.editor.document
        val line = document.getLineNumber(offset)
        return (line + 1) to (offset - document.getLineStartOffset(line) + 1)
    }

    private fun Set<Pair<Int, Int>>.ordered() = sortedWith(compareBy({ it.first }, { it.second }))

    private fun cases(): List<Case> {
        val directory = File("testData/org/elixir_lang/model/psi/variable/oracle/cases")
        val files = directory.listFiles()?.groupBy { it.extension == "golden" }
            ?: error("no corpus under ${directory.absolutePath}; run generate.exs beside it")
        val goldens = files[true].orEmpty().takeIf { it.isNotEmpty() }
            ?: error("no goldens under ${directory.absolutePath}; run generate.exs beside it")
        val sources = files[false].orEmpty().associateBy { it.nameWithoutExtension }
        // ELIXIR_ORACLE_CASES narrows a run to the ids matching a regex, for iterating on a handful of fixtures
        val only = System.getenv("ELIXIR_ORACLE_CASES")?.toRegex()
        val selected = goldens.sortedBy { it.name }.filter { only?.containsMatchIn(it.nameWithoutExtension) ?: true }
        assertFalse("no fixture id matches ELIXIR_ORACLE_CASES=$only", selected.isEmpty())

        return selected.filter { Math.floorMod(it.nameWithoutExtension.hashCode(), SHARDS) == shard }.map { golden ->
            val id = golden.nameWithoutExtension
            val source = sources.getValue(id)
            val occurrences = golden.readLines().filterNot { it.startsWith("#") || it.isBlank() }.map { line ->
                val fields = line.split(" ")
                Occurrence(
                    fields[0],
                    fields[1].toInt(),
                    fields[2].toInt(),
                    fields[3].toInt(),
                    fields[4].toInt(),
                    fields[5] == "bind"
                )
            }
            Case(id, source.name, source.readText().replace("\r\n", "\n"), occurrences)
        }
    }

    companion object {
        const val SHARDS = 40

        private val PATHS =
            listOf("declaration", "resolution", "chain root", "usages", "rename target", "scope", "rename")

        private const val WATCHED = "x"
        private const val RENAMED = "renamed"
        private const val REPORTED = 25
    }
}

class VariableOracleCorpusShard00Test : VariableOracleCorpusTestCase(0)

class VariableOracleCorpusShard01Test : VariableOracleCorpusTestCase(1)

class VariableOracleCorpusShard02Test : VariableOracleCorpusTestCase(2)

class VariableOracleCorpusShard03Test : VariableOracleCorpusTestCase(3)

class VariableOracleCorpusShard04Test : VariableOracleCorpusTestCase(4)

class VariableOracleCorpusShard05Test : VariableOracleCorpusTestCase(5)

class VariableOracleCorpusShard06Test : VariableOracleCorpusTestCase(6)

class VariableOracleCorpusShard07Test : VariableOracleCorpusTestCase(7)

class VariableOracleCorpusShard08Test : VariableOracleCorpusTestCase(8)

class VariableOracleCorpusShard09Test : VariableOracleCorpusTestCase(9)

class VariableOracleCorpusShard10Test : VariableOracleCorpusTestCase(10)

class VariableOracleCorpusShard11Test : VariableOracleCorpusTestCase(11)

class VariableOracleCorpusShard12Test : VariableOracleCorpusTestCase(12)

class VariableOracleCorpusShard13Test : VariableOracleCorpusTestCase(13)

class VariableOracleCorpusShard14Test : VariableOracleCorpusTestCase(14)

class VariableOracleCorpusShard15Test : VariableOracleCorpusTestCase(15)

class VariableOracleCorpusShard16Test : VariableOracleCorpusTestCase(16)

class VariableOracleCorpusShard17Test : VariableOracleCorpusTestCase(17)

class VariableOracleCorpusShard18Test : VariableOracleCorpusTestCase(18)

class VariableOracleCorpusShard19Test : VariableOracleCorpusTestCase(19)

class VariableOracleCorpusShard20Test : VariableOracleCorpusTestCase(20)

class VariableOracleCorpusShard21Test : VariableOracleCorpusTestCase(21)

class VariableOracleCorpusShard22Test : VariableOracleCorpusTestCase(22)

class VariableOracleCorpusShard23Test : VariableOracleCorpusTestCase(23)

class VariableOracleCorpusShard24Test : VariableOracleCorpusTestCase(24)

class VariableOracleCorpusShard25Test : VariableOracleCorpusTestCase(25)

class VariableOracleCorpusShard26Test : VariableOracleCorpusTestCase(26)

class VariableOracleCorpusShard27Test : VariableOracleCorpusTestCase(27)

class VariableOracleCorpusShard28Test : VariableOracleCorpusTestCase(28)

class VariableOracleCorpusShard29Test : VariableOracleCorpusTestCase(29)

class VariableOracleCorpusShard30Test : VariableOracleCorpusTestCase(30)

class VariableOracleCorpusShard31Test : VariableOracleCorpusTestCase(31)

class VariableOracleCorpusShard32Test : VariableOracleCorpusTestCase(32)

class VariableOracleCorpusShard33Test : VariableOracleCorpusTestCase(33)

class VariableOracleCorpusShard34Test : VariableOracleCorpusTestCase(34)

class VariableOracleCorpusShard35Test : VariableOracleCorpusTestCase(35)

class VariableOracleCorpusShard36Test : VariableOracleCorpusTestCase(36)

class VariableOracleCorpusShard37Test : VariableOracleCorpusTestCase(37)

class VariableOracleCorpusShard38Test : VariableOracleCorpusTestCase(38)

class VariableOracleCorpusShard39Test : VariableOracleCorpusTestCase(39)
