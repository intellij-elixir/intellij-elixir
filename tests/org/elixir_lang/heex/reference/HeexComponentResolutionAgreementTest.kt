package org.elixir_lang.heex.reference

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.heex.xml.ComponentTagName
import org.elixir_lang.heex.xml.HeexComponentResolver
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.languageLevelFor
import org.elixir_lang.lowering.identifierAtomName
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
import org.elixir_lang.reference.resolver.Module as ModuleResolver
import java.io.File

/**
 * Every component tag in the HEEx fixtures resolves, through the declaration source, to what walking the module
 * directly resolves it to: in a `.heex` file, and with the same body in a `~H` sigil of its view module.
 */
class HeexComponentResolutionAgreementTest : HeexHostTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/heex/reference"

    fun testEveryComponentTagResolvesAsTheWalkDoes() {
        val disagreements = mutableListOf<String>()
        var compared = 0

        for (directory in fixtureDirectories()) {
            clearFixtureFiles()
            val files = directory.walkTopDown().filter { it.isFile }
                .map { FileUtil.toSystemIndependentName(it.relativeTo(File(testDataPath)).path) }.toList()

            myFixture.configureByFiles(*files.sortedBy { if (it.endsWith(".heex")) 0 else 1 }.toTypedArray())

            for (file in files) {
                myFixture.configureFromTempProjectFile(file)

                val roots = when {
                    file.endsWith(".heex") -> listOf(myFixture.file)
                    PsiTreeUtil.findChildrenOfType(myFixture.file, SigilHeredocLiteral::class.java).isNotEmpty() -> myFixture.injectedHeexRoots()
                    else -> emptyList()
                }

                compared += compare(file, roots, disagreements)

                if (file.endsWith(".heex")) {
                    compared += compare("$file in a sigil", sigilRoots(directory, file), disagreements)
                }
            }
        }

        assertEquals(emptyList<String>(), disagreements)
        assertTrue("compared only $compared tags", compared >= TAGS_FLOOR)
    }

    /** `<group>/<case>`: one fixture is a directory holding the files of one scenario, however deep. */
    private fun fixtureDirectories(): List<File> =
        File(testDataPath).listFiles()!!.filter { it.isDirectory }.sorted().flatMap { group ->
            group.listFiles()!!.filter { it.isDirectory }.sorted()
        }

    private fun clearFixtureFiles() {
        runWriteAction { myFixture.tempDirFixture.getFile(".")!!.children.forEach { it.delete(this) } }
    }

    /**
     * The `.heex` [heex] spliced into a `~H` sigil of its view module, the `.ex` file of the same name beside it, or a
     * module of no definitions where it has none.
     */
    private fun sigilRoots(directory: File, heex: String): List<PsiFile> {
        val body = File(testDataPath, heex).readText().trimEnd('\n').replace("<caret>", "")
        val view = File(testDataPath, heex.substringBefore('.') + ".ex")
        val others = directory.walkTopDown().filter { it.isFile && it.extension == "ex" && it != view }.map { it.readText().trimEnd('\n') }

        myFixture.configureByText(
            "sigil_host.ex",
            heexSigilModuleText(
                entranceModuleText = if (view.isFile) view.readText().trimEnd('\n') else "defmodule HeexHost do\nend",
                heexBody = body,
                *others.toList().toTypedArray()
            )
        )

        return myFixture.injectedHeexRoots()
    }

    private fun compare(label: String, roots: List<PsiFile>, disagreements: MutableList<String>): Int {
        val tags = roots.flatMap {
            PsiTreeUtil.findChildrenOfType(it.viewProvider.getPsi(HTMLLanguage.INSTANCE), XmlTag::class.java)
        }.filter { ComponentTagName.parse(it.name) != null }

        for (tag in tags) {
            val expected = text(mainResolveCall(tag))
            val actual = text(HeexComponentResolver.resolveCall(tag))

            if (expected != actual) {
                disagreements += "$label <${tag.name}>: the walk resolves $expected, the declaration source $actual"
            }
        }

        return tags.size
    }

    private fun text(element: PsiElement?): String? = element?.let { "${it.containingFile.name}: ${it.text.lines().first()}" }

    // The resolver as it was before component tags read the declaration source.
    private fun mainResolveCall(tag: XmlTag): Call? {
        val component = ComponentTagName.parse(tag.name) ?: return null
        val elixirRoot = tag.containingFile.originalFile.viewProvider.getPsi(ElixirLanguage) as? ElixirFile ?: return null

        return when (component) {
            is ComponentTagName.Local -> mainResolveLocalCall(atomName(component.functionName, elixirRoot), elixirRoot)
            is ComponentTagName.Remote ->
                mainResolveRemoteCall(component.aliasChain, atomName(component.functionName, elixirRoot), elixirRoot)
            is ComponentTagName.Slot -> null
        }
    }

    private fun atomName(functionName: String, elixirRoot: ElixirFile): String =
        identifierAtomName(functionName) { languageLevelFor(elixirRoot) }

    private fun mainResolveLocalCall(functionName: String, entrance: PsiElement): Call? =
        MultiResolve
            .resolveResults(functionName, 1, false, entrance, atom = true)
            .firstOrNull { it.isValidResult }
            ?.element as? Call

    private fun mainResolveRemoteCall(aliasChain: String, functionName: String, entrance: PsiElement): Call? =
        ModuleResolver
            .resolve(entrance, aliasChain, false)
            .filter { it.isValidResult }
            .mapNotNull { it.element }
            .filter(::isModular)
            .firstNotNullOfOrNull { modular ->
                MultiResolve.resolveResults(functionName, 1, false, modular, atom = true).firstOrNull { it.isValidResult }?.element
            } as? Call

    private fun isModular(element: PsiElement): Boolean =
        element is Call && (Module.`is`(element) || Protocol.`is`(element) || Implementation.`is`(element))

    private companion object {
        const val TAGS_FLOOR = 40
    }
}
