package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.impl.DependentSdkType
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.sdk.ProcessOutput.isSmallIde
import org.elixir_lang.sdk.SdkHomeChooser
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.Type.Companion.erlangSdkType
import org.jdom.Element
import java.util.function.Consumer
import javax.swing.JComponent

/**
 * An SDK that depends on an Erlang SDK, either
 * * org.intellij.erlang.sdk.ErlangSdkType when intellij-erlang IS installed
 * * org.elixir_lang.sdk.erlang.Type when intellij-erlang IS NOT installed
 */
abstract class Type protected constructor(name: String) : DependentSdkType(name) {
    override fun isValidDependency(sdk: Sdk): Boolean {
        return staticIsValidDependency(sdk)
    }

    override fun getUnsatisfiedDependencyMessage(): String {
        return "Before you can create your first $presentableName you need to select an ${dependencyType.presentableName}."
    }

    override fun getDependencyType(): SdkType {
        return erlangSdkType()
    }

    override fun createAdditionalDataConfigurable(
        sdkModel: SdkModel,
        sdkModificator: SdkModificator
    ): AdditionalDataConfigurable? {
        return null
    }

    override fun isRootTypeApplicable(type: OrderRootType): Boolean {
        // Small IDEs do not register JavadocOrderRootType, so calling getInstance() there throws.
        // documentationRootType() returns null instead. See
        // https://github.com/intellij-elixir/intellij-elixir/issues/976.
        return type === OrderRootType.CLASSES ||
                type === OrderRootType.SOURCES ||
                type === org.elixir_lang.sdk.Type.documentationRootType()
    }

    override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) {
        // intentionally left blank
    }

    /**
     * The Elixir install first, then the Erlang it runs on, from the Erlang step. A pick built for another OTP can go
     * back to choose another Elixir install, opening at one built for the pick's OTP.
     */
    override fun showCustomCreateUI(
        sdkModel: SdkModel, parentComponent: JComponent, selectedSdk: Sdk?,
        sdkCreatedCallback: Consumer<in Sdk?>
    ) {
        val basePath = SdkHomeChooser.defaultBasePath(SdkHomeChooser.projectOf(parentComponent))

        @RequiresEdt
        fun create(preferredOtpMajor: Int?) {
            SdkHomeChooser.selectSdkHome(this, basePath, SdkHomeChooser.registeredHomes(sdkModel, this), preferredOtpMajor) { elixirHome ->
                val sdks = sdkModel.sdks.asList()
                val erlangSdk = ErlangStep.getInstance().ask(parentComponent, elixirHome, sdkModel, chooseAnotherInstall = ::create)
                    ?: return@selectSdkHome
                val elixirSdk = SdkConfigurationUtil.createSdk(sdks, elixirHome, this, null, null)
                // Before the callback's `setupSdkPaths`, which reads the pick from `ERLANG_SDK_KEY`.
                WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, erlangSdk) }
                sdkCreatedCallback.accept(elixirSdk)
            }
        }

        create(null)
    }

    companion object {
        private const val ERLANG_SDK_TYPE_CANONICAL_NAME = "org.intellij.erlang.sdk.ErlangSdkType"
        private val ERLANG_SDK_FOR_ELIXIR_SDK_TYPE_CANONICAL_NAME: String =
            org.elixir_lang.sdk.erlang.Type::class.java.canonicalName

        @JvmStatic
        val ERLANG_SDK_KEY = com.intellij.openapi.util.Key.create<Sdk>("ERLANG_SDK_FOR_ELIXIR")

        fun staticIsValidDependency(sdk: Sdk): Boolean {
            val sdkTypeCanonicalName = sdk.sdkType.javaClass.canonicalName

            return if (isSmallIde) {
                sdkTypeCanonicalName == ERLANG_SDK_FOR_ELIXIR_SDK_TYPE_CANONICAL_NAME
            } else {
                sdkTypeCanonicalName == ERLANG_SDK_TYPE_CANONICAL_NAME ||
                        sdkTypeCanonicalName == ERLANG_SDK_FOR_ELIXIR_SDK_TYPE_CANONICAL_NAME
            }
        }
    }
}
