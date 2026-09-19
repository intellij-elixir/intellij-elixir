package org.elixir_lang.psi.impl

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.annotations.RequiresEdt

class TreeReloadTest : BasePlatformTestCase() {
    /**
     * Building a stub asks whether a keyword pair's key is `do`, which quotes a key such as `"a"` and so asks for its
     * line. When the file's tree is being loaded from its stubs, that must not ask the file for its tree again. The
     * `?\` and newline before the key are a newline Elixir did not count before 1.19, so the line also looks up the
     * language level.
     */
    @RequiresEdt
    fun testTreeLoadsFromStubsWhenAQuotedKeyIsAskedForItsLine() {
        val file = myFixture.configureByText(
            "injector.ex",
            "defmodule Injector do\n  defmacro __using__(_opts) do\n    _ = [?\\\n]\n" +
                "    %{\"a\": x} = %{a: quote do\n      def injected(), do: :ok\n    end}\n" +
                "    quote do\n      unquote(x)\n    end\n  end\nend\n"
        ) as PsiFileImpl

        WriteAction.run<Throwable> { file.onContentReload() }

        assertNull("the tree is still loaded, so this test checks nothing", file.treeElement)
        assertNotNull("no stubs to load the tree from, so this test checks nothing", file.stubTree)
        assertNotNull(file.node)
    }
}
