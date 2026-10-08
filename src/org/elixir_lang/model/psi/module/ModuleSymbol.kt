package org.elixir_lang.model.psi.module

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.model.psi.ElixirRenameTarget
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.call.CanonicallyNamedImpl
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.stripAccessExpression
import java.util.*

/**
 * Symbol representing a module declaration: see [isDeclaration].
 */
@Suppress("UnstableApiUsage")
class ModuleSymbol(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    /**
     * The name as the declaration writes it, which a rename replaces: `Inner` for `A.Inner`. A declaration written
     * as an atom with an alias spelling seeds that spelling: `A.B` for `:"Elixir.A.B"`.
     */
    override val targetName: String = moduleName
) : ElixirRenameTarget, NavigationTarget, SearchTarget {
    override val searchText: String get() = moduleName.substringAfterLast('.')

    override val declarationTextByName: ((String) -> String)?
        get() = textAt(file.viewProvider.contents.subSequence(range.startOffset, range.endOffset).toString(), null)

    override fun newNameRefusal(newName: String): String? {
        val accepted = when {
            isAlias(targetName) -> isAlias(newName)
            targetName.startsWith(":") -> isAlias(newName) || isAtomLiteral(newName)
            else -> true
        }

        return if (accepted) null else "$newName is not a module name"
    }

    override fun createPointer(): Pointer<out ModuleSymbol> {
        val moduleName = this.moduleName
        val targetName = this.targetName
        // Anchor to the enclosing declaring call (a stable ancestor) rather than to the
        // name-identifier element or a bare file range: an in-place (Shift+F6) rename fully replaces
        // the identifier's text, which swaps out the identifier leaf (collapsing a pointer anchored to
        // it) and collapses a plain range marker to an empty range - either way the subsequent
        // programmatic commit edits the wrong range and applies nothing. The declaring call survives
        // the identifier replacement, so its name-element range is recomputed correctly on restore.
        val modular = generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { isDeclaration(it) && moduleNameElement(it)?.textRange == range }
        if (modular != null) {
            val modularPointer = SmartPointerManager.getInstance(file.project)
                .createSmartPsiElementPointer(modular, file)
            return Pointer {
                val restoredModular = modularPointer.dereference() ?: return@Pointer null
                val restoredRange = moduleNameElement(restoredModular)?.textRange
                    ?: return@Pointer null
                ModuleSymbol(restoredModular.containingFile, restoredRange, moduleName, targetName)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            ModuleSymbol(restoredFile, restoredRange, moduleName, targetName)
        }
    }

    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

    override val maximalSearchScope: SearchScope? get() = null

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler(moduleName)

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(moduleName)
            .icon(AllIcons.Nodes.Module)
            .presentation()

    override fun equals(other: Any?): Boolean =
        other is ModuleSymbol && other.moduleName == moduleName

    override fun hashCode(): Int = Objects.hash(moduleName)

    override fun toString(): String = "ModuleSymbol($moduleName)"

    companion object {
        /**
         * Whether [call]'s first argument names the module it declares. A `defimpl` is not one: its first
         * argument refers to the protocol.
         */
        @RequiresReadLock
        fun isDeclaration(call: Call): Boolean = Module.`is`(call) || Protocol.`is`(call)

        @RequiresReadLock
        fun fromModular(call: Call): ModuleSymbol? {
            if (!isDeclaration(call)) return null
            val nameElement = moduleNameElement(call) ?: return null
            val written = moduleNameText(call) ?: return null
            val moduleName = CanonicallyNamedImpl.canonicalName(SyntacticCall.of(call))
                ?.takeUnless { org.elixir_lang.Module.atom(it) == null }
                ?: written.removeElixirPrefix()
            val targetName = if (written.startsWith(":")) {
                org.elixir_lang.Module.inspect(moduleName).takeIf(::isAlias) ?: written
            } else {
                written.removeElixirPrefix()
            }

            // For a `defmodule` in a decompiled `.beam` mirror, containingFile is the in-memory mirror;
            // originalFile is the navigable compiled `.beam` whose editor shows the decompiled text at
            // the same offsets (same anchoring as TypeSymbol/FunctionSymbol). For source files,
            // originalFile is the file itself.
            val declarationFile = call.containingFile.originalFile

            return ModuleSymbol(declarationFile, nameElement.textRange, moduleName, targetName)
        }

        @RequiresReadLock
        fun moduleNameElement(call: Call): PsiElement? {
            val firstArgument = call.primaryArguments()
                ?.firstOrNull()
                ?: call.finalArguments()?.firstOrNull()
                ?: return null
            val stripped = firstArgument.stripAccessExpression()

            return when (stripped) {
                is QualifiableAlias -> stripped
                is Call -> stripped.functionNameElement() ?: stripped
                else -> stripped
            }
        }

        @RequiresReadLock
        fun moduleNameText(call: Call): String? {
            val firstArgument = call.primaryArguments()
                ?.firstOrNull()
                ?: call.finalArguments()?.firstOrNull()
                ?: return null
            val stripped = firstArgument.stripAccessExpression()

            return when (stripped) {
                is QualifiableAlias -> stripped.fullyQualifiedName()
                else -> stripped.text
            }
        }

        private fun String.removeElixirPrefix(): String =
            if (startsWith(ELIXIR_HEAD)) removePrefix(ELIXIR_HEAD) else this

        private const val ELIXIR_HEAD = "Elixir."

        /** Whether `Elixir.` and [name] make an alias. */
        private fun isAlias(name: String): Boolean = InspectAtom.classify("$ELIXIR_HEAD$name") == InspectAtom.Class.ALIAS

        /** Whether [name] is `:` and an atom, written as `inspect` writes an atom that is not an alias. */
        private fun isAtomLiteral(name: String): Boolean =
            name.startsWith(":") && InspectAtom.literal(name.substring(1)) == name

        /** [name] with the one `Elixir.` head that makes an alias absolute. */
        private fun absolute(name: String): String = if (name.startsWith(ELIXIR_HEAD)) name else "$ELIXIR_HEAD$name"

        /**
         * The text a rename writes, for a new name, at a place that writes this module's name as [written], or `null`
         * where the new name goes in as typed. [current] is what the usage query makes of the place relative to the
         * qualifier around it, or `null`.
         *
         * A new name that starts with `Elixir.` is absolute, so it is written as typed wherever the whole name is
         * written. A place written absolutely stays absolute, and an atom declaration stays an atom: given an alias,
         * it is written as the atom of the alias's absolute form.
         */
        fun textAt(written: String, current: ((String) -> String)?): ((String) -> String)? =
            when {
                written.startsWith(":") -> { newName ->
                    if (newName.startsWith(":")) newName else ":\"${InspectAtom.escape(absolute(newName))}\""
                }

                written.startsWith(ELIXIR_HEAD) -> { newName ->
                    if (newName.startsWith(ELIXIR_HEAD)) newName else absolute(current?.invoke(newName) ?: newName)
                }

                current != null -> { newName -> if (newName.startsWith(ELIXIR_HEAD)) newName else current(newName) }
                else -> null
            }
    }
}
