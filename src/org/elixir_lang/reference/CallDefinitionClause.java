package org.elixir_lang.reference;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall;
import org.elixir_lang.psi.call.Call;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CallDefinitionClause extends PsiReferenceBase<Call> implements PsiPolyVariantReference {
    /*
     *
     * Fields
     *
     */

    @NotNull
    public final AtUnqualifiedNoParenthesesCall moduleAttribute;

    /*
     *
     * Constructors
     *
     */

    /**
     * @param call {@code foo(arg1, ...) :: return1} in {@code @spec foo(arg1, ...) :: return1}
     * @param moduleAttribute {@code @spec foo(arg1, ...) ... return1}, so that the tree doesn't have to be walked up
     *   again if there is a {@code when}.
     */
    public CallDefinitionClause(@NotNull Call call, @NotNull AtUnqualifiedNoParenthesesCall moduleAttribute) {
        super(call, TextRange.create(0, call.getTextLength()));
        this.moduleAttribute = moduleAttribute;
    }

    /*
     *
     * Instance Methods
     *
     */

    /*
     * Public Instance Methods
     */

    /**
     * Returns the array of String, {@link PsiElement} and/or {@link LookupElement}
     * instances representing all identifiers that are visible at the location of the reference. The contents
     * of the returned array is used to build the lookup list for basic code completion. (The list
     * of visible identifiers may not be filtered by the completion prefix string - the
     * filtering is performed later by IDEA core.)
     *
     * @return the array of available identifiers.
     */
    @NotNull
    @Override
    public Object @NotNull [] getVariants() {
        return new Object[0];
    }

    /**
     * Returns the results of resolving the reference.
     *
     * @param incompleteCode if true, the code in the context of which the reference is
     *                       being resolved is considered incomplete, and the method may return additional
     *                       invalid results.
     * @return the array of results for resolving the reference.
     */
    @NotNull
    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        return ResolveCache
                .getInstance(this.myElement.getProject())
                .resolveWithCaching(
                        this,
                        org.elixir_lang.reference.resolver.CallDefinitionClause.INSTANCE,
                        false,
                        incompleteCode
                );
    }

    /** {@link Resolver#resolved}: the first preferred valid result, or {@code null}. */
    @Nullable
    @Override
    public PsiElement resolve() {
        return Resolver.INSTANCE.resolved(myElement, java.util.Arrays.asList(multiResolve(false)));
    }
}
