package org.elixir_lang.expander

/** The dispatches Elixir traces for a `defstruct`, which `defexception` calls as well. */
internal object StructEvents {
    const val DEFSTRUCT = "imported_macro Elixir.Kernel.defstruct/1"
    const val DEFSTRUCT_QUOTED = "remote_macro Elixir.Kernel.defstruct/1"
    const val AT = "imported_macro Elixir.Kernel.@/1"
    const val BOOTSTRAP_AT = "remote_macro elixir_bootstrap.@/1"
    const val KERNEL_AT = "remote_macro Elixir.Kernel.@/1"
    const val BOOTSTRAP_DEF = "remote_macro elixir_bootstrap.def/2"
    const val PUT_4 = "remote_function Elixir.Module.__put_attribute__/4"
    const val ANNOUNCE = "remote_function Elixir.Kernel.Utils.announce_struct/1"
    const val REDUCE = "remote_function Elixir.Enum.reduce/3"
    const val REPLACE = "remote_function Elixir.Map.replace!/3"
    const val DELETE = "remote_function Elixir.List.delete/2"

    /** `raise ArgumentError, message <> ...`. */
    val RAISE = listOf(
        "remote_macro Elixir.Kernel.raise/2",
        "remote_function erlang.error/1",
        "remote_function Elixir.ArgumentError.exception/1",
        "remote_macro Elixir.Kernel.<>/2",
    )

    /** `#{inspect(...)}`. */
    val INSPECTED = listOf(
        "remote_macro Elixir.Kernel.to_string/1",
        "remote_function Elixir.String.Chars.to_string/1",
        "remote_function Elixir.Kernel.inspect/1",
    )

    fun put(version: String) =
        "remote_function Elixir.Module.__put_attribute__/${if (isBefore(version, "1.14.0-rc.0")) 4 else 5}"

    /**
     * The dispatches of a `defstruct` that is [first], up to its definitions, with the [fields] events where its fields
     * expand: before `Kernel.Utils.defstruct` up to 1.13, which builds them in its arguments, and after the binding from
     * 1.14.
     */
    fun output(version: String, first: String, fields: List<String>, enforced: Boolean): List<String> {
        val prelude = if (enforced) listOf(AT, put(version)) else emptyList()

        return when {
            isBefore(version, "1.14.0-rc.0") -> prelude + legacyOutput(first, fields)
            else -> {
                val utils = if (isBefore(version, "1.16.0-rc.0")) 3 else 4

                listOf(
                    prelude,
                    listOf(first),
                    fields,
                    listOf(
                        "remote_function Elixir.Kernel.Utils.defstruct/$utils",
                        "remote_function Elixir.Protocol.__derive__/3",
                        BOOTSTRAP_DEF,
                        BOOTSTRAP_DEF,
                        ANNOUNCE,
                    ),
                ).flatten()
            }
        }
    }

    /** The bodies of the definitions, which come after every other dispatch of the module body. */
    fun bodies(version: String, enforced: Boolean): List<String> {
        if (isBefore(version, "1.14.0-rc.0")) return legacyBodies(enforced)

        val recorded = !isBefore(version, "1.18.0-rc.0")
        val enforce = if (enforced) listOf(listOf(DELETE), RAISE, INSPECTED, INSPECTED).flatten() else emptyList()

        return if (recorded) listOf(REDUCE) + enforce else listOf(BOOTSTRAP_AT, REDUCE, KERNEL_AT) + enforce
    }

    /** The output of `defstruct/1` up to 1.13: the check for a second call, then the attribute writes. */
    private fun legacyOutput(first: String, fields: List<String>) =
        listOf(
            listOf(
                first,
                "remote_macro Elixir.Kernel.if/2",
                "remote_function Elixir.Module.has_attribute?/2",
                "remote_macro Elixir.Kernel.in/2",
                "remote_function erlang.orelse/2",
                "remote_function erlang.=:=/2",
                "remote_function erlang.=:=/2",
            ),
            RAISE,
            INSPECTED,
            listOf("remote_function Elixir.Kernel.Utils.defstruct/2"),
            fields,
            listOf(
                BOOTSTRAP_AT,
                PUT_4,
                BOOTSTRAP_AT,
                PUT_4,
                "remote_function Elixir.Protocol.__derive__/3",
                BOOTSTRAP_DEF,
                BOOTSTRAP_AT,
                "remote_function Elixir.Module.__get_attribute__/3",
                BOOTSTRAP_DEF,
                BOOTSTRAP_DEF,
                ANNOUNCE,
            ),
        ).flatten()

    /** The one `__struct__/1` of the two the output defines, whose `@enforce_keys` case chose it. */
    private fun legacyBodies(enforced: Boolean) =
        if (enforced) {
            listOf(listOf(BOOTSTRAP_AT, REDUCE, BOOTSTRAP_AT, BOOTSTRAP_AT, REPLACE, DELETE), RAISE, INSPECTED, INSPECTED).flatten()
        } else {
            listOf(BOOTSTRAP_AT, REDUCE, BOOTSTRAP_AT, REPLACE)
        }
}
