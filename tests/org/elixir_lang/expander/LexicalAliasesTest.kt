package org.elixir_lang.expander

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.atLanguageLevel
import org.elixir_lang.mix.Project
import org.elixir_lang.mix.ProjectKeywords
import org.elixir_lang.mix.ProjectKeywords.UsesMixProject
import org.elixir_lang.psi.ElixirFile

class LexicalAliasesTest : PlatformTestCase() {
    /** phx_new runs this before its `defmodule`: a scope-opening form adds no alias around it. */
    fun testATopLevelForBeforeTheModuleKeepsTheProject() {
        assertProject(
            """
            for path <- :code.get_path(), Regex.match?(~r/phx_new-[\w\.\-]+\/ebin$/, List.to_string(path)) do
              Code.delete_path(path)
            end

            ${module("use Mix.Project")}
            """
        )
    }

    /** inflex starts Hex before its `defmodule`: `and` is `Kernel`'s, and the remote calls are function calls. */
    fun testATopLevelAndBeforeTheModuleKeepsTheProject() {
        assertProject("Code.ensure_loaded?(Hex) and Hex.start()\n\n${module("use Mix.Project")}")
    }

    fun testAModuleAttributeBeforeTheUseKeepsTheProject() {
        assertProject(module("@moduledoc false\nuse Mix.Project"))
    }

    /** `Kernel.defmodule` is the `defmodule` macro, so it aliases its first segment as `defmodule` does. */
    fun testAQualifiedNestedDefmoduleMakesMixProjectAnotherModule() {
        assertModule(UsesMixProject.NO, module("Kernel.defmodule Mix.Project do\nend\n\nuse Mix.Project"))
    }

    /** `defimpl`'s module name is computed, so it aliases nothing. */
    fun testAnImplementationBeforeTheUseKeepsTheProject() {
        assertProject(module("defimpl String.Chars, for: Integer do\n  def to_string(_), do: \"\"\nend\n\nuse Mix.Project"))
    }

    /** A `use` may define any alias, so what a later `use` names isn't known. */
    fun testAUseBeforeTheUseMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("use Foo\nuse Mix.Project"))
    }

    /** No alias applies to an `Elixir.`-rooted name, so what it names is known after a `use`. */
    fun testARootedUseAfterAUseKeepsTheProject() {
        assertProject(module("use Foo\nuse Elixir.Mix.Project"))
    }

    /** No alias applies to an atom, so what it names is known after a `use`. */
    fun testAnAtomUseAfterAUseKeepsTheProject() {
        assertProject(module("use Foo\nuse :\"Elixir.Mix.Project\""))
    }

    /** `defmodule __MODULE__.Helper` defines `MyApp.MixProject.Helper` and aliases nothing. */
    fun testANestedDefmoduleThroughModuleBeforeTheUseKeepsTheProject() {
        assertProject(module("defmodule __MODULE__.Helper do\nend\n\nuse Mix.Project"))
    }

    /** A list's elements are expanded where it is written, so the `alias` in it aliases `Mix`. */
    fun testAListOfAnAliasOfMixMakesMixProjectAnotherModule() {
        assertModule(UsesMixProject.NO, module("[alias(Foo.Mix)]\nuse Mix.Project"))
    }

    /** A `def`'s head is a call of the function it defines, which reaches no macro that stores an alias. */
    fun testADefAfterAnImportBeforeTheUseKeepsTheProject() {
        assertProject(module("import Foo\ndef helper(a), do: a\nuse Mix.Project"))
    }

    /**
     * A known gap: a macro an `import` may bring, called inside an argument, isn't read, so whether it aliases `Mix`
     * isn't seen.
     */
    fun testAnImportedCallInAnArgumentBeforeTheUseKeepsTheProject() {
        assertProject(module("import Foo\nEnum.at([aliasing(Foo.Mix)], 0)\nuse Mix.Project"))
    }

    /** A function's body runs when it is called, so an `alias` in it aliases nothing around it. */
    fun testAFunctionWhoseBodyAliasesMixKeepsTheProject() {
        assertProject(module("fn -> alias Foo.Mix end\nuse Mix.Project"))
    }

    /** After an `import`, a local call may be the imported module's macro, which may define any alias. */
    fun testALocalCallAfterAnImportBeforeTheUseMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("import Foo\nfoo()\nuse Mix.Project"))
    }

    /** No `import` brings a special form, so `quote` is `Kernel.SpecialForms`' after one. */
    fun testASpecialFormAfterAnImportBeforeTheUseKeepsTheProject() {
        assertProject(module("import Foo\nquote do: 1\nuse Mix.Project"))
    }

    /** A macro of `Kernel` and an imported one of the same name make an ambiguous call, so an `import` alone shadows none. */
    fun testAKernelMacroAfterAnImportBeforeTheUseKeepsTheProject() {
        assertProject(module("import Foo\nunless true, do: 1\nuse Mix.Project"))
    }

    /** Once `Kernel` is imported without `if`, `if` may be another module's macro, which may define any alias. */
    fun testAKernelMacroAfterAnImportOfKernelExceptItMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("import Kernel, except: [if: 2]\nif true, do: 1\nuse Mix.Project"))
    }

    /** `alias` is a special form, so `Kernel.alias` is a call of a function `Kernel` doesn't define, and aliases nothing. */
    fun testAQualifiedKernelAliasBeforeTheUseMakesMixProjectAnotherModule() {
        assertModule(UsesMixProject.NO, module("Kernel.alias Mix, as: M\nuse M.Project"))
    }

    /** `Kernel` defines `if/2` only, so after an `import`, `if/3` may be the imported module's macro. */
    fun testAnIfOfAnotherArityAfterAnImportBeforeTheUseMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("import Foo\nif(true, 1, 2)\nuse Mix.Project"))
    }

    /** A remote call to a required module may be its macro, which may define any alias. */
    fun testARemoteCallToARequiredModuleBeforeTheUseMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("require Foo\nFoo.bar()\nuse Mix.Project"))
    }

    /** `Foo.if` is `Foo`'s call, not `Kernel`'s `if`, so after `require Foo` it may be a macro that defines any alias. */
    fun testARemoteIfOfARequiredModuleBeforeTheUseMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("require Foo\nFoo.if(true)\nuse Mix.Project"))
    }

    /** `should_warn/3` crashes on a `warn:` other than `true` or `false`, so the alias is never stored. */
    fun testAnAliasWhoseWarnIsNotABooleanMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("alias Mix.Project, warn: :maybe\nuse Project"))
    }

    /** `alias` raises on an option it doesn't take. */
    fun testAnAliasWithAnOptionItDoesNotTakeMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("alias Mix.Project, only: :functions\nuse Project"))
    }

    /** A `defmodule` without a body isn't `defmodule/2`, so it aliases nothing. */
    fun testADefmoduleWithoutABodyBeforeTheUseKeepsTheProject() {
        assertProject(module("defmodule Mix\nuse Mix.Project"))
    }

    /** A `defmodule` without a `do` defines nothing, so it aliases nothing. */
    fun testADefmoduleWithoutADoBeforeTheUseKeepsTheProject() {
        assertProject(module("defmodule Mix, []\nuse Mix.Project"))
    }

    /** `Application` is required by default, and `compile_env/2` stores no alias. */
    fun testACallToADefaultRequireBeforeTheUseKeepsTheProject() {
        assertProject(module("Application.compile_env(:a, :b)\nuse Mix.Project"))
    }

    /** Before 1.16 a `require` without `as:` removes the alias keyed by its module, so `Mix` names `Mix` again. */
    fun testARequireOfAnAliasedNameRemovesItsAliasBefore116() {
        atLanguageLevel(project, "1.15.0") {
            assertProject(module("alias Other.Mix\nrequire Elixir.Mix\nuse Mix.Project"))
        }
    }

    /** From 1.16 a `require` without `as:` stores nothing, so `Mix` still names `Other.Mix`. */
    fun testARequireOfAnAliasedNameKeepsItsAliasFrom116() {
        atLanguageLevel(project, "1.16.0") {
            assertModule(UsesMixProject.NO, module("alias Other.Mix\nrequire Elixir.Mix\nuse Mix.Project"))
        }
    }

    /** Before 1.16 an `import` implies a `require` without `as:`, whatever its `only:`, so it removes the alias too. */
    fun testAnImportOfAnAliasedNameRemovesItsAliasBefore116() {
        atLanguageLevel(project, "1.15.0") {
            assertProject(module("alias Other.Mix\nimport Elixir.Mix, only: [env: 0]\nuse Mix.Project"))
        }
    }

    /** From 1.16 an `import` stores nothing. */
    fun testAnImportOfAnAliasedNameKeepsItsAliasFrom116() {
        atLanguageLevel(project, "1.16.0") {
            assertModule(UsesMixProject.NO, module("alias Other.Mix\nimport Elixir.Mix, only: [env: 0]\nuse Mix.Project"))
        }
    }

    /** `__MODULE__` in an alias is the module around it, so `alias __MODULE__.Helpers` aliases `Helpers` only. */
    fun testAnAliasThroughModuleBeforeTheUseKeepsTheProject() {
        assertProject(module("alias __MODULE__.Helpers\nuse Mix.Project"))
    }

    /** `alias __MODULE__.Mix` aliases `Mix` to `MyApp.MixProject.Mix`, so `use Mix.Project` names another module. */
    fun testAnAliasThroughModuleOfMixMakesMixProjectAnotherModule() {
        assertModule(UsesMixProject.NO, module("alias __MODULE__.Mix\nuse Mix.Project"))
    }

    /** `__MODULE__` in a grouped alias is the module around it, so `alias __MODULE__.{Helpers, Tasks}` aliases those only. */
    fun testAGroupedAliasThroughModuleBeforeTheUseKeepsTheProject() {
        assertProject(module("alias __MODULE__.{Helpers, Tasks}\nuse Mix.Project"))
    }

    /** `alias __MODULE__.{Mix}` aliases `Mix` to `MyApp.MixProject.Mix`, so `use Mix.Project` names another module. */
    fun testAGroupedAliasThroughModuleOfMixMakesMixProjectAnotherModule() {
        assertModule(UsesMixProject.NO, module("alias __MODULE__.{Mix}\nuse Mix.Project"))
    }

    /** An alias whose module can't be read may alias `Mix`, so what the `use` names isn't known. */
    fun testAnAliasOfAnUnreadableModuleBeforeTheUseMakesItUnknown() {
        assertModule(UsesMixProject.UNKNOWN, module("alias unquote(m).Mix\nuse Mix.Project"))
    }

    private fun assertProject(text: String) {
        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs(text)))
    }

    /** That the file [text] has no project, and that its first module, the project module, is marked [expected]. */
    private fun assertModule(expected: UsesMixProject, text: String) {
        val mixExs = mixExs(text)

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))

        assertEquals(expected, ProjectKeywords.projectModules(mixExs).first().usesMixProject)
    }

    /** A project module whose [body] precedes its `project/0`. */
    private fun module(body: String): String =
        "defmodule MyApp.MixProject do\n${body.prependIndent("  ")}\n\n  def project, do: [app: :my_app]\nend\n"

    private fun mixExs(text: String): ElixirFile = myFixture.configureByText(Project.MIX_EXS, text.trimIndent()) as ElixirFile
}
