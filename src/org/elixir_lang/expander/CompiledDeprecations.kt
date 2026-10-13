package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.chunk.Literals
import org.elixir_lang.beam.chunk.code.Operation
import org.elixir_lang.beam.term.Atom
import org.elixir_lang.beam.term.Label
import org.elixir_lang.beam.term.Literal
import org.elixir_lang.beam.term.XRegister
import org.elixir_lang.beam.chunk.code.operation.Code as OpCode
import org.elixir_lang.beam.term.List as TermList

/**
 * A compiled module's hard deprecations, as `__info__(:deprecated)` gives them: the list its `deprecated` clause
 * returns, read from the module's code and literals without running it.
 */
internal object CompiledDeprecations {
    /** How `'__info__'/1`'s `deprecated` clause returns its list. */
    enum class Shape {
        /** A literal from the literal table. */
        LITERAL,

        /** `[]`, at the clause's own label. */
        NIL,

        /** `[]`, from a block that other clauses jump or select to as well. */
        SHARED_NIL,
    }

    sealed class Read {
        data class Found(val deprecations: Deprecations.Known, val shape: Shape) : Read()

        /** The module has no `__info__/1`, as no Erlang module does, so Elixir reads no deprecations from it. */
        data object NoInfo : Read()

        /** The module has an `__info__/1` whose `deprecated` answer isn't a literal list, as a hand-written one may. */
        data object Unreadable : Read()
    }

    fun of(reader: BeamReader): Deprecations =
        when (val read = read(reader)) {
            is Read.Found -> read.deprecations
            Read.NoInfo -> Deprecations.Known(emptyMap())
            Read.Unreadable -> Deprecations.Unreadable
        }

    fun read(reader: BeamReader): Read {
        val atoms = reader.atomsResult.valueOrNull ?: return Read.Unreadable
        val atomIndex = { name: String -> (1..atoms.size()).firstOrNull { atoms.getOrNull(it)?.string == name } }
        val info = atomIndex("__info__") ?: return Read.NoInfo
        val code = reader.codeResult.valueOrNull ?: return Read.Unreadable
        val operations = (0 until code.size()).map(code::get)
        val start = operations.indexOfFirst { operation ->
            operation.code == OpCode.FUNC_INFO &&
                (operation.termList.getOrNull(1) as? Atom)?.index == info &&
                (operation.termList.getOrNull(2) as? Literal)?.index == 1
        }
        if (start < 0) return Read.NoInfo

        val deprecated = atomIndex("deprecated") ?: return Read.Unreadable
        val select = operations
            .subList(start + 1, operations.size)
            .takeWhile { it.code != OpCode.FUNC_INFO && it.code != OpCode.INT_CODE_END }
            .firstOrNull { it.code == OpCode.SELECT_VAL }
            ?: return Read.Unreadable
        val targets = (select.termList.getOrNull(2) as? TermList)?.elements?.chunked(2) ?: return Read.Unreadable
        val target = targets.firstOrNull { (it.first() as? Atom)?.index == deprecated }?.getOrNull(1) as? Label
            ?: return Read.Unreadable
        val shared = targets.count { (it.getOrNull(1) as? Label)?.index == target.index } > 1
        val (value, shape) = returned(operations, { reader.literalsResult.valueOrNull }, target.index, shared)
            ?: return Read.Unreadable

        return deprecations(value)?.let { Read.Found(it, shape) } ?: Read.Unreadable
    }

    /**
     * What the block at [label] returns, where it moves a literal or `[]` to `x0` and returns, after any jumps.
     *
     * @param shared whether other clauses select [label] too
     */
    private fun returned(
        operations: List<Operation>,
        literals: () -> Literals?,
        label: Int,
        shared: Boolean,
    ): Pair<OtpErlangObject, Shape>? {
        val labels = operations.withIndex()
            .filter { it.value.code == OpCode.LABEL }
            .mapNotNull { (index, operation) -> (operation.termList.firstOrNull() as? Literal)?.index?.let { it to index } }
            .toMap()
        var at = labels[label] ?: return null
        var jumped = false

        repeat(MAX_STEPS) {
            at++
            val operation = operations.getOrNull(at) ?: return null

            when (operation.code) {
                OpCode.LABEL, OpCode.LINE -> Unit
                OpCode.JUMP -> {
                    at = (operation.termList.firstOrNull() as? Label)?.index?.let(labels::get) ?: return null
                    jumped = true
                }
                OpCode.MOVE -> {
                    if ((operation.termList.getOrNull(1) as? XRegister)?.index != 0) return null
                    if (operations.getOrNull(at + 1)?.code != OpCode.RETURN) return null

                    return when (val source = operation.termList.firstOrNull()) {
                        is Literal -> literals()?.getOrNull(source.index)?.let { it to Shape.LITERAL }
                        // Atom 0 is `[]`.
                        is Atom if source.index == 0 ->
                            OtpErlangList() to if (shared || jumped) Shape.SHARED_NIL else Shape.NIL
                        else -> null
                    }
                }
                else -> return null
            }
        }

        return null
    }

    /**
     * A `[{{name, arity}, reason}]` list's reasons, or null for any other term. A name and arity listed more than once,
     * for clauses that gave different reasons, keeps its first, which `elixir_dispatch:check_deprecated/6` reports.
     */
    private fun deprecations(value: OtpErlangObject): Deprecations.Known? =
        (value as? OtpErlangList)?.elements()?.map { element ->
            val (nameArity, reason) = (element as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elements() ?: return null
            val (name, arity) = (nameArity as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elements() ?: return null

            NameArity(
                (name as? OtpErlangAtom)?.atomValue() ?: return null,
                (arity as? OtpErlangLong)?.intValue() ?: return null,
            ) to String((reason as? OtpErlangBinary)?.binaryValue() ?: return null, Charsets.UTF_8)
        }?.distinctBy { it.first }?.toMap()?.let(Deprecations::Known)

    /** Steps from the clause's label to its `return`: a jump, labels and line markers come before the move. */
    private const val MAX_STEPS = 8
}
