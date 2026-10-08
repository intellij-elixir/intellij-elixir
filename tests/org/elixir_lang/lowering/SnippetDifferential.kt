package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.ElixirFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Holds a parser snippet's lowering, where nothing in it is left unlowered, equal to the quoter's quoting with columns
 * and token metadata, printing one line per snippet, compared or skipped; and a snippet with errors lowering around
 * them.
 */
object SnippetDifferential {
    private val COLUMNS_AND_TOKEN_METADATA = ParserOptions(columns = true, tokenMetadata = true)

    @JvmStatic
    fun assertLowersLikeTheQuoter(file: PsiFile) {
        val root = file.viewProvider.getPsi(ElixirLanguage) as ElixirFile
        val lowered = ReadAction.computeBlocking<ElixirAst, Throwable> {
            Lowering.lower(root, ElixirLanguageLevelResolver.languageLevelFor(root))
        }

        if (lowered.hasUnlowered()) {
            println("lowering skipped a snippet with something unlowered")
            return
        }

        println("lowering compared a snippet")

        val reply = Quoter.quote(file.text, COLUMNS_AND_TOKEN_METADATA)

        assertEquals("the quoter's reply to the snippet", OtpErlangAtom("ok"), reply.elementAt(0))
        Quoter.assertQuotedCorrectly(reply.elementAt(1), lowered.toOtp(COLUMNS_AND_TOKEN_METADATA))
    }

    /**
     * Holds a parser snippet with errors lowering with each error element inside an error placeholder: its own, or the
     * one for the broken shape around it.
     */
    @JvmStatic
    fun assertLowersErrorsToPlaceholders(file: PsiFile) {
        val root = file.viewProvider.getPsi(ElixirLanguage) as ElixirFile
        val (lowered, errors) = ReadAction.computeBlocking<Pair<ElixirAst, List<PsiErrorElement>>, Throwable> {
            Lowering.lower(root, ElixirLanguageLevelResolver.languageLevelFor(root)) to
                PsiTreeUtil.findChildrenOfType(root, PsiErrorElement::class.java).toList()
        }
        val placeholders = lowered.placeholders().filter { it.reason == ElixirAst.Placeholder.Reason.Error }
        val dropped = errors.filter { error -> placeholders.none { it.meta.origin.contains(error.textRange) } }

        assertTrue(
            "errors the lowering dropped: ${dropped.joinToString { "${it.textRange} ${it.errorDescription}" }}",
            dropped.isEmpty()
        )
    }
}

internal fun ElixirAst.placeholders(): List<ElixirAst.Placeholder> =
    when (this) {
        is ElixirAst.Placeholder -> listOf(this)
        is ElixirAst.Call -> callee.placeholders() + arguments.orEmpty().flatMap { it.placeholders() }
        is ElixirAst.Alias -> segments.flatMap { it.placeholders() }
        is ElixirAst.Literal -> emptyList()
        is ElixirAst.ListNode -> elements.flatMap { it.placeholders() }
        is ElixirAst.Tuple -> elements.flatMap { it.placeholders() }
        is ElixirAst.Block -> expressions.flatMap { it.placeholders() }
    }

/** Whether any part of this was left to a family that has no lowering for it yet. */
internal fun ElixirAst.hasUnlowered(): Boolean =
    placeholders().any {
        when (it.reason) {
            is ElixirAst.Placeholder.Reason.Unlowered -> true
            ElixirAst.Placeholder.Reason.Error, ElixirAst.Placeholder.Reason.Compiled -> false
        }
    }
