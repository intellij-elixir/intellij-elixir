package org.elixir_lang.declaration

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.expander.ConfigHeavyTestCase
import org.elixir_lang.expander.ModuleNamesTracker
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.inspection.UnreadConfigKey
import org.elixir_lang.model.psi.config.ConfigKeyReference
import org.elixir_lang.model.psi.config.ConfigReads
import org.elixir_lang.model.psi.config.ConfigSettings
import org.elixir_lang.model.psi.config.Keys
import org.elixir_lang.model.psi.config.Read
import org.elixir_lang.psi.call.Call
import java.util.concurrent.Callable

/**
 * With the Experimental setting off, asking about config keys (usages, navigation, the unread key inspection) starts no
 * expander service. With it on, the same asks do.
 */
class ExpanderOffHeavyTest : ConfigHeavyTestCase() {
    private var savedProperty: String? = null

    override fun setUp() {
        savedProperty = System.getProperty(SOURCE_PROPERTY)
        System.clearProperty(SOURCE_PROPERTY)
        super.setUp()
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            savedProperty?.let { System.setProperty(SOURCE_PROPERTY, it) } ?: System.clearProperty(SOURCE_PROPERTY)
        }
    }

    fun testNoServiceStartsOnAConfigAskWithTheSettingOff() {
        val root = configProject()

        assertEquals("before any ask", emptyList<String>(), started())

        ask(root)

        assertEquals(emptyList<String>(), started())
    }

    fun testTheServicesStartWithTheSettingOn() {
        val root = configProject()
        enableExpander(testRootDisposable)

        ask(root)

        assertEquals(listOf("ModuleNamesTracker", "ProjectModules", "SdkInputs"), started())
    }

    /** The services a config ask starts, by name, sorted. */
    private fun started(): List<String> =
        listOfNotNull(
            "ModuleNamesTracker".takeIf { project.getServiceIfCreated(ModuleNamesTracker::class.java) != null },
            "ProjectModules".takeIf { project.getServiceIfCreated(ProjectModules::class.java) != null },
            "SdkInputs".takeIf { project.getServiceIfCreated(SdkInputs::class.java) != null },
        )

    private fun configProject(): VirtualFile =
        project(
            "config/config.exs" to "import Config\n\nconfig :app, App.Repo, a: 1\nconfig :app, :k, 1\n",
            "lib/reader.ex" to
                "defmodule Reader do\n  def read, do: {Application.get_env(:app, :k), Application.get_env(:app, App.Repo)}\nend\n",
        )

    /** Usages, navigation and the inspection over the project's config and its reads. */
    private fun ask(root: VirtualFile) {
        val scope = GlobalSearchScope.allScope(project)
        val reader = psi(root, "lib/reader.ex")
        val config = psi(root, "config/config.exs")

        ReadAction.nonBlocking(Callable {
            ConfigSettings.of(project, "app", "k")
            ConfigSettings.apps(project)
            ConfigReads.of(project, "app", "k", scope).toList()
            ConfigReads.keys(project, "app", scope)

            for (read in PsiTreeUtil.findChildrenOfType(reader, Call::class.java).mapNotNull(Read::of)) {
                ConfigKeyReference(read.element, Keys.range(read.element), read.app, read.key, read).resolveReference()
                Read.slotAt(read.element)
            }

            inspect(config)
        }).executeSynchronously()
    }

    private fun inspect(file: PsiFile) {
        val holder = ProblemsHolder(InspectionManager.getInstance(project), file, false)
        val visitor = UnreadConfigKey().buildVisitor(holder, false)

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                visitor.visitElement(element)
                super.visitElement(element)
            }
        })
    }
}
