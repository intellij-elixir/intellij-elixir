package org.elixir_lang.model.psi.protocol

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.ArityNamed
import org.elixir_lang.psi.impl.nameTextRange
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import java.util.*

/**
 * Symbol representing a single `def`/`defmacro` clause declared inside a `defprotocol`.
 *
 * "Usages" are the **call sites** that dispatch to it (`Protocol.function(args)` of matching
 * name/arity) plus the implementing `def`/`defmacro` clauses inside each `defimpl`, computed by
 * `ElixirSymbolUsageSearcher`. Rename updates the implementations so the protocol member and its
 * implementations stay in sync; for Find Usages/navigation the implementations are also reachable
 * via "Go To Implementation" (`Ctrl+Alt+B`) / the gutter marker.
 *
 * NOTE: this is intentionally a regular class, not a `data class` - [equals]/[hashCode] are by
 * semantic identity `(protocolName, name, arity, macro)` and must NOT include [file]/[range].
 */
@Suppress("UnstableApiUsage")
class ProtocolFunction(
    override val file: PsiFile,
    override val range: TextRange,
    val protocolName: String,
    val name: String,
    override val arity: Int,
    val macro: Boolean
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget, ArityNamed {

    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out ProtocolFunction> {
        val protocolName = this.protocolName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        return FunctionSymbol.declarationPointer(file, range) { restoredFile, restoredRange ->
            ProtocolFunction(restoredFile, restoredRange, protocolName, name, arity, macro)
        }
    }

    // --- NavigationTarget ---
    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

    // --- SearchTarget ---
    override val maximalSearchScope: SearchScope? get() = null

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler("$name/$arity")

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder("$protocolName.$name/$arity")
            .icon(AllIcons.Nodes.AbstractMethod)
            .presentation()

    override fun equals(other: Any?): Boolean =
        other is ProtocolFunction &&
            other.protocolName == protocolName &&
            other.name == name &&
            other.arity == arity &&
            other.macro == macro

    override fun hashCode(): Int = Objects.hash(protocolName, name, arity, macro)

    override fun toString(): String = "ProtocolFunction($protocolName.$name/$arity, macro=$macro)"

    companion object {
        /** The [ProtocolFunction]s a clause directly inside a `defprotocol` declares, one per arity. */
        @RequiresReadLock
        fun fromClause(clause: Call): List<ProtocolFunction> {
            val named = CallableDeclaration.named(clause)
                ?.takeIf { it.form == CallableDeclaration.Form.CLAUSE && Protocol.`is`(it.modular) }
                ?: return emptyList()
            val range = nameTextRange(named.nameElement)

            return named.declarations.flatMap { declaration ->
                declaration.arityInterval?.closed()?.map { arity ->
                    ProtocolFunction(clause.containingFile.originalFile, range, named.moduleName, declaration.name, arity, named.compileTime)
                }.orEmpty()
            }
        }

        /** What [clause] declares at [arity], which is what a use at that arity names. */
        @RequiresReadLock
        fun at(clause: Call, arity: Int): List<ProtocolFunction> = fromClause(clause).filter { it.namedAt(arity) }
    }
}
