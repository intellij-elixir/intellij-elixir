package org.elixir_lang.psi.impl

import com.ericsson.otp.erlang.*
import com.intellij.lang.ASTNode
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.impl.QuotableImpl.metadata
import org.elixir_lang.psi.impl.QuotableImpl.quotedFunctionCall
import org.elixir_lang.psi.impl.QuotableImpl.quotedInterpolationCall
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER
import org.elixir_lang.language_level.ElixirLanguageFeature.UNESCAPED_SIGIL_HEREDOC_TERMINATOR
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.isAvailable
import org.jetbrains.annotations.Contract
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

object ParentImpl {
    /**
     * Added to a byte to carry it through a code point list, as `\xHH` escapes a byte rather than a code point. Past
     * what six hex digits can escape, so not even an invalid `\u{110000}` collides with it. Only [elixirString] and
     * [elixirCharList] understand it.
     */
    const val RAW_BYTE_OFFSET = 0x1000000

    @JvmStatic
    fun addChildTextCodePoints(codePointList: MutableList<Int>?, child: ASTNode): MutableList<Int> =
        addStringCodePoints(codePointList, child.text)

    fun elixirCharList(codePointList: List<Int>): OtpErlangObject =
        if (codePointList.any { it >= RAW_BYTE_OFFSET }) {
            // Elixir decodes a charlist's bytes as UTF-8; invalid UTF-8 is its error, not this quoting's.
            String(utf8Bytes(codePointList), Charsets.UTF_8).codePoints().toArray().toList()
        } else {
            codePointList
        }
            .let { elixirCodePointList(it) }
            .let { elixirCharList(it) }

    /**
     * Erlang will automatically stringify a list that is just a list of LATIN-1 printable code
     * points.
     * OtpErlangString and OtpErlangList are not equal when they have the same content, so to check against
     * Elixir.Code.string_to_quoted, this code must determine if Erlang would return an OtpErlangString instead
     * of OtpErlangList and do the same.
     */
    fun elixirCharList(erlangList: OtpErlangList): OtpErlangObject =
        /* JInterface will return an OtpErlangString in some case and an OtpErlangList in other.  Right now, I'm
           assuming it works similar to the printing in `iex` and is based on whether the codePoint is printable, but
           ASCII printable instead of Unicode printable since Erlang is ASCII/LATIN-1 based */
        if (isErlangPrintable(erlangList)) {
            try {
                OtpErlangString(erlangList)
            } catch (e: OtpErlangException) {
                TODO()
            }
        } else {
            erlangList
        }

    fun elixirString(codePointList: List<Int>): OtpErlangBinary = OtpErlangBinary(utf8Bytes(codePointList))

    private fun utf8Bytes(codePointList: List<Int>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val pending = StringBuilder()

        for (codePoint in codePointList) {
            if (codePoint >= RAW_BYTE_OFFSET) {
                bytes.write(pending.toString().toByteArray(Charsets.UTF_8))
                pending.setLength(0)
                bytes.write(codePoint - RAW_BYTE_OFFSET)
            } else {
                pending.appendCodePoint(codePoint)
            }
        }

        bytes.write(pending.toString().toByteArray(Charsets.UTF_8))

        return bytes.toByteArray()
    }

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

    @Contract(pure = true)
    @JvmStatic
    fun quoteBinary(
        elixirLine: ElixirLine,
        metadata: OtpErlangList,
        argumentList: List<OtpErlangObject>
    ): OtpErlangObject =
        if (elixirLine.isCharList) {
            // See https://github.com/elixir-lang/elixir/commit/e89e9d874bf803379d729a3bae185052a5323a85
            quotedFunctionCall(
                "Elixir.List",
                "to_charlist",
                metadata,
                OtpErlangList(argumentList.toTypedArray())
            )
        } else {
            quoteBinary(metadata, argumentList)
        }

    @Contract(pure = true)
    @JvmStatic
    fun quoteBinary(
        elixirHeredoc: ElixirHeredoc,
        metadata: OtpErlangList,
        argumentList: List<OtpErlangObject>
    ): OtpErlangObject =
        if (elixirHeredoc.isCharList) {
            // See https://github.com/elixir-lang/elixir/commit/e89e9d874bf803379d729a3bae185052a5323a85
            quotedFunctionCall(
                "Elixir.List",
                "to_charlist",
                metadata,
                OtpErlangList(argumentList.toTypedArray())
            )
        } else {
            quoteBinary(metadata, argumentList)
        }

    @Contract(pure = true)
    @JvmStatic
    fun quoteBinary(metadata: OtpErlangList, argumentList: List<OtpErlangObject>): OtpErlangObject =
        quotedFunctionCall("<<>>", metadata, *argumentList.toTypedArray())

    @Contract(pure = true)
    @JvmStatic
    fun quoteEmpty(quote: Quote): OtpErlangObject =
        if (quote.isCharList) {
            OtpErlangList()
        } else {
            quoteEmpty()
        }

    @Contract(pure = true)
    @JvmStatic
    fun quoteEmpty(): OtpErlangObject = elixirString("")

    // See https://github.com/elixir-lang/elixir/commit/e89e9d874bf803379d729a3bae185052a5323a85
    @RequiresReadLock
    @JvmStatic
    fun quoteInterpolation(quote: Quote, interpolation: ElixirInterpolation): OtpErlangObject =
        if (quote.isCharList) {
            val quotedChildren = QuotableImpl.quote(interpolation)
            val interpolationMetadata = metadata(interpolation)

            quotedInterpolationCall(
                interpolation,
                Module.prependElixirPrefix(Module.KERNEL),
                "to_string",
                interpolationMetadata,
                quotedChildren
            )
        } else {
            val quotedChildren = QuotableImpl.quote(interpolation)
            val interpolationMetadata = metadata(interpolation)

            val quotedKernelToStringCall = quotedInterpolationCall(
                interpolation,
                Module.prependElixirPrefix(Module.KERNEL),
                "to_string",
                interpolationMetadata,
                quotedChildren
            )
            val quotedBinaryCall = QuotableImpl.quotedVariable(
                "binary",
                interpolationMetadata
            )

            quotedFunctionCall(
                "::",
                interpolationMetadata,
                quotedKernelToStringCall,
                quotedBinaryCall
            )
        }

    /* "#{a}" is transformed to "<<Kernel.to_string(a) :: binary>>" in
     * `"\"\#{a}\"" |> Code.string_to_quoted |> Macro.to_string`, so interpolation has to be represented as a type call
     * (`:::`) to binary of a call of `Kernel.to_string`
     */
    @RequiresReadLock
    @JvmStatic
    fun quoteInterpolation(interpolation: ElixirInterpolation): OtpErlangObject {
        val quotedChildren = QuotableImpl.quote(interpolation)
        val interpolationMetadata = metadata(interpolation)

        val quotedKernelToStringCall = quotedInterpolationCall(
            interpolation,
            Module.prependElixirPrefix(Module.KERNEL),
            "to_string",
            interpolationMetadata,
            quotedChildren
        )
        val quotedBinaryCall = QuotableImpl.quotedVariable(
            "binary",
            interpolationMetadata
        )

        return quotedFunctionCall(
            "::",
            interpolationMetadata,
            quotedKernelToStringCall,
            quotedBinaryCall
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quoteLiteral(quote: Quote, codePointList: List<Int>): OtpErlangObject =
        if (quote.isCharList) {
            elixirCharList(codePointList)
        } else {
            quoteLiteral(codePointList)
        }

    @Contract(pure = true)
    @JvmStatic
    fun quoteLiteral(codePointList: List<Int>): OtpErlangObject = elixirString(codePointList)

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

    private fun elixirCodePointList(codePointList: List<Int>): OtpErlangList =
        codePointList.map { OtpErlangLong(it.toLong()) }.toTypedArray().let { OtpErlangList(it) }

    private fun ensureCodePointList(codePointList: MutableList<Int>?): MutableList<Int> =
        codePointList ?: mutableListOf()

    private fun filterEscapedEOL(unfiltered: String): String = unfiltered.replace("\\\n", "")

    private fun isErlangPrintable(erlangList: OtpErlangList): Boolean {
        var isErlangPrintable = true

        for (erlangObject in erlangList) {
            if (erlangObject is OtpErlangLong) {
                val codePoint: Int

                try {
                    codePoint = erlangObject.intValue()
                } catch (e: OtpErlangRangeException) {
                    isErlangPrintable = false
                    break
                }

                if (!isErlangPrintable(codePoint)) {
                    isErlangPrintable = false
                    break
                }
            } else {
                isErlangPrintable = false
                break
            }
        }

        if (erlangList.arity() == 0) {
            isErlangPrintable = false
        }

        return isErlangPrintable
    }

    @Contract(pure = true)
    private fun isErlangPrintable(codePoint: Int): Boolean = codePoint in 0..255
}
