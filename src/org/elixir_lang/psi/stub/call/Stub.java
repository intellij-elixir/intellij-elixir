package org.elixir_lang.psi.stub.call;

import com.intellij.psi.PsiReference;
import com.intellij.psi.stubs.IStubElementType;
import com.intellij.psi.stubs.NamedStubBase;
import com.intellij.psi.stubs.StubElement;
import com.intellij.util.containers.SmartHashSet;
import com.intellij.util.io.StringRef;
import org.elixir_lang.psi.Definition;
import org.elixir_lang.psi.call.Call;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

import static org.elixir_lang.psi.DefinitionKt.definition;

// I normally wouldn't add the redundant StubBased prefix, but it makes generating from Elixir.bnf work
public class Stub<T extends org.elixir_lang.psi.call.StubBased> extends NamedStubBase<T> implements Stubbic {
    private static Set<String> setStringRefToSetString(Set<StringRef> stringRefSet) {
        Set<String> stringSet = new SmartHashSet<>(stringRefSet.size());

        for (StringRef stringRef : stringRefSet) {
            stringSet.add(StringRef.toString(stringRef));
        }

        return stringSet;
    }

    private static Set<StringRef> setStringToSetStringRef(Set<String> stringSet) {
        Set<StringRef> stringRefSet = new SmartHashSet<>(stringSet.size());

        for (String string : stringSet) {
            stringRefSet.add(StringRef.fromString(string));
        }

        return stringRefSet;
    }

    /*
     * Fields
     */

    private final Set<StringRef> canonicalNameSet;
    private final boolean hasDoBlockOrKeyword;
    private final int resolvedFinalArity;
    private final StringRef resolvedFunctionName;
    @Nullable
    private final StringRef resolvedModuleName;
    @Nullable
    private final Definition definition;
    @Nullable
    private final StringRef implementedProtocolName;

    /*
     * Constructors
     */

    public Stub(StubElement parent,
                @NotNull IStubElementType elementType,
                @Nullable String resolvedModuleName,
                @Nullable String resolvedFunctionName,
                int resolvedFinalArity,
                boolean hasDoBlockOrKeyword,
                @NotNull String name,
                @NotNull Set<String> canonicalNameSet,
                @Nullable String implementedProtocolName) {
        this(
                parent,
                elementType,
                StringRef.fromString(resolvedModuleName),
                StringRef.fromString(resolvedFunctionName),
                resolvedFinalArity,
                hasDoBlockOrKeyword,
                StringRef.fromString(name),
                setStringToSetStringRef(canonicalNameSet),
                StringRef.fromString(implementedProtocolName)
        );
    }

    public Stub(StubElement parent,
                 @NotNull IStubElementType elementType,
                 @Nullable String resolvedModuleName,
                 @Nullable String resolvedFunctionName,
                 int resolvedFinalArity,
                 boolean hasDoBlockOrKeyword,
                 @NotNull String name,
                 @NotNull Set<String> canonicalNameSet,
                 @Nullable Definition definition,
                 @Nullable String implementedProtocolName) {
        this(
                parent,
                elementType,
                StringRef.fromString(resolvedModuleName),
                StringRef.fromString(resolvedFunctionName),
                resolvedFinalArity,
                hasDoBlockOrKeyword,
                StringRef.fromString(name),
                setStringToSetStringRef(canonicalNameSet),
                definition,
                StringRef.fromString(implementedProtocolName)
        );
    }

    public Stub(StubElement parent,
                @NotNull IStubElementType elementType,
                @NotNull Deserialized deserialized) {
        this(
                parent,
                elementType,
                deserialized.resolvedModuleName,
                deserialized.resolvedFunctionName,
                deserialized.resolvedFinalArity,
                deserialized.hasDoBlockOrKeyword,
                deserialized.name,
                deserialized.canonicalNameSet,
                deserialized.implementedProtocolName
        );
    }

    private Stub(StubElement parent,
                 @NotNull IStubElementType elementType,
                 @Nullable StringRef resolvedModuleName,
                 @Nullable StringRef resolvedFunctionName,
                 int resolvedFinalArity,
                 boolean hasDoBlockOrKeyword,
                 @NotNull StringRef name,
                 @NotNull Set<StringRef> canonicalNameSet,
                 @Nullable StringRef implementedProtocolName) {
        this(
                parent,
                elementType,
                resolvedModuleName,
                resolvedFunctionName,
                resolvedFinalArity,
                hasDoBlockOrKeyword,
                name,
                canonicalNameSet,
                definition(
                        StringRef.toString(resolvedModuleName),
                        StringRef.toString(resolvedFunctionName),
                        resolvedFinalArity,
                        hasDoBlockOrKeyword
                ),
                implementedProtocolName
        );
    }

    private Stub(StubElement parent,
                 @NotNull IStubElementType elementType,
                 @Nullable StringRef resolvedModuleName,
                 @Nullable StringRef resolvedFunctionName,
                 int resolvedFinalArity,
                 boolean hasDoBlockOrKeyword,
                 @NotNull StringRef name,
                 @NotNull Set<StringRef> canonicalNameSet,
                 @Nullable Definition definition,
                 @Nullable StringRef implementedProtocolName) {
        super(parent, elementType, name);
        this.canonicalNameSet = canonicalNameSet;
        this.hasDoBlockOrKeyword = hasDoBlockOrKeyword;
        this.resolvedFinalArity = resolvedFinalArity;
        this.resolvedFunctionName = resolvedFunctionName;
        this.resolvedModuleName = resolvedModuleName;
        this.definition = definition;
        this.implementedProtocolName = implementedProtocolName;
    }

    /*
     * Instance Methods
     */

    /**
     * These names do not depend on aliases or nested modules.
     *
     * @return the canonical texts of the reference
     * @see PsiReference#getCanonicalText()
     */
    @Override
    @NotNull
    public Set<String> canonicalNameSet() {
        return setStringRefToSetString(canonicalNameSet);
    }

    /**
     * Whether this call has a {@code do} block or a {@code :do} keyword, so it is a macro
     *
     * @return {@code true} if {@link Call#getDoBlock()} is NOT {@code null} or there is a {@code "do"} keyword argument
     * @see org.elixir_lang.psi.impl.call.CallImplKt#keywordArgument(Call, String)
     */
    @Override
    public boolean hasDoBlockOrKeyword() {
        return hasDoBlockOrKeyword;
    }

    /**
     * The final arity that is non-{@code null}.
     *
     * @return {@link Call#resolvedSecondaryArity()} if it is not {@code null}; {@link Call#resolvedPrimaryArity()} if
     *   it is not {@code null}; otherwise, {@code 0}.
     */
    @Override
    public int resolvedFinalArity() {
        return resolvedFinalArity;
    }

    /**
     * @return name of the function/macro after taking into account any imports
     */
    @Override
    @Nullable
    public String resolvedFunctionName() {
        return StringRef.toString(resolvedFunctionName);
    }

    /**
     * @return name of the qualifying module after taking into account any aliases
     */
    @Override
    @Nullable
    public String resolvedModuleName() {
        return StringRef.toString(resolvedModuleName);
    }

    @Nullable
    @Override
    public Definition getDefinition() {
        return definition;
    }

    @Nullable
    @Override
    public String getImplementedProtocolName() {
        return StringRef.toString(implementedProtocolName);
    }
}
