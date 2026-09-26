package org.elixir_lang.refactoring

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.ide.structureView.StructureViewTreeElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.structure_view.Model
import org.elixir_lang.code_insight.gotoDeclarationLineAtCaret
import org.elixir_lang.code_insight.gotoDeclarationLinesAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.documentation.quickDocumentationAtCaret

/**
 * A function defined under a compile-time `if` is imported and called like any other: Go To reaches it, Find Usages
 * finds the calls and rename renames them, from a definition in either branch.
 */
class ConditionalDefinitionScopeTest : PlatformTestCase() {
    private val source = """
        defmodule Definer do
          if Code.ensure_loaded?(Kernel) do
            def snoc(q, x), do: {q, x}
          else
            def other(q), do: q
          end
        end

        defmodule Caller do
          import Definer

          def unqualified(a, b), do: snoc(a, b)
          def piped(a, b), do: a |> snoc(b)
          def in_else(a), do: other(a)
        end
    """.trimIndent()

    fun testGoToFromAnImportedCallReachesTheDefinitionUnderTheIf() =
        assertGoesTo("do: sn<caret>oc(a, b)", "def snoc(q, x), do: {q, x}")

    fun testGoToFromAnImportedPipedCallReachesTheDefinitionUnderTheIf() =
        assertGoesTo("a |> sn<caret>oc(b)", "def snoc(q, x), do: {q, x}")

    fun testGoToFromAnImportedCallReachesTheDefinitionInTheElse() =
        assertGoesTo("do: ot<caret>her(a)", "def other(q), do: q")

    fun testFindUsagesFromADefinitionUnderTheIfFindsTheImportedCalls() {
        configure("def sn<caret>oc(q, x)")
        val text = myFixture.file.text

        val lines = myFixture.psiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .map { usage -> text.substring(0, usage.range.startOffset).count { it == '\n' } + 1 }
            .sorted()

        assertEquals(listOf("do: snoc(a, b)", "a |> snoc(b)").map { line(it) }, lines)
    }

    fun testRenamingFromAnImportedCallRenamesTheDefinitionAndBothCalls() {
        configure("do: sn<caret>oc(a, b)")

        myFixture.renameTargetAtCaret("renamed")

        assertEquals(
            source.replace("def snoc(q, x)", "def renamed(q, x)")
                .replace("do: snoc(a, b)", "do: renamed(a, b)")
                .replace("a |> snoc(b)", "a |> renamed(b)"),
            myFixture.editor.document.text
        )
    }

    /**
     * A definition in a `case`, `cond` or `receive` clause, or a `try` or `with` body, belongs to the module around it,
     * as one under an `if` does; a `quote`, which is a scope of its own for `use`, does not.
     */
    fun testADefinitionInAnyConditionalBelongsToItsModule() {
        myFixture.configureByText(
            "wrappers.ex",
            """
            defmodule InCase do
              case Code.ensure_loaded?(Kernel) do
                true -> def snoc(q, x), do: {q, x}
                false -> def snoc(q, x), do: q
              end
            end

            defmodule InCond do
              cond do
                Code.ensure_loaded?(Kernel) -> def snoc(q, x), do: {q, x}
                true -> def snoc(q, x), do: q
              end
            end

            defmodule InTry do
              try do
                def snoc(q, x), do: {q, x}
              rescue
                _ -> :ok
              end
            end

            defmodule InWith do
              with true <- Code.ensure_loaded?(Kernel) do
                def snoc(q, x), do: {q, x}
              end
            end

            defmodule InReceive do
              receive do
                :go -> def snoc(q, x), do: {q, x}
              after
                0 -> def snoc(q, x), do: q
              end
            end

            defmodule InQuote do
              defmacro __using__(_) do
                quote do
                  def snoc(q, x), do: {q, x}
                end
              end
            end
            """.trimIndent()
        )

        val calls = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, org.elixir_lang.psi.call.Call::class.java)
        val modules = calls
            .filter { it.text.startsWith("def snoc") }
            .map { org.elixir_lang.model.psi.function.FunctionSymbol.fromDeclaration(it).firstOrNull()?.moduleName }
        val listed = calls
            .filter { org.elixir_lang.psi.Module.`is`(it) }
            .map { module ->
                org.elixir_lang.psi.Module.name(module) + ": " +
                    org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(module).count { it.text.startsWith("def snoc") }
            }

        // The `quote` is the scope of its definition, not `InQuote`, so no module is named for it.
        assertEquals(
            listOf("InCase", "InCase", "InCond", "InCond", "InTry", "InWith", "InReceive", "InReceive", null),
            modules
        )
        assertEquals(
            listOf("InCase: 2", "InCond: 2", "InTry: 1", "InWith: 1", "InReceive: 2", "InQuote: 0"),
            listed
        )
    }

    /**
     * Clauses in different branches do not compile together, so defaults in one do not make a clause in the other
     * part of the same function: only one branch's `f` is defined, as Elixir compiles it.
     */
    fun testDefaultsInOneBranchDoNotTakeInAClauseInAnother() {
        myFixture.configureByText(
            "branches.ex",
            """
            defmodule Branches do
              if Code.ensure_loaded?(Kernel) do
                def f(a, b \\ 1), do: {a, b}
              else
                def f(a), do: a
              end
            end

            defmodule Caller do
              def two(a), do: Branches.f(a, a)
              def one(a), do: Branches.f(a)
            end
            """.trimIndent()
        )

        fun goToFrom(call: String): List<String> {
            myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(call) + "Branches.".length + 1)

            return myFixture.gotoDeclarationLinesAtCaret()
        }

        assertEquals(listOf("""def f(a, b \\ 1), do: {a, b}"""), goToFrom("Branches.f(a, a)"))
        assertEquals(listOf("def f(a), do: a", """def f(a, b \\ 1), do: {a, b}"""), goToFrom("Branches.f(a)"))
    }

    fun testGoToFromAnImportedCallReachesADefinitionInACaseClause() {
        val cased = """
            defmodule Definer do
              case Code.ensure_loaded?(Kernel) do
                true -> def snoc(q, x), do: {q, x}
                false -> def snoc(q, x), do: q
              end
            end

            defmodule Caller do
              import Definer

              def unqualified(a, b), do: snoc(a, b)
            end
        """.trimIndent()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        myFixture.configureByText("cased.ex", cased.replace("do: snoc(a, b)", "do: sn<caret>oc(a, b)"))

        val landed = myFixture.gotoDeclarationLinesAtCaret()

        // Either branch's definition is the function, as from a local call.
        assertEquals(listOf("false -> def snoc(q, x), do: q", "true -> def snoc(q, x), do: {q, x}"), landed)
    }

    /** Everything that lists what a module declares sees a definition under a conditional, as the compiler does. */
    private val declared = """
        defmodule Definer do
          if Code.ensure_loaded?(Kernel) do
            @spec snoc(term(), term()) :: term()
            def snoc(q, x), do: {q, x}

            defmodule Inner do
              def inner, do: :inner
            end
          else
            def other(q), do: q
          end
        end

        defmodule Caller do
          import Definer, only: [snoc: 2]

          def nested, do: Definer.Inner.inner()
        end
    """.trimIndent()

    fun testGoToFromTheSpecOfADefinitionUnderTheIfReachesIt() =
        assertDeclaredGoesTo("@spec sn<caret>oc(", "def snoc(q, x), do: {q, x}")

    fun testGoToFromAnImportOnlyKeyReachesADefinitionUnderTheIf() =
        assertDeclaredGoesTo("only: [sn<caret>oc: 2]", "def snoc(q, x), do: {q, x}")

    /** A module under a conditional is nested in the module around it: `Definer.Inner`, not `if.Inner`. */
    fun testGoToFromACallOfAModuleNestedUnderTheIfReachesIt() =
        assertDeclaredGoesTo("Definer.Inner.in<caret>ner()", "def inner, do: :inner")

    fun testCompletionAfterTheModuleOffersDefinitionsInEveryBranch() {
        myFixture.configureByText("completion.ex", declared.replace("Definer.Inner.inner()", "Definer.<caret>"))

        myFixture.completeBasic()

        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("got $offered", offered.containsAll(listOf("snoc", "other")))
    }

    fun testTheStructureViewListsDefinitionsInEveryBranchUnderTheirModule() {
        myFixture.configureByText("structure.ex", declared)

        val entries = mutableListOf<String>()

        fun walk(element: StructureViewTreeElement, module: String?) {
            val text = element.presentation.presentableText.orEmpty()
            if (text.contains('/')) entries.add("$module $text")
            val here = if (element is org.elixir_lang.structure_view.element.modular.Module) text.removePrefix("defmodule ") else module
            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child, here)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root, null)

        assertEquals(
            listOf("Definer other/1", "Definer snoc/2", "Inner inner/0"),
            entries.filterNot { it.startsWith("Caller") }.sorted()
        )
    }

    /** A definition in a `case` or `cond` clause is found from its own module and from another, as one under `if` is. */
    fun testLocalAndQualifiedCallsReachADefinitionInACaseOrCondClause() {
        myFixture.configureByText(
            "case_cond.ex",
            """
            defmodule InCase do
              case Code.ensure_loaded?(Kernel) do
                true -> def snoc(q, x), do: {q, x}
                false -> def snoc(q, x), do: q
              end

              def local(a, b), do: snoc(a, b)
            end

            defmodule InCond do
              cond do
                Code.ensure_loaded?(Kernel) -> def snoc(q, x), do: {q, x}
                true -> def snoc(q, x), do: q
              end

              def local(a, b), do: snoc(a, b)
            end

            defmodule Caller do
              def remote(a, b), do: {InCase.snoc(a, b), InCond.snoc(a, b)}
            end
            """.trimIndent()
        )

        assertEquals(listOf(true, true, true, true), resolvedAt("snoc(a, b)"))
    }

    /** A definition in a `for` body belongs to the module around it, as the compiler defines it there. */
    fun testADefinitionInAForBodyIsListedByItsModule() {
        myFixture.configureByText(
            "for_body.ex",
            """
            defmodule ForDefs do
              for _ <- [1] do
                def in_for, do: :ok
              end
            end

            defmodule Caller do
              def calls, do: ForDefs.<caret>
            end
            """.trimIndent()
        )

        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("got $offered", "in_for" in offered)
        assertTrue("got ${structureEntries()}", "ForDefs in_for/0" in structureEntries())
    }

    /** A `__using__` under an `if` still injects, as the compiler defines it. */
    fun testUseInjectsFromAUsingUnderAnIf() {
        myFixture.configureByText(
            "using_under_if.ex",
            """
            defmodule Injector do
              if Code.ensure_loaded?(Kernel) do
                defmacro __using__(_) do
                  quote do
                    def injected, do: :ok
                  end
                end
              end
            end

            defmodule User do
              use Injector

              def calls, do: injected()
            end
            """.trimIndent()
        )

        assertEquals(listOf(true), resolvedAt("injected()"))
    }

    /** A module attribute, type or `@behaviour` under an `if` belongs to the module, as a definition there does. */
    fun testAttributesTypesAndBehavioursUnderAnIfBelongToTheModule() {
        val text = """
            defmodule Behaviour do
              @callback run() :: :ok
            end

            defmodule Attributed do
              if Code.ensure_loaded?(Kernel) do
                @limit 3
                @type t :: atom()
                @behaviour Behaviour
              end

              @spec limit(t) :: t
              def limit(_), do: @limit
              def run, do: :ok
            end
        """.trimIndent()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)

        fun lineOfDestination(caretAt: String): String? {
            myFixture.configureByText("attributed.ex", text.replace(caretAt.replace("<caret>", ""), caretAt))
            return myFixture.gotoDeclarationLineAtCaret()
        }

        assertEquals("@limit 3", lineOfDestination("do: @li<caret>mit"))
        assertEquals("@type t :: atom()", lineOfDestination("@spec limit(<caret>t)"))

        myFixture.configureByText("attributed.ex", text.replace("def run, do", "def r<caret>un, do"))
        val run = myFixture.enclosingCallAtCaret { org.elixir_lang.psi.CallDefinitionClause.`is`(it) }!!
        val callbacks = com.intellij.model.psi.PsiSymbolReferenceService.getService()
            .getReferences(run)
            .flatMap { it.resolveReference() }
            .filterIsInstance<org.elixir_lang.model.psi.callback.Callback>()
            .map { "${it.moduleName}.${it.name}" }
        assertEquals(listOf("Behaviour.run"), callbacks)
    }

    /** A variable or an `alias` inside an `if` stays there: lexical scope is not the module's. */
    fun testAVariableOrAliasInsideAnIfIsNotSeenAfterIt() {
        myFixture.configureByText(
            "lexical.ex",
            """
            defmodule Foo.Bar do
              def x, do: :ok
            end

            defmodule Lexical do
              if Code.ensure_loaded?(Kernel) do
                alias Foo.Bar
              end

              def after_alias, do: Bar.x()

              def after_variable do
                if true do
                  bound = 1
                end

                bound
              end
            end
            """.trimIndent()
        )

        assertEquals(listOf(false), resolvedAt("Bar.x()"))
        assertEquals(listOf(false), resolvedAt("bound\n"))
    }

    /** A function passed to `Enum.each`, `map` or `reduce` in a module body runs there, and defines in the module. */
    private val generated = """
        defmodule EachDefs do
          Enum.each([:a], fn _ ->
            @doc "Defined in a loop."
            def in_each(x), do: x
          end)

          def local(a), do: in_each(a)
        end

        defmodule MapDefs do
          Enum.map([:a], fn _ -> def in_map(x), do: x end)

          def local(a), do: in_map(a)
        end

        defmodule ReduceDefs do
          Enum.reduce([:a], :ok, fn _, acc ->
            def in_reduce(x), do: x
            acc
          end)

          def local(a), do: in_reduce(a)
        end

        defmodule Caller do
          def remote(a), do: {EachDefs.in_each(a), MapDefs.in_map(a), ReduceDefs.in_reduce(a)}
        end
    """.trimIndent()

    fun testLocalAndQualifiedCallsReachADefinitionInAFunctionPassedToEnum() {
        myFixture.configureByText("generated.ex", generated)

        assertEquals(
            listOf(listOf(true, true), listOf(true, true), listOf(true, true)),
            listOf("in_each(a)", "in_map(a)", "in_reduce(a)").map(::resolvedAt)
        )
    }

    fun testCompletionAndTheStructureViewSeeADefinitionInAFunctionPassedToEnum() {
        myFixture.configureByText("generated.ex", generated.replace("EachDefs.in_each(a)", "EachDefs.<caret>"))

        myFixture.completeBasic()

        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("got $offered", "in_each" in offered)
        assertTrue("got ${structureEntries()}", "EachDefs in_each/1" in structureEntries())
    }

    fun testQuickDocsShowTheDocOfADefinitionInAFunctionPassedToEnum() {
        myFixture.configureByText("generated.ex", generated.replace("do: in_each(a)", "do: in_ea<caret>ch(a)"))

        val documentation = myFixture.quickDocumentationAtCaret(project).orEmpty()

        assertTrue("got: $documentation", "Defined in a loop." in documentation)
    }

    /** A bodiless head with defaults and its clause in such a function are one function, as anywhere in the module. */
    fun testGoToFromACallLandsOnTheHeadAndClauseInAFunctionPassedToEnum() {
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        myFixture.configureByText(
            "generated_head.ex",
            """
            defmodule Gen do
              Enum.each([:a], fn _ ->
                def f(a, b \\ 1)
                def f(a, b), do: {a, b}
              end)

              def calls, do: <caret>f(1)
            end
            """.trimIndent()
        )

        assertEquals(listOf("def f(a, b \\\\ 1)", "def f(a, b), do: {a, b}"), myFixture.gotoDeclarationLinesAtCaret().sorted())
    }

    /** A function bound in a `quote` is called there, so what it defines is the `quote`'s, as for `Phoenix` route helpers. */
    fun testADefinitionInABoundFunctionBelongsToTheQuote() {
        myFixture.configureByText(
            "bound.ex",
            """
            defmodule Helpers do
              defmacro __using__(_) do
                quote do
                  helper = fn -> def from_helper, do: :ok end
                  helper.()
                end
              end
            end

            defmodule User do
              use Helpers

              def calls, do: from_helper()
            end
            """.trimIndent()
        )

        assertEquals(listOf(true), resolvedAt("from_helper()"))
    }

    /**
     * Whatever runs a function in the module body - a pipe, `Task.async`, a keyword list's callback - runs it while the
     * module compiles, and Elixir defines what it defines there; a variable bound in it stays lexical.
     */
    fun testADefinitionInAFunctionAnyCallRunsIsTheModules() {
        myFixture.configureByText(
            "any_runner.ex",
            """
            defmodule Runs do
              [:a] |> Enum.each(fn _ -> def in_pipe(x), do: x end)
              Task.async(fn -> def in_task(x), do: x end) |> Task.await()
              opts = [callback: fn -> def in_keyword(x), do: x end]
              opts[:callback].()

              def local(a), do: {in_pipe(a), in_task(a), in_keyword(a)}

              def after_variable do
                Enum.each([1], fn n -> bound = n end)
                bound
              end
            end

            defmodule Caller do
              def remote(a), do: {Runs.in_pipe(a), Runs.in_task(a), Runs.in_keyword(a)}
            end
            """.trimIndent()
        )

        assertEquals(
            listOf(listOf(true, true), listOf(true, true), listOf(true, true)),
            listOf("in_pipe(a)", "in_task(a)", "in_keyword(a)").map(::resolvedAt)
        )
        assertEquals(listOf(false), resolvedAt("bound\n"))
        assertTrue("got ${structureEntries()}", structureEntries().containsAll(listOf("Runs in_pipe/1", "Runs in_task/1")))
    }

    /** A call in a module-level `case` clause runs while the module compiles, and still reaches what is imported. */
    fun testACallInAModuleLevelCaseClauseReachesAnImport() {
        myFixture.configureByText(
            "case_import.ex",
            """
            defmodule Helper do
              def help(x), do: x
            end

            defmodule UsesCase do
              import Helper

              case :ok do
                :ok -> help(1)
              end
            end
            """.trimIndent()
        )

        assertEquals(listOf(true), resolvedAt("help(1)"))
    }

    /**
     * What stops module scope: a definition's body, which runs when it is called, and a macro whose definition puts its
     * block somewhere other than the module body, as ExUnit's `test` puts it in a `def`.
     */
    fun testADefinitionInADefinitionOrInAMacroBlockPutInADefIsNotTheModules() {
        myFixture.configureByText(
            "boundaries.ex",
            """
            defmodule MyCase do
              defmacro test(name, do: block) do
                quote do
                  def unquote(String.to_atom(name))(), do: unquote(block)
                end
              end
            end

            defmodule Bounded do
              import MyCase

              def outer do
                def in_def(x), do: x
              end

              test "x" do
                def in_test(x), do: x
              end

              def calls(a), do: {in_def(a), in_test(a)}
            end
            """.trimIndent()
        )

        assertEquals(listOf(false), resolvedAt("in_def(a)"))
        assertEquals(listOf(false), resolvedAt("in_test(a)"))
    }

    /**
     * An `import` is lexical, unlike a definition: it reaches from where it is written to the end of its block, so
     * one inside a carrier, or written after the call, or injected by a `use` written after it, does not reach the call.
     * Elixir 1.20 rejects each of these calls as undefined.
     */
    fun testAnImportDoesNotReachACallOutsideItsBlockOrBeforeIt() {
        myFixture.configureByText("lexical_import.ex", LEXICAL_IMPORT_FIXTURE)

        val reached = listOf(
            "helper(in_if)", "helper(in_fn)", "helper(in_case)", "helper(in_pipe)", "helper(after_def)",
            "helper(use_after_def)", "helper(in_body_other)"
        ).filter { resolvedAt(it).single() }

        assertEquals("reached by an import that does not reach them", emptyList<String>(), reached)
    }

    /** What an `import` does reach: the rest of its own block, the calls after it, and a `use`'s definitions anywhere. */
    fun testAnImportReachesWhatLexicallyFollowsItAndAUsesDefinitionsReachAll() {
        myFixture.configureByText("lexical_import.ex", LEXICAL_IMPORT_FIXTURE)

        val missed = listOf(
            "helper(in_block)", "helper(before_def)", "helper(use_before_def)", "helper(in_body)", "injected(after_use)"
        ).filterNot { resolvedAt(it).single() }

        assertEquals("not reached by an import or `use` that reaches them", emptyList<String>(), missed)
    }

    /** Whether each occurrence of [fragment] resolves to something valid. */
    private fun resolvedAt(fragment: String): List<Boolean> {
        val text = myFixture.file.text

        return generateSequence(text.indexOf(fragment)) { text.indexOf(fragment, it + 1).takeIf { next -> next >= 0 } }
            .map { offset ->
                generateSequence(myFixture.file.findElementAt(offset)) { it.parent }
                    .mapNotNull { it.reference as? com.intellij.psi.PsiPolyVariantReference }
                    .firstOrNull()
                    ?.multiResolve(false)
                    ?.any { it.isValidResult } == true
            }
            .toList()
    }

    private val LEXICAL_IMPORT_FIXTURE = """
        defmodule Helpers do
          def helper(x), do: {:helped, x}
        end

        defmodule Injects do
          defmacro __using__(_) do
            quote do
              import Helpers
              def injected(x), do: x
            end
          end
        end

        defmodule InIf do
          if true do
            import Helpers
            def in_block(a), do: helper(in_block)
          end

          def calls(a), do: helper(in_if)
        end

        defmodule InFn do
          Enum.each([1], fn _ -> import Helpers end)

          def calls(a), do: helper(in_fn)
        end

        defmodule InCase do
          case :ok do
            :ok -> import Helpers
          end

          def calls(a), do: helper(in_case)
        end

        defmodule InPipe do
          [1] |> Enum.each(fn _ -> import Helpers end)

          def calls(a), do: helper(in_pipe)
        end

        defmodule AfterDef do
          def calls(a), do: helper(after_def)

          import Helpers
        end

        defmodule BeforeDef do
          import Helpers

          def calls(a), do: helper(before_def)
        end

        defmodule UseAfterDef do
          def calls(a), do: helper(use_after_def)
          def calls_injected(a), do: injected(after_use)

          use Injects
        end

        defmodule UseBeforeDef do
          use Injects

          def calls(a), do: helper(use_before_def)
        end

        defmodule InBody do
          def imports(a) do
            import Helpers
            helper(in_body)
          end

          def calls(a), do: helper(in_body_other)
        end
    """.trimIndent()

    private fun structureEntries(): List<String> {
        val entries = mutableListOf<String>()

        fun walk(element: StructureViewTreeElement, module: String?) {
            val text = element.presentation.presentableText.orEmpty()
            if (text.contains('/')) entries.add("$module $text")
            val here = if (element is org.elixir_lang.structure_view.element.modular.Module) text.removePrefix("defmodule ") else module
            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child, here)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root, null)

        return entries
    }

    private fun assertDeclaredGoesTo(caretAt: String, targetLine: String) {
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        val plain = caretAt.replace("<caret>", "")
        assertEquals("`$plain` must occur once", 1, declared.split(plain).size - 1)
        myFixture.configureByText("declared.ex", declared.replace(plain, caretAt))

        val line = myFixture.gotoDeclarationLineAtCaret()

        assertNotNull("Go To Declaration from `$caretAt` went nowhere", line)
        assertEquals(targetLine, line)
    }

    private fun line(fragment: String): Int = source.substring(0, source.indexOf(fragment)).count { it == '\n' } + 1

    private fun configure(caretAt: String) {
        val plain = caretAt.replace("<caret>", "")
        assertEquals("`$plain` must occur once", 1, source.split(plain).size - 1)
        myFixture.configureByText("conditional_definition.ex", source.replace(plain, caretAt))
    }

    private fun assertGoesTo(caretAt: String, targetLine: String) {
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        configure(caretAt)

        val line = myFixture.gotoDeclarationLineAtCaret()

        assertNotNull("Go To Declaration from `$caretAt` went nowhere", line)
        assertEquals(targetLine, line)
    }
}
