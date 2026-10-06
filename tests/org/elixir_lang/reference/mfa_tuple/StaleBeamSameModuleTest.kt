package org.elixir_lang.reference.mfa_tuple

import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.model.psi.atom.AtomSymbol
import java.util.concurrent.Callable

/**
 * Two IDE modules both define `Dup`, and the one the caller is in depends on the other. A module written as an atom
 * resolves to the caller's own `Dup`, as the module written as an alias does.
 */
@Suppress("UnstableApiUsage")
class StaleBeamSameModuleTest : HeavyTestCase() {
    fun testAtomResolvesToTheCallersOwnModule() {
        assertEquals(listOf(callersOwnDup()), declarationFiles("apply(:\"Elixir.Dup\", :f, [])"))
    }

    fun testAliasResolvesToTheCallersOwnModule() {
        assertEquals(listOf(callersOwnDup()), declarationFiles("apply(Dup, :f, [])"))
    }

    private lateinit var callerModule: Module

    /** Module `a`, which depends on `b`, and `b` each hold a `Dup`; returns `a`'s. */
    private fun callersOwnDup(): VirtualFile {
        callerModule = createModule("a")
        val b = createModule("b")
        ModuleRootModificationUtil.addDependency(callerModule, b)

        val dup = "defmodule Dup do\n  def f, do: :ok\nend\n"
        addFile(b, "dup.ex", dup)

        return addFile(callerModule, "dup.ex", dup)
    }

    private fun addFile(module: Module, name: String, text: String): VirtualFile {
        val directory = tempDir.createVirtualDir()
        PsiTestUtil.addSourceRoot(module, directory)

        return WriteAction.computeAndWait<VirtualFile, Throwable> {
            directory.createChildData(this, name).also { VfsUtil.saveText(it, text) }
        }
    }

    /** The files of the declarations Go to Declaration offers at `:f` in a caller of module `a` written as [call]. */
    private fun declarationFiles(call: String): List<VirtualFile> {
        val caller = addFile(callerModule, "caller.ex", "defmodule Caller do\n  def run, do: $call\nend\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val file = ReadAction.computeBlocking<PsiFile, Throwable> { PsiManager.getInstance(project).findFile(caller)!! }
        val offset = file.text.indexOf(":f") + ":f".length

        return onPooledThread {
            ReadAction.nonBlocking(Callable {
                targetSymbols(file, offset).filterIsInstance<AtomSymbol>().map { it.file.virtualFile }.distinct()
            }).executeSynchronously()
        }
    }
}
