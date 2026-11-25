package org.elixir_lang.beam.psi.stubs;

import com.intellij.psi.stubs.StubElement;
import org.elixir_lang.beam.psi.Module;
import org.elixir_lang.psi.stub.call.Stubbic;
import org.jetbrains.annotations.NotNull;

public interface ModuleStub<T extends Module> extends Stubbic, StubElement<T> {
    @NotNull
    String getName();

    /**
     * Whether the {@code .beam}'s types were read into its type stubs; where not, which types it defines isn't known.
     */
    boolean typesRead();
}
