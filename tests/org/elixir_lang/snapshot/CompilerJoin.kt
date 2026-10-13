package org.elixir_lang.snapshot

import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Capabilities
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Definer
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.declaration.preferred
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.expander.DispatchEvents
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Tokenization
import org.elixir_lang.model.psi.module.ModuleReference
import org.elixir_lang.model.psi.module.ModuleSymbol
import org.elixir_lang.psi.ElixirDotInfixOperator
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.reference.CaptureNameArity
import org.elixir_lang.snapshot.CompilerFacts.Fact
import org.elixir_lang.snapshot.CompilerFacts.Position

/**
 * What the plugin answers at each compiler fact about the files under [root], whose positions [elixirVersion]'s
 * tokenizer counted. The `ref` facts at one position are one group, matched when any of them is.
 */
@Suppress("UnstableApiUsage")
class CompilerJoin(private val project: Project, private val root: VirtualFile, private val elixirVersion: String) {
    /** After [MATCHED], in the order an unmatched group takes the first of. */
    enum class Outcome(val text: String) {
        MATCHED("matched"),
        NO_USE("no use"),
        RESOLVED("resolved"),
        UNKNOWN("unknown"),
        ARITY("arity"),
        DEFINER("definer"),
        OTHER("other"),
        NOTHING("nothing"),
    }

    /** A [kind] of fact, one of [KINDS], its [outcome], and the [line] that names it. */
    data class Joined(val kind: String, val outcome: Outcome, val line: String)

    private class Declared(val module: String, val declaration: Declaration, val line: String)

    private val files = mutableMapOf<String, Pair<ElixirFile, Map<Pair<Int, Int>, Int>>?>()

    @RequiresReadLock
    fun join(facts: List<String>): List<Joined> {
        val parsed = facts.mapNotNull { line -> CompilerFacts.parse(line)?.let { it to line } }
        val undefined = parsed.mapNotNull { (fact, _) ->
            (fact as? Fact.Diag)?.takeIf { it.kind == "undefined_function" }?.let {
                Triple(it.path to it.line, it.subject[0], it.subject[1].toInt())
            }
        }.toSet()
        val positioned = parsed.mapNotNull { (fact, line) ->
            when (fact) {
                is Fact.Ref -> Triple(fact.position, fact, line)
                is Fact.Alias -> Triple(fact.position, fact, line)
                else -> null
            }
        }.groupBy({ (position, fact, _) -> fact.javaClass to position }, { (_, fact, line) -> fact to line })
        val defs = parsed.mapNotNull { (fact, line) -> (fact as? Fact.Def)?.let { it to line } }
        val declarations = declarations()

        return positioned.map { (key, group) -> group(key.second, group, undefined) } +
            defs.map { (fact, line) -> Joined("def", outcome(fact, declarations), line) } +
            declarations.map { declared ->
                val outcome = if (defs.any { (fact, _) -> declared.matches(fact) }) Outcome.MATCHED else Outcome.NOTHING

                Joined("declaration", outcome, declared.line)
            }
    }

    private fun group(
        position: Position,
        facts: List<Pair<Fact, String>>,
        undefined: Set<Triple<Pair<String, Int>, String, Int>>,
    ): Joined {
        val alias = facts.first().first is Fact.Alias
        val outcomes = if (alias) {
            facts.map { (fact, line) -> outcome(fact as Fact.Alias) to line }
        } else {
            val found = found(position)

            facts.map { (fact, line) -> outcome(fact as Fact.Ref, found, undefined) to line }
        }
        val (outcome, line) =
            outcomes.firstOrNull { it.first == Outcome.MATCHED } ?: outcomes.minBy { it.first.ordinal }

        return Joined(if (alias) "alias" else "ref", outcome, line)
    }

    /** The preferred candidates at the use whose call is at [position], or `null` when there is no use. */
    private fun found(position: Position): List<Found>? {
        val (file, offset) = offset(position) ?: return null
        val call = ancestors(file, offset).filterIsInstance<Call>().firstOrNull { offset in starts(it) } ?: return null
        if (call is NonNumeric || capturedName(call)) return null
        val use = Use.of(call) ?: return null

        return preferred(use, sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, false), false).map { it.found }
    }

    private fun starts(call: Call): List<Int> =
        listOfNotNull(
            call.functionNameElement()?.textOffset,
            PsiTreeUtil.getChildOfType(call, ElixirDotInfixOperator::class.java)?.textOffset
        )

    /** Whether [call] is the name of a `&name/arity` capture, which no use reads. */
    private fun capturedName(call: Call): Boolean =
        (PsiTreeUtil.getParentOfType(call, NonNumeric::class.java)?.reference as? CaptureNameArity)?.nameElement == call

    private fun outcome(
        fact: Fact.Ref,
        found: List<Found>?,
        undefined: Set<Triple<Pair<String, Int>, String, Int>>,
    ): Outcome {
        found ?: return Outcome.NO_USE

        val key = Triple(fact.position.path to fact.position.line, fact.name, fact.arity)

        if (fact.kind in DispatchEvents.LOCAL_KINDS && key in undefined) {
            return if (found.any { it.candidate.applicability.admitted }) Outcome.RESOLVED else Outcome.MATCHED
        }

        val named = found.filter {
            it.candidate.declaration.name == fact.name && it.declaringModuleName() == fact.module
        }

        return when {
            named.any { it.candidate.applicability.admitted && it.candidate.declaration.arity.accepts(fact.arity) } ->
                Outcome.MATCHED
            named.any { it.candidate.declaration.arity == ArityKnowledge.Unknown } -> Outcome.UNKNOWN
            named.isNotEmpty() -> Outcome.ARITY
            found.isNotEmpty() -> Outcome.OTHER
            else -> Outcome.NOTHING
        }
    }

    /**
     * What the module reference to the whole alias starting at the fact's position resolves to, or, where no
     * reference starts there, the module the IDE's symbol lookup finds there: a declaring name's. Each segment's
     * reference covers only that segment, so the whole alias's is the last to end.
     */
    private fun outcome(fact: Fact.Alias): Outcome {
        val (file, offset) = offset(fact.position) ?: return Outcome.NO_USE
        val reference = ancestors(file, offset)
            .filter { it.textOffset == offset }
            .flatMap { PsiSymbolReferenceService.getService().getReferences(it).asSequence() }
            .filterIsInstance<ModuleReference>()
            .maxByOrNull { it.absoluteRange.endOffset }
        val symbols = reference?.resolveReference()?.filterIsInstance<ModuleSymbol>()
            ?: targetSymbols(file, offset).filterIsInstance<ModuleSymbol>().ifEmpty { return Outcome.NO_USE }
        val modules = symbols.map { it.moduleName }

        return when {
            fact.module in modules -> Outcome.MATCHED
            modules.isNotEmpty() -> Outcome.OTHER
            else -> Outcome.NOTHING
        }
    }

    private fun outcome(fact: Fact.Def, declarations: List<Declared>): Outcome {
        val named = declarations.filter { it.module == fact.module && it.declaration.name == fact.name }

        return when {
            named.any { it.matches(fact) } -> Outcome.MATCHED
            named.any { it.declaration.arity == ArityKnowledge.Unknown } -> Outcome.UNKNOWN
            named.any { it.declaration.arity.accepts(fact.arity) } -> Outcome.DEFINER
            named.isNotEmpty() -> Outcome.ARITY
            else -> Outcome.NOTHING
        }
    }

    private fun Declared.matches(fact: Fact.Def): Boolean =
        module == fact.module &&
            declaration.name == fact.name &&
            declaration.capabilities.defines(fact.kind) &&
            declaration.arity.accepts(fact.arity)

    /** Each declaration of each module in the files under [root], once for each name its module has. */
    private fun declarations(): List<Declared> =
        psiFiles().flatMap { file ->
            PsiTreeUtil.findChildrenOfType(file, Call::class.java).filter { Stub.isModular(it) }.flatMap { modular ->
                val (names, clauses) = definitions(modular)

                names.flatMap { module ->
                    clauses.mapNotNull { (clause, declaration) ->
                        declaration?.let {
                            val line = StringUtil.offsetToLineNumber(file.viewProvider.contents, clause.textOffset) + 1
                            val fields = listOf(
                                "declaration",
                                module,
                                definer(it.capabilities),
                                it.name,
                                it.arity.text(),
                                "${path(file)}:$line"
                            )

                            Declared(module, it, fields.joinToString("\t"))
                        }
                    }
                }
            }
        }

    private fun psiFiles(): List<PsiFile> {
        val files = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            if (!file.isDirectory) files += file
            true
        }

        return files.sortedBy { VfsUtilCore.getRelativePath(it, root) }
            .mapNotNull { PsiManager.getInstance(project).findFile(it) }
    }

    private fun path(file: PsiFile): String = VfsUtilCore.getRelativePath(file.viewProvider.virtualFile, root)!!

    private fun offset(position: Position): Pair<ElixirFile, Int>? {
        val (file, offsets) = files.getOrPut(position.path) {
            val virtualFile = root.findFileByRelativePath(position.path)

            (virtualFile?.let { PsiManager.getInstance(project).findFile(it) } as? ElixirFile)
                ?.let { it to offsets(it, elixirVersion) }
        } ?: return null

        return offsets[position.line to position.column]?.let { file to it }
    }

    private fun ancestors(file: PsiFile, offset: Int): Sequence<PsiElement> =
        generateSequence(file.findElementAt(offset)) { it.parent }.takeWhile { it !is PsiFile }

    companion object {
        val KINDS = listOf("ref", "alias", "def", "declaration")

        /** A count for each (kind, outcome), then each line not matched, as the facts and declarations came. */
        fun lines(joined: List<Joined>): List<String> {
            val counts = joined.groupingBy { it.kind to it.outcome }.eachCount().entries
                .sortedWith(compareBy({ KINDS.indexOf(it.key.first) }, { it.key.second.ordinal }))
                .map { (key, count) -> "${key.first}\t${key.second.text}\t$count" }

            return counts + joined.filter { it.outcome != Outcome.MATCHED }.map { "${it.outcome.text}\t${it.line}" }
        }

        /**
         * The offset in [file] at each (line, column) [elixirVersion]'s tokenizer counts: the last. An offset no column
         * starts at (inside a code point or a cluster, or in text the tokenizer does not count, such as heredoc
         * indentation) shares its position with the column that starts after it.
         */
        @RequiresReadLock
        fun offsets(file: ElixirFile, elixirVersion: String): Map<Pair<Int, Int>, Int> {
            val text = file.text
            val lines = Tokenization.lines(file, text, ElixirLanguageLevel.of(elixirVersion))

            return (0..text.length).associateBy { offset ->
                val position = lines.position(offset)
                position.line to position.column
            }
        }

        private fun ArityKnowledge.accepts(arity: Int): Boolean = arityInterval()?.contains(arity) == true

        /** The [Definer]s of the keywords a `def` fact names. */
        private val FACT_DEFINERS = CompilerFacts.DEF_KINDS.mapNotNull(Definer::of)

        /** The fact keyword declaring a function or macro of [capabilities]; `?` marks an undecided visibility. */
        private fun definer(capabilities: Capabilities): String {
            val undecided = capabilities.visibility == Visibility.UNDECIDED
            val visibility = if (undecided) Visibility.PUBLIC else capabilities.visibility
            val definer = FACT_DEFINERS.single {
                it.capabilities.compileTime == capabilities.compileTime && it.capabilities.visibility == visibility
            }

            return if (undecided) "${definer.keyword}?" else definer.keyword
        }

        /** Whether a declaration of these capabilities is one [kind] defines; an undecided visibility is either. */
        private fun Capabilities.defines(kind: String): Boolean {
            val defined = Definer.of(kind)?.capabilities ?: return false

            return compileTime == defined.compileTime &&
                (visibility == Visibility.UNDECIDED || visibility == defined.visibility)
        }
    }
}
