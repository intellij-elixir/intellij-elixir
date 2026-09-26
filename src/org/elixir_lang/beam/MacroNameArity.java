package org.elixir_lang.beam;

import org.elixir_lang.NameArity;
import org.elixir_lang.call.Visibility;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elixir_lang.call.Visibility.PRIVATE;
import static org.elixir_lang.call.Visibility.PUBLIC;
import static org.elixir_lang.psi.call.name.Function.*;

/**
 * The macro ({@code def} or {@code defmacro}), name of the call definition and arity to define an export
 */
public class MacroNameArity implements Comparable<MacroNameArity> {
    /*
     * CONSTANTS
     */

    public static final Map<Visibility, String> FUNCTION_MACRO_BY_VISIBILITY = new EnumMap<>(Visibility.class);
    private static final String MACRO_EXPORT_PREFIX = "MACRO-";
    private static final Map<Visibility, String> MACRO_MACRO_BY_VISIBILITY = new EnumMap<>(Visibility.class);
    public static final List<String> MACRO_ORDER = Arrays.asList(DEFMACRO, DEFMACROP, DEF, DEFP);
    private static final Map<String, Integer> ORDER_BY_MACRO = new HashMap<>();

    static {
        FUNCTION_MACRO_BY_VISIBILITY.put(PUBLIC, DEF);
        FUNCTION_MACRO_BY_VISIBILITY.put(PRIVATE, DEFP);

        MACRO_MACRO_BY_VISIBILITY.put(PUBLIC, DEFMACRO);
        MACRO_MACRO_BY_VISIBILITY.put(PRIVATE, DEFMACROP);

        int i = 0;
        for (String macro : MACRO_ORDER) {
            ORDER_BY_MACRO.put(macro, i++);
        }
    }

    /*
     * Fields
     */

    /**
     * {@code defmacro} if exportArity is prefixed with {@code MACRO-}; otherwise, {@code def}.
     */
    @NotNull
    public final String macro;

    /**
     * Elixir macros are defined as Erlang functions with names prefixed by {@code MACRO-}.  That prefix is stripped
     * from this name as it is the call definition name passed to {@code def} or {@code defmacro}.
     */
    @NotNull
    public final String name;

    /**
     * Elixir macros are defined as Erlang functions that take an extra Caller argument, so the exportArity is +1 for
     * macros compared to this arity, which is the number of parameters to the call definition head passed to
     * {@code defmacro}.
     */
    @NotNull
    public final Integer arity;

    /*
     * Constructors
     */

    public MacroNameArity(@NotNull String macro, @NotNull String name, int arity) {
        this.macro = macro;
        this.name = name;
        this.arity = arity;
    }

    public MacroNameArity(@NotNull Visibility visibility, @NotNull String exportName, int exportArity) {
        if (exportName.startsWith(MACRO_EXPORT_PREFIX)) {
            macro = MACRO_MACRO_BY_VISIBILITY.get(visibility);
            name = exportName.substring(MACRO_EXPORT_PREFIX.length());
            arity = exportArity - 1;
        } else {
            macro = FUNCTION_MACRO_BY_VISIBILITY.get(visibility);
            name = exportName;
            arity = exportArity;
        }
    }

    /*
     * Instance Methods
     */

    @Override
    public int compareTo(@NotNull MacroNameArity other) {
        int comparison = compareMacroTo(other.macro);

        if (comparison == 0) {
            comparison = compareNameTo(other.name);

            if (comparison == 0) {
                comparison = compareArityTo(other.arity);
            }
        }

        return comparison;
    }

    @Contract(pure = true)
    private int compareArityTo(int otherArity) {
        return Double.compare(arity, otherArity);
    }

    @Contract(pure = true)
    private int compareMacroTo(@NotNull String otherMacro) {
        return ORDER_BY_MACRO.get(macro).compareTo(ORDER_BY_MACRO.get(otherMacro));
    }

    @Contract(pure = true)
    private int compareNameTo(@NotNull String otherName) {
        return name.compareTo(otherName);
    }

    @NotNull
    public NameArity toNameArity() {
        return new NameArity(name, arity);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MacroNameArity)) return false;
        MacroNameArity that = (MacroNameArity) o;
        return macro.equals(that.macro) && name.equals(that.name) && arity.equals(that.arity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(macro, name, arity);
    }
}
