package org.elixir_lang.psi.scope.atom;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.stubs.StubIndex;
import org.elixir_lang.Module;
import org.elixir_lang.psi.scope.Atom;
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult;
import org.elixir_lang.psi.stub.index.ModularName;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Variants extends Atom {
    @NotNull
    public static List<LookupElement> lookupElementList(@NotNull PsiElement entrance) {
        return new Variants().projectLookupElementStream(entrance);
    }

    private List<LookupElement> projectLookupElementStream(@NotNull PsiElement entrance) {
        Project project = entrance.getProject();
        /* getAllKeys is not the actual keys in the actual project.  They need to be checked.
           See https://intellij-support.jetbrains.com/hc/en-us/community/posts/207930789-StubIndex-persisting-between-test-runs-leading-to-incorrect-completions */
        Collection<String> indexedNameCollection = StubIndex.getInstance().getAllKeys(ModularName.KEY, project);

        String prefix = prefix(entrance);
        InsertHandler<LookupElement> insertHandler =
                entrance.getText().startsWith(":\"") ? Variants::removeReplacedClosingQuote : null;
        List<LookupElement> lookupElementList = new ArrayList<>();

        for (String atomName : indexedNameCollection) {
            String atom = Module.atom(atomName);

            if (atom == null || !atom.startsWith(prefix)) {
                continue;
            }

            for (VisitedElementSetResolveResult resolveResult :
                    org.elixir_lang.reference.resolver.Module.INSTANCE.resolvePreferred(entrance, atomName, false, false)) {
                PsiElement navigationElement = resolveResult.getElement().getNavigationElement();
                lookupElementList.add(
                        LookupElementBuilder
                                .createWithSmartPointer(Module.inspect(atomName), navigationElement)
                                .withLookupStrings(List.of(":" + atom, ":\"" + atom + "\""))
                                .withInsertHandler(insertHandler)
                );
            }
        }

        return lookupElementList;
    }

    /** Completion replaces a quoted atom only up to the caret, so its closing quote would follow the inserted atom. */
    private static void removeReplacedClosingQuote(@NotNull InsertionContext context, @NotNull LookupElement item) {
        Document document = context.getDocument();
        int tailOffset = context.getTailOffset();

        if (tailOffset < document.getTextLength() && document.getCharsSequence().charAt(tailOffset) == '"') {
            document.deleteString(tailOffset, tailOffset + 1);
        }
    }

    /** The atom typed so far, however it is quoted. */
    @Contract(pure = true)
    @NotNull
    private static String prefix(PsiElement atom) {
        String text = atom.getText();
        int caret = text.indexOf("IntellijIdeaRulezzz");
        String typed = caret < 0 ? text : text.substring(0, caret);

        return StringUtil.trimStart(StringUtil.trimStart(typed, ":"), "\"");
    }
}
