package org.elixir_lang.ui.framework.core

import com.intellij.driver.client.Remote

@Remote("com.intellij.openapi.projectRoots.ProjectJdkTable")
internal interface SdkTable {
    fun getInstance(): SdkTable
    fun getAllJdks(): Array<TableSdk>
    fun findJdk(name: String): TableSdk?
}

@Remote("com.intellij.openapi.projectRoots.Sdk")
internal interface TableSdk {
    fun getName(): String
    fun getSdkType(): TableSdkType
    fun getHomePath(): String?
    fun getVersionString(): String?
    fun getSdkAdditionalData(): TableSdkAdditionalData?
    fun getRootProvider(): RootProviderRef
}

@Remote("com.intellij.openapi.projectRoots.SdkTypeId")
internal interface TableSdkType {
    fun getName(): String
}

@Remote("com.intellij.openapi.projectRoots.SdkAdditionalData")
internal interface TableSdkAdditionalData

@Remote("com.intellij.openapi.roots.RootProvider")
internal interface RootProviderRef {
    fun getUrls(rootType: OrderRootTypeRef): Array<String>
}

@Remote("com.intellij.openapi.roots.OrderRootType")
internal interface OrderRootTypesRef {
    fun getAllTypes(): Array<OrderRootTypeRef>
}

@Remote("com.intellij.openapi.roots.OrderRootType")
internal interface OrderRootTypeRef {
    fun name(): String
}
