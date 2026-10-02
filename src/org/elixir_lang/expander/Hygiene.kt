package org.elixir_lang.expander

import org.elixir_lang.lowering.Meta

/** The `counter` entry of [meta]: `{Module, n}`, or the integer Elixir draws when there is no module. */
internal fun counterOf(meta: Meta): Env.Counter? =
    meta.keys
        .firstNotNullOfOrNull { key -> (key as? Meta.Key.Entry)?.takeIf { it.name == "counter" }?.value }
        ?.let { value ->
            when (value) {
                is Meta.Value.Integer -> Env.Counter.Unique(value.value)
                is Meta.Value.Tuple -> {
                    val (module, n) = value.elements

                    Env.Counter.InModule((module as Meta.Value.Atom).name, (n as Meta.Value.Integer).value)
                }
                is Meta.Value.Atom, is Meta.Value.Binary, is Meta.Value.Keywords -> null
            }
        }
