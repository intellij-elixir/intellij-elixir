package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.parser_definition.ElixirLangElixirParsingTestCase
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.ElixirFile
import java.nio.file.Path

/**
 * Lowers every file of the parser corpus, holds each one lowered completely, and holds the lowering's terms equal to
 * the quoter's with columns and token metadata. Prints how many files were covered.
 */
class LoweringDifferentialTest : ParsingTestCase() {
    fun testLoweringQuotesLikeTheQuoterWithColumnsAndTokenMetadataOnEveryFile() =
        assertAgreesOnEveryFile("the quoter with columns and token metadata") { lowered, text ->
            val reply = Quoter.quote(text, COLUMNS_AND_TOKEN_METADATA)

            reply.elementAt(0) == OtpErlangAtom("ok") && lowered.toOtp(COLUMNS_AND_TOKEN_METADATA) == reply.elementAt(1)
        }

    private fun assertAgreesOnEveryFile(
        reference: String,
        agrees: (lowered: ElixirAst, text: String) -> Boolean
    ) {
        val corpus = System.getenv(CORPUS)
        assertFalse("$CORPUS is not set; the Gradle test task sets it", corpus.isNullOrEmpty())
        val root = Path.of(corpus!!)
        val paths = ElixirLangElixirParsingTestCase.sourcePaths(root)
        assertFalse("no .ex or .exs files under $root", paths.isEmpty())

        val outcomes = paths.associateWith { path ->
            compare(path, FileUtil.loadFile(root.resolve(path).toFile(), Charsets.UTF_8.name(), true).trim(), agrees)
        }
        val covered = outcomes.values.count { it != Outcome.UNCOVERED }
        println("covered $covered of ${paths.size} files")
        assertTrue(
            "lowered nodes carry a form only the expander builds in ${expanderForms.size} files:\n  ${expanderForms.joinToString("\n  ")}",
            expanderForms.isEmpty()
        )

        val differing = outcomes.filterValues { it == Outcome.DIFFERS }.keys
        assertTrue(
            "lowering and $reference differ in ${differing.size} of $covered covered files:\n  ${differing.joinToString("\n  ")}",
            differing.isEmpty()
        )

        val uncovered = outcomes.filterValues { it == Outcome.UNCOVERED }.keys
        assertTrue(
            "${uncovered.size} of ${paths.size} files are left partly unlowered:\n  ${uncovered.joinToString("\n  ")}",
            uncovered.isEmpty()
        )
    }

    private enum class Outcome { UNCOVERED, AGREES, DIFFERS }

    /** The files whose lowering has a variable context, a tuple metadata value or a built mark. */
    private val expanderForms = mutableListOf<String>()

    private fun hasExpanderForm(node: ElixirAst): Boolean {
        fun isTuple(value: Meta.Value): Boolean =
            value is Meta.Value.Tuple ||
                value is Meta.Value.Keywords && value.keys.any { it is Meta.Key.Entry && isTuple(it.value) }

        val children = when (node) {
            is ElixirAst.Call -> listOf(node.callee) + node.arguments.orEmpty()
            is ElixirAst.Alias -> node.segments
            is ElixirAst.Tuple -> node.elements
            is ElixirAst.ListNode -> node.elements
            is ElixirAst.Block -> node.expressions
            is ElixirAst.Literal, is ElixirAst.Placeholder -> emptyList()
        }

        return node is ElixirAst.Call && node.context != ElixirAst.VariableContext.Nil ||
            node.meta.built ||
            node.meta.keys.any { it is Meta.Key.Entry && isTuple(it.value) } ||
            children.any(::hasExpanderForm)
    }

    private fun compare(
        path: String,
        text: String,
        agrees: (lowered: ElixirAst, text: String) -> Boolean
    ): Outcome {
        val file = createPsiFile(FileUtilRt.getNameWithoutExtension(path.substringAfterLast('/')), text) as ElixirFile
        val lowered = ReadAction.computeBlocking<ElixirAst, Throwable> {
            Lowering.lower(file, ElixirLanguageLevelResolver.languageLevelFor(file))
        }

        if (hasExpanderForm(lowered)) expanderForms.add(path)

        return when {
            lowered.hasUnlowered() -> Outcome.UNCOVERED
            agrees(lowered, text) -> Outcome.AGREES
            else -> Outcome.DIFFERS
        }
    }

    private companion object {
        const val CORPUS = ElixirLangElixirParsingTestCase.CORPUS_ENVIRONMENT_VARIABLE
        val COLUMNS_AND_TOKEN_METADATA = ParserOptions(columns = true, tokenMetadata = true)
    }
}
