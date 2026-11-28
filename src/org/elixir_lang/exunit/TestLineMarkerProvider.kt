package org.elixir_lang.exunit

import com.intellij.execution.TestStateStorage
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.system.OS
import org.elixir_lang.exunit.configuration.lineNumber
import org.elixir_lang.mix.Test
import org.elixir_lang.mix.TestFinder
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.sdk.wsl.wslCompat

/**
 * Provides the gutter "run" icons for tests.
 */
internal class ExUnitLineMarkerProvider : RunLineMarkerContributor() {
    private val testMethodNames = setOf("test", "doctest")

    override fun getInfo(element: PsiElement): Info? {

        // The platform calls getInfo for every element (leaf and composite) and recommends returning null as fast
        // as possible, marking leaf elements only. Do the cheap, local AST checks before any file-system, index,
        // or stub-resolution work.
        if (element !is LeafPsiElement) {
            return null
        }

        val parent = element.parent?.parent

        if (parent !is Call) {
            return null
        }

        // Assignment
        if (parent.parent is Match) {
            return null
        }

        val functionElement = parent.firstChild

        if (functionElement != element.parent) {
            return null
        }

        val isClass = when (parent.functionName()) {
            "test" -> false
            "describe" -> true
            "defmodule" -> true.takeIf { Module.`is`(parent) }
            "doctest" -> false
            else -> null
        } ?: return null

        // Only genuine test/describe/defmodule/doctest call-name leaves reach here; now pay for the file-level and
        // stub-resolution checks. Resolve the containing file once (an AST walk) and reuse it below.
        val containingFile = element.containingFile ?: return null

        if (!Test.isUnderTestSources(containingFile)) {
            return null
        }

        if (!TestFinder.isTestElement(element)) {
            return null
        }
        val fileUrl = fileUrlAsExUnitSeesIt(containingFile)
        val state = if (isClass) {
            // describe/defmodule: aggregate states of all contained tests, since
            // ExUnit never emits describe-level TC events with a locationHint, so
            // TestStateStorage has no direct entry for the describe/module URL.
            aggregateChildTestStates(parent, element.project, fileUrl, containingFile)
        } else {
            val url = appendLineNumber(fileUrl, element, containingFile)
            TestStateStorage.getInstance(element.project)?.getState(url)
        }

        return withExecutorActions(getTestStateIcon(state, isClass))
    }

    private fun fileUrlAsExUnitSeesIt(containingFile: PsiFile): String =
            "file://" + if (OS.CURRENT == OS.Windows) {
                // In Elixir, the drive letter is lowercase for some reason.
                val fixedDriveLetter = containingFile.virtualFile.path.replaceFirstChar { it.lowercase() }
                // On WSL, ExUnit reports the POSIX path, but IDEA references the file by the UNC path. We convert it to posix if
                // it is UNC, otherwise return unchanged.
                wslCompat.maybeParseWindowsUncPath(fixedDriveLetter)
            } else {
                containingFile.virtualFile.path
            }

    private fun appendLineNumber(fileUrl: String, element: PsiElement, containingFile: PsiFile): String =
            "$fileUrl:${lineNumber(element, containingFile)}"

    /**
     * For describe/defmodule gutter icons: lazily iterate over all child test/doctest calls, looking up each
     * test's state in turn from TestStateStorage. Returns a failure state as soon as one is found, returns the
     * first good state if there are no bad states, or null if nothing is found.
     *
     * Priority: FAILED (6) or ERROR (8) > PASSED/COMPLETE (1) > null (no data).
     */
    private fun aggregateChildTestStates(
        scopeCall: Call,
        project: Project,
        fileUrl: String,
        containingFile: PsiFile
    ): TestStateStorage.Record? {
        val storage = TestStateStorage.getInstance(project) ?: return null
        var goodState: TestStateStorage.Record? = null

        val states = PsiTreeUtil.findChildrenOfType(scopeCall, Call::class.java)
            .asSequence()
            .filter { call -> call.functionName() in testMethodNames }
            .mapNotNull { testCall -> storage.getState(appendLineNumber(fileUrl, testCall, containingFile)) }

        for (state in states) {
            when (state.magnitude) {
                6, 8 -> return state                            // FAILED/ERROR -> red icon, stop scanning
                1 -> if (goodState == null) goodState = state   // PASSED/COMPLETE -> remember first, keep looking
            }
        }

        return goodState
    }
}
