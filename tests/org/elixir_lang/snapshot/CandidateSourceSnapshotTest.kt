package org.elixir_lang.snapshot

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.sourceFor

/**
 * Fails when the candidates at any use in an input directory, or what completion sees at the start of any reference,
 * differ from that directory's committed snapshot.
 */
class CandidateSourceSnapshotTest : SnapshotTestCase() {
    fun testCallableDeclarationCandidates() =
        assertCandidates("psi/callable_declaration", "snapshot/callable_declaration.candidates.txt")

    fun testInputsCandidates() = assertCandidates("snapshot/inputs", "snapshot/inputs.candidates.txt")

    fun testCallableDeclarationVisible() =
        assertVisible("psi/callable_declaration", "snapshot/callable_declaration.visible.txt")

    fun testInputsVisible() = assertVisible("snapshot/inputs", "snapshot/inputs.visible.txt")

    private fun assertCandidates(inputDirectory: String, golden: String) {
        val (root, files) = copyInputs(inputDirectory)
        val snapshot = ResolutionSnapshot(root)
        val source = sourceFor(Feature.SYMBOL_REFERENCES)
        val lines = uses(files).flatMap { (file, reference) ->
            val label = label(snapshot, file, reference)

            listOf(false, true).flatMap { incompleteCode ->
                val use = Use.of(reference.element)
                val found = use?.let { source.candidates(it, incompleteCode) }.orEmpty()
                val prefix = "$label  ic=$incompleteCode"

                when {
                    use == null -> listOf("$prefix  ->  names nothing")
                    found.isEmpty() -> listOf("$prefix  ->  nothing")
                    else -> found.mapIndexed { index, it -> "$prefix  ${index + 1}  ${describe(snapshot, it)}" }
                }
            }
        }

        assertGolden(golden, CANDIDATES_HEADER, lines, REGENERATE)
    }

    private fun assertVisible(inputDirectory: String, golden: String) {
        val (root, files) = copyInputs(inputDirectory)
        val snapshot = ResolutionSnapshot(root)
        val source = sourceFor(Feature.COMPLETION)
        val lines = uses(files).flatMap { (file, reference) ->
            val label = label(snapshot, file, reference)

            source.visible(reference.element).map { "$label  ${describe(snapshot, it)}" }
        }

        assertGolden(golden, VISIBLE_HEADER, lines, REGENERATE)
    }

    private fun uses(files: List<PsiFile>): List<Pair<PsiFile, org.elixir_lang.reference.Callable>> {
        val uses = mutableListOf<Pair<PsiFile, org.elixir_lang.reference.Callable>>()

        for (file in files.flatMap { it.viewProvider.allFiles }) {
            file.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)

                    PsiReferenceService.getService().getReferences(element, NO_HINTS)
                        .filterIsInstance<org.elixir_lang.reference.Callable>()
                        .forEach { uses += file to it }
                }
            })
        }

        return uses.sortedWith(compareBy({ it.first.viewProvider.virtualFile.path }, { it.second.absoluteRange.startOffset }))
    }

    private fun label(snapshot: ResolutionSnapshot, file: PsiFile, reference: org.elixir_lang.reference.Callable): String =
        "${snapshot.location(file, reference.absoluteRange)}  `${snapshot.text(file, reference.absoluteRange)}`"

    private fun describe(snapshot: ResolutionSnapshot, found: Found): String {
        val (declaration, reach, applicability) = found.candidate
        val via = found.via.joinToString(", ") { location(snapshot, it) }

        return "${location(snapshot, found.element)}  ${describe(declaration)}  ${reach.name.lowercase()}  " +
            "${applicability.name.lowercase()}  via [$via]"
    }

    private fun describe(snapshot: ResolutionSnapshot, visible: Visible): String {
        val declared = visible.declaration?.let(::describe) ?: "(no atom)"

        return "${visible.lookupName}  ->  $declared  ${location(snapshot, visible.element)}"
    }

    private fun describe(declaration: Declaration): String {
        val arity = declaration.arity.text()
        val form = declaration.declared.label()

        return "${declaration.name}/$arity $form"
    }

    private fun location(snapshot: ResolutionSnapshot, element: PsiElement): String =
        snapshot.location(element.containingFile, element.textRange)

    private companion object {
        const val REGENERATE =
            "./gradlew test --tests org.elixir_lang.snapshot.CandidateSourceSnapshotTest -PoverwriteTestData=true"
        val CANDIDATES_HEADER = listOf(
            "# Generated by CandidateSourceSnapshotTest: do not edit. Regenerate with $REGENERATE",
            "# Per use, for incompleteCode false then true, each candidate in the order the source found it:",
            "#   use file:line:column  `text`  ic=<incompleteCode>  n  element file:line:column  name/arity form  reach  " +
                "applicability  via [import, use and defdelegate calls crossed]",
            "# or `->  nothing` for no candidate, `->  names nothing` for a use that names nothing.",
            "# Review each moved line in a diff.",
        )
        val VISIBLE_HEADER = listOf(
            "# Generated by CandidateSourceSnapshotTest: do not edit. Regenerate with $REGENERATE",
            "# Per reference start, what completion offers, in the order the walk offered it, without an SDK:",
            "#   use file:line:column  `text`  lookup string  ->  name/arity form, or (no atom)  element file:line:column",
            "# Review each moved line in a diff.",
        )
    }
}
