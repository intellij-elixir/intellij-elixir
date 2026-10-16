package org.elixir_lang.snapshot

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageOptions
import com.intellij.find.usages.impl.AllSearchOptions
import com.intellij.find.usages.impl.buildQuery
import com.intellij.find.usages.impl.searchTargets
import com.intellij.find.usages.impl.symbolSearchTarget
import com.intellij.model.Symbol
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.rename.api.RenameTarget
import org.elixir_lang.junit.onPooledThread
import java.util.concurrent.Callable

/**
 * Records, at every reference in an input directory, where Find Usages and Rename disagree with what the reference
 * resolves to. The golden is measured output.
 */
class ResolveUsagesAgreementTest : SnapshotTestCase() {
    fun testCallableDeclaration() =
        assertAgreement("psi/callable_declaration", "snapshot/callable_declaration.agreement.txt")

    fun testInputs() = assertAgreement("snapshot/inputs", "snapshot/inputs.agreement.txt")

    private fun assertAgreement(inputDirectory: String, golden: String) {
        val (root, files) = copyInputs(inputDirectory)
        val snapshot = ResolutionSnapshot(root)
        val references = snapshot.rows(files).filterIsInstance<ResolutionSnapshot.Reference>()
        // Off the EDT, as the Find Usages action searches.
        val disagreements = onPooledThread {
            ReadAction.nonBlocking(Callable { Agreement(project, snapshot, references).disagreements() })
                .executeSynchronously()
        }

        assertGolden(golden, listOf("# ${disagreements.size} disagreements") + HEADER, disagreements, REGENERATE)
    }

    @Suppress("UnstableApiUsage")
    private class Agreement(
        private val project: Project,
        private val snapshot: ResolutionSnapshot,
        private val references: List<ResolutionSnapshot.Reference>,
    ) {
        private class Disagreement(val path: String, val offset: Int, val line: String)

        private val options = AllSearchOptions(
            UsageOptions.createOptions(GlobalSearchScope.projectScope(project)),
            textSearch = false
        )
        private val usagesByTarget = mutableMapOf<String, List<PsiUsage>>()

        fun disagreements(): List<String> {
            val disagreements = mutableListOf<Disagreement>()
            val searched = linkedMapOf<String, SearchTarget>()

            for (reference in references) {
                val offset = reference.range.startOffset
                val searchTargets = searchTargets(reference.file, offset).associateBy(::describe).toSortedMap()
                searched.putAll(searchTargets)

                val location = snapshot.location(reference.file, reference.range)
                fun disagree(check: String, detail: String) {
                    disagreements +=
                        Disagreement(reference.path, offset, "$location  $check  ${reference.label}  $detail")
                }

                if (reference.resolves && searchTargets.isEmpty()) {
                    disagree("(a)", "resolves, but Find Usages has no target")
                }

                // As the platform maps a Symbol to the target Find Usages searches.
                val symbolTargets = reference.symbols.map { describe(symbolSearchTarget(project, it) ?: it) }
                for (symbol in symbolTargets.distinct().sorted()) {
                    if (symbol !in searchTargets) disagree("(b)", "$symbol is not a Find Usages target")
                }

                for ((description, target) in searchTargets) {
                    val usages = usages(description, target)
                    val covered = usages.any {
                        it.file.viewProvider == reference.file.viewProvider && reference.range in it.range
                    }
                    if (!covered) {
                        disagree("(c)", "$description's usages miss it: ${locations(usages)}")
                    }
                }

                // The Rename handler would also ask `SymbolRenameTargetFactory` and `RenameableSymbol`, which the
                // plugin does not use.
                val renameTargets = targetSymbols(reference.file, offset)
                    .filterIsInstance<RenameTarget>()
                    .map(::describe)
                    .toSortedSet()
                if (renameTargets != searchTargets.keys) {
                    disagree("(d)", "Rename offers ${list(renameTargets)}, Find Usages ${list(searchTargets.keys)}")
                }
            }

            for ((description, target) in searched) {
                for (usage in usages(description, target).filterNot(PsiUsage::declaration)) {
                    val overlapping = references.filter {
                        it.file.viewProvider == usage.file.viewProvider &&
                            (it.range in usage.range || usage.range in it.range)
                    }
                    val atUsage = overlapping.filter { it.range == usage.range }.ifEmpty { overlapping }
                    if (atUsage.none { reference -> reference.symbols.any { describe(it) == description } }) {
                        val label = atUsage.map { it.label }.distinct().sorted().joinToString(" | ")
                            .ifEmpty { "no reference  `${snapshot.text(usage.file, usage.range)}`" }
                        disagreements += Disagreement(
                            snapshot.path(usage.file),
                            usage.range.startOffset,
                            "${snapshot.location(usage.file, usage.range)}  (e)  $label  " +
                                "a usage of $description that does not resolve to it"
                        )
                    }
                }
            }

            return disagreements
                .sortedWith(compareBy(Disagreement::path, Disagreement::offset, Disagreement::line))
                .map(Disagreement::line)
        }

        private fun usages(description: String, target: SearchTarget): List<PsiUsage> =
            usagesByTarget.getOrPut(description) {
                buildQuery(project, target, options).findAll().filterIsInstance<PsiUsage>()
            }

        private fun locations(usages: List<PsiUsage>): String =
            usages
                .sortedWith(compareBy({ snapshot.path(it.file) }, { it.range.startOffset }))
                .map { snapshot.location(it.file, it.range) }
                .distinct()
                .ifEmpty { listOf("none") }
                .joinToString(", ")

        private fun list(descriptions: Collection<String>): String =
            descriptions.joinToString(" | ", "{", "}")

        private fun describe(target: Any): String =
            if (target is Symbol) snapshot.describe(target) else target.toString()
    }

    companion object {
        private const val REGENERATE =
            "./gradlew test --tests 'org.elixir_lang.snapshot.*' -PoverwriteTestData=true"
        private val HEADER = listOf(
            "# Generated by ResolveUsagesAgreementTest: do not edit. Regenerate with $REGENERATE",
            "# One line per disagreement: file:line:column  check  system  reference class  `text`  detail, where",
            "#   (a) the reference resolves, but Find Usages has no target at it;",
            "#   (b) the search target of a symbol it resolves to is not a Find Usages target at it;",
            "#   (c) a Find Usages target's usages do not include it;",
            "#   (d) Rename offers different targets from Find Usages;",
            "#   (e) a usage Find Usages reports is not a reference that resolves to the target; its label lists the",
            "#       references at the usage, joined by ` | `, or is no reference  `text`.",
            "# Review each moved line in a diff.",
        )
    }
}
