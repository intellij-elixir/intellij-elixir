package org.elixir_lang.lowering

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.golden.CommittedGolden
import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.call.Call
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.Callable

/** Each of a file root's [quotedElements], quoted, against a golden written from `Quotable.quote()`. */
object RootGoldens {
    const val DIRECTORY = "testData/org/elixir_lang/lowering/roots"
    private const val REGENERATE =
        "./gradlew test --tests org.elixir_lang.lowering.ElementLoweringRootsTest --tests org.elixir_lang.lowering.BeamMirrorRootTest -PoverwriteTestData=true"

    fun assertQuotes(golden: String, root: PsiFile?) {
        assertNotNull("no Elixir root", root)
        val (base, entry) = ReadAction.nonBlocking(Callable {
            lines(root!!) { it.quote() } to lines(root) { ElementLowering.quote(it) }
        }).executeSynchronously()
        assertTrue("no elements in ${root!!.name}", base.isNotEmpty())

        CommittedGolden.assertMatches("$DIRECTORY/$golden.txt", base, REGENERATE)
        CommittedGolden.assertMatches("$DIRECTORY/$golden.txt", entry, REGENERATE)
    }

    /** Outermost calls too, as their quotes carry line metadata, where names and literals have none. */
    private fun lines(root: PsiFile, quote: (Quotable) -> Any): String =
        (outermostCalls(root) + quotedElements(root)).joinToString("\n") { (kind, element) ->
            val line = StringUtil.offsetToLineNumber(root.text, element.textRange.startOffset) + 1
            "$line\t$kind\t`${element.text.replace("\n", "\\n")}`\t${quote(element)}"
        }

    private fun outermostCalls(root: PsiFile): List<Pair<String, Quotable>> =
        PsiTreeUtil.findChildrenOfType(root, Call::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, Call::class.java) == null }
            .filterIsInstance<Quotable>()
            .map { "call" to it }
}
