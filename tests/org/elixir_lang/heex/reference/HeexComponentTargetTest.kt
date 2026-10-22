package org.elixir_lang.heex.reference

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.heex.xml.HeexComponentResolver
import org.elixir_lang.psi.call.Call
import java.io.File

/**
 * What a component tag resolves to where it can differ from a one-argument call of the same name at the same place.
 * Each fixture holds the view module `page_live.ex` with a probe calling the component, and a `.heex` with its tags.
 */
class HeexComponentTargetTest : HeexHostTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/heex/component_target"

    fun testOpaqueFunctionImportedLocal() =
        assertTag("opaque", ".button", LOCAL_CALL, Versus.SAME, target = null, declaration = null, symbols = emptyList())

    fun testOpaqueFunctionRemote() =
        assertTag("opaque", REMOTE_TAG, REMOTE_CALL, Versus.DIFFERENT, target = null, declaration = null, symbols = emptyList())

    fun testOpaqueFunctionOwn() =
        assertTag("opaque_own", ".button", LOCAL_CALL, Versus.DIFFERENT, target = null, declaration = null, symbols = emptyList())

    fun testContractImportedLocal() = assertTag("contract", ".button", LOCAL_CALL, Versus.SAME, DEF, BUTTON, BUTTON_1)

    fun testContractRemote() =
        assertTag("contract", REMOTE_TAG, REMOTE_CALL, Versus.DIFFERENT, CALLBACK, CALLBACK, emptyList())

    fun testContractOwn() =
        assertTag("contract_own", ".button", LOCAL_CALL, Versus.DIFFERENT, OWN_CALLBACK, OWN_CALLBACK, emptyList())

    fun testTwoImportsFullImportLast() = assertTag("two_imports", ".button", LOCAL_CALL, Versus.SAME, DEF, BUTTON, BUTTON_1)

    fun testTwoImportsFullImportLastRemote() = assertTag("two_imports", REMOTE_TAG, REMOTE_CALL, Versus.SAME, DEF, BUTTON, BUTTON_1)

    fun testTwoImportsRestrictedImportLast() =
        assertTag("two_imports_last", ".button", LOCAL_CALL, Versus.UNCOMPARED, DEF, BUTTON, BUTTON_1)

    fun testOverriddenImportedLocal() = assertTag("overridden_def", ".button", LOCAL_CALL, Versus.SAME, DEF, BUTTON, BUTTON_1)

    fun testOverriddenRemote() = assertTag("overridden_def", REMOTE_TAG, REMOTE_CALL, Versus.DIFFERENT, DEF, BUTTON, BUTTON_1)

    fun testOverriddenOwn() =
        assertTag("overridden_def_own", ".button", LOCAL_CALL, Versus.DIFFERENT, OWN_DEF, OWN_BUTTON, BUTTON_1)

    fun testExceptionMessageOverriddenImportedLocal() =
        assertTag("exception_overridden", ".message", MESSAGE_CALL, Versus.SAME, MESSAGE_DEF, MESSAGE, MESSAGE_1)

    fun testExceptionMessageOverriddenRemote() =
        assertTag("exception_overridden", REMOTE_MESSAGE_TAG, REMOTE_MESSAGE_CALL, Versus.DIFFERENT, DEFEXCEPTION, DEFEXCEPTION, emptyList())

    fun testExceptionMessageOverriddenOwn() =
        assertTag("exception_overridden_own", ".message", MESSAGE_CALL, Versus.DIFFERENT, OWN_DEFEXCEPTION, OWN_DEFEXCEPTION, emptyList())

    fun testExceptionMessageKeptImportedLocal() =
        assertTag("exception_kept", ".message", MESSAGE_CALL, Versus.SAME, target = null, declaration = null, symbols = emptyList())

    fun testExceptionMessageKeptRemote() =
        assertTag("exception_kept", REMOTE_MESSAGE_TAG, REMOTE_MESSAGE_CALL, Versus.SAME, DEFEXCEPTION, DEFEXCEPTION, emptyList())

    fun testExceptionMessageKeptOwn() =
        assertTag("exception_kept_own", ".message", MESSAGE_CALL, Versus.SAME, OWN_DEFEXCEPTION, OWN_DEFEXCEPTION, emptyList())

    fun testDelegationLocal() = assertTag("delegate", ".button", LOCAL_CALL, Versus.SAME, DEFDELEGATE, DEFDELEGATE, emptyList())

    fun testDelegationRemote() = assertTag("delegate", REMOTE_TAG, REMOTE_CALL, Versus.SAME, DEFDELEGATE, DEFDELEGATE, emptyList())

    fun testDelegationWithUnquoteSplicingLocal() =
        assertTag("unquote_splicing", ".button", LOCAL_CALL, Versus.SAME, SPLICED_DEFDELEGATE, SPLICED_DEFDELEGATE, emptyList())

    fun testDelegationWithUnquoteSplicingRemote() =
        assertTag("unquote_splicing", REMOTE_TAG, REMOTE_CALL, Versus.SAME, SPLICED_DEFDELEGATE, SPLICED_DEFDELEGATE, emptyList())

    fun testSpecLocal() = assertTag("spec", ".button", LOCAL_CALL, Versus.SAME, DEF, BUTTON, BUTTON_1)

    fun testSpecRemote() = assertTag("spec", REMOTE_TAG, REMOTE_CALL, Versus.SAME, DEF, BUTTON, BUTTON_1)

    fun testDefaultArgumentLocal() = assertTag("default", ".button", LOCAL_CALL, Versus.SAME, DEFAULT_DEF, BUTTON, BUTTON_1_AND_2)

    fun testDefaultArgumentRemote() = assertTag("default", REMOTE_TAG, REMOTE_CALL, Versus.SAME, DEFAULT_DEF, BUTTON, BUTTON_1_AND_2)

    fun testMacroLocal() = assertTag("macro", ".button", LOCAL_CALL, Versus.SAME, DEFMACRO, DEFMACRO, BUTTON_1)

    fun testMacroRemote() = assertTag("macro", REMOTE_TAG, REMOTE_CALL, Versus.SAME, DEFMACRO, DEFMACRO, BUTTON_1)

    /** A module present only as a `.beam` is no source modular, so a remote tag to it resolves to nothing. */
    fun testCompiledModuleRemote() {
        addKernelBeam()

        assertTag("compiled", "Kernel.abs", null, Versus.UNCOMPARED, target = null, declaration = null, symbols = emptyList())
    }

    /** A function imported from a module present only as a `.beam` is no source clause, so a local tag resolves to nothing. */
    fun testCompiledFunctionImportedLocal() {
        addKernelBeam()

        assertTag("compiled", ".abs", null, Versus.UNCOMPARED, target = null, declaration = null, symbols = emptyList())
    }

    private enum class Versus { SAME, DIFFERENT, UNCOMPARED }

    private fun assertTag(
        directory: String,
        tagName: String,
        callText: String?,
        comparison: Versus,
        target: String?,
        declaration: String?,
        symbols: List<String>
    ) {
        configure(directory)
        val tag = tag(tagName)
        val resolvedTarget = text(HeexComponentResolver.resolveCall(tag))

        assertEquals("resolveCall", target, resolvedTarget)
        assertEquals("resolveDeclaration", declaration, text(HeexComponentResolver.resolveDeclaration(tag)))
        assertEquals(
            "resolveFunctionSymbols",
            symbols,
            HeexComponentResolver.resolveFunctionSymbols(tag).map { "${it.name}/${it.arity}" }
        )

        val callTarget = callText?.let { text(call(directory, it).reference?.resolve()) }

        when (comparison) {
            Versus.SAME -> assertEquals("the call at the same place", callTarget, resolvedTarget)
            Versus.DIFFERENT -> assertFalse("the call at the same place resolves to $callTarget", callTarget == resolvedTarget)
            Versus.UNCOMPARED -> Unit
        }
    }

    private fun text(element: PsiElement?): String? = element?.let { "${it.containingFile.name}: ${it.text.lines().first()}" }

    private fun configure(directory: String) {
        val names = File(testDataPath, directory).list()!!.sortedBy { if (it.endsWith(".heex")) 0 else 1 }

        myFixture.configureByFiles(*names.map { "$directory/$it" }.toTypedArray())
    }

    private fun tag(name: String): XmlTag =
        PsiTreeUtil
            .findChildrenOfType(myFixture.file.viewProvider.getPsi(HTMLLanguage.INSTANCE), XmlTag::class.java)
            .first { it.name == name }

    /** The probe's call: the last of the text, as a clause's head can be written the same. */
    private fun call(directory: String, text: String): PsiElement {
        val view = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("$directory/page_live.ex"))!!

        return PsiTreeUtil.findChildrenOfType(view, Call::class.java).last { it.text == text }
    }

    private fun addKernelBeam() {
        val ebin = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, ebin.path)
        BeamLibraryFixture.addLibrary(
            project,
            myFixture.module,
            "beam-fixture-lib",
            listOf(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebin)!!)
        )
    }

    private companion object {
        const val LOCAL_CALL = "button(assigns)"
        const val REMOTE_CALL = "MyAppWeb.CoreComponents.button(assigns)"
        const val REMOTE_TAG = "MyAppWeb.CoreComponents.button"
        const val MESSAGE_CALL = "message(assigns)"
        const val REMOTE_MESSAGE_CALL = "MyAppWeb.CoreComponents.message(assigns)"
        const val REMOTE_MESSAGE_TAG = "MyAppWeb.CoreComponents.message"

        const val DEF = "core_components.ex: def button(assigns), do: assigns"
        const val BUTTON = "core_components.ex: button"
        const val CALLBACK = "core_components.ex: @callback button(map) :: term"
        const val MESSAGE_DEF = "core_components.ex: def message(assigns), do: assigns"
        const val MESSAGE = "core_components.ex: message"
        const val DEFEXCEPTION = "core_components.ex: defexception [:message]"
        const val DEFDELEGATE = "core_components.ex: defdelegate button(assigns), to: MyAppWeb.Components"
        const val SPLICED_DEFDELEGATE =
            "core_components.ex: defdelegate button(unquote_splicing(args)), to: MyAppWeb.Components"
        const val DEFAULT_DEF = "core_components.ex: def button(assigns, opts \\\\ []), do: {assigns, opts}"
        const val DEFMACRO = "core_components.ex: defmacro button(assigns), do: assigns"

        const val OWN_CALLBACK = "page_live.ex: @callback button(map) :: term"
        const val OWN_DEF = "page_live.ex: def button(assigns), do: assigns"
        const val OWN_BUTTON = "page_live.ex: button"
        const val OWN_DEFEXCEPTION = "page_live.ex: defexception [:message]"

        val BUTTON_1 = listOf("button/1")
        val BUTTON_1_AND_2 = listOf("button/1", "button/2")
        val MESSAGE_1 = listOf("message/1")
    }
}
