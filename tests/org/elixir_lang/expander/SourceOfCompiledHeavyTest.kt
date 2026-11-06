package org.elixir_lang.expander

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.gtduNavigationAtCaret
import org.elixir_lang.code_insight.parameterInfoSignaturesAtCaret
import org.elixir_lang.declaration.*
import org.elixir_lang.documentation.quickDocumentationAtCaret
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.expander.ExpanderSourceRows.render
import org.elixir_lang.expander.ExpanderSourceRows.withExpanderSwitch
import org.elixir_lang.mix.library.CONSOLIDATED_LIBRARY_BASE_NAME
import org.elixir_lang.mix.sync.MixSyncTestHelpers
import org.elixir_lang.mix.sync.contentRootToken
import org.elixir_lang.mix.sync.scopedDepLibraryName
import org.elixir_lang.model.psi.config.caret
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import java.io.File

/**
 * What the expander answers a use of a module that a library or the SDK holds as a `.beam` with: the definitions of the
 * source the same library or SDK attaches, where it attaches one, as the walk finds them; the `.beam`'s otherwise.
 */
class SourceOfCompiledHeavyTest : LegSdkFixtureTestCase() {
    override fun setUpSdk(sdk: Sdk) {
        // The sources `ElixirSdkPathConfigurator` attaches, and Erlang's `stdlib`, as `ElixirErlangClasspath` adds the Erlang
        // SDK's code paths.
        val stdlib = appEbins("ERLANG_SDK_HOME").single { it.parentFile.name.startsWith("stdlib-") }
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                ElixirSdkPathConfigurator.addSourcePaths(this)
                addRoot(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(stdlib)!!, OrderRootType.CLASSES)
                commitChanges()
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    // Mapped: each finds the source the `.beam`'s own library or SDK attaches, at the heads that write it.

    fun testAFindInADependencyOrTheSdkAnswersFromItsSource() {
        dependency()
        val file = caller(
            """
            defmodule Caller do
              require Phoenix.Component
              alias Phoenix.Component

              def remote(s), do: Phoenix.Component.assign(s, :k, 1)
              def single(s), do: Phoenix.Component.connected?(s)
              def macro, do: Phoenix.Component.slot(:a)
              def guard(x) when Phoenix.Component.is_assigns(x), do: x
              def capture, do: &Phoenix.Component.assign/3
              def pipe(s), do: s |> Phoenix.Component.assign(:k, 1)
              def aliased(s), do: Component.assign(s, :aliased, 1)
              def own_delegation(t), do: Phoenix.Component.render(t)
              def nested(x), do: Phoenix.Component.Nested.nested(x)
              def exception(x), do: Phoenix.Component.Error.exception(x)
              def message(e), do: Phoenix.Component.Error.message(e)
              def own_message(e), do: Phoenix.Component.OwnMessage.message(e)
              def default(s), do: String.split(s, ",")
              def written(s), do: String.split(s, ",", [])
            end
            """
        )
        val split = sdkHeads("lib/string.ex", "def split(string, pattern, options \\\\ [])")
        val delegated = sdkHeads("lib/string.ex", "defdelegate split(binary)").first()
        // Every head of the name, each with the applicability the call's arity gives it.
        val splits = { arity: Int ->
            (
                listOf(
                    "String.split/1 OWN WRONG_ARITY via [] line $delegated",
                    "String.split/2..3 OWN VALID via [] line ${split.first()}",
                ) + split.drop(1).map { "String.split/3 OWN ${if (arity == 3) "VALID" else "WRONG_ARITY"} via [] line $it" }
                ).joinToString("\n")
        }
        val assign = lines("Phoenix.Component.assign/3 OWN VALID via []", *ASSIGN_HEADS)

        assertEquals(
            rows(
                "remote" to assign,
                "single" to "Phoenix.Component.connected?/1 OWN VALID via [] line 9",
                "macro" to "Phoenix.Component.slot/1 OWN VALID via [] line 11",
                "guard" to "Phoenix.Component.is_assigns/1 OWN VALID via [] line 13",
                "capture" to assign,
                "pipe" to assign,
                "aliased" to assign,
                "own delegation" to "Phoenix.Component.render/1 OWN VALID via [] line 19",
                "nested" to "Phoenix.Component.Nested.nested/1 OWN VALID via [] line 26",
                "exception" to "Phoenix.Component.Error.exception/1 OWN VALID via [] line 35",
                "message" to "Phoenix.Component.Error.message/1 OWN VALID via [] line 35",
                "own message" to lines("Phoenix.Component.OwnMessage.message/1 OWN VALID via []", 39, 41),
                "default" to splits(2),
                "written" to splits(3),
            ),
            rows(
                "remote" to candidates(file, "Phoenix.Component.assign(s, :k, 1)"),
                "single" to candidates(file, "Phoenix.Component.connected?(s)"),
                "macro" to candidates(file, "Phoenix.Component.slot(:a)"),
                "guard" to candidates(file, "Phoenix.Component.is_assigns(x)"),
                "capture" to capture(file, "Phoenix.Component.assign", 3),
                "pipe" to candidates(file, "Phoenix.Component.assign(:k, 1)"),
                "aliased" to candidates(file, "Component.assign(s, :aliased, 1)"),
                "own delegation" to candidates(file, "Phoenix.Component.render(t)"),
                "nested" to candidates(file, "Phoenix.Component.Nested.nested(x)"),
                "exception" to candidates(file, "Phoenix.Component.Error.exception(x)"),
                "message" to candidates(file, "Phoenix.Component.Error.message(e)"),
                "own message" to candidates(file, "Phoenix.Component.OwnMessage.message(e)"),
                "default" to candidates(file, "String.split(s, \",\")"),
                "written" to candidates(file, "String.split(s, \",\", [])"),
            ),
        )
    }

    /** A mapped find reads as the walk's find of the same clause: its form, and a guard's capabilities. */
    fun testAMappedFindHasTheWalksFormAndCapabilities() {
        dependency()
        val file = caller(
            """
            defmodule Caller do
              require Phoenix.Component

              def guard(x) when Phoenix.Component.is_assigns(x), do: x
              def exception(x), do: Phoenix.Component.Error.exception(x)
              def message(e), do: Phoenix.Component.Error.message(e)
            end
            """
        )
        val guard = use(file, "Phoenix.Component.is_assigns(x)")
        val exception = use(file, "Phoenix.Component.Error.exception(x)")
        val message = use(file, "Phoenix.Component.Error.message(e)")

        assertEquals(
            listOf(
                read { LegacyWalkSource.candidates(guard, false).map { it.candidate.declaration.capabilities } },
                listOf(Form.EXCEPTION),
                listOf(Form.EXCEPTION),
            ),
            read {
                listOf(
                    ExpanderSource.candidates(guard, false).map { it.candidate.declaration.capabilities },
                    ExpanderSource.candidates(exception, false).map { (it.candidate.declaration.declared as? Declared.Source)?.form },
                    ExpanderSource.candidates(message, false).map { (it.candidate.declaration.declared as? Declared.Source)?.form },
                )
            },
        )
    }

    /** Through an `import`, `Kernel`'s implicit one, `Kernel.SpecialForms` and a project `defdelegate`'s target. */
    fun testAFindThroughAnImportOrDelegationAnswersFromItsSource() {
        dependency()
        val file = caller(
            """
            defmodule Caller do
              import Phoenix.Component

              defdelegate delegated(s, k, v), to: Phoenix.Component, as: :assign

              def imported(s), do: assign(s, :k, 1)
              def kernel(x), do: to_string(x)
              def special, do: alias(String, as: S)
              def through(s), do: delegated(s, :k, 1)
            end
            """
        )
        val toString = sdkHeads("lib/kernel.ex", "defmacro to_string(term)").single()
        val alias = sdkHeads("lib/kernel/special_forms.ex", "defmacro alias(module, opts)").single()

        assertEquals(
            rows(
                "imported" to lines("Phoenix.Component.assign/3 IMPORT VALID via [import Phoenix.Component]", *ASSIGN_HEADS),
                "kernel" to "Kernel.to_string/1 IMPLICIT_IMPORT VALID via [] line $toString",
                "special" to "Kernel.SpecialForms.alias/1..2 IMPLICIT_IMPORT VALID via [] line $alias",
                "through" to "Caller.delegated/3 OWN VALID via [] line 4\n" +
                    lines(
                        "Phoenix.Component.assign/3 DELEGATION_TARGET VALID " +
                            "via [defdelegate delegated(s, k, v), to: Phoenix.Component, as: :assign]",
                        *ASSIGN_HEADS,
                    ),
            ),
            rows(
                "imported" to candidates(file, "assign(s, :k, 1)"),
                "kernel" to candidates(file, "to_string(x)"),
                "special" to candidates(file, "alias(String, as: S)"),
                "through" to candidates(file, "delegated(s, :k, 1)"),
            ),
        )
    }

    /** A source in the project's content that leaves the name no module falls through to the `.beam`, and so to its source. */
    fun testTheFallThroughBeamAnswersFromItsSource() {
        dependency()
        myFixture.addFileToProject("lib/component.ex", "defmodule Phoenix.Component do\n  :ok = :bad\nend\n")
        val file = caller("defmodule Caller do\n  def single(s), do: Phoenix.Component.connected?(s)\nend\n")

        assertEquals("Phoenix.Component.connected?/1 OWN VALID via [] line 9", candidates(file, "Phoenix.Component.connected?(s)"))
    }

    // Kept on the `.beam`.

    /** What no written head declares, a `defp` and a module the source defines twice keep the `.beam`'s answer. */
    fun testWhatNoSingleWrittenHeadDeclaresKeepsTheBeam() {
        dependency()
        val file = caller(
            """
            defmodule Caller do
              def generated, do: Phoenix.Component.generated()
              def info, do: Phoenix.Component.__info__(:functions)
              def hidden(x), do: Phoenix.Component.hidden(x)
              def twice(x), do: SourceOfCompiled.Twice.g(x)
              def erlang(l), do: :lists.reverse(l)
            end
            """
        )

        assertEquals(
            rows(
                "generated" to "Phoenix.Component.generated/0 OWN VALID via []",
                "info" to "Phoenix.Component.__info__/1 OWN VALID via []",
                "hidden" to "",
                "twice" to "SourceOfCompiled.Twice.g/1 OWN VALID via []",
                "erlang" to ":lists.reverse/1 OWN VALID via []\n:lists.reverse/2 OWN WRONG_ARITY via []",
            ),
            rows(
                "generated" to candidates(file, "Phoenix.Component.generated()"),
                "info" to candidates(file, "Phoenix.Component.__info__(:functions)"),
                "hidden" to candidates(file, "Phoenix.Component.hidden(x)"),
                "twice" to candidates(file, "SourceOfCompiled.Twice.g(x)"),
                "erlang" to candidates(file, ":lists.reverse(l)"),
            ),
        )
    }

    /** A head written as another kind than the `.beam` exports its name and arity as, or private, declares none of it. */
    fun testAHeadOfAnotherKindOrVisibilityDeclaresNoExport() {
        dependency()
        val file = caller(
            """
            defmodule Caller do
              require SourceOfCompiled.Unexported

              def macro(x), do: SourceOfCompiled.Unexported.f(x)
              def public(x), do: SourceOfCompiled.Unexported.h(x)
            end
            """
        )

        assertEquals(
            rows(
                "macro" to "SourceOfCompiled.Unexported.f/1 OWN VALID via [] line 12",
                "public" to "SourceOfCompiled.Unexported.h/1 OWN VALID via [] line 14",
            ),
            rows(
                "macro" to candidates(file, "SourceOfCompiled.Unexported.f(x)"),
                "public" to candidates(file, "SourceOfCompiled.Unexported.h(x)"),
            ),
        )
    }

    /** A head declares only the arities the `.beam` exports of those it admits: none of a splice's others, or a default's. */
    fun testAHeadDeclaresOnlyTheExportedArities() {
        dependency()
        val file = caller(
            """
            defmodule Caller do
              def none, do: SourceOfCompiled.Unexported.spliced()
              def capture, do: &SourceOfCompiled.Unexported.spliced/3
              def defaulted(x), do: SourceOfCompiled.Unexported.g(x)
            end
            """
        )
        val spliced = "SourceOfCompiled.Unexported.spliced/2 OWN WRONG_ARITY via [] line 4"

        assertEquals(
            rows(
                "none" to spliced,
                "capture" to spliced,
                "defaulted" to lines("SourceOfCompiled.Unexported.g/2 OWN WRONG_ARITY via []", 9, 16),
            ),
            rows(
                "none" to candidates(file, "SourceOfCompiled.Unexported.spliced()"),
                "capture" to capture(file, "SourceOfCompiled.Unexported.spliced", 3),
                "defaulted" to candidates(file, "SourceOfCompiled.Unexported.g(x)"),
            ),
        )
    }

    /** A source edited to define the module once answers from its heads, though the names its files define are unchanged. */
    fun testASourceEditedToDefineTheModuleOnceAnswersFromIt() {
        dependency()
        val file = caller("defmodule Caller do\n  def twice(x), do: SourceOfCompiled.Twice.g(x)\nend\n")
        val call = "SourceOfCompiled.Twice.g(x)"
        val before = candidates(file, call)

        val twice = read { PsiManager.getInstance(project).findFile(dir("deps/d/lib/twice.ex"))!! }
        edit(project, twice, read { twice.text }.substringBefore("\n\ndefmodule") + "\n")

        assertEquals(
            rows(
                "before" to "SourceOfCompiled.Twice.g/1 OWN VALID via []",
                "after" to "SourceOfCompiled.Twice.g/1 OWN VALID via [] line 3",
            ),
            rows("before" to before, "after" to candidates(file, call)),
        )
    }

    /**
     * A library with CLASSES only keeps the `.beam`, though a sibling library attaches the same module's source, and so
     * does its protocol, whose consolidated copy's library has CLASSES only too.
     */
    fun testALibraryWithNoSourcesKeepsTheBeam() {
        write(
            "classes_only/ebin" to beams(),
            "sibling/lib" to sources(),
            "_build/dev/consolidated" to listOf(PROTOCOL_BEAM),
        )
        exclude("classes_only", "sibling", "_build")
        BeamLibraryFixture.addLibrary(project, module, "classes_only", listOf(dir("classes_only/ebin")))
        BeamLibraryFixture.addLibrary(project, module, "sibling", emptyList(), listOf(dir("sibling/lib")))
        ModuleRootModificationUtil.addDependency(module, synced(CONSOLIDATED_LIBRARY_BASE_NAME))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val file = caller(
            """
            defmodule Caller do
              def remote(s), do: Phoenix.Component.assign(s, :k, 1)
              def protocol(x), do: SourceOfCompiled.Protocol.p(x)
            end
            """
        )

        assertEquals(
            rows(
                "remote" to "Phoenix.Component.assign/3 OWN VALID via []",
                "protocol" to "SourceOfCompiled.Protocol.p/1 OWN VALID via []",
            ),
            rows(
                "remote" to candidates(file, "Phoenix.Component.assign(s, :k, 1)"),
                "protocol" to candidates(file, "SourceOfCompiled.Protocol.p(x)"),
            ),
        )
    }

    /** A `.beam` that reaches the asker only through a module it depends on has no source in the asker's view. */
    fun testABeamThroughADependencyModuleKeepsTheBeam() {
        dependency(exported = true)
        val askerDirectory = FileUtil.createTempDirectory("asker", null)
        val asker = File(askerDirectory, "lib").apply { mkdirs() }
        File(asker, "caller.ex").writeText("defmodule Caller do\n  def single(s), do: Phoenix.Component.connected?(s)\nend\n")
        val askerRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(askerDirectory)!!
        val askerModule = PsiTestUtil.addModule(project, ModuleTypeManager.getInstance().defaultModuleType, "asker", askerRoot)
        ModuleRootModificationUtil.addDependency(askerModule, module)
        ModuleRootModificationUtil.setModuleSdk(askerModule, ModuleRootManager.getInstance(module).sdk)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val file = read { PsiManager.getInstance(project).findFile(askerRoot.findFileByRelativePath("lib/caller.ex")!!)!! }

        try {
            assertEquals("Phoenix.Component.connected?/1 OWN VALID via []", candidates(file, "Phoenix.Component.connected?(s)"))
        } finally {
            WriteAction.runAndWait<Throwable> { ModuleManager.getInstance(project).disposeModule(askerModule) }
            FileUtil.delete(askerDirectory)
        }
    }

    // The cost of reading the heads.

    /** A module's heads, the nine its clauses write with an atom name, are read from its source once; a second lookup reads none. */
    fun testASecondLookupInAModuleIndexesNothing() {
        dependency()
        val source = read { PsiManager.getInstance(project).findFile(dir("deps/d/lib/phoenix_component.ex")) as ElixirFile }

        val indexed = read {
            (1..2).map {
                ExpanderSource.Probe.reset()
                ExpanderSource.Probe.counting { NameIndex.of(source, "Phoenix.Component") }
                ExpanderSource.Probe.snapshot().getValue(ExpanderSource.Probe.Counter.INDEXED)
            }
        }

        assertEquals(listOf(9L, 0L), indexed)
    }

    // What the user sees, with the expander behind every feature.

    fun testGoToDeclarationLandsInTheSource() {
        dependency()
        caller(
            """
            defmodule Caller do
              def default(s), do: String.split(s, ",")
              def remote(s), do: Phoenix.Component.assign(s, :k, 1)
              def single(s), do: Phoenix.Component.connected?(s)
              def own_message(e), do: Phoenix.Component.OwnMessage.message(e)
            end
            """
        )
        val split = sdkHeads("lib/string.ex", "def split(string, pattern, options \\\\ [])").first()
        val landings = {
            rows(
                "default" to destinations("String.sp<caret>lit(s, \",\")"),
                "remote" to destinations("Phoenix.Component.ass<caret>ign(s, :k, 1)"),
                "single" to destinations("Phoenix.Component.conn<caret>ected?(s)"),
                "own message" to destinations("Phoenix.Component.OwnMessage.mes<caret>sage(e)"),
            )
        }
        val expected = rows(
            "default" to "elixir/lib/string.ex:$split",
            "remote" to ASSIGN_HEADS.joinToString("\n") { "deps/d/lib/phoenix_component.ex:$it" },
            "single" to "deps/d/lib/phoenix_component.ex:9",
            "own message" to "deps/d/lib/phoenix_component.ex:41",
        )

        assertEquals("the walk", expected, landings())
        assertEquals("the expander", expected, withExpanderSwitch(landings))
    }

    fun testQuickDocShowsTheWrittenHeads() {
        dependency()
        caller(
            """
            defmodule Caller do
              def default(s), do: String.split(s, ",")
              def remote(s), do: Phoenix.Component.assign(s, :k, 1)
            end
            """
        )

        val docs = {
            listOf("String.sp<caret>lit(s, \",\")", "Phoenix.Component.ass<caret>ign(s, :k, 1)").map { marked ->
                myFixture.caret(CALLER, marked)
                StringUtil.unescapeXmlEntities(StringUtil.removeHtmlTags(myFixture.quickDocumentationAtCaret(project).orEmpty()))
            }
        }
        val walk = docs()
        val expander = withExpanderSwitch(docs)
        val (split, assign) = expander

        assertEquals("the walk's", walk, expander)

        assertEquals(
            listOf(true, false, true, false),
            listOf(
                "split(string, pattern, options \\\\ [])" in split,
                "x0" in split,
                "assign(socket_or_assigns, key, value)" in assign,
                "x0" in assign,
            ),
        )
    }

    fun testParameterInfoNamesTheHeadsParameters() {
        dependency()
        caller(
            """
            defmodule Caller do
              def default(s), do: String.split(s, ",")
              def remote(s), do: Phoenix.Component.assign(s, :k, 1)
            end
            """
        )

        val signatures = withExpanderSwitch {
            listOf("String.split(s, <caret>\",\")", "Phoenix.Component.assign(<caret>s, :k, 1)").map { marked ->
                myFixture.caret(CALLER, marked)
                myFixture.parameterInfoSignaturesAtCaret()
            }
        }

        assertEquals(
            listOf(emptyList<String>(), emptyList()),
            signatures.map { signature -> signature.filter { "x0" in it || "x1" in it } },
        )
        assertEquals(
            signatures.toString(),
            listOf(true, true),
            listOf(
                signatures[0].any { it.startsWith("string, pattern") },
                signatures[1].any { it.startsWith("socket_or_assigns, key, value") },
            ),
        )
    }

    /**
     * After the qualifier, each public name the source writes once, from its head; for an exception module, as the walk
     * offers it.
     */
    fun testQualifiedCompletionOffersEachNameOnceFromItsSource() {
        dependency()
        caller("defmodule Caller do\n  def f, do: Phoenix.Component.\nend\n")

        val (names, files) = withExpanderSwitch {
            myFixture.caret(CALLER, "Phoenix.Component.<caret>")
            val items = myFixture.completeBasic().orEmpty()

            items.map { it.lookupString }.sorted() to
                items.filter { it.lookupString == "assign" }.map { read { it.psiElement?.containingFile?.name } }
        }

        assertEquals(WRITTEN, names.filter { it in WRITTEN })
        assertEquals(listOf("phoenix_component.ex"), files)
    }

    /** A module `defexception` writes completes from its `.beam` as the expander completes it written in the project. */
    fun testQualifiedCompletionOfAnExceptionIsItsProjectSources() {
        caller("defmodule Caller do\n  def f, do: Phoenix.Component.Error.\nend\n")
        val error = myFixture.addFileToProject("lib/error.ex", "defmodule Phoenix.Component.Error do\n  defexception [:message]\nend\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val written = errorCompletion()
        assertEquals(listOf("exception", "message"), written.map { it.substringBefore(' ') })
        WriteAction.runAndWait<Throwable> { error.virtualFile.delete(this) }

        dependency()

        assertEquals(written, errorCompletion())
    }

    /** What completion offers of `exception` and `message` after `Phoenix.Component.Error.` in `lib/caller.ex`, the switch on. */
    private fun errorCompletion(): List<String> =
        withExpanderSwitch {
            myFixture.caret(CALLER, "Phoenix.Component.Error.<caret>")
            myFixture.completeBasic().orEmpty().filter { it.lookupString in setOf("exception", "message") }.map(::presented).sorted()
        }

    private fun presented(item: LookupElement): String {
        val presentation = LookupElementPresentation().also(item::renderElement)

        return "${item.lookupString} ${presentation.itemText} ${presentation.tailText} ${presentation.typeText}"
    }

    /**
     * The fixture's sources in `deps/d/lib` and their `.beam`s in `_build/dev/lib/d/ebin`, in the library Mix sync builds
     * for `d`, which the module is given, [exported] to the modules that depend on it.
     */
    private fun dependency(exported: Boolean = false) {
        write("deps/d/lib" to sources(), "_build/dev/lib/d/ebin" to beams())
        exclude("deps", "_build")
        ModuleRootModificationUtil.addDependency(module, synced("d"), DependencyScope.COMPILE, exported)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** The library Mix sync built from the project's `deps` and `_build` for [name], which the module is then given. */
    private fun synced(name: String): Library {
        val scoped = scopedDepLibraryName(contentRootToken(project, root().url), name)
        val library = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(scoped)
        assertNotNull("Mix sync's `$scoped` library", library)

        return library!!
    }

    /** Copies each of the fixture's files named in each pair into the directory it names, under the project. */
    private fun write(vararg into: Pair<String, List<String>>) {
        for ((directory, names) in into) {
            val target = File(myFixture.tempDirPath, directory).apply { mkdirs() }
            names.forEach { File(FIXTURES, it).copyTo(File(target, it)) }
        }
        VfsUtil.markDirtyAndRefresh(false, true, true, root())
        // The `deps` and `_build` files queue a Mix deps sync, whose libraries are the ones attached.
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
    }

    private fun exclude(vararg directories: String) {
        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.single()
            directories.forEach { entry.addExcludeFolder("${root().url}/$it") }
        }
    }

    private fun sources(): List<String> = fixtures(".ex")

    private fun beams(): List<String> = fixtures(".beam")

    private fun fixtures(extension: String): List<String> = FIXTURES.list()!!.filter { it.endsWith(extension) }.sorted()

    private fun root(): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(myFixture.tempDirPath)!!

    private fun dir(path: String): VirtualFile = myFixture.findFileInTempDir(path)

    private fun caller(text: String): PsiFile {
        val file = myFixture.addFileToProject(CALLER, text.trimIndent() + "\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return file
    }

    /** The 1-based lines in the leg SDK's [path] of the first line starting [head], then of each clause of its name after it. */
    private fun sdkHeads(path: String, head: String): List<Int> {
        val lines = LegManifest.source(path).readLines()
        val at = lines.indexOfFirst { it.trim().startsWith(head) }
        assertTrue("`$head` in the leg's $path", at >= 0)
        val clause = head.substringBefore('(') + "("

        return listOf(at + 1) + (at + 1 until lines.size).filter { lines[it].trim().startsWith(clause) }.map { it + 1 }
    }

    /** `label: rendered` pairs, one block each, so one comparison shows every row that differs. */
    private fun rows(vararg rows: Pair<String, String>): String = rows.joinToString("\n\n") { (label, rendered) -> "$label:\n$rendered" }

    private fun lines(prefix: String, vararg lines: Int): String = lines.joinToString("\n") { "$prefix line $it" }

    /** The expander's candidates for the call [call], which occurs once in [file], rendered one per line. */
    private fun candidates(file: PsiFile, call: String): String = candidates(use(file, call))

    private fun candidates(use: Use): String = render(read { ExpanderSource.candidates(use, false) })

    /** The expander's candidates for the capture `&`[name]`/`[arity], which occurs once in [file]. */
    private fun capture(file: PsiFile, name: String, arity: Int): String =
        candidates(read { Use.capture(ExpanderSourceRows.call(file, "&$name/$arity", name), arity)!! })

    /** The use that is the call [call], which occurs once in [file]. */
    private fun use(file: PsiFile, call: String): Use.OfCall = ExpanderSourceRows.use(file, call, call)

    /** Where Go to Declaration at [marked] in the caller lands: each target's file, relative, and line. */
    private fun destinations(marked: String): String {
        myFixture.caret(CALLER, marked)

        return when (val navigation = myFixture.gtduNavigationAtCaret()) {
            is GtduNavigation.GotoDeclaration -> navigation.targets.map { target -> read { place(target.destination!!) } }.sorted().joinToString("\n")
            GtduNavigation.None -> "none"
            is GtduNavigation.ShowUsages -> "show usages"
        }
    }

    private companion object {
        val FIXTURES: File = File("testData/org/elixir_lang/expander/source_of_compiled").absoluteFile
        const val CALLER = "lib/caller.ex"
        const val PROTOCOL_BEAM = "Elixir.SourceOfCompiled.Protocol.beam"

        /** The lines of the heads `phoenix_component.ex` writes for `assign`. */
        val ASSIGN_HEADS = intArrayOf(3, 5, 7)

        /** The public names `phoenix_component.ex` writes in `Phoenix.Component`, in order. */
        val WRITTEN = listOf("assign", "connected?", "is_assigns", "render", "slot", "visible")
    }
}
