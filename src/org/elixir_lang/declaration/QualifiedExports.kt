package org.elixir_lang.declaration

import org.elixir_lang.NameArity

/**
 * What a module exports, as the compiler's undefined-function hint reads it.
 *
 * @property exports with [hasInfo], `__info__(:macros)` then `__info__(:functions)`; without, `module_info(:exports)`
 * @property hasInfo whether the module exports `__info__/1`, as every Elixir module does
 * @property behaviourInfo whether, with [hasInfo], the compilers may add `behaviour_info/1` beside `__info__`'s lists,
 * as they do to an Elixir behaviour
 */
data class QualifiedExports(val exports: List<Export>, val hasInfo: Boolean, val behaviourInfo: Boolean) {
    /** @property deprecated whether `__info__(:deprecated)` names it */
    data class Export(val nameArity: NameArity, val macro: Boolean, val deprecated: Boolean)
}
