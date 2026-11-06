package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFMACRO
import org.elixir_lang.psi.call.name.Function.DEFMACROP
import org.elixir_lang.psi.call.name.Function.DEFP

/**
 * A `.beam`'s decompiled source, [forms], as the source its module was compiled from: each definition the decompiler
 * writes for what the compiler added to the `.beam` (`__info__/1`, `module_info/0,1`, `behaviour_info/1`) and the
 * module's source, [written], doesn't define, or that a definition of in source raises for, as `:erlang`'s export
 * `is_record/2` does, is dropped; and a guard, which the decompiler writes as `defguard` with the body its macro
 * compiled to, is that macro's `defmacro`.
 *
 * @param written [writtenDefinitions]; `null` where they can't be read, which drops all of the compiler's names. Asked
 * at most once, and only for a name source may define, as [compilerAdded] asks it.
 */
internal fun compilerOutput(forms: ElixirAst, written: () -> Set<NameArity>?): ElixirAst {
    val once by lazy(written)

    return output(forms) { once }
}

/**
 * The definitions [reader]'s `.beam` lists in its `Dbgi` chunk: those its module's source wrote, a `behaviour_info/1`
 * of its own among them, and none the compiler added. `null` where the `.beam` has no Elixir definitions to read.
 */
internal fun writtenDefinitions(reader: BeamReader): Set<NameArity>? = CompiledDefaults.fromDebugInfo(reader)?.keys

/** [writtenDefinitions] of [mirror]'s `.beam`. */
internal fun writtenDefinitions(mirror: ElixirFile): Set<NameArity>? =
    (mirror.originalFile as? BeamFileImpl)?.virtualFile?.let { beam ->
        BeamReader.readResult(beam, ::writtenDefinitions)?.valueOrNull
    }

/**
 * Whether [nameArity] is a definition the compiler added to a `.beam` whose module's source wrote [written]: one of
 * [ModuleExports.NOT_IN_INFO] that source can't define, or that [written] doesn't list, all of them where it is `null`.
 * [written] is asked only for a name source may define.
 */
internal fun compilerAdded(nameArity: NameArity, written: () -> Set<NameArity>?): Boolean =
    nameArity in ModuleExports.NOT_IN_INFO &&
        (reservedNameError(Kind.DEF, nameArity.name, nameArity.arity) != null || nameArity !in written().orEmpty())

/** The kind of definition a clause written with [definer] stores, `null` for no `def*`: a guard's is its macro's. */
internal fun storedKind(definer: String): Kind? =
    when (definer) {
        DEF, DEFP, DEFMACRO, DEFMACROP -> Kind.of(definer)
        else -> GUARD_KINDS[definer]
    }

internal val Kind.keyword: String get() = name.lowercase()

private fun output(forms: ElixirAst, written: () -> Set<NameArity>?): ElixirAst =
    when (forms) {
        is ElixirAst.Block -> ElixirAst.Block(forms.meta, forms.expressions.map { output(it, written) })
        is ElixirAst.Call if isCall(forms, "defmodule", 2) -> defmodule(forms, written)
        else -> forms
    }

private fun defmodule(call: ElixirAst.Call, written: () -> Set<NameArity>?): ElixirAst.Call {
    val (name, options) = call.arguments!!
    val doBlock = (options as? ElixirAst.ListNode)?.elements?.singleOrNull()?.takeIf { keyOf(it) == "do" } as? ElixirAst.Tuple
        ?: return call
    val body = doBlock.elements[1]
    val statements = (body as? ElixirAst.Block)?.expressions ?: listOf(body)
    val output = statements.mapNotNull { statement(it, written) }
    val kept = if (body is ElixirAst.Block || output.size != 1) ElixirAst.Block(body.meta, output) else output.single()
    val doPair = ElixirAst.Tuple(doBlock.meta, listOf(doBlock.elements[0], kept))

    return ElixirAst.Call(call.meta, call.callee, listOf(name, ElixirAst.ListNode(options.meta, listOf(doPair))), call.context)
}

/** [statement] of a module body as its source has it, `null` where the source has none. */
private fun statement(statement: ElixirAst, written: () -> Set<NameArity>?): ElixirAst? {
    val call = statement as? ElixirAst.Call ?: return statement
    if (isCall(call, "defmodule", 2)) return defmodule(call, written)
    val definer = call.callee as? ElixirAst.Literal.Atom ?: return statement
    val kind = storedKind(definer.name) ?: return statement
    val head = call.arguments?.firstOrNull()?.let { extractGuards(it).first } as? ElixirAst.Call
    val name = (head?.callee as? ElixirAst.Literal.Atom)?.name
    val arity = head?.arguments?.size ?: 0

    return when {
        name != null && (compilerAdded(NameArity(name, arity), written) || reservedNameError(kind, name, arity) != null) -> null
        definer.name == kind.keyword -> call
        else -> ElixirAst.Call(call.meta, ElixirAst.Literal.Atom(definer.meta, kind.keyword), call.arguments, call.context)
    }
}
