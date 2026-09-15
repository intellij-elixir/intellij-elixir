package org.elixir_lang.psi.impl

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.otpErlangTuple
import org.elixir_lang.psi.*
import org.elixir_lang.psi.impl.QuotableImpl.BLOCK
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.languageLevelFor
import org.jetbrains.annotations.Contract

object QuotableArgumentsImpl {
    val DO = OtpErlangAtom("do")

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quoteArguments(arguments: Arguments): Array<OtpErlangObject> =
            arguments
                    .arguments()
                    .map { it as Quotable }
                    .map(Quotable::quote)
                    .toTypedArray()

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quoteArguments(blockList: ElixirBlockList): Array<OtpErlangObject> =
            blockList
                    .blockItemList
                    .map(ElixirBlockItem::quote)
                    .toTypedArray()

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quoteArguments(
            unqualifiedNoParenthesesManyArgumentsCall: ElixirUnqualifiedNoParenthesesManyArgumentsCall
    ): Array<OtpErlangObject> =
            unqualifiedNoParenthesesManyArgumentsCall
                    .primaryArguments()
                    .map { it as Quotable }
                    .map(Quotable::quote)
                    .toTypedArray()

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quoteArguments(doBlock: ElixirDoBlock): Array<OtpErlangObject> {
        // See ElixirLanguageLevel.V1_20.
        val blockMetadata =
            if (languageLevelFor(doBlock).emitsLineMetadataOnBlock) QuotableImpl.metadata(doBlock) else OtpErlangList()
        val doValue = doBlock.stab?.quote(blockMetadata) ?: emptyDoValue(blockMetadata)

        val quotedKeywordPairListPrefix = arrayOf(DO, doValue).let(::OtpErlangTuple).let { listOf(it) }
        val quotedKeywordPairListSuffix = doBlock.blockList?.quoteArguments()?.toList() ?: emptyList()
        val quotedKeywordPairList = quotedKeywordPairListPrefix + quotedKeywordPairListSuffix

        return quotedKeywordPairList.toTypedArray().let(::OtpErlangList).let { arrayOf(it) }
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quoteArguments(mapConstructionArguments: ElixirMapConstructionArguments): Array<OtpErlangObject> =
            mapConstructionArguments
                    .arguments()
                    .map { it as Quotable }
                    .flatMap {
                        it
                                .quote()
                                .let { it as OtpErlangList }
                                .elements()
                                .asList()
                    }.toTypedArray()

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quoteArguments(noParenthesesArguments: ElixirNoParenthesesArguments): Array<OtpErlangObject> =
            noParenthesesArguments
                    .children
                    .single()
                    .let { it as QuotableArguments }
                    .quoteArguments()

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quoteArguments(parenthesesArguments: ElixirParenthesesArguments): Array<OtpErlangObject> =
            parenthesesArguments
                    .arguments()
                    .map { it as Quotable }
                    .map(Quotable::quote)
                    .toTypedArray()

    private fun emptyDoValue(metadata: OtpErlangList): OtpErlangObject = otpErlangTuple(
                BLOCK, metadata, OtpErlangList()
        )
}
