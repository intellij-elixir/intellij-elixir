package org.elixir_lang.sdk.elixir

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.sdk.erlang_dependent.Type as ErlangDependentType
import org.elixir_lang.sdk.wsl.wslCompat

/**
 * Write-side SDK mutation commands for Elixir SDK additional data.
 *
 * Caller-managed threading: all methods require an already-held write lock.
 */
object ElixirSdkMutation {

    /**
     * Applies dependent Erlang SDK selection and optional warning-suppress flag update
     * in a single committed SDK mutation.
     *
     * @param preserveExistingWhenRequestedNull when true and [erlangSdk] is null, preserves an
     * existing dependent SDK reference instead of clearing it.
     * @return whether the stored Erlang home changed, so [ElixirSdkPathConfigurator.followErlangHome] is owed.
     */
    @RequiresWriteLock
    fun applyDependencySelection(
        elixirSdk: Sdk,
        erlangSdk: Sdk?,
        suppressOtpMismatchWarning: Boolean? = null,
        preserveExistingWhenRequestedNull: Boolean = false,
    ): Boolean {
        ThreadingAssertions.assertWriteAccess()
        var rehomed = false
        mutateAdditionalData(elixirSdk) { data ->
            if (!preserveExistingWhenRequestedNull || erlangSdk != null || data.getErlangSdkName() == null) {
                val storedHome = data.getErlangSdkHomePath()
                data.setErlangSdk(erlangSdk)
                val home = data.getErlangSdkHomePath()
                rehomed = storedHome != home && !wslCompat.pathsEqualWslAware(storedHome, home)
            }
            if (suppressOtpMismatchWarning != null) {
                data.setSuppressOtpMismatchWarning(suppressOtpMismatchWarning)
            }
        }
        // Read and cleared by the set-up that follows every write changing the home; a write keeping it has none.
        if (rehomed) elixirSdk.putUserData(ErlangDependentType.ERLANG_SDK_KEY, erlangSdk)
        return rehomed
    }

    /**
     * Pairs with [erlangSdk] the Elixir SDKs in [sdks] whose stored pairing names it as it was before an edit: by
     * [previousName] when it was renamed, or by its name and [previousHome] when its home moved. The home is the pairing's
     * identity, so a pairing left at the old home would go to whatever Erlang SDK is registered there. A pairing that
     * names another SDK in [sdks] by its name and its home is that SDK's, as when another Erlang SDK took the old name
     * and an earlier repair paired its Elixir SDKs under it, and is left. Every stored pairing is read before any is
     * written. Returns those whose stored home this changes.
     */
    @RequiresWriteLock
    fun repairPairings(sdks: Iterable<Sdk>, erlangSdk: Sdk, previousName: String?, previousHome: String?): List<Sdk> {
        ThreadingAssertions.assertWriteAccess()
        val stale = sdks.filter { elixirSdk ->
            val data = elixirSdk.elixirAdditionalData ?: return@filter false
            val storedName = data.getErlangSdkName()
            (previousName != null && storedName == previousName ||
                storedName == erlangSdk.name && wslCompat.pathsEqualWslAware(data.getErlangSdkHomePath(), previousHome)) &&
                sdks.none { it !== erlangSdk && data.names(it) }
        }
        return stale.filter { applyDependencySelection(it, erlangSdk) }
    }

    /**
     * Persists suppress/unsuppress choice for OTP mismatch warning without changing SDK pairing. An Elixir SDK with no
     * additional data gets some, pairing no Erlang.
     */
    @RequiresWriteLock
    fun setOtpMismatchWarningSuppressed(elixirSdk: Sdk, suppressed: Boolean) {
        ThreadingAssertions.assertWriteAccess()
        mutateAdditionalData(elixirSdk) { data ->
            data.setSuppressOtpMismatchWarning(suppressed)
        }
    }

    @RequiresWriteLock
    private fun mutateAdditionalData(elixirSdk: Sdk, mutate: (SdkAdditionalData) -> Unit) {
        ThreadingAssertions.assertWriteAccess()

        val modificator = elixirSdk.sdkModificator
        val data = (modificator.sdkAdditionalData as? SdkAdditionalData)
            ?: elixirSdk.elixirAdditionalData
            ?: SdkAdditionalData(null, elixirSdk)

        mutate(data)
        modificator.sdkAdditionalData = data
        modificator.commitChanges()
    }
}
