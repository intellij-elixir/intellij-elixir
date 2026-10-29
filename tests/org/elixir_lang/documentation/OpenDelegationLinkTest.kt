package org.elixir_lang.documentation

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.psi.call.Call

class OpenDelegationLinkTest : HeavyTestCase() {
    fun testALinkThroughAnOpenDelegationOpensTheTargetInTheLinkingModule() {
        val context = contextOf(
            """
            def open(a, b), do: {a, b}
            """.trimIndent()
        )

        assertEquals("def open(a, b), do: {a, b}", DocumentationLink.element("OpenDelegator.open/4", context)?.text)
    }

    fun testALinkThroughAnOpenDelegationToSeveralTargetsOpensNothing() {
        val context = contextOf(
            """
            def open(a, b), do: {a, b}
            def open(a, b, c), do: {a, b, c}
            def open(a, b, c, d), do: {a, b, c, d}
            """.trimIndent()
        )

        assertNull(DocumentationLink.element("OpenDelegator.open/4", context))
    }

    /** The module `Reader`, which links `OpenDelegator.open/4`, over a delegator in an IDE module that depends on it. */
    private fun contextOf(targetBody: String): PsiElement {
        val reading = createModule("a")
        val delegating = createModule("b")
        ModuleRootModificationUtil.addDependency(reading, delegating)
        ModuleRootModificationUtil.addDependency(delegating, reading)

        addFile(
            delegating,
            "open_delegator.ex",
            """
            defmodule OpenDelegator do
              defdelegate open(a, unquote_splicing(rest)), to: OpenTarget
            end
            """.trimIndent()
        )
        val reader = addFile(
            reading,
            "reader.ex",
            "defmodule OpenTarget do\n" +
                targetBody.prependIndent("  ") + "\n" +
                "end\n\n" +
                """
                defmodule Reader do
                  @moduledoc "See `OpenDelegator.open/4`."
                end
                """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val file = PsiManager.getInstance(project).findFile(reader)!!

        return PsiTreeUtil.findChildrenOfType(file, Call::class.java).first { it.text.startsWith("defmodule Reader") }
    }

    private fun addFile(module: Module, name: String, text: String): VirtualFile {
        val directory = tempDir.createVirtualDir()
        PsiTestUtil.addSourceRoot(module, directory)

        return WriteAction.computeAndWait<VirtualFile, Throwable> {
            directory.createChildData(this, name).also { VfsUtil.saveText(it, text) }
        }
    }
}
