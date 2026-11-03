package org.elixir_lang.ui.framework.core

import com.intellij.driver.client.Remote

@Remote("com.intellij.openapi.projectRoots.ProjectJdkTable")
internal interface SdkTable {
    fun getInstance(): SdkTable
    fun getAllJdks(): Array<TableSdk>
}

@Remote("com.intellij.openapi.projectRoots.Sdk")
internal interface TableSdk {
    fun getSdkType(): TableSdkType
}

@Remote("com.intellij.openapi.projectRoots.SdkTypeId")
internal interface TableSdkType {
    fun getName(): String
}
