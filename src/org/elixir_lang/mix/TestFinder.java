package org.elixir_lang.mix;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Function;
import org.elixir_lang.psi.NamedElement;
import org.elixir_lang.psi.QuoteMacro;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.psi.call.StubBased;
import org.elixir_lang.psi.stub.index.AllName;
import org.elixir_lang.psi.Implementation;
import org.elixir_lang.psi.Module;
import org.elixir_lang.psi.Protocol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

public final class TestFinder implements com.intellij.testIntegration.TestFinder {
    private static final String TEST_SUFFIX = "Test";

    @NotNull
    private static Collection<PsiElement> corresponding(@NotNull PsiElement element,
                                                        @NotNull Function<String, String> correspondingName,
                                                        @NotNull Condition<Call> correspondingCallCondition) {
        Call sourceElement = sourceElement(element);
        Collection<PsiElement> correspondingCollection = new ArrayList<>();

        if (sourceElement instanceof @SuppressWarnings("rawtypes")StubBased sourceStubBased) {
            Set<String> canonicalNameSet = sourceStubBased.canonicalNameSet();

            if (!canonicalNameSet.isEmpty()) {
                Project project = element.getProject();
                GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

                for (String canonicalName : canonicalNameSet) {
                    String correspondingCanonicalName = correspondingName.fun(canonicalName);

                    if (correspondingCanonicalName != null) {
                        Collection<NamedElement> correspondingElements = StubIndex.getElements(
                                AllName.KEY,
                                correspondingCanonicalName,
                                project,
                                scope,
                                NamedElement.class
                        );

                        for (NamedElement correspondingElement : correspondingElements) {
                            if (correspondingElement instanceof Call correspondingCall) {
                                if (correspondingCallCondition.value(correspondingCall)) {
                                    correspondingCollection.add(correspondingCall);
                                }
                            }
                        }
                    }
                }
            }
        }

        return correspondingCollection;
    }

    private static boolean isModular(@NotNull Call call) {
        return Implementation.is(call) || Module.is(call) || Protocol.is(call) || QuoteMacro.is(call);
    }

    @Nullable
    private static Call parentCallSourceElement(@NotNull PsiElement from) {
        Call parentCall = PsiTreeUtil.getParentOfType(from, Call.class);
        Call sourceElement = null;

        if (parentCall != null) {
            sourceElement = sourceElement(parentCall);
        }

        return sourceElement;
    }

    @Nullable
    private static Call sourceElement(@NotNull Call from) {
        Call sourceElement;

        if (isModular(from)) {
            sourceElement = from;
        } else {
            sourceElement = parentCallSourceElement(from);
        }

        return sourceElement;
    }

    @Nullable
    private static Call sourceElement(@NotNull PsiElement from) {
        Call sourceElement;

        if (from instanceof Call) {
            sourceElement = sourceElement((Call) from);
        } else {
            sourceElement = parentCallSourceElement(from);
        }

        return sourceElement;
    }

    @Nullable
    @Override
    public Call findSourceElement(@NotNull PsiElement from) {
        return sourceElement(from);
    }

    @NotNull
    @Override
    public Collection<PsiElement> findTestsForClass(@NotNull PsiElement element) {
        return corresponding(
                element,
                canonicalName -> {
                    String atom = org.elixir_lang.Module.atom(canonicalName);

                    return atom != null ? org.elixir_lang.Module.indexName(atom + TEST_SUFFIX) : null;
                },
                Module::is
        );
    }

    @NotNull
    @Override
    public Collection<PsiElement> findClassesForTest(@NotNull PsiElement element) {
        return corresponding(
                element,
                canonicalName -> {
                    String atom = org.elixir_lang.Module.atom(canonicalName);
                    String correspondingCanonicalName = null;

                    if (atom != null && atom.endsWith(TEST_SUFFIX)) {
                        correspondingCanonicalName =
                                org.elixir_lang.Module.indexName(atom.substring(0, atom.length() - TEST_SUFFIX.length()));
                    }

                    return correspondingCanonicalName;
                },
                TestFinder::isModular
        );
    }

    @Override
    public boolean isTest(@NotNull PsiElement element) {
        return isTestElement(element);
    }

    /**
     * Check if the given element is a test or not.
     */
    public static boolean isTestElement(@NotNull PsiElement element) {
        Call sourceElement = sourceElement(element);
        boolean isTest = false;

        if (sourceElement instanceof StubBased sourceStubBased) {
            Set<String> canonicalNameSet = sourceStubBased.canonicalNameSet();

            if (!canonicalNameSet.isEmpty()) {
                for (String canonicalName : canonicalNameSet) {
                    if (canonicalName.endsWith("Test")) {
                        isTest = true;
                        break;
                    }
                }
            }
        }

        return isTest;
    }
}
