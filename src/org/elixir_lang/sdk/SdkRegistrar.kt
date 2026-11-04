package org.elixir_lang.sdk

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.erlang_dependent.resolveErlangSdkOrNullAndNotify
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

object SdkRegistrar {
    /**
     * Canonicalises [homePath], resolves the version string and SDK name, and builds an SDK
     * template for registration. Returns null if the path or version cannot be resolved.
     * Shared by both the suspend ([registerOrUpdateErlangSdk]) and synchronous
     * ([org.elixir_lang.sdk.elixir.ElixirInternalErlangSdkSetup.registerErlangSdk]) registration paths.
     */
    internal fun prepareErlangSdk(
        homePath: String,
        resolvedVersion: String? = null,
        alreadyCanonical: Boolean = false,
    ): PreparedErlangSdk? {
        val canonicalHomePath = if (alreadyCanonical) homePath else canonicalHomePath(homePath) ?: return null
        val sdkType = ErlangSdkType.instance
        // The suspend path has filled the store already; the synchronous one arrives with nothing read.
        if (!alreadyCanonical) SdkVersionsFiller.fillIfUnreadBlocking(canonicalHomePath)
        val release = SdkVersionsStore.getInstance().otpRelease(canonicalHomePath)
        val versionString =
            ErlangSdkType.versionStringForHome(canonicalHomePath, resolvedVersion, release) ?: return null
        val sdkName = ErlangSdkType.suggestSdkNameForHome(canonicalHomePath, resolvedVersion, release)
        return PreparedErlangSdk(ProjectJdkImpl(sdkName, sdkType, canonicalHomePath, versionString))
    }

    internal class PreparedErlangSdk(val template: ProjectJdkImpl)

    suspend fun registerOrUpdateErlangSdk(
        homePath: String,
        resolvedVersion: String? = null,
    ): Sdk? {
        val canonicalHomePath = canonicalHomePath(homePath) ?: return null
        SdkVersionsFiller.fillCanonical(canonicalHomePath)
        val prepared = prepareErlangSdk(canonicalHomePath, resolvedVersion, alreadyCanonical = true) ?: return null
        val registeredSdk = edtWriteAction {
            registerOrUpdatePreparedErlangSdk(prepared, ProjectJdkTable.getInstance())
        }
        ErlangSdkType.instance.setupSdkPaths(registeredSdk)
        return registeredSdk
    }

    @RequiresBackgroundThread
    suspend fun registerOrUpdateElixirSdk(
        homePath: String,
        erlangSdk: Sdk?,
        resolvedVersion: String? = null,
        project: Project? = null,
    ): Sdk? {
        val canonicalHomePath = canonicalHomePath(homePath) ?: return null
        val sdkType = ElixirSdkType.instance
        SdkVersionsFiller.fillCanonical(canonicalHomePath)

        val versions = SdkVersionsStore.getInstance().elixirVersions(canonicalHomePath)
        val elixirVersion = resolvedVersion ?: versions?.elixirVersion

        // Fast path: confirmed exact match - erlangSdkHomePath is persisted and Erlang home matches.
        // Capture whether erlangSdkName is stale in the same read action as the lookup, so a rename can be synced
        // under a write action without an extra round-trip.
        // Both allJdks access and sdkAdditionalData reads require a read lock.
        val confirmedResult: Pair<Sdk, Boolean>? = readAction {
            val sdk = findElixirSdkByVariant(canonicalHomePath, erlangSdk)?.takeIf {
                (it.sdkAdditionalData as? SdkAdditionalData)?.getErlangSdkHomePath() != null
            } ?: return@readAction null
            val data = sdk.sdkAdditionalData as? SdkAdditionalData
            sdk to (data?.getErlangSdkName() != erlangSdk?.name)
        }
        if (confirmedResult != null) {
            val (confirmedExisting, nameIsStale) = confirmedResult
            // Sync stale erlangSdkName under a write action when the Erlang SDK was renamed after
            // pairing. Home path still matches, so no re-registration is needed.
            if (nameIsStale && erlangSdk != null) {
                edtWriteAction { ElixirSdkMutation.applyDependencySelection(confirmedExisting, erlangSdk) }
            }
            return confirmedExisting
        }

        // Slow path: no confirmed match. Re-check under a read lock for a legacy SDK (present but
        // missing persisted erlangSdkHomePath) so we can update it in place rather than duplicating.
        val existing = readAction { findElixirSdkByVariant(canonicalHomePath, erlangSdk) }

        // Compute version/name only when a write will be needed (new SDK or legacy update).
        val erlangHome = erlangSdk?.homePath
        erlangHome?.let { SdkVersionsFiller.fillIfUnread(it) }
        val versionString =
                ElixirSdkType.versionStringForHome(canonicalHomePath, elixirVersion, versions) ?: return null
        val sdkName = ElixirSdkType.suggestSdkNameFromStore(canonicalHomePath, elixirVersion, erlangHome)

        // Perform SDK registration + Erlang attachment atomically so the SDK is never visible
        // in a half-configured state (added to table but Erlang dependency not yet attached).
        val registeredSdk = edtWriteAction {
            val table = ProjectJdkTable.getInstance()
            val updatedSdk = ProjectJdkImpl(sdkName, sdkType, canonicalHomePath, versionString)
            // Legacy SDK matched (erlangSdkHomePath not yet persisted) is updated in-place via
            // registerOrUpdate rather than creating a duplicate.
            val sdk = registerOrUpdate(existing, updatedSdk, table)

            // Attach Erlang dependency - done inside the same write action for atomicity
            ElixirSdkMutation.applyDependencySelection(
                elixirSdk = sdk,
                erlangSdk = erlangSdk,
                preserveExistingWhenRequestedNull = true,
            )
            sdk
        }

        readAction { registeredSdk.resolveErlangSdkOrNullAndNotify(sdkModel = null, project = project) }
        sdkType.setupSdkPaths(registeredSdk)
        return registeredSdk
    }

    private fun canonicalHomePath(homePath: String?): String? =
            wslCompat.canonicalizePathNullable(homePath)

    private fun findSdkByHomePath(sdkType: SdkTypeId, homePath: String, table: ProjectJdkTable): Sdk? {
        ThreadingAssertions.assertReadAccess()
        return table.allJdks.firstOrNull { sdk ->
            sdk.sdkType == sdkType && wslCompat.pathsEqualWslAware(sdk.homePath, homePath)
        }
    }

    @RequiresWriteLock
    internal fun registerOrUpdatePreparedErlangSdk(
        prepared: PreparedErlangSdk,
        table: ProjectJdkTable,
    ): Sdk {
        ThreadingAssertions.assertWriteAccess()
        val template = prepared.template
        val existing = template.homePath?.let { findSdkByHomePath(template.sdkType, it, table) }
        return registerOrUpdate(existing, template, table)
    }

    @RequiresWriteLock
    private fun registerOrUpdate(
        existing: Sdk?,
        updatedSdk: Sdk,
        table: ProjectJdkTable,
    ): Sdk {
        ThreadingAssertions.assertWriteAccess()

        return if (existing == null) {
            table.addJdk(updatedSdk)
            updatedSdk
        } else {
            table.updateJdk(existing, updatedSdk)
            existing
        }
    }

    /**
     * Finds an existing Elixir SDK that matches the given home path AND Erlang home path
     * (via [sameErlangHome]).
     *
     * Variant identity is `(elixirHomePath, erlangSdkHomePath)`. A given Elixir home path
     * physically determines its compiled-against OTP major (only one OTP build can exist at a
     * path), so no separate OTP-major check is required. Two projects can share the same Elixir
     * install path but be paired with different Erlang SDK patch versions (e.g. 24.1.2.3 vs
     * 24.5.6.7); [sameErlangHome] distinguishes those as separate variants.
     *
     * [sameErlangHome] treats legacy SDKs (no persisted `erlangSdkHomePath`, but a name is stored)
     * as name-consistent matches: the stored Erlang SDK name must equal the requested SDK's name.
     * The caller distinguishes confirmed vs. legacy matches by checking whether `erlangSdkHomePath`
     * is non-null on the returned SDK.
     *
     * Returns the first match in [sdks], or null if none exists.
     */
    internal fun findElixirSdkByVariant(sdks: Iterable<Sdk>, homePath: String, erlangSdk: Sdk?): Sdk? =
        sdks.firstOrNull { sdk -> onInstall(sdk, homePath) && sameErlangHome(sdk, erlangSdk) }

    /** [sdk] is an Elixir SDK on the install at [homePath]: the variant rule's first half. */
    internal fun onInstall(sdk: Sdk, homePath: String): Boolean =
        sdk.sdkType is ElixirSdkType && wslCompat.pathsEqualWslAware(sdk.homePath, homePath)

    private fun findElixirSdkByVariant(homePath: String, erlangSdk: Sdk?): Sdk? {
        ThreadingAssertions.assertReadAccess()
        return findElixirSdkByVariant(ProjectJdkTable.getInstance().allJdks.asList(), homePath, erlangSdk)
    }

    private fun sameErlangHome(sdk: Sdk, erlangSdk: Sdk?): Boolean {
        val additionalData = sdk.sdkAdditionalData as? SdkAdditionalData
        val persistedHome = additionalData?.getErlangSdkHomePath()
        val requestedHome = erlangSdk?.homePath
        return when {
            persistedHome == null && requestedHome == null -> true
            // Legacy SDK: home path not yet persisted but a name is stored. Require the stored
            // name to match the requested SDK's name - this prevents an Elixir SDK already paired
            // with Erlang 24 from absorbing a registration request for Erlang 25, which would
            // recreate the variant-collapse bug Problem 5 fixed for modern (home-path-persisted)
            // SDKs. Branch 1 already handles the erlangSdk == null case (requestedHome == null),
            // so when this branch is evaluated, erlangSdk is always non-null.
            persistedHome == null && additionalData?.getErlangSdkName() != null ->
                additionalData.getErlangSdkName() == erlangSdk?.name

            persistedHome == null || requestedHome == null -> false
            else -> wslCompat.pathsEqualWslAware(persistedHome, requestedHome)
        }
    }
}
