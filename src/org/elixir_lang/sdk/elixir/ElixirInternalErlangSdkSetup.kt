package org.elixir_lang.sdk.elixir

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResolver
import org.elixir_lang.sdk.erlang_dependent.ErlangStep
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.erlang_dependent.Type.Companion.ERLANG_SDK_KEY
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.util.ReadActions
import java.awt.Component

/**
 * Links an Elixir SDK being set up to the Erlang SDK it was created with or is already paired with. It never picks one
 * itself: an Elixir SDK with neither is set up unpaired, and [erlangSdkOrAsk] asks the user where the pick is known.
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
        sdkModel: SdkModel? = null,
    ) {
        val existingAdditionalData = elixirSdk.elixirAdditionalData
        val existingErlangSdk = existingAdditionalData?.let { data -> ReadActions.compute { data.getErlangSdk(sdkModel) } }
        val explicitErlangSdk = elixirSdk.getUserData(ERLANG_SDK_KEY)

        LOG.trace { "[${elixirSdk.name}] configureInternalErlangSdk: explicitErlangSdk=${explicitErlangSdk?.name}, existingErlangSdk=${existingErlangSdk?.name}" }

        val erlangSdk = explicitErlangSdk ?: existingErlangSdk

        if (erlangSdk != null) {
            LOG.trace { "[${elixirSdk.name}] Using Erlang SDK '${erlangSdk.name}' (identity: ${System.identityHashCode(erlangSdk)})" }

            if (existingAdditionalData == null || existingErlangSdk == null) {
                val sdkAdditionData: com.intellij.openapi.projectRoots.SdkAdditionalData =
                    SdkAdditionalData(erlangSdk, elixirSdk)
                elixirSdkModificator.sdkAdditionalData = sdkAdditionData
            }

            ElixirErlangClasspath.addNewCodePathsFromInternErlangSdk(elixirSdk, erlangSdk, elixirSdkModificator)

            elixirSdk.putUserData(ERLANG_SDK_KEY, null)
        } else {
            LOG.info("No Erlang SDK is paired with Elixir SDK '${elixirSdk.name}'; it is set up unpaired")
        }
    }

    /**
     * The Erlang SDK [elixirSdk], an Elixir SDK in [sdksModel], is paired with. When it has none, the Erlang step asks
     * for one, over [parent]: [elixirSdk] is paired with the pick and set up again, or on Cancel removed from
     * [sdksModel]. When [elixirSdk] is one already in the table, the step asks for it as the Elixir SDK being re-paired,
     * and Cancel leaves it. Null on Cancel.
     */
    @RequiresEdt
    internal fun erlangSdkOrAsk(
        sdksModel: ProjectSdksModel,
        elixirSdk: Sdk,
        parent: Component? = null,
    ): Sdk? {
        ErlangSdkResolver.getInstance().resolveErlangSdk(elixirSdk, sdksModel)?.let { return it }

        val asking = elixirSdk.takeIf { ProjectJdkTable.getInstance().findJdk(it.name) != null }
        val pick = elixirSdk.homePath?.let { ErlangStep.getInstance().ask(parent, it, sdksModel, asking) }
        if (pick == null) {
            // Only an SDK the pick added: removing a table SDK's copy would delete it on the model's next apply.
            if (asking == null) sdksModel.removeSdk(elixirSdk)
            return null
        }

        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, pick) }
        Type.instance.setupSdkPaths(elixirSdk, sdksModel)
        return pick
    }
}
