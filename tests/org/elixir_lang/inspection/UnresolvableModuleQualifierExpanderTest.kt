package org.elixir_lang.inspection

import com.intellij.openapi.projectRoots.Sdk
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.model.psi.config.withSigilInjection

/**
 * With Run the expander on, the undefined-module inspection reports a qualifier only where the expander is certain no
 * module it names is held; anywhere the expander can't be sure, it reports nothing.
 */
class UnresolvableModuleQualifierExpanderTest : LegSdkFixtureTestCase() {
    override fun setUpSdk(sdk: Sdk) {
        enableExpander(myFixture.testRootDisposable)
        myFixture.enableInspections(UnresolvableModuleQualifier::class.java)
        myFixture.addFileToProject("lib/known.ex", "defmodule Known.Deep do\n  def f, do: 1\nend\n")
    }

    fun testAnUndefinedModuleIsReported() =
        assertEquals(listOf(undefined("Missing")), reports("defmodule Caller do\n  def run, do: Missing.f()\nend"))

    fun testAnUndefinedModuleSpelledAfterElixirIsReported() =
        assertEquals(listOf(undefined("Elixir.Missing")), reports("defmodule Caller do\n  def run, do: Elixir.Missing.f()\nend"))

    fun testAnUndefinedModuleUnderTheCurrentModuleIsReported() =
        assertEquals(listOf(undefined("__MODULE__.Nope")), reports("defmodule Caller do\n  def run, do: __MODULE__.Nope.f()\nend"))

    fun testAModuleAnotherFileDefinesIsNotReported() =
        assertEmpty(reports("defmodule Caller do\n  def run, do: Known.Deep.f()\nend"))

    fun testAModuleAnotherFileDefinesNamedThroughAnAliasIsNotReported() =
        assertEmpty(reports("defmodule Caller do\n  alias Known.Deep\n\n  def run, do: Deep.f()\nend"))

    fun testAModuleAnotherFileDefinesNamedThroughARequireAsNameIsNotReported() =
        assertEmpty(reports("defmodule Caller do\n  require Known.Deep, as: M\n\n  def run, do: M.f()\nend"))

    fun testAModuleAnotherFileDefinesNamedThroughARequireAsNameOfAModuleRelativeAliasIsNotReported() {
        myFixture.addFileToProject("lib/sub.ex", "defmodule Foo.Sub do\n  def f, do: 1\nend\n")

        assertEmpty(reports("defmodule Foo do\n  require __MODULE__.Sub, as: M\n\n  def run, do: M.f()\nend"))
    }

    fun testAModuleABeforeCompileCallbackCreatesIsNotReported() {
        myFixture.addFileToProject("lib/definer.ex", definer())

        assertEmpty(reports("defmodule Caller do\n  def run, do: Created.FromBeforeCompile.Helpers.a()\nend"))
    }

    /** The callback runs before the module's functions can, so the module it creates is there when they call it. */
    fun testAModuleABeforeCompileCallbackCreatesIsNotReportedInTheModuleItsCallbackRunsFor() =
        assertEmpty(reports(definer("\n  def run, do: Created.FromBeforeCompile.Helpers.a()\n")))

    fun testAModuleABeforeCompileCallbackCreatesIsNotReportedLaterInItsFile() =
        assertEmpty(reports(definer() + "\ndefmodule Caller do\n  def run, do: Created.FromBeforeCompile.Helpers.a()\nend\n"))

    /** The quote's code runs in the module it is injected into, so `__MODULE__` there may name a module that defines `Sub`. */
    fun testAModuleUnderTheCurrentModuleInADefmacrosQuoteIsNotReported() =
        assertEmpty(
            reports("defmodule L do\n  defmacro m do\n    quote do\n      def run, do: __MODULE__.Sub.helper(1)\n    end\n  end\nend"),
        )

    fun testAModuleUnderTheCurrentModuleInABareQuoteIsNotReported() =
        assertEmpty(reports("defmodule L do\n  def code, do: quote(do: __MODULE__.Sub.helper(1))\nend"))

    fun testAModuleAUsedModulesUsingAliasesIsNotReported() {
        addConnCase()

        assertEmpty(
            reports("defmodule MyApp.RepoTest do\n  use MyApp.ConnCase\n\n  def test, do: Repo.insert!(%{})\nend"),
        )
    }

    /** The expander reads what the `use` adds, so an undefined module after it is reported, as the walk can't. */
    fun testAnUndefinedModuleAfterAUseTheExpanderExpandsIsReported() {
        addConnCase()

        assertEquals(
            listOf(undefined("Missing")),
            reports("defmodule MyApp.RepoTest do\n  use MyApp.ConnCase\n\n  def test, do: Missing.f()\nend"),
        )
    }

    /** What an undefined module's `__using__` would have added is unknown, so so is what the alias after it names. */
    fun testAModuleAfterAUseOfAnUndefinedModuleIsNotReported() =
        assertEmpty(reports("defmodule Caller do\n  use Undefined.Case\n\n  def run, do: Repo.insert!(%{})\nend"))

    /** An empty router makes no `Helpers`, whatever an alias of one is named. */
    fun testARouterHelpersAliasOfAnEmptyRouterIsReported() {
        myFixture.addFileToProject("lib/ic_web_router.ex", "defmodule IcWeb.Router do\nend\n")

        assertEquals(
            listOf(undefined("Helpers")),
            reports(
                "defmodule Caller do\n  alias IcWeb.Router.Helpers\n\n  def run, do: Helpers.device_url(Endpoint, :index, tag_id: 123)\nend",
            ),
        )
    }

    /** The expander doesn't answer inside a template, so nothing there is reported. */
    fun testAnUndefinedModuleInsideATemplateSigilIsNotReported() =
        assertEmpty(
            myFixture.withSigilInjection {
                reports("defmodule Caller do\n  def render(assigns) do\n    ~H\"<p><%= Missing.f() %></p>\"\n  end\nend")
            },
        )

    /** The inspection's reports in [text], written at `lib/caller.ex`, each as `<reported text>: <message>`. */
    private fun reports(text: String): List<String> {
        myFixture.configureFromExistingVirtualFile(caller(text).virtualFile)

        return myFixture.doHighlighting()
            .filter { it.inspectionToolId == "UnresolvableModuleQualifier" }
            .map { "${it.text}: ${it.description}" }
    }

    /** `MyApp.ConnCase`, whose `using` aliases `MyApp.Repo`, and the `MyApp.Repo` it aliases. */
    private fun addConnCase() {
        myFixture.addFileToProject("lib/repo.ex", "defmodule MyApp.Repo do\n  def insert!(x), do: x\nend\n")
        myFixture.addFileToProject("lib/conn_case.ex", CONN_CASE)
    }

    private companion object {
        const val CONN_CASE =
            "defmodule MyApp.ConnCase do\n  use ExUnit.CaseTemplate\n\n  using do\n    quote do\n      alias MyApp.Repo\n    end\n  end\nend\n"

        /**
         * `Created.Definer`, whose `@before_compile` callback creates `Created.FromBeforeCompile.Helpers`, and that
         * module, its body ending with [body].
         */
        fun definer(body: String = ""): String = """
            defmodule Created.Definer do
              defmacro __using__(_) do
                quote do
                  @before_compile Created.Definer
                  Module.register_attribute(__MODULE__, :names, accumulate: true)
                end
              end

              defmacro __before_compile__(env) do
                names = Module.get_attribute(env.module, :names)
                defs = for name <- names, do: quote(do: def(unquote(name)(), do: unquote(name)))
                Created.Definer.define(env, defs)
                :ok
              end

              def define(env, defs) do
                code = quote do: (unquote_splicing(defs))
                Module.create(Module.concat(env.module, Helpers), code, line: env.line, file: env.file)
              end
            end

            defmodule Created.FromBeforeCompile do
              use Created.Definer
              @names :a
        """.trimIndent() + "\n" + body + "end\n"

        fun undefined(module: String): String = "$module: Module '$module' is not defined or aliased in this scope"
    }
}
