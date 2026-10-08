package org.elixir_lang.psi.stub.type;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.stubs.StubInputStream;
import org.elixir_lang.psi.DefinitionKt;
import org.elixir_lang.psi.ElixirMatchedAtUnqualifiedNoParenthesesCall;
import org.elixir_lang.psi.impl.ElixirMatchedAtUnqualifiedNoParenthesesCallImpl;
import org.elixir_lang.psi.call.SyntacticCall;
import org.elixir_lang.psi.stub.call.Deserialized;
import org.elixir_lang.psi.stub.type.call.Stub;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public class MatchedAtUnqualifiedNoParenthesesCall extends Stub<org.elixir_lang.psi.stub.MatchedAtUnqualifiedNoParenthesesCall, ElixirMatchedAtUnqualifiedNoParenthesesCall> {
    /*
     * Constructors
     */

    public MatchedAtUnqualifiedNoParenthesesCall(@NotNull String debugName) {
        super(debugName);
    }

    /*
     * Instance Methods
     */

    @Override
    public ElixirMatchedAtUnqualifiedNoParenthesesCall createPsi(@NotNull org.elixir_lang.psi.stub.MatchedAtUnqualifiedNoParenthesesCall stub) {
        return new ElixirMatchedAtUnqualifiedNoParenthesesCallImpl(stub, this);
    }

    @Override
    public org.elixir_lang.psi.stub.MatchedAtUnqualifiedNoParenthesesCall createStub(@NotNull SyntacticCall call, StubElement parentStub) {
        return new org.elixir_lang.psi.stub.MatchedAtUnqualifiedNoParenthesesCall(
                parentStub,
                this,
                call.resolvedModuleName(),
                call.functionName(),
                call.resolvedFinalArity(),
                call.hasDoBlockOrKeyword(),
                StringUtil.notNullize(call.name(), "?"),
                call.canonicalNameSet(),
                DefinitionKt.moduleAttributeDefinition(call),
                call.implementedProtocolName()
        );
    }

    @NotNull
    @Override
    public org.elixir_lang.psi.stub.MatchedAtUnqualifiedNoParenthesesCall deserialize(
            @NotNull StubInputStream stubInputStream,
            @Nullable StubElement parentStub
    ) throws IOException {
        Deserialized deserialized = Deserialized.deserialize(stubInputStream);
        return new org.elixir_lang.psi.stub.MatchedAtUnqualifiedNoParenthesesCall(parentStub, this, deserialized);
    }
}
