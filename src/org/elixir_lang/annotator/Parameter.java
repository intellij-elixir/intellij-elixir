package org.elixir_lang.annotator;

import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.elixir_lang.psi.*;
import org.elixir_lang.psi.call.Call;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Parameter {
    public enum Type {
        FUNCTION_NAME,
        MACRO_NAME,
        VARIABLE;

        public static boolean isCallDefinitionClauseName(Type type) {
            return type == FUNCTION_NAME || type == MACRO_NAME;
        }
    }

    /*
     * Public Static Methods
     */

    /**
     * A new {@link Parameter} with {@link #parameterized} filled in if {@code parameter}'s {@link #entrance} is a
     * parameter element.
     *
     * @return a new {@link Parameter} with {@link #parameterized} filled in if {@link #entrance} is a valida parameter
     *   element.
     */
    @Contract(pure = true)
    @NotNull
    public static Parameter putParameterized(final @NotNull Parameter parameter) {
        return putParameterized(parameter, parameter.entrance);
    }

    /*
     * Private Static Methods
     */

    @Contract(pure = true)
    @NotNull
    private static <T> T notNullize(@Nullable T nullable, @NotNull T defaultValue) {
        T notNull;

        if (nullable == null) {
            notNull = defaultValue;
        } else {
            notNull = nullable;
        }

        return notNull;
    }

    @Contract(pure = true)
    @NotNull
    private static Parameter putParameterized(@NotNull final Parameter parameter, final @NotNull Call ancestor) {
        Parameter parameterizedParameter;

        CallableDeclaration.Form form = CallableDeclaration.INSTANCE.headBindingFormOf(ancestor);

        if (form != null) {
            CallableDeclaration.Definer definer = CallableDeclaration.INSTANCE.definerOf(ancestor);
            Type nameType = definer != null && definer.getCapabilities().getCompileTime() ? Type.MACRO_NAME : Type.FUNCTION_NAME;

            parameterizedParameter = new Parameter(
                    parameter.defaultValue,
                    parameter.entrance,
                    notNullize(parameter.parameterized, ancestor),
                    notNullize(parameter.type, nameType)
            );
        } else if (ancestor.hasDoBlockOrKeyword()) {
            parameterizedParameter = new Parameter(
                    parameter.defaultValue,
                    parameter.entrance,
                    ancestor,
                    notNullize(parameter.type, Type.VARIABLE)
            );
        } else {
            PsiElement element = ancestor.functionNameElement();
            Parameter updatedParameter = parameter;

            if (!PsiTreeUtil.isAncestor(element, parameter.entrance, false)) {
                updatedParameter = new Parameter(
                        parameter.defaultValue,
                        parameter.entrance,
                        ancestor,
                        notNullize(parameter.type, Type.VARIABLE)
                );
            }

            // use generic handling so that parent is checked
            parameterizedParameter = putParameterized(updatedParameter, (PsiElement) ancestor);
        }

        return parameterizedParameter;
    }

    @Contract(pure = true)
    @NotNull
    private static Parameter putParameterized(@NotNull final Parameter parameter,
                                              @NotNull final ElixirAnonymousFunction ancestor) {
        return new Parameter(
                parameter.defaultValue,
                parameter.entrance,
                ancestor,
                notNullize(parameter.type, Type.VARIABLE)
        );
    }

    @Contract(pure = true)
    @NotNull
    private static Parameter putParameterized(@NotNull final Parameter parameter, @NotNull final PsiElement ancestor) {
        PsiElement parent = ancestor.getParent();

        if (parent == null) {
            return new Parameter(parameter.entrance);
        }

        return switch (ParameterWalk.classify(parent)) {
            case RECURSE -> putParameterized(parameter, parent);
            case CALL -> putParameterized(parameter, (Call) parent);
            case ANONYMOUS_FUNCTION -> putParameterized(parameter, (ElixirAnonymousFunction) parent);
            case STOP, LEAF -> new Parameter(parameter.entrance);
        };
    }

    /*
     * Fields
     */

    @Nullable
    public final PsiElement defaultValue;
    @NotNull
    public final PsiElement entrance;
    @Nullable
    public final NavigatablePsiElement parameterized;
    @Nullable
    public final Type type;

    /*
     * Constructors
     */

    public Parameter(@NotNull PsiElement entrance) {
        this.defaultValue = null;
        this.entrance = entrance;
        this.parameterized = null;
        this.type = null;
    }

    private Parameter(@Nullable PsiElement defaultValue,
                      @NotNull PsiElement entrance,
                      @Nullable NavigatablePsiElement parameterized,
                      @Nullable Type type) {
        this.defaultValue = defaultValue;
        this.entrance = entrance;
        this.parameterized = parameterized;
        this.type = type;
    }

    /*
     * Public Instance Methods
     */

    /**
     * Whether the {@link #type} is call definition clause name
     *
     * @return {@code true} if {@link #type} is {@link Type#FUNCTION_NAME} or {@link Type#MACRO_NAME}.
     */
    @Contract(pure = true)
    public boolean isCallDefinitionClauseName() {
        return Type.isCallDefinitionClauseName(type);
    }

    /**
     * Whether {@link #entrance} represents a parameter to a {@link #parameterized} element
     * @return {@code true} if {@link #parameterized} is not {@code null}
     */
    @Contract(pure = true)
    boolean isValid() {
        return parameterized == null;
    }

    /**
     * A Parameter that is not a parameter to anything.
     *
     * @param parameter The original {@link Parameter} that may or may not be parameterized
     * @return an invalid parameter
     */
    @NotNull
    public Parameter not(final @NotNull Parameter parameter) {
        Parameter not;

        if (parameter.defaultValue == null && parameter.parameterized == null) {
            not = parameter;
        } else {
            not = new Parameter(parameter.entrance);
        }

        return not;
    }
}
