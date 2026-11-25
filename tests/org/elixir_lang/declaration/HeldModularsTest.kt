package org.elixir_lang.declaration

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.expander.ExpanderSourceRows.call
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.expander.TypeQualifierShapes.ALIASED
import org.elixir_lang.expander.TypeQualifierShapes.CURRENT_MODULE_NESTED
import org.elixir_lang.expander.TypeQualifierShapes.DUPLICATED
import org.elixir_lang.expander.TypeQualifierShapes.ERLANG_QUEUE
import org.elixir_lang.expander.TypeQualifierShapes.MODULE_CREATE
import org.elixir_lang.expander.TypeQualifierShapes.NESTED_BEFORE_AND_AFTER
import org.elixir_lang.expander.TypeQualifierShapes.OF_DUPLICATED
import org.elixir_lang.expander.TypeQualifierShapes.STRING_TYPES
import org.elixir_lang.expander.addOtpStdlib
import org.elixir_lang.expander.attachSdkSources
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.qualification.qualifiedToModulars
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource

/** The module PSI each source maps the module it holds for a qualified type's qualifier to. */
class HeldModularsTest : LegSdkFixtureTestCase() {
    override fun setUpSdk(sdk: Sdk) {
        enableExpander(myFixture.testRootDisposable)
    }

    fun testTheWalksModularsForAnAliasedModuleAreTheModularsItsQualifierResolvesTo() =
        assertTheWalksAreItsQualifiers(caller(ALIASED, "lib/s2.ex"), "@type t :: Bar.t()", "Bar.t()")

    fun testTheWalksModularsForANestedModuleAfterItIsDefinedAreTheModularsItsQualifierResolvesTo() =
        assertTheWalksAreItsQualifiers(caller(NESTED_BEFORE_AND_AFTER, "lib/s3.ex"), "@type later :: Inner.t()", "Inner.t()")

    fun testTheWalksModularsForAModuleUnderTheCurrentOneAreTheModularsItsQualifierResolvesTo() =
        assertTheWalksAreItsQualifiers(caller(CURRENT_MODULE_NESTED, "lib/s5.ex"), "@type sub :: __MODULE__.Sub.t()", "__MODULE__.Sub.t()")

    fun testTheWalksModularsForAnErlangModuleAreTheModularsItsQualifierResolvesTo() {
        addOtpStdlib(module, myFixture.testRootDisposable)

        assertTheWalksAreItsQualifiers(caller(ERLANG_QUEUE, "lib/s11.ex"), "@type t :: :queue.queue()", ":queue.queue()")
    }

    fun testTheExpandersModularsForACompiledModuleWithItsSourceAreItsDefmodule() {
        attachSdkSources(module)

        assertEquals("string.ex: defmodule String do", expanders(caller(STRING_TYPES, "lib/s1.ex"), "@type t :: String.t()", "String.t()"))
    }

    fun testTheExpandersModularsForACompiledModuleWithNoSourceAreItsBeamsModule() =
        assertEquals("Elixir.String.beam: String", expanders(caller(STRING_TYPES, "lib/s1.ex"), "@type t :: String.t()", "String.t()"))

    fun testTheExpandersModularsForASourceModuleAreItsDefmodule() =
        assertEquals("s2.ex: defmodule Foo.Bar do", expanders(caller(ALIASED, "lib/s2.ex"), "@type t :: Bar.t()", "Bar.t()"))

    /** `Module.create` writes no `defmodule`. */
    fun testTheExpandersModularsForAModuleModuleCreateMakesAreNone() =
        assertEquals("", expanders(caller(MODULE_CREATE, "lib/s9.ex"), "@type created :: S9.Created.t()", "S9.Created.t()"))

    fun testTheExpandersModularsForAModuleInTwoSourcesAreBothDefmodules() {
        caller(DUPLICATED, "lib/dup_a.ex")
        caller(DUPLICATED, "lib/dup_b.ex")

        assertEquals(
            "dup_a.ex: defmodule Dup13 do\ndup_b.ex: defmodule Dup13 do",
            expanders(caller(OF_DUPLICATED, "lib/s13.ex"), "@type t :: Dup13.t()", "Dup13.t()"),
        )
    }

    fun testTheExpanderRefusesAModuleTheWalkHolds() = assertRefused(found = LegacyWalkSource, mapped = ExpanderSource)

    fun testTheWalkRefusesAModuleTheExpanderHolds() = assertRefused(found = ExpanderSource, mapped = LegacyWalkSource)

    /** A [QualifiedModule.Held] is mapped only by the source that [found] it: [mapped] throws. */
    private fun assertRefused(found: CandidateSource, mapped: CandidateSource) {
        val qualified = call(caller(ALIASED, "lib/s2.ex"), "@type t :: Bar.t()", "Bar.t()") as Qualified
        read {
            val held = found.qualifiedModule(qualified) as QualifiedModule.Held
            assertThrows(IllegalArgumentException::class.java) { mapped.modulars(held) }
        }
    }

    private fun assertTheWalksAreItsQualifiers(file: PsiFile, context: String, type: String) {
        val qualified = call(file, context, type) as Qualified
        val (walks, qualifiers) = read { modulars(LegacyWalkSource, qualified) to qualified.qualifiedToModulars() }

        assertNotEmpty(qualifiers)
        assertEquals(qualifiers, walks)
    }

    /** The computing value's modulars for [type] in [context], one a line, sorted. */
    private fun expanders(file: PsiFile, context: String, type: String): String {
        val qualified = call(file, context, type) as Qualified

        return read { modulars(ExpanderSource, qualified).map(::render).sorted().joinToString("\n") }
    }

    private fun modulars(source: CandidateSource, qualified: Qualified): Set<PsiElement> =
        when (val module = source.qualifiedModule(qualified)) {
            is QualifiedModule.Held -> source.modulars(module)
            QualifiedModule.Absent, QualifiedModule.Unknown -> error("$module, not held")
        }

    /** A `.beam`'s module by its file and name, a source modular by its file and first line. */
    private fun render(modular: PsiElement): String =
        when (modular) {
            is BeamModule -> "${modular.containingFile.name}: ${modular.name}"
            else -> "${modular.containingFile.name}: ${modular.text.lineSequence().first()}"
        }
}
