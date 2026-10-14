package org.elixir_lang.snapshot

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed

/** A module's sorted canonical [names], and each definition [clauses] the resolver sees in it. */
data class ModuleDefinitions(val names: List<String>, val clauses: List<Clause>) {
    /** A definition clause and its declaration, `null` when the plugin reads none from it. */
    data class Clause(val call: Call, val declaration: Declaration?)
}

@RequiresReadLock
fun definitions(modular: Call): ModuleDefinitions =
    ModuleDefinitions(
        (modular as? CanonicallyNamed)?.canonicalNameSet().orEmpty().sorted(),
        Modular.callDefinitionClauseCallSequence(modular).map { clause ->
            ModuleDefinitions.Clause(clause, CallDefinitionClause.declaration(clause, ResolveState.initial()))
        }.toList()
    )
