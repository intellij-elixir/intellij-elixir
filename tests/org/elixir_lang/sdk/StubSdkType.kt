package org.elixir_lang.sdk

import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import org.jdom.Element

/** An SDK type named [name] that takes any home and has no additional data, for a test that needs a type of its own. */
open class StubSdkType(name: String) : SdkType(name) {
    override fun suggestHomePath(): String? = null

    override fun isValidSdkHome(path: String): Boolean = true

    override fun suggestSdkName(currentSdkName: String?, sdkHome: String): String = sdkHome

    override fun createAdditionalDataConfigurable(
        sdkModel: SdkModel,
        sdkModificator: SdkModificator,
    ): AdditionalDataConfigurable? = null

    override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) {}

    override fun getPresentableName(): String = name
}
