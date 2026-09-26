package org.elixir_lang.psi.stub.impl;

import com.intellij.psi.stubs.PsiFileStubImpl;
import org.elixir_lang.beam.psi.stubs.ElixirFileStub;
import org.elixir_lang.beam.psi.stubs.ModuleStubElementTypes;
import org.elixir_lang.psi.ElixirFile;
import org.elixir_lang.psi.call.CanonicallyNamed;
import org.jetbrains.annotations.NotNull;
import com.intellij.psi.stubs.IStubElementType;
import org.jetbrains.annotations.Nullable;

public class ElixirFileStubImpl extends PsiFileStubImpl<ElixirFile> implements ElixirFileStub {
    public ElixirFileStubImpl() {
        super(null);
    }

    @NotNull
    @Override
    public CanonicallyNamed[] modulars() {
        return getChildrenByType(ModuleStubElementTypes.MODULE, CanonicallyNamed[]::new);
    }

    // Only silences javac, which reports `PsiFileStubImpl`'s raw return type as an unchecked override. The platform
    // does not call it: `PsiFileStubImpl.getStubSerializer` answers null itself.
    @Override
    public @Nullable IStubElementType<?, ?> getStubType() {
        return null;
    }
}
