package org.elixir_lang.psi.impl

import com.ericsson.otp.erlang.OtpErlangBinary
import com.intellij.lang.ASTNode
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.*
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER
import org.elixir_lang.language_level.ElixirLanguageFeature.UNESCAPED_SIGIL_HEREDOC_TERMINATOR
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.isAvailable
import java.nio.charset.Charset

object ParentImpl {
    @JvmStatic
    fun addChildTextCodePoints(codePointList: MutableList<Int>?, child: ASTNode): MutableList<Int> =
        addStringCodePoints(codePointList, child.text)

    @JvmStatic
    fun elixirString(javaString: String): OtpErlangBinary =
        javaString.toByteArray(Charset.forName("UTF-8")).let(::OtpErlangBinary)

    // Parent methods

    @JvmStatic
    fun addEscapedCharacterCodePoints(
        @Suppress("UNUSED_PARAMETER") parent: Quote,
        maybeCodePointList: MutableList<Int>?,
        child: ASTNode
    ): List<Int> {
        val escapedCharacterCodePoint = child.psi.let { it as ElixirEscapedCharacter }.codePoint()

        val codePointList = ensureCodePointList(maybeCodePointList)
        codePointList.add(escapedCharacterCodePoint)

        return codePointList
    }

    @JvmStatic
    fun addEscapedCharacterCodePoints(parent: Sigil, codePointList: MutableList<Int>?, child: ASTNode): List<Int> {
        val childText = child.text

        // Not sure, why, but \ gets stripped in front of # when quoting using Quoter prior to 1.6
        val string = if (parent is SigilLine) {
            val terminator = parent.terminator()

            if (childText == "\\" + terminator) {
                String(
                    charArrayOf(terminator)
                )
            } else {
                childText
            }
        } else {
            childText
        }

        return addStringCodePoints(codePointList, string)
    }

    @RequiresReadLock
    @JvmStatic
    fun addEscapedEOL(
        parent: Parent,
        maybeCodePointList: MutableList<Int>?
    ): List<Int> {
        val codePointList: MutableList<Int> = ensureCodePointList(maybeCodePointList)

        // `~S` and plain strings are the same on every release, and only a sigil reaches `isAvailable` - atom
        // resolution calls this too.
        if (parent is Sigil &&
            (parent !is Interpolated || isAvailable(ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER, parent))
        ) {
            codePointList.addAll(codePoints("\\\n"))
        }

        return codePointList
    }

    @RequiresReadLock
    @JvmStatic
    fun addEscapedTerminator(parent: Parent, maybeCodePointList: MutableList<Int>?, child: ASTNode): List<Int> {
        val codePointList: MutableList<Int> = ensureCodePointList(maybeCodePointList)

        // Plain heredocs and sigil lines are the same on every release.
        val text = if (parent is SigilHeredocLiteral && !isAvailable(UNESCAPED_SIGIL_HEREDOC_TERMINATOR, parent)) {
            child.text
        } else {
            child.psi.lastChild.text
        }

        codePointList.addAll(codePoints(text))

        return codePointList
    }

    @JvmStatic
    fun addFragmentCodePoints(codePointList: MutableList<Int>?, child: ASTNode): List<Int> =
        addChildTextCodePoints(codePointList, child)

    @JvmStatic
    fun addHexadecimalEscapeSequenceCodePoints(
        @Suppress("UNUSED_PARAMETER") parent: Quote,
        maybeCodePointList: MutableList<Int>?,
        child: ASTNode
    ): List<Int> {
        val hexadecimalEscapeSequenceCodePoint = child
            .psi.let { it as ElixirQuoteHexadecimalEscapeSequence }
            .codePoint()

        val codePointList = ensureCodePointList(maybeCodePointList)
        codePointList.add(hexadecimalEscapeSequenceCodePoint)

        return codePointList
    }

    @JvmStatic
    fun addHexadecimalEscapeSequenceCodePoints(
        @Suppress("UNUSED_PARAMETER") parent: Sigil,
        codePointList: MutableList<Int>?,
        child: ASTNode
    ): List<Int> =
        addChildTextCodePoints(codePointList, child)

    private fun addStringCodePoints(maybeCodePointList: MutableList<Int>?, string: String): MutableList<Int> {
        val codePointList = ensureCodePointList(maybeCodePointList)
        val filteredString = filterEscapedEOL(string)

        for (codePoint in codePoints(filteredString)) {
            codePointList.add(codePoint)
        }

        return codePointList
    }

    /*
     * @todo use String.codePoints in Java 8 when IntelliJ is using it
     * @see https://stackoverflow.com/questions/1527856/how-can-i-iterate-through-the-unicode-codepoints-of-a-java-string/21791059#21791059
     */
    private fun codePoints(string: String): Iterable<Int> =
        Iterable {
            object : Iterator<Int> {
                internal var nextIndex = 0

                override fun hasNext(): Boolean {
                    return nextIndex < string.length
                }

                override fun next(): Int {
                    val result = string.codePointAt(nextIndex)
                    nextIndex += Character.charCount(result)
                    return result
                }
            }
        }

    private fun ensureCodePointList(codePointList: MutableList<Int>?): MutableList<Int> =
        codePointList ?: mutableListOf()

    private fun filterEscapedEOL(unfiltered: String): String = unfiltered.replace("\\\n", "")
}
