package org.intellij.erlang.sdk

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.StubSdkType
import java.nio.file.Path

/** Stands in for intellij-erlang's "Erlang SDK" type, which the plugin knows only by this class name. */
class ErlangSdkType : StubSdkType("Erlang SDK") {
    /** As intellij-erlang's: the home's `lib` is the one class root. */
    override fun setupSdkPaths(sdk: Sdk) {
        val lib = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(sdk.homePath!!, "lib"))!!
        SdkFixtures.commit(sdk) {
            removeAllRoots()
            addRoot(lib, OrderRootType.CLASSES)
        }
    }
}
