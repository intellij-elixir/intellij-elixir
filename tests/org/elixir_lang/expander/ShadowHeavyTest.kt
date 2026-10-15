package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.declaration.CandidateSource
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.EXPANDER
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.expanderSwitchOn
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.snapshot.CompilerFacts.INPUTS
import org.elixir_lang.snapshot.CompilerFacts.ROOT
import org.elixir_lang.snapshot.CompilerJoin
import org.elixir_lang.snapshot.text
import java.io.File

/**
 * Compares [ExpanderSource]'s candidates with [LegacyWalkSource]'s at every use in the snapshot inputs, and the
 * compiler join with the source switch off and on, in a project whose module has the leg's SDK; and the candidates at
 * every use of the Phoenix app `-Dorg.elixir_lang.expander.phoenix` names. It writes what moved to
 * `build/reports/expander-shadow/<elixir version>/` and asserts nothing on it.
 *
 * It runs only when `-Dorg.elixir_lang.declaration.source=expander` is set, since that puts the expander behind the
 * join, and the corpus's part only when the corpus is named too.
 */
class ShadowHeavyTest : HeavyTestCase() {
    override fun shouldRunTest(): Boolean {
        val skipped = if (name == CORPUS_TEST) {
            PhoenixCorpus.skipReason()
        } else {
            "$SOURCE_PROPERTY=$EXPANDER is not set".takeUnless { expanderSwitchOn() }
        }

        if (skipped != null) {
            println("${javaClass.simpleName}.$name skipped: $skipped")
            return false
        }

        return super.shouldRunTest()
    }

    override fun setUp() {
        super.setUp()
        val sdk = LegSdk.setUp(module, testRootDisposable)
        // A library file has no module, so it reads the project's SDK.
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }
    }

    override fun tearDown() {
        try {
            WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = null }
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testCallableDeclaration() = shadow("callable_declaration", seam = true)

    fun testInputs() = shadow("inputs", seam = true)

    fun testColumns() = shadow("columns", seam = false)

    fun testPhoenixCorpus() {
        val corpus = PhoenixCorpus(project, testRootDisposable, File(System.getProperty(PhoenixCorpus.PROPERTY)!!))
        corpus.setUp(module)
        val root = LocalFileSystem.getInstance().findFileByIoFile(corpus.dir)!!
        val report = File(REPORTS, environment("ELIXIR_VERSION")).apply { mkdirs() }

        for ((set, files) in corpus.asked().groupBy { it.set }) {
            val uses = files.flatMap { file -> file.uses.map { file.psi to it } }
            val before = loweringErrors(corpus)
            val candidates = corpus.quiet { candidates(root, uses) }
            val errors = loweringErrors(corpus).mapValues { (message, count) -> count - (before[message] ?: 0) }.filterValues { it > 0 }

            File(report, "phoenix-$set.candidates.txt").writeText(
                buildString {
                    appendLine("# ${errors.values.sum()} lowering errors logged, counted rather than failing the run")
                    errors.forEach { (message, count) -> appendLine("#   $count\t$message") }
                    append(candidates)
                }
            )
        }
    }

    private fun loweringErrors(corpus: PhoenixCorpus): Map<String, Int> =
        synchronized(corpus.loweringErrors) { corpus.loweringErrors.toMap() }

    private fun shadow(input: String, seam: Boolean) {
        val elixirVersion = environment("ELIXIR_VERSION")
        val (root, files) = copy(File(INPUTS.getValue(input)))
        val report = File(REPORTS, elixirVersion).apply { mkdirs() }

        if (seam) {
            val uses = ReadAction.computeBlocking<List<Pair<PsiFile, Use?>>, Throwable> {
                uses(files).map { (file, call) -> file to Use.of(call) }
            }
            File(report, "$input.candidates.txt").writeText(candidates(root, uses))
        }
        File(report, "$input.join.txt").writeText(join(root, elixirVersion, input))
    }

    /**
     * Each use whose candidates differ between the sources, as the walk's only (`-`), the expander's only (`+`) and a
     * line both give but different numbers of times (`~ <walk's> to <expander's>`), after a count of each shape of
     * difference: its lines with the locations left out.
     */
    private fun candidates(root: VirtualFile, uses: List<Pair<PsiFile, Use?>>): String =
        ReadAction.computeBlocking<String, Throwable> {
            val moved = uses.flatMap { (file, use) ->
                val label = use?.entrance?.textRange?.let { "${location(root, file, it)}  `${text(file, it)}`" } ?: "?"

                listOf(false, true).mapNotNull { incompleteCode ->
                    val walk = describe(root, use, LegacyWalkSource, incompleteCode)
                    val expander = describe(root, use, ExpanderSource, incompleteCode)

                    if (walk == expander) {
                        null
                    } else {
                        val lines = (walk - expander.toSet()).map { "- $it" } + (expander - walk.toSet()).map { "+ $it" }
                        val counts = walk.distinct().filter { it in expander }.mapNotNull { line ->
                            val (inWalk, inExpander) = walk.count { it == line } to expander.count { it == line }

                            "~ $inWalk to $inExpander $line".takeIf { inWalk != inExpander }
                        }

                        "$label  ic=$incompleteCode" to (lines + counts).ifEmpty { listOf("~ order only") }
                    }
                }
            }
            val shapes = moved.groupingBy { (_, lines) -> lines.map(::shape).distinct().sorted().joinToString(" ; ") }
                .eachCount().entries.sortedByDescending { it.value }

            buildString {
                appendLine("# ${uses.size * 2} use and incompleteCode pairs compared, ${moved.size} differ")
                appendLine("# by shape:")
                shapes.forEach { (shape, count) -> appendLine("#   $count\t$shape") }
                moved.forEach { (label, lines) ->
                    appendLine(label)
                    lines.forEach { appendLine("  $it") }
                }
            }
        }

    /** A difference line without its locations, its `via` only a count. */
    private fun shape(line: String): String =
        VIA.replace(line) { via -> "via ${via.groupValues[1].split(", ").count(String::isNotEmpty)}" }.replace(LOCATION, "@")

    /** The join's lines with the switch off then on, and the lines only one of them has. */
    private fun join(root: VirtualFile, elixirVersion: String, input: String): String {
        val facts = File("$ROOT/$elixirVersion/$input.facts").readLines()
        val off = withoutSwitch { joined(root, elixirVersion, facts) }
        val on = joined(root, elixirVersion, facts)

        return buildString {
            appendLine("# counts, switch off")
            off.filter(::isCount).forEach { appendLine(it) }
            appendLine("# counts, switch on")
            on.filter(::isCount).forEach { appendLine(it) }
            appendLine("# unmatched off only")
            (off.filterNot(::isCount) - on.toSet()).forEach { appendLine("- $it") }
            appendLine("# unmatched on only")
            (on.filterNot(::isCount) - off.toSet()).forEach { appendLine("+ $it") }
        }
    }

    private fun joined(root: VirtualFile, elixirVersion: String, facts: List<String>): List<String> =
        ReadAction.computeBlocking<List<String>, Throwable> {
            CompilerJoin.lines(CompilerJoin(project, root, elixirVersion).join(facts))
        }

    /** A count line is a kind, an outcome and a number; an unmatched line starts with its outcome. */
    private fun isCount(line: String): Boolean = line.substringAfterLast('\t').all(Char::isDigit) &&
        line.count { it == '\t' } == 2 && line.substringBefore('\t') in CompilerJoin.KINDS

    private fun <T> withoutSwitch(block: () -> T): T {
        val switch = System.clearProperty(SOURCE_PROPERTY)

        return try {
            block()
        } finally {
            System.setProperty(SOURCE_PROPERTY, switch)
        }
    }

    private fun describe(root: VirtualFile, use: Use?, source: CandidateSource, incompleteCode: Boolean): List<String> =
        when (use) {
            null -> listOf("names nothing")
            else -> source.candidates(use, incompleteCode).map { describe(root, it) }.ifEmpty { listOf("nothing") }
        }

    private fun describe(root: VirtualFile, found: Found): String {
        val (declaration, reach, applicability) = found.candidate
        val form = when (val declared = declaration.declared) {
            is Declared.Source -> declared.form.name.lowercase()
            is Declared.Compiled -> "compiled"
        }
        val via = found.via.joinToString(", ") { location(root, it.containingFile, it.textRange) }

        return "${location(root, found.element.containingFile, found.element.textRange)}  " +
            "${declaration.name}/${declaration.arity.text()} $form  ${reach.name.lowercase()}  " +
            "${applicability.name.lowercase()}  via [$via]"
    }

    /** The uses in [files], each a call a callable reference is on, in path and offset order. */
    private fun uses(files: List<PsiFile>): List<Pair<PsiFile, Call>> {
        val uses = mutableListOf<Pair<PsiFile, Call>>()

        for (file in files.flatMap { it.viewProvider.allFiles }) {
            file.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)

                    PsiReferenceService.getService().getReferences(element, NO_HINTS)
                        .filterIsInstance<org.elixir_lang.reference.Callable>()
                        .forEach { uses += file to it.element }
                }
            })
        }

        return uses.sortedWith(compareBy({ it.first.viewProvider.virtualFile.path }, { it.second.textOffset }))
    }

    /** Where [range] is in [file]: its path under [root], or only its name for an SDK file. */
    private fun location(root: VirtualFile, file: PsiFile, range: TextRange): String {
        val virtualFile = file.viewProvider.virtualFile
        val path = VfsUtilCore.getRelativePath(virtualFile, root) ?: virtualFile.name
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return "$path@${range.startOffset}"
        val line = document.getLineNumber(range.startOffset)

        return "$path:${line + 1}:${range.startOffset - document.getLineStartOffset(line) + 1}"
    }

    private fun text(file: PsiFile, range: TextRange): String =
        range.subSequence(file.viewProvider.contents).toString().replace("\n", "\\n").let {
            if (it.length > MAX_TEXT) it.take(MAX_TEXT) + "..." else it
        }

    /** [input] copied into a source root of its own, and its files. */
    private fun copy(input: File): Pair<VirtualFile, List<PsiFile>> {
        val directory = createTempDir("shadow")
        FileUtil.copyDir(input, directory)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val psiManager = PsiManager.getInstance(project)
        val files = mutableListOf<PsiFile>()
        ReadAction.computeBlocking<Unit, Throwable> {
            VfsUtilCore.iterateChildrenRecursively(root, null) { virtualFile ->
                if (!virtualFile.isDirectory) psiManager.findFile(virtualFile)?.let { files += it }
                true
            }
        }

        return root to files
    }

    private companion object {
        const val CORPUS_TEST = "testPhoenixCorpus"
        const val MAX_TEXT = 80
        val LOCATION = Regex("""\S+(:\d+:\d+|@\d+)""")
        val VIA = Regex("""via \[([^]]*)]""")
        val REPORTS = File("build/reports/expander-shadow")
    }
}
