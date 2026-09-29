package org.elixir_lang.psi.stub.type.call;

import com.intellij.lang.ASTNode;
import com.intellij.psi.stubs.StubOutputStream;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.elixir_lang.module.PutAttribute;
import org.elixir_lang.module.RegisterAttribute;
import org.elixir_lang.psi.*;
import org.elixir_lang.psi.Module;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.psi.call.StubBased;
import org.elixir_lang.psi.stub.call.Deserialized;
import org.elixir_lang.structure_view.element.CallDefinitionHead;
import org.elixir_lang.structure_view.element.CallDefinitionSpecification;
import org.elixir_lang.structure_view.element.Callback;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import static org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall;

public abstract class Stub<Stub extends org.elixir_lang.psi.stub.call.Stub<Psi>,
        Psi extends org.elixir_lang.psi.call.StubBased> extends org.elixir_lang.psi.stub.type.Named<Stub, Psi> {
    /*
     * Static Methods
     */

    public Stub(@NotNull String debugName) {
        super(debugName);
    }

    @RequiresReadLock
    public static boolean isModular(Call call) {
        return Implementation.is(call) || Module.is(call) || Protocol.is(call);
    }

    private boolean hasCanonicalNames(Call call) {
        boolean hasCanonicalNames = false;

        if (call instanceof StubBased) {
            StubBased stubBased = (StubBased) call;

            hasCanonicalNames = stubBased.canonicalNameSet().size() > 0;
        }

        return hasCanonicalNames;
    }

    private boolean hasName(Call call) {
        return call.getName() != null;
    }

    private boolean hasNameOrCanonicalNames(Call call) {
        return hasName(call) || hasCanonicalNames(call);
    }

    private boolean isDelegationCallDefinitionHead(Call call) {
        return CallDefinitionHead.Companion.is(call) && CallDefinitionHead.Companion.enclosingDelegationCall(call) != null;
    }

    /**
     * Nothing here may resolve a reference: this runs during stub building. Changing what it accepts changes the index
     * and needs a stub version bump; a {@code defdelegate} is stubbed through its head instead, by
     * {@link #isDelegationCallDefinitionHead}.
     */
    private boolean isEnclosableByModular(Call call) {
        return CallableDeclaration.INSTANCE.headBindingFormOf(call) == CallableDeclaration.Form.CLAUSE ||
                Callback.Companion.is(call) ||
                CallDefinitionSpecification.Companion.is(call);
    }

    private boolean isNameable(Call call) {
        return isEnclosableByModular(call) || isDelegationCallDefinitionHead(call) || isModular(call) || isQuoted(call);
    }

    private boolean isQuoted(Call call) {
        boolean isQuoted;

        if (ModuleAttribute.isDeclaration(call) || RegisterAttribute.is(call) || PutAttribute.is(call) ||
                Variable.isDeclaration(call)) {
            Call enclosingModularMacroCall = enclosingModularMacroCall(call);

            if (enclosingModularMacroCall != null) {
                isQuoted = QuoteMacro.is(enclosingModularMacroCall);
            } else {
                isQuoted = false;
            }
        } else {
            isQuoted = false;
        }

        return isQuoted;
    }

    @Override
    public void serialize(@NotNull Stub stub, @NotNull StubOutputStream stubOutputStream) throws IOException {
        Deserialized.serialize(stubOutputStream, stub);
    }

    @Override
    public boolean shouldCreateStub(ASTNode node) {
        Call call = (Call) node.getPsi();

        return isNameable(call) && hasNameOrCanonicalNames(call);
    }
}
