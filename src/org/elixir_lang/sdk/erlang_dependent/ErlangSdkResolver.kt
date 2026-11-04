package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.elixir_lang.sdk.SdkEnvironment
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.erlang.Release
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.knownOrNull
import org.elixir_lang.sdk.wsl.wslCompat
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs


/**
 * Interface allows mocking in tests
 */
interface ErlangSdkResolver {
    fun resolveErlangSdkResult(elixirSdk: Sdk, sdkModel: SdkModel? = null): ErlangSdkResult

    /**
     * Lightweight resolver that returns only the SDK or null, discarding failure details.
     * Use [resolveErlangSdkResult] when you need the reason for a missing dependency.
     */
    fun resolveErlangSdk(elixirSdk: Sdk, sdkModel: SdkModel? = null): Sdk? =
        when (val result = resolveErlangSdkResult(elixirSdk, sdkModel)) {
            is ErlangSdkResult.Success -> result.sdk
            is ErlangSdkResult.Missing -> null
        }

    companion object {
        fun getInstance(): ErlangSdkResolver =
            ApplicationManager.getApplication().getService(ErlangSdkResolver::class.java)

        /**
         * The registered Erlang SDK in [elixirSdk]'s environment that best runs it, for when none has been paired. With
         * [sdkModel], a settings dialog's, its SDKs are the candidates instead, as the dialog's Apply will leave them.
         *
         * Ordered by the OTP the Elixir build was compiled against: that major first, then higher majors, nearest
         * first, then lower majors, nearest first - a newer OTP runs an older build, an older one may not. Within a
         * major the newest release wins. An installation whose version has not been read yet sorts last, and when the
         * Elixir build's own OTP major has not been read the first candidate with a home is taken.
         */
        @RequiresReadLock
        fun bestRegisteredFor(
            elixirSdk: Sdk,
            sdkModel: SdkModel? = null,
            visibleFor: (Sdk) -> (Sdk) -> Boolean = SdkEnvironment::visibleFor,
        ): Sdk? {
            ThreadingAssertions.assertReadAccess()
            val store = SdkVersionsStore.getInstance()
            val candidates = candidatesFor(elixirSdk, sdkModel, visibleFor)
            val elixirOtpMajor = store.elixirVersions(elixirSdk.homePath)?.elixirOtpMajor?.knownOrNull?.toIntOrNull()
                ?: return candidates.firstOrNull()

            return candidates.minWithOrNull(preferredFor(elixirOtpMajor, store))
        }

        /** The registered Erlang SDKs [bestRegisteredFor] chooses between, so a caller can read their homes first. */
        @RequiresReadLock
        fun candidatesFor(
            elixirSdk: Sdk,
            sdkModel: SdkModel? = null,
            visibleFor: (Sdk) -> (Sdk) -> Boolean = SdkEnvironment::visibleFor,
        ): List<Sdk> {
            ThreadingAssertions.assertReadAccess()
            val inSameEnvironment = visibleFor(elixirSdk)
            val erlangSdkType = org.elixir_lang.sdk.erlang.Type.instance
            val erlangSdks = sdkModel?.sdks?.filter { it.sdkType == erlangSdkType }
                ?: ProjectJdkTable.getInstance().getSdksOfType(erlangSdkType)

            return erlangSdks.filter { it.homePath != null && inSameEnvironment(it) }
        }

        private fun preferredFor(elixirOtpMajor: Int, store: SdkVersionsStore): Comparator<Sdk> =
            Comparator { one, other ->
                val oneRelease = releaseOf(one, store)
                val otherRelease = releaseOf(other, store)
                val byMajor = compareValuesBy(
                    oneRelease,
                    otherRelease,
                    { tier(it, elixirOtpMajor) },
                    { distanceFrom(it, elixirOtpMajor) },
                )
                when {
                    byMajor != 0 -> byMajor
                    oneRelease == null || otherRelease == null -> 0
                    else -> otherRelease.compareTo(oneRelease)
                }
            }

        /** Every higher major outranks every lower one, whatever the gap: [distanceFrom] only orders within a tier. */
        private fun tier(release: Release?, elixirOtpMajor: Int): Int {
            val major = release?.otpMajor?.toIntOrNull() ?: return UNREAD

            return if (major >= elixirOtpMajor) AT_OR_ABOVE else BELOW
        }

        /** How far a candidate's major is from the one asked for, either way; only ever compared within a [tier]. */
        private fun distanceFrom(release: Release?, elixirOtpMajor: Int): Int {
            val major = release?.otpMajor?.toIntOrNull() ?: return 0

            return abs(major - elixirOtpMajor)
        }

        private const val AT_OR_ABOVE = 0
        private const val BELOW = 1
        private const val UNREAD = 2

        private fun releaseOf(sdk: Sdk, store: SdkVersionsStore): Release? =
            store.otpRelease(sdk.homePath)
    }
}

/** The Erlang SDK a stored pairing's name names, in [sdkModel] first, then in the table. */
@RequiresReadLock
internal fun erlangSdkNamed(name: String, sdkModel: SdkModel?): Sdk? =
    sdkModel?.sdks?.find { it.name == name && Type.staticIsValidDependency(it) }
        ?: ProjectJdkTable.getInstance().findJdk(name)?.takeIf { Type.staticIsValidDependency(it) }

/**
 * Default implementation.
 *
 * Resolution priority:
 * 1. `erlangSdkHomePath` (stable; survives SDK renames) - WSL-aware path match in ProjectJdkTable
 * 2. `erlangSdkName` (legacy fallback for configs written before home-path was added) - name match;
 *    [ErlangPairingHeal] commits the resolved path to `erlangSdkHomePath` so future lookups use path
 * 3. Both absent -> NOT_CONFIGURED
 */
internal class DefaultErlangSdkResolver : ErlangSdkResolver {
    @RequiresReadLock
    override fun resolveErlangSdkResult(elixirSdk: Sdk, sdkModel: SdkModel?): ErlangSdkResult {
        ThreadingAssertions.assertReadAccess()
        val elixirName = elixirSdk.name
        val additionalData = elixirSdk.elixirAdditionalData
            ?: return ErlangSdkResult.Missing(elixirSdk, MissingErlangSdkReason.NOT_CONFIGURED)

        // Check cache first
        additionalData.getCachedErlangSdk()?.let { cached ->
            if (isValidAndExists(cached, sdkModel)) {
                if (cached.homePath.isNullOrBlank()) {
                    return ErlangSdkResult.Missing(
                        elixirSdk,
                        MissingErlangSdkReason.MISSING_HOME_PATH,
                        cached.name,
                    )
                }
                return ErlangSdkResult.Success(cached)
            }
            logger.debug { "[$elixirName] Cached Erlang SDK '${cached.name}' no longer valid" }
            additionalData.setCachedErlangSdk(null)
        }

        // 1. Path-first lookup (stable; survives renames)
        val configuredHomePath = additionalData.getErlangSdkHomePath()?.takeIf { it.isNotBlank() }
        if (configuredHomePath != null) {
            logger.debug { "[$elixirName] Looking up Erlang SDK by home path: $configuredHomePath" }
            val found = findErlangSdkByHomePath(configuredHomePath, sdkModel)
            if (found != null) {
                logger.debug { "[$elixirName] Found Erlang SDK by home path: ${found.name}" }
                additionalData.setCachedErlangSdk(found)
                if (found.homePath.isNullOrBlank()) {
                    return ErlangSdkResult.Missing(elixirSdk, MissingErlangSdkReason.MISSING_HOME_PATH, found.name)
                }
                return ErlangSdkResult.Success(found)
            }
            logger.debug { "[$elixirName] No Erlang SDK found at home path: $configuredHomePath" }
        }

        // 2. Name-based fallback (legacy configs without erlangSdkHomePath)
        val configuredName = additionalData.getErlangSdkName()?.takeIf { it.isNotBlank() }
            ?: return ErlangSdkResult.Missing(elixirSdk, MissingErlangSdkReason.NOT_CONFIGURED)

        logger.debug { "[$elixirName] Falling back to name lookup: $configuredName" }
        val found = erlangSdkNamed(configuredName, sdkModel)
        if (found != null) {
            logger.debug { "[$elixirName] Found Erlang SDK by name: $configuredName - scheduling a commit of its home path" }
            additionalData.setCachedErlangSdk(found)
            ErlangPairingHeal.schedule(elixirSdk, found)
            if (found.homePath.isNullOrBlank()) {
                return ErlangSdkResult.Missing(elixirSdk, MissingErlangSdkReason.MISSING_HOME_PATH, found.name)
            }
            return ErlangSdkResult.Success(found)
        }

        logger.debug { "[$elixirName] Erlang SDK '$configuredName' not found" }
        val candidate =
            sdkModel?.sdks?.find { it.name == configuredName }
                ?: ProjectJdkTable.getInstance().findJdk(configuredName)
        val reason = when {
            candidate == null -> MissingErlangSdkReason.NOT_FOUND
            !Type.staticIsValidDependency(candidate) -> MissingErlangSdkReason.INVALID_TYPE
            else -> MissingErlangSdkReason.NOT_FOUND
        }

        return ErlangSdkResult.Missing(elixirSdk, reason, configuredName)
    }

    private fun isValidAndExists(sdk: Sdk, sdkModel: SdkModel?): Boolean {
        if (!Type.staticIsValidDependency(sdk)) return false
        val name = sdk.name
        val jdkTable = ProjectJdkTable.getInstance()
        return (sdkModel?.sdks?.any { it.name == name } == true)
            || (jdkTable.findJdk(name) != null)
    }

    @RequiresReadLock
    private fun findErlangSdkByHomePath(homePath: String, sdkModel: SdkModel?): Sdk? {
        ThreadingAssertions.assertReadAccess()
        val fromModel = sdkModel?.sdks?.find { sdk ->
            Type.staticIsValidDependency(sdk) && wslCompat.pathsEqualWslAware(sdk.homePath, homePath)
        }
        if (fromModel != null) return fromModel
        return ProjectJdkTable.getInstance().allJdks.firstOrNull { sdk ->
            Type.staticIsValidDependency(sdk) && wslCompat.pathsEqualWslAware(sdk.homePath, homePath)
        }
    }

    companion object {
        private val logger = Logger.getInstance(DefaultErlangSdkResolver::class.java)
    }
}

/**
 * Commits a pairing the resolver repaired. The resolver runs under a read lock and must not write the live additional
 * data in place: only a modificator commit is saved, and the next commit replaces the live instance.
 */
internal object ErlangPairingHeal {
    private val pending: MutableSet<Sdk> = ConcurrentHashMap.newKeySet()

    fun schedule(elixirSdk: Sdk, erlangSdk: Sdk) {
        // A pairing to an Erlang SDK with no home stores the same blank home, so the next resolution would repair it
        // again, and every repair is a commit.
        if (erlangSdk.homePath.isNullOrBlank()) return
        if (!pending.add(elixirSdk)) return

        val application = ApplicationManager.getApplication()
        application.invokeLater(
            {
                try {
                    WriteAction.run<RuntimeException> { heal(elixirSdk, erlangSdk) }
                } finally {
                    pending.remove(elixirSdk)
                }
            },
            ModalityState.nonModal(),
            // An expired runnable never runs its `finally`, so the expiry condition clears the entry.
            { application.isDisposed.also { expired -> if (expired) pending.remove(elixirSdk) } },
        )
    }

    /**
     * Stores the home of a pairing found by name. A pairing found by home under another name is left as it is: two
     * Erlang SDKs can share a home, and the name says which one was picked. A settings dialog's editable copies are not
     * in the table, and a commit to them would not persist. A pairing changed since the repair was scheduled, for
     * example by the user, is left as it is.
     */
    @RequiresWriteLock
    private fun heal(elixirSdk: Sdk, erlangSdk: Sdk) {
        ThreadingAssertions.assertWriteAccess()
        val sdks = ProjectJdkTable.getInstance().allJdks
        if (sdks.none { it === elixirSdk } || sdks.none { it === erlangSdk }) return

        val data = elixirSdk.elixirAdditionalData ?: return
        val storedHomePath = data.getErlangSdkHomePath()
        val homeMatches = !storedHomePath.isNullOrBlank() &&
            wslCompat.pathsEqualWslAware(storedHomePath, erlangSdk.homePath)
        if (data.getErlangSdkName() != erlangSdk.name || homeMatches) return

        ElixirSdkMutation.applyDependencySelection(elixirSdk, erlangSdk)
    }
}

enum class MissingErlangSdkReason {
    NOT_CONFIGURED,
    NOT_FOUND,
    INVALID_TYPE,
    MISSING_HOME_PATH
}

sealed class ErlangSdkResult {
    data class Success(val sdk: Sdk) : ErlangSdkResult()
    data class Missing(
        val elixirSdkName: String,
        val reason: MissingErlangSdkReason,
        val erlangSdkName: String? = null,
    ) : ErlangSdkResult() {
        constructor(
            elixirSdk: Sdk,
            reason: MissingErlangSdkReason,
            erlangSdkName: String? = null,
        ) : this(elixirSdk.name, reason, erlangSdkName)
    }
}
