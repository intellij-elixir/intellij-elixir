package org.elixir_lang.beam.psi.stubs

import com.intellij.psi.stubs.Stub

/** The call definitions of every module under a `.beam`'s file stub. */
fun callDefinitionStubs(root: Stub): List<CallDefinitionStub<*>> =
    root.childrenStubs.flatMap { module -> module.childrenStubs.filterIsInstance<CallDefinitionStub<*>>() }
