package org.elixir_lang.sdk.elixir

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.sdk.erlang_dependent.Type as ErlangDependentType

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
     */
    @RequiresWriteLock
    fun applyDependencySelection(
        elixirSdk: Sdk,
        erlangSdk: Sdk?,
        suppressOtpMismatchWarning: Boolean? = null,
        preserveExistingWhenRequestedNull: Boolean = false,
    ) {
        ThreadingAssertions.assertWriteAccess()
        mutateAdditionalData(elixirSdk) { data ->
            if (!preserveExistingWhenRequestedNull || erlangSdk != null || data.getErlangSdkName() == null) {
                data.setErlangSdk(erlangSdk)
            }
            if (suppressOtpMismatchWarning != null) {
                data.setSuppressOtpMismatchWarning(suppressOtpMismatchWarning)
            }
        }
        elixirSdk.putUserData(ErlangDependentType.ERLANG_SDK_KEY, erlangSdk)
    }

    /**
     * Pairs the Elixir SDKs in [sdks] that store [previousName] as their Erlang SDK's name with [renamed], under its new
     * name. A pairing that names another SDK in [sdks] by its name and its home is that SDK's, as when another Erlang SDK
     * took the old name and an earlier repair paired its Elixir SDKs under it, and is left.
     */
    @RequiresWriteLock
    fun repairRenamedPairings(sdks: Iterable<Sdk>, renamed: Sdk, previousName: String) {
        ThreadingAssertions.assertWriteAccess()
        for (elixirSdk in sdks) {
            val data = elixirSdk.elixirAdditionalData ?: continue
            if (elixirSdk.sdkType is Type && data.getErlangSdkName() == previousName &&
                sdks.none { it !== renamed && data.names(it) }
            ) {
                applyDependencySelection(elixirSdk, renamed)
            }
        }
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
            ?: (elixirSdk.sdkAdditionalData as? SdkAdditionalData)
            ?: SdkAdditionalData(null, elixirSdk)

        mutate(data)
        modificator.sdkAdditionalData = data
        modificator.commitChanges()
    }
}
