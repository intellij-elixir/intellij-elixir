package org.elixir_lang.mix

import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.package_manager.DepsStatusResult
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot

class PackageManagerTest : LightTestCase() {
    /** A project with no Elixir SDK yet is not a failed check: the missing SDK is reported where it can be set up. */
    fun testWithNoElixirSdkTheDepsAreNotChecked() {
        val mixExs = myFixture.addFileToProject("mix.exs", "defmodule App.MixProject do\nend\n").virtualFile

        assertEquals(DepsStatusResult.Unsupported, PackageManager().depsStatus(project, mixExs, null))
    }

    /** Nor is an Elixir SDK with no Erlang SDK: building the command line has already said so where it can be set up. */
    fun testWithNoErlangSdkTheDepsAreNotChecked() {
        val mixExs = myFixture.addFileToProject("mix.exs", "defmodule App.MixProject do\nend\n").virtualFile
        val sdk = SdkFixtures.register(SdkFixtures.elixirSdk("Deps Elixir Without Erlang", fakeHome("elixir/deps")), testRootDisposable)

        assertEquals(DepsStatusResult.Unsupported, onPooledThread { PackageManager().depsStatus(project, mixExs, ElixirSdkSnapshot.of(sdk)) })
    }
}
