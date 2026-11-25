package org.elixir_lang.psi.scope.atom;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.stubs.StubIndex;
import org.elixir_lang.Module;
import org.elixir_lang.code_insight.completion.insert_handler.QualifiedName;
import org.elixir_lang.psi.ElixirAtom;
import org.elixir_lang.psi.scope.Atom;
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult;
import org.elixir_lang.psi.stub.index.ModularName;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Variants extends Atom {
    /**
     * The module atoms starting with {@code prefix}, what is typed in {@code atom}, each written as
     * {@link QualifiedName.Companion#forAtom} writes a name in an atom being typed at {@code offset}.
     */
    @NotNull
    public static List<LookupElement> lookupElementList(@NotNull ElixirAtom atom, @NotNull String prefix, int offset) {
        return new Variants().projectLookupElementStream(atom, prefix, offset);
    }

    private List<LookupElement> projectLookupElementStream(@NotNull ElixirAtom entrance, @NotNull String prefix, int offset) {
        Project project = entrance.getProject();
        /* getAllKeys is not the actual keys in the actual project.  They need to be checked.
           See https://intellij-support.jetbrains.com/hc/en-us/community/posts/207930789-StubIndex-persisting-between-test-runs-leading-to-incorrect-completions */
        Collection<String> indexedNameCollection = StubIndex.getInstance().getAllKeys(ModularName.KEY, project);

        InsertHandler<LookupElement> insertHandler = QualifiedName.Companion.forAtom(entrance, offset);
        List<LookupElement> lookupElementList = new ArrayList<>();

        for (String atomName : indexedNameCollection) {
            String atom = Module.atom(atomName);

            if (atom == null || !atom.startsWith(prefix)) {
                continue;
            }

            String literal = Module.inspect(atomName);

            for (VisitedElementSetResolveResult resolveResult :
                    org.elixir_lang.reference.resolver.Module.INSTANCE.resolvePreferred(entrance, atomName, false, false)) {
                lookupElementList.add(
                        LookupElementBuilder
                                .createWithSmartPointer(atom, resolveResult.getElement())
                                .withPresentableText(literal)
                                .withInsertHandler(insertHandler)
                );
            }
        }

        return lookupElementList;
    }
}
