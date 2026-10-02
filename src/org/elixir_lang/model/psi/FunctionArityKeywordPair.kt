package org.elixir_lang.model.psi

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangRangeException
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.DEFOVERRIDABLE
import org.elixir_lang.psi.call.name.Function.IMPORT
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.moduleAttributeName

/**
 * The shared `name: arity` keyword-pair construct: a [QuotableKeywordPair] whose key names a
 * function/macro and whose value is its arity. It recurs across several Elixir directives, each of
 * which is a distinct [Host]:
 *
 *  - `import Mod, only: [name: arity]` / `except: [name: arity]` ([Host.IMPORT_ONLY]/[Host.IMPORT_EXCEPT])
 *  - `@compile inline: [name: arity]` ([Host.COMPILE_INLINE])
 *  - `@dialyzer {:nowarn_function, name: arity}` ([Host.DIALYZER])
 *  - `defoverridable name: arity` ([Host.DEFOVERRIDABLE])
 *
 * This is the single place that *recognizes* and *extracts* the construct; resolution to a
 * `FunctionSymbol` (or, for `defoverridable`, a `Callback`) lives in
 * [org.elixir_lang.model.psi.function.FunctionArityKeywordPairReference], and reverse Find-Usages in
 * [ElixirSymbolUsageSearcher].
 *
 * Host detection is deliberately **syntactic** (`Call.functionName()` / module-attribute name) rather
 * than going through `Import.is`/`Overridable.is`, whose `Call.isCalling(KERNEL, ...)` resolution
 * requires `Kernel` to be resolvable - many test fixtures (and real edited buffers) don't include a
 * `kernel.ex`, yet the construct must still be recognized.
 */
object FunctionArityKeywordPair {
    enum class Host {
        IMPORT_ONLY,
        IMPORT_EXCEPT,
        COMPILE_INLINE,
        DIALYZER,
        DEFOVERRIDABLE
    }

    /**
     * A single recognized `name: arity` keyword pair, together with the directive ([host]/[hostCall])
     * that governs it and the extracted [name]/[arity].
     */
    data class Occurrence(
        val pair: QuotableKeywordPair,
        val host: Host,
        val hostCall: Call,
        val name: String,
        val arity: Int
    ) {
        /** The keyword key's range, absolute in the file. */
        val keyRange: TextRange get() = pair.keywordKey.textRange
    }

    /**
     * Classify [pair] as a function-arity keyword pair, or `null` if it is not one (value is not an
     * integer arity, or it is not inside a recognized directive).
     */
    @RequiresReadLock
    fun classify(pair: QuotableKeywordPair): Occurrence? {
        val name = nameOf(pair.keywordKey) ?: return null
        val arity = arityOf(pair.keywordValue) ?: return null

        val hostCall = generateSequence(pair.parent) { it.parent }
            .takeWhile { it !is PsiFile }
            .filterIsInstance<Call>()
            .firstOrNull { isPotentialHost(it) }
            ?: return null

        val host = when {
            isDefoverridable(hostCall) -> Host.DEFOVERRIDABLE
            isImport(hostCall) -> importHostOf(pair, hostCall) ?: return null
            attributeName(hostCall) == "@compile" ->
                if (isCompileInline(pair, hostCall)) Host.COMPILE_INLINE else return null
            attributeName(hostCall) == "@dialyzer" -> Host.DIALYZER
            else -> return null
        }

        return Occurrence(pair, host, hostCall, name, arity)
    }

    /**
     * The function-arity keyword-pair [Occurrence] the [leaf] participates in **through its key**, or
     * `null`. A leaf inside the *value* (e.g. the arity literal, or a call in a `do: expr` value) is
     * not a key occurrence.
     */
    @RequiresReadLock
    fun at(leaf: PsiElement): Occurrence? {
        val pair = generateSequence(leaf) { it.parent }
            .takeWhile { it !is PsiFile }
            .filterIsInstance<QuotableKeywordPair>()
            .firstOrNull()
            ?: return null
        if (!PsiTreeUtil.isAncestor(pair.keywordKey, leaf, false)) return null
        return classify(pair)
    }

    /**
     * All function-arity keyword-pair occurrences governed by [hostCall] (i.e. whose nearest recognized
     * host is [hostCall]). Cheap for non-host calls: returns immediately unless [hostCall] is itself a
     * potential host.
     */
    @RequiresReadLock
    fun occurrencesIn(hostCall: Call): List<Occurrence> {
        if (!isPotentialHost(hostCall)) return emptyList()
        return PsiTreeUtil.findChildrenOfType(hostCall, QuotableKeywordPair::class.java)
            .mapNotNull { classify(it) }
            .filter { it.hostCall == hostCall }
    }

    private fun isPotentialHost(call: Call): Boolean =
        isImport(call) || isDefoverridable(call) || attributeName(call).let { it == "@compile" || it == "@dialyzer" }

    private fun isImport(call: Call): Boolean = call.functionName() == IMPORT

    private fun isDefoverridable(call: Call): Boolean = call.functionName() == DEFOVERRIDABLE

    private fun attributeName(call: Call): String? =
        (call as? AtUnqualifiedNoParenthesesCall<*>)?.let { moduleAttributeName(it) }

    /**
     * Whether [pair] is a `@compile` inline entry, in either documented form:
     *  - keyword form `@compile inline: [fun: arity]` - inside an ancestor `inline:` keyword pair, or
     *  - tuple form `@compile {:inline, fun: arity}` - inside an ancestor `{:inline, ...}` tuple.
     */
    private fun isCompileInline(pair: QuotableKeywordPair, hostCall: Call): Boolean {
        val ancestors = generateSequence(pair.parent) { it.parent }
            .takeWhile { it !== hostCall }
            .toList()
        val underInlineKeyword = ancestors
            .filterIsInstance<QuotableKeywordPair>()
            .any { nameOf(it.keywordKey) == "inline" }
        if (underInlineKeyword) return true
        return ancestors
            .filterIsInstance<ElixirTuple>()
            .any { tupleLeadingAtom(it) == "inline" }
    }

    /** The atom value of a tuple's first element (e.g. `:inline` in `{:inline, ...}`), or `null`. */
    private fun tupleLeadingAtom(tuple: ElixirTuple): String? =
        ((ElementLowering.quote(tuple) as? OtpErlangTuple)?.elementAt(0) as? OtpErlangAtom)?.atomValue()

    private fun importHostOf(pair: QuotableKeywordPair, hostCall: Call): Host? =
        generateSequence(pair.parent) { it.parent }
            .takeWhile { it !== hostCall }
            .filterIsInstance<QuotableKeywordPair>()
            .mapNotNull {
                when (nameOf(it.keywordKey)) {
                    "only" -> Host.IMPORT_ONLY
                    "except" -> Host.IMPORT_EXCEPT
                    else -> null
                }
            }
            .firstOrNull()

    /** The function/macro name named by [keywordKey], or `null` if it has no textual name. */
    @RequiresReadLock
    fun nameFromKey(keywordKey: Quotable): String? =
        (ElementLowering.quote(keywordKey) as? OtpErlangAtom)?.atomValue() ?: keywordKey.text.takeIf { it.isNotEmpty() }

    /** The arity named by [keywordValue], or `null` if the value is not an integer literal. */
    @RequiresReadLock
    fun arityFromValue(keywordValue: Quotable): Int? =
        (ElementLowering.quote(keywordValue) as? OtpErlangLong)?.let {
            try {
                it.intValue()
            } catch (_: OtpErlangRangeException) {
                null
            }
        }

    private fun nameOf(keywordKey: Quotable): String? = nameFromKey(keywordKey)

    private fun arityOf(keywordValue: Quotable): Int? = arityFromValue(keywordValue)
}
