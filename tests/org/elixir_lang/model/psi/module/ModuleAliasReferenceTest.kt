package org.elixir_lang.model.psi.module

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.QualifiableAlias

/**
 * Regression for [ModuleReferenceProvider]: a no-block `alias Foo` (and `import`/`use`) must expose a
 * module reference over the aliased name so navigation and rename can be initiated from the caret.
 * The provider previously used `isCallingMacro` (which requires a do-block/keyword), so these common
 * no-block forms produced no reference at all.
 *
 * References are hosted on the outermost [QualifiableAlias] itself (not the `alias ...` Call), so the
 * same provider covers bare aliased names, multi-alias braces, struct literals, and `@behaviour`.
 */
@Suppress("UnstableApiUsage")
class ModuleAliasReferenceTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/module"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testNoBlockAliasExposesModuleReference() {
        myFixture.configureByFiles("alias_reference.ex")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)
        assertNotNull("Element at caret should exist", element)

        val alias = generateSequence(element!!) { it.parent }
            .filterIsInstance<QualifiableAlias>()
            .last()

        val references = PsiSymbolReferenceService.getService().getReferences(alias)
        assertTrue(
            "alias Sample.Target should expose a ModuleReference over the aliased name",
            references.any { it is ModuleReference }
        )
    }

    /** `Foo` of `alias Foo.{A}` is an outermost alias that names a module, so it hosts a reference. */
    fun testMultiAliasQualifierHostsAModuleReference() =
        assertHostsModuleReference("alias Foo.{A}\n", "Foo", hosts = true)

    /** `M` of `alias Foo.Bar, as: M` is no declaration, so it hosts a reference that Ctrl+Click follows. */
    fun testAsValueHostsAModuleReference() =
        assertHostsModuleReference("alias Foo.Bar, as: M\n", "M", hosts = true)

    /** `BitString` is the `defimpl ..., for: BitString` pseudo-module of bitstrings, which no module declares. */
    fun testBitStringHostsNoModuleReference() =
        assertHostsModuleReference("defimpl P, for: BitString do\nend\n", "BitString", hosts = false)

    fun testElixirPrefixedBitStringHostsNoModuleReference() =
        assertHostsModuleReference("defimpl P, for: Elixir.BitString do\nend\n", "Elixir.BitString", hosts = false)

    private fun assertHostsModuleReference(source: String, aliasText: String, hosts: Boolean) {
        myFixture.configureByText("alias.ex", source)

        val alias = PsiTreeUtil.findChildrenOfType(myFixture.file, QualifiableAlias::class.java)
            .single { it.text == aliasText }
        val references = PsiSymbolReferenceService.getService().getReferences(alias)

        assertEquals("$aliasText hosts a ModuleReference", hosts, references.any { it is ModuleReference })
    }
}
