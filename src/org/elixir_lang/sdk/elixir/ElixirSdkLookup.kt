package org.elixir_lang.sdk.elixir

import com.intellij.facet.FacetManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleJdkOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModels
import org.elixir_lang.sdk.ProcessOutput.isSmallIde
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResolver
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResult

/**
 * The result of resolving an Elixir SDK for a module, project, or PSI element.
 *
 * [Ready] carries the Elixir [Sdk] (and, when resolved via [ElixirSdkLookup.resolveWithErlang],
 * a verified Erlang dependency). [MissingElixirSdk] means no Elixir SDK is configured at all.
 * [MissingErlangSdk] means an Elixir SDK was found but its Erlang dependency is absent or broken;
 * the `detail` property carries the typed reason for rich user-facing messages.
 *
 * Use the [sdk] extension property to extract the [Sdk] or null without a `when` expression.
 * Use `sdkOrNotify` (from `org.elixir_lang.notification.setup_sdk`) to fire the appropriate
 * setup-SDK notification and return the SDK or null in one call.
 */
sealed class ElixirSdkResolution {
    data class Ready(val sdk: Sdk) : ElixirSdkResolution()
    data object MissingElixirSdk : ElixirSdkResolution()
    data class MissingErlangSdk(val detail: ErlangSdkResult.Missing) : ElixirSdkResolution()
}

/** Returns the [Sdk] if this is [ElixirSdkResolution.Ready], otherwise null. */
val ElixirSdkResolution.sdk: Sdk? get() = (this as? ElixirSdkResolution.Ready)?.sdk

/**
 * Resolves the most specific Elixir SDK for a given [Module], [Project], or [PsiElement].
 *
 * All overloads require a read lock ([RequiresReadLock]). Callers that are not already inside
 * a platform-provided read action (e.g. background inspection threads) must acquire one
 * explicitly via [com.intellij.openapi.application.ReadAction.nonBlocking].
 *
 * Resolution order for [Module]: module SDK -> project SDK; a small IDE reads only the Facet SDK.
 *
 * Use [resolveWithErlang] when the caller needs to verify the Erlang dependency as well.
 */
object ElixirSdkLookup {
    private val LOG = com.intellij.openapi.diagnostic.logger<ElixirSdkLookup>()

    @RequiresReadLock
    fun mostSpecificSdk(psiElement: PsiElement): Sdk? = resolve(psiElement).sdk

    /** Resolves to [ElixirSdkResolution.Ready] or [ElixirSdkResolution.MissingElixirSdk]. */
    @RequiresReadLock
    fun resolve(module: Module): ElixirSdkResolution {
        ThreadingAssertions.assertReadAccess()
        // Each IDE reads only what its settings can change: a small IDE has no module SDK settings, and IntelliJ IDEA
        // none for the facet, which a small IDE sharing the project leaves behind.
        if (isSmallIde) {
            val facetSdk = facetSdk(module)
            LOG.trace("ElixirSdkLookup.resolve(module='${module.name}'): small IDE, Facet -> '${facetSdk?.name}'")
            return facetSdk?.let(ElixirSdkResolution::Ready) ?: ElixirSdkResolution.MissingElixirSdk
        }
        ownElixirEntry(module)?.let { entry ->
            val sdk = entry.jdk
            LOG.trace("ElixirSdkLookup.resolve(module='${module.name}'): own Elixir module SDK -> '${sdk?.name}'")
            return sdk?.let(ElixirSdkResolution::Ready) ?: ElixirSdkResolution.MissingElixirSdk
        }

        val projResolution = resolve(module.project)
        LOG.trace(
            "ElixirSdkLookup.resolve(module='${module.name}'): fell through to project SDK -> " +
                "'${projResolution.sdk?.name}'"
        )
        return projResolution
    }

    /** The Elixir SDK [module]'s facet supplies: its facet's SDK on a small IDE, and null on IntelliJ IDEA. */
    @RequiresReadLock
    fun facetSdk(module: Module): Sdk? = facet(module)?.sdk

    /** The name [module]'s facet stores for the SDK it supplies, read as [facetSdk] is. */
    @RequiresReadLock
    fun facetSdkName(module: Module): String? = facet(module)?.configuration?.sdkName

    /** [module]'s Elixir facet on a small IDE, the only IDE that reads it. */
    private fun facet(module: Module): Facet? =
        if (isSmallIde) FacetManager.getInstance(module).getFacetByType(Facet.ID) else null

    /** Resolves to [ElixirSdkResolution.Ready] or [ElixirSdkResolution.MissingElixirSdk]. */
    @RequiresReadLock
    fun resolve(psiElement: PsiElement): ElixirSdkResolution {
        ThreadingAssertions.assertReadAccess()
        val project = psiElement.project
        if (project.isDisposed) return ElixirSdkResolution.MissingElixirSdk

        val module = moduleFor(psiElement)
        return if (module != null) resolve(module) else resolve(project)
    }

    /**
     * [ModuleUtilCore.findModuleForPsiElement], or for a file or directory under the roots of an SDK a facet names,
     * the module the platform would pick from those whose facet names it. Those roots come from no library entity,
     * which the platform maps a library file to its modules by.
     */
    @RequiresReadLock
    private fun moduleFor(element: PsiElement): Module? {
        ModuleUtilCore.findModuleForPsiElement(element)?.let { return it }
        if (element !is PsiFileSystemItem || (element is PsiFile && element.context != null)) return null

        val file = element.virtualFile ?: return null
        val project = element.project

        return FacetModels.getInstance(project).modulesHolding(file)
            .minWithOrNull(ModuleManager.getInstance(project).moduleDependencyComparator())
    }

    /** Resolves to [ElixirSdkResolution.Ready] or [ElixirSdkResolution.MissingElixirSdk]. */
    @RequiresReadLock
    fun resolve(project: Project): ElixirSdkResolution {
        ThreadingAssertions.assertReadAccess()
        val sdk = projectSdk(project)
        return if (sdk != null) ElixirSdkResolution.Ready(sdk) else ElixirSdkResolution.MissingElixirSdk
    }

    /**
     * Resolves the Elixir SDK for [module] and verifies its Erlang dependency.
     *
     * Returns [ElixirSdkResolution.Ready], [ElixirSdkResolution.MissingElixirSdk], or
     * [ElixirSdkResolution.MissingErlangSdk]. Use the [sdk] extension property or
     * `sdkOrNotify` (from `org.elixir_lang.notification.setup_sdk`) on the result.
     */
    @RequiresReadLock
    fun resolveWithErlang(module: Module): ElixirSdkResolution {
        ThreadingAssertions.assertReadAccess()
        val elixirSdk = resolve(module).sdk ?: return ElixirSdkResolution.MissingElixirSdk
        return when (val result = ErlangSdkResolver.getInstance().resolveErlangSdkResult(elixirSdk)) {
            is ErlangSdkResult.Success -> ElixirSdkResolution.Ready(elixirSdk)
            is ErlangSdkResult.Missing -> ElixirSdkResolution.MissingErlangSdk(result)
        }
    }

    /**
     * Resolves the Elixir SDK for [psiElement] and verifies its Erlang dependency.
     */
    @RequiresReadLock
    fun resolveWithErlang(psiElement: PsiElement): ElixirSdkResolution {
        ThreadingAssertions.assertReadAccess()
        val elixirSdk = resolve(psiElement).sdk ?: return ElixirSdkResolution.MissingElixirSdk
        return when (val result = ErlangSdkResolver.getInstance().resolveErlangSdkResult(elixirSdk)) {
            is ErlangSdkResult.Success -> ElixirSdkResolution.Ready(elixirSdk)
            is ErlangSdkResult.Missing -> ElixirSdkResolution.MissingErlangSdk(result)
        }
    }

    /**
     * Resolves the Elixir SDK for the module that owns [root], falling back to the first module
     * in the project when no module directly owns the file.
     *
     * Returns null when no module exists or no Elixir SDK is configured.
     */
    @RequiresReadLock
    @RequiresBackgroundThread
    fun resolve(project: Project, root: VirtualFile): Sdk? {
        ThreadingAssertions.assertReadAccess()
        ThreadingAssertions.assertBackgroundThread()
        val module = ModuleUtilCore.findModuleForFile(root, project)
            ?: ModuleManager.getInstance(project).modules.firstOrNull()
            ?: return null
        return resolve(module).sdk
    }

    /**
     * Collects all distinct active Elixir SDKs across all modules in the project.
     *
     * Useful for SDK refresh actions and "does any Elixir SDK exist?" guards.
     */
    @RequiresReadLock
    fun resolveAll(project: Project): Set<Sdk> {
        ThreadingAssertions.assertReadAccess()
        val sdks = mutableSetOf<Sdk>()
        for (module in ModuleManager.getInstance(project).modules) {
            ProgressManager.checkCanceled()
            val sdk = resolve(module).sdk
            if (sdk != null) sdks.add(sdk)
        }
        return sdks
    }

    /**
     * [module]'s own SDK entry when it is Elixir's: its SDK is Elixir-typed, or it has none and stores Elixir's type. The
     * stored type alone won't do, because the entry's SDK is the table's first of that name, whatever its type.
     */
    @RequiresReadLock
    internal fun ownElixirEntry(module: Module): ModuleJdkOrderEntry? =
        ModuleRootManager.getInstance(module).orderEntries.asSequence().filterIsInstance<ModuleJdkOrderEntry>().firstOrNull {
            when (val sdk = it.jdk) {
                null -> it.jdkTypeName == Type.instance.name
                else -> sdk.sdkType === Type.instance
            }
        }

    private fun projectSdk(project: Project): Sdk? =
        ProjectRootManager.getInstance(project).projectSdk?.takeIf { it.sdkType === Type.instance }
}

@RequiresReadLock
@RequiresBackgroundThread
fun findElixirSdkForRoot(project: Project, root: VirtualFile): Sdk? = ElixirSdkLookup.resolve(project, root)
