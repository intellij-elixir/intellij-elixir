package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpExternal
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.StubBuildCachedValuesManager
import com.intellij.psi.stubs.StubBuildCachedValuesManager.StubBuildCachedValue
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE
import org.elixir_lang.language_level.ElixirLanguageFeature.NEWLINE_COUNTED_IN_CHARACTER
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.Quotable
import org.elixir_lang.unicode_util.Graphemes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Lowers one element on its own, over line starts cached per file version and language level.
 *
 * Columns ignore where the tokenizer counts quoted text and heredoc indentation differently from the text's own code
 * points, as `Code.string_to_quoted`'s default options give no columns.
 */
object ElementLowering {
    @RequiresReadLock
    @JvmStatic
    fun lower(element: PsiElement): ElixirAst {
        ThreadingAssertions.assertReadAccess()
        LoweringCounters.count(LoweringCounters.requests)

        val root = root(element)
        val file = element.containingFile
        val languageLevel = ElixirLanguageLevelResolver.languageLevelFor(element)
        val text = text(file, root)
        val lines = Lines(text, Graphemes.of(languageLevel), lineStarts(file, root, text, languageLevel))

        return Lowering.of(text, lines, languageLevel).lower(element)
    }

    /** [element] as `Code.string_to_quoted` gives it with default options. */
    @RequiresReadLock
    @JvmStatic
    fun quote(element: Quotable): OtpErlangObject = lower(element).toOtp()

    /** The atom [element] lowers to, or `null` when it lowers to anything else or to an atom too long for Erlang. */
    @RequiresReadLock
    @JvmStatic
    fun atomName(element: PsiElement): String? =
        (lower(element) as? ElixirAst.Literal.Atom)?.name?.takeIf { it.codePointCount(0, it.length) <= OtpExternal.maxAtomLength }

    /** A stub build can lower while the file's tree loads from its stubs, and asking the file for its tree then loads it again. */
    private fun root(element: PsiElement): ASTNode = generateSequence(element.node) { it.treeParent }.last()

    /** `root.text` copies the whole file on each call. */
    private fun text(file: PsiFile, root: ASTNode): CharSequence =
        file.viewProvider.contents.takeIf { it.length == root.textLength } ?: root.text

    private fun lineStarts(file: PsiFile, root: ASTNode, text: CharSequence, languageLevel: ElixirLanguageLevel): IntArray {
        // The stub-build value is held on the file itself: the platform's other overloads hold it on the file's node, which
        // loads a stub-backed tree.
        val byLevel =
            if (StubBuildCachedValuesManager.isBuildingStubs) {
                StubBuildCachedValuesManager.getCachedValueIfBuildingStubs(file, STUB_LINE_STARTS, Unit) {
                    ConcurrentHashMap()
                }
            } else {
                CachedValuesManager.getCachedValue(file, LINE_STARTS) {
                    CachedValueProvider.Result.create(ConcurrentHashMap(), ModificationTracker { file.modificationStamp })
                }
            }

        return byLevel[languageLevel] ?: run {
            LoweringCounters.count(LoweringCounters.lineIndexes)
            val countsEveryNewline = NEWLINE_COUNTED_IN_CHARACTER.isSufficient(languageLevel) &&
                ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE.isSufficient(languageLevel)
            val uncounted =
                if (countsEveryNewline) {
                    emptyList()
                } else {
                    Tokenization.scan(root, text, languageLevel, newlines = true, columns = false).uncountedNewlines
                }
            val starts = Lines.starts(text, uncounted)

            byLevel.putIfAbsent(languageLevel, starts) ?: starts
        }
    }

    internal val LINE_STARTS = Key.create<CachedValue<ConcurrentMap<ElixirLanguageLevel, IntArray>>>("ELIXIR_LINE_STARTS")
    internal val STUB_LINE_STARTS =
        Key.create<StubBuildCachedValue<ConcurrentMap<ElixirLanguageLevel, IntArray>>>("ELIXIR_LINE_STARTS.stub.building")
}
