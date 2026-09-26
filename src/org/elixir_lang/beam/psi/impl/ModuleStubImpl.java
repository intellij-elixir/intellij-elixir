package org.elixir_lang.beam.psi.impl;

import com.intellij.psi.stubs.StubElement;
import com.intellij.util.io.StringRef;
import org.elixir_lang.beam.psi.Module;
import org.elixir_lang.beam.psi.stubs.ModuleStub;
import org.elixir_lang.beam.psi.stubs.ModuleStubElementTypes;
import org.elixir_lang.psi.Definition;
import org.elixir_lang.psi.call.name.Function;
import org.elixir_lang.psi.stub.call.Deserialized;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

import static org.elixir_lang.psi.call.name.Function.DEFMODULE;
import static org.elixir_lang.psi.call.name.Module.KERNEL;

/**
 * See {@link com.intellij.psi.impl.java.stubs.impl.PsiClassStubImpl}
 */
public class ModuleStubImpl<T extends Module> extends StubbicBase<T> implements ModuleStub<T> {
    private static final int RESOLVED_FINAL_ARITY = 2;
    private static final String RESOLVED_FUNCTION_NAME = DEFMODULE;
    private static final String RESOLVED_MODULE_NAME = KERNEL;

    public ModuleStubImpl(@NotNull StubElement parentStub,
                          @NotNull String name) {
        super(parentStub, ModuleStubElementTypes.MODULE, name);
    }

    public ModuleStubImpl(@NotNull StubElement parentStub,
                          @NotNull Deserialized deserialized) {
        super(parentStub, ModuleStubElementTypes.MODULE, deserialized.name.toString());

        StringRef resolvedModuleName = deserialized.resolvedModuleName;
        assert resolvedModuleName != null;
        assert resolvedModuleName.getString().equals(RESOLVED_MODULE_NAME);

        StringRef resolvedFunctionName = deserialized.resolvedFunctionName;
        assert resolvedFunctionName != null;
        assert resolvedFunctionName.toString().equals(RESOLVED_FUNCTION_NAME);

        assert  deserialized.resolvedFinalArity == RESOLVED_FINAL_ARITY;

        assert  deserialized.hasDoBlockOrKeyword == HAS_DO_BLOCK_OR_KEYWORD;

        Set<StringRef> canonicalNameSet = deserialized.canonicalNameSet;
        assert canonicalNameSet.size() == 1;
        assert canonicalNameSet.iterator().next().toString().equals(this.getName());
    }

    /**
     * Arity of {@code defmodule .. do}.
     *
     * @return 2 (1 for name and 1 for do block)
     */
    @Override
    public int resolvedFinalArity() {
        return RESOLVED_FINAL_ARITY;
    }

    /**
     * @return {@link Function#DEFMODULE}
     */
    @Nullable
    @Override
    public String resolvedFunctionName() {
        return RESOLVED_FUNCTION_NAME;
    }

    /**
     * @return {@link org.elixir_lang.psi.call.name.Module#KERNEL}
     */
    @Nullable
    @Override
    public String resolvedModuleName() {
        return RESOLVED_MODULE_NAME;
    }

    @Nullable
    @Override
    public Definition getDefinition() {
        return Definition.MODULE;
    }

    @Nullable
    @Override
    public String getImplementedProtocolName() {
        return null;
    }
}
