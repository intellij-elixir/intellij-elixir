package org.elixir_lang.psi;

import org.jetbrains.annotations.NotNull;

/**
 * `@name[key]`: the brackets read the attribute `@name`.
 */
public interface AtUnqualifiedBracketOperation extends Quotable {
    @NotNull
    ElixirMatchedAtOperation getMatchedAtOperation();

    Quotable getBracketArguments();
}
