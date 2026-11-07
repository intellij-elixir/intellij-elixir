package org.elixir_lang.sdk.elixir

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.erlang_dependent.Type.Companion.ERLANG_SDK_KEY
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.util.ReadActions

/**
 * Links an Elixir SDK being set up to the Erlang SDK it was created with or is already paired with. It never picks one
 * itself: an Elixir SDK with neither is set up unpaired.
 */
object ElixirInternalErlangSdkSetup {

    private val LOG = Logger.getInstance(ElixirInternalErlangSdkSetup::class.java)

    /**
     * The Erlang SDK the create flow put in [ERLANG_SDK_KEY], else the one [elixirSdk]'s stored pairing resolves to,
     * looked up in [sdkModel] before ProjectJdkTable so a dependency chosen in the same dialog resolves before it is
     * committed. With neither, [elixirSdk] is left unpaired.
     */
    internal fun configureInternalErlangSdk(
        elixirSdk: Sdk,
        elixirSdkModificator: SdkModificator,
        sdkModel: SdkModel?,
    ) {
        val existingErlangSdk = elixirSdk.elixirAdditionalData?.let { data -> ReadActions.compute { data.getErlangSdk(sdkModel) } }
        val explicitErlangSdk = elixirSdk.getUserData(ERLANG_SDK_KEY)

        LOG.trace { "[${elixirSdk.name}] configureInternalErlangSdk: explicitErlangSdk=${explicitErlangSdk?.name}, existingErlangSdk=${existingErlangSdk?.name}" }

        val erlangSdk = explicitErlangSdk ?: existingErlangSdk

        if (erlangSdk != null) {
            LOG.trace { "[${elixirSdk.name}] Using Erlang SDK '${erlangSdk.name}' (identity: ${System.identityHashCode(erlangSdk)})" }

            if (existingErlangSdk == null) {
                elixirSdkModificator.sdkAdditionalData = SdkAdditionalData(erlangSdk, elixirSdk)
            }

            ElixirErlangClasspath.addNewCodePathsFromInternErlangSdk(elixirSdk, erlangSdk, elixirSdkModificator)

            elixirSdk.putUserData(ERLANG_SDK_KEY, null)
        } else {
            LOG.info("No Erlang SDK is paired with Elixir SDK '${elixirSdk.name}'; it is set up unpaired")
        }
    }
}
