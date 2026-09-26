package org.elixir_lang.eex.file.psi;

import com.intellij.psi.stubs.PsiFileStubImpl;
import com.intellij.psi.tree.IStubFileElementType;
import org.elixir_lang.eex.file.ElementType;
import org.elixir_lang.eex.File;
import org.jetbrains.annotations.NotNull;
import com.intellij.psi.stubs.IStubElementType;
import org.jetbrains.annotations.Nullable;

public class Stub extends PsiFileStubImpl<File> {
    public Stub(File file) {
        super(file);
    }

    @NotNull
    @Override
    public IStubFileElementType<?> getType() {
        return ElementType.INSTANCE;
    }

    // See `org.elixir_lang.psi.stub.impl.ElixirFileStubImpl.getStubType`.
    @Override
    public @Nullable IStubElementType<?, ?> getStubType() {
        return null;
    }
}
