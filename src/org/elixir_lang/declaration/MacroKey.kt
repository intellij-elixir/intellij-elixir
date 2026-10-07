package org.elixir_lang.declaration

import org.elixir_lang.declaration.MacroRole.Block

/**
 * Each macro whose expansion the plugin summarises, as a call dispatches to it: its receiver as atom text, its
 * [macroName] and its arity, at every arity any supported Elixir release exports it. [block] is [MacroRole.block] for a
 * `Kernel` macro.
 */
enum class MacroKey(val receiver: String, val macroName: String, val arity: Int, val block: Block) {
    IF(KERNEL, "if", 2, Block.IN_PLACE),
    UNLESS(KERNEL, "unless", 2, Block.IN_PLACE),
    AND_AND(KERNEL, "&&", 2, Block.NOT_A_BLOCK),
    OR_OR(KERNEL, "||", 2, Block.NOT_A_BLOCK),
    NOT(KERNEL, "!", 1, Block.NOT_A_BLOCK),
    AND(KERNEL, "and", 2, Block.NOT_A_BLOCK),
    OR(KERNEL, "or", 2, Block.NOT_A_BLOCK),
    PIPE(KERNEL, "|>", 2, Block.NOT_A_BLOCK),
    IN(KERNEL, "in", 2, Block.NOT_A_BLOCK),
    CONCAT(KERNEL, "<>", 2, Block.NOT_A_BLOCK),
    TO_STRING(KERNEL, "to_string", 1, Block.NOT_A_BLOCK),
    RAISE_1(KERNEL, "raise", 1, Block.NOT_A_BLOCK),
    RAISE_2(KERNEL, "raise", 2, Block.NOT_A_BLOCK),
    BINDING_0(KERNEL, "binding", 0, Block.NOT_A_BLOCK),
    BINDING_1(KERNEL, "binding", 1, Block.NOT_A_BLOCK),
    DESTRUCTURE(KERNEL, "destructure", 2, Block.NOT_A_BLOCK),
    RANGE(KERNEL, "..", 2, Block.NOT_A_BLOCK),
    STEP_RANGE(KERNEL, "..//", 3, Block.NOT_A_BLOCK),
    FULL_RANGE(KERNEL, "..", 0, Block.NOT_A_BLOCK),
    VAR_BANG_1(KERNEL, "var!", 1, Block.NOT_A_BLOCK),
    VAR_BANG_2(KERNEL, "var!", 2, Block.NOT_A_BLOCK),
    ALIAS_BANG(KERNEL, "alias!", 1, Block.NOT_A_BLOCK),
    // A `def*` call itself runs in place, but its block is a new function body.
    DEF_1(KERNEL, "def", 1, Block.BOUNDARY),
    DEF_2(KERNEL, "def", 2, Block.BOUNDARY),
    DEFP_1(KERNEL, "defp", 1, Block.BOUNDARY),
    DEFP_2(KERNEL, "defp", 2, Block.BOUNDARY),
    DEFMACRO_1(KERNEL, "defmacro", 1, Block.BOUNDARY),
    DEFMACRO_2(KERNEL, "defmacro", 2, Block.BOUNDARY),
    DEFMACROP_1(KERNEL, "defmacrop", 1, Block.BOUNDARY),
    DEFMACROP_2(KERNEL, "defmacrop", 2, Block.BOUNDARY),
    DEFMODULE(KERNEL, "defmodule", 2, Block.BOUNDARY),
    AT(KERNEL, "@", 1, Block.NOT_A_BLOCK),
    DEFSTRUCT(KERNEL, "defstruct", 1, Block.NOT_A_BLOCK),
    DEFEXCEPTION(KERNEL, "defexception", 1, Block.NOT_A_BLOCK),
    DEFGUARD(KERNEL, "defguard", 1, Block.NOT_A_BLOCK),
    DEFGUARDP(KERNEL, "defguardp", 1, Block.NOT_A_BLOCK),
    DEFOVERRIDABLE(KERNEL, "defoverridable", 1, Block.NOT_A_BLOCK),
    DEFDELEGATE(KERNEL, "defdelegate", 2, Block.NOT_A_BLOCK),
    DEFPROTOCOL(KERNEL, "defprotocol", 2, Block.BOUNDARY),
    DEFIMPL_2(KERNEL, "defimpl", 2, Block.BOUNDARY),
    DEFIMPL_3(KERNEL, "defimpl", 3, Block.BOUNDARY),
    USE_1(KERNEL, "use", 1, Block.NOT_A_BLOCK),
    USE_2(KERNEL, "use", 2, Block.NOT_A_BLOCK),
    SIGIL_C_UPPER(KERNEL, "sigil_C", 2, Block.NOT_A_BLOCK),
    SIGIL_D(KERNEL, "sigil_D", 2, Block.NOT_A_BLOCK),
    SIGIL_N(KERNEL, "sigil_N", 2, Block.NOT_A_BLOCK),
    SIGIL_R_UPPER(KERNEL, "sigil_R", 2, Block.NOT_A_BLOCK),
    SIGIL_S_UPPER(KERNEL, "sigil_S", 2, Block.NOT_A_BLOCK),
    SIGIL_T(KERNEL, "sigil_T", 2, Block.NOT_A_BLOCK),
    SIGIL_U(KERNEL, "sigil_U", 2, Block.NOT_A_BLOCK),
    SIGIL_W_UPPER(KERNEL, "sigil_W", 2, Block.NOT_A_BLOCK),
    SIGIL_C(KERNEL, "sigil_c", 2, Block.NOT_A_BLOCK),
    SIGIL_R(KERNEL, "sigil_r", 2, Block.NOT_A_BLOCK),
    SIGIL_S(KERNEL, "sigil_s", 2, Block.NOT_A_BLOCK),
    SIGIL_W(KERNEL, "sigil_w", 2, Block.NOT_A_BLOCK),
    PROTOCOL_DEF("Elixir.Protocol", "def", 1, Block.NOT_A_BLOCK),
    UTILS_DEFGUARD("Elixir.Kernel.Utils", "defguard", 2, Block.NOT_A_BLOCK),
    BOOTSTRAP_AT("elixir_bootstrap", "@", 1, Block.NOT_A_BLOCK),
    BOOTSTRAP_DEF("elixir_bootstrap", "def", 2, Block.BOUNDARY);

    companion object {
        private val BY_DISPATCH: Map<Triple<String, String, Int>, MacroKey> =
            entries.associateBy { Triple(it.receiver, it.macroName, it.arity) }

        /** The key of a call that dispatches to [receiver]'s macro [name]/[arity], or `null` when none is summarised. */
        fun of(receiver: String, name: String, arity: Int): MacroKey? = BY_DISPATCH[Triple(receiver, name, arity)]
    }
}

private const val KERNEL = "Elixir.Kernel"
