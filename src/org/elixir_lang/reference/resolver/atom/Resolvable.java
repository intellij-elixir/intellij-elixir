package org.elixir_lang.reference.resolver.atom;

import com.intellij.psi.ResolveResult;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.elixir_lang.psi.*;
import org.elixir_lang.psi.impl.ElixirAtomImplKt;
import org.elixir_lang.reference.resolver.atom.resolvable.Exact;
import org.elixir_lang.reference.resolver.atom.resolvable.InterpolatedAtomPatternKt;
import org.elixir_lang.reference.resolver.atom.resolvable.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * How to resolve an {@link ElixirAtom}.
 * <p>
 * If the {ElixirAtom} is a normal, unquoted atom, it can be resolved exactly, but if it's quoted and contains
 * interpolation, then it cannot be resolved exactly.
 */
public abstract class Resolvable {
    /** An unquoted atom has no value only when it is longer than an atom can be, so it names no module. */
    private static final Resolvable NOTHING = new Resolvable() {
        @Override
        public ResolveResult[] resolve(@NotNull ElixirAtom element) {
            return ResolveResult.EMPTY_ARRAY;
        }
    };

    @NotNull
    @RequiresReadLock
    public static Resolvable resolvable(@NotNull ElixirAtom atom) {
        String indexName = ElixirAtomImplKt.indexName(atom);
        ElixirLine line = atom.getLine();
        Resolvable resolvable;

        if (indexName != null) {
            resolvable = new Exact(indexName);
        } else if (line != null) {
            Pattern pattern = InterpolatedAtomPatternKt.interpolatedAtomPattern(atom);

            resolvable = pattern != null ? pattern : NOTHING;
        } else {
            resolvable = NOTHING;
        }

        return resolvable;
    }

    public abstract ResolveResult[] resolve(@NotNull ElixirAtom element);
}
