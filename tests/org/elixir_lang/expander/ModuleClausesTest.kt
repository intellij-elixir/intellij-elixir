package org.elixir_lang.expander

import com.intellij.testFramework.LightVirtualFile
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.inspect

/**
 * The clauses a source module loads with: each definition its table holds, with the arguments, guards and body of each
 * clause as `def_to_clauses/6` gives them, and the escaped block of each `quote` a body holds.
 */
class ModuleClausesTest : ExpanderTestCase() {
    override val exports: Exports = AttributeFixtures.EXPORTS
    override val kernel: KernelImports = AttributeFixtures.KERNEL

    fun testEachClauseIsItsArgumentsGuardsAndBody() =
        assertClauses(
            """
            defmodule A do
              def f(x, y \\ 1) when is_integer(x) when x > 0, do: x + y
              defp g(1), do: :one
              defp g(_), do: :other
            end
            """,
            """
            module Elixir.A from a.ex
              def f/2: (x, y) when [is_integer(x), x > 0] -> x + y
              def f/1: (x0) -> super(x0, 1)
              defp g/1: (1) -> :one; (_) -> :other
            """,
        )

    /**
     * A default arity's clause binds the arguments it is given and calls the full arity through `super` with the
     * defaults it lacks, its variables numbered from the version the defaults' expansion left.
     */
    fun testADefaultArityCallsTheFullArity() =
        assertClauses(
            """
            defmodule A do
              def g(a, b = 2, c \\ 3, d \\ 4), do: {a, b, c, d}
              def h(a, b \\ (z = 1; z)), do: {a, b}
            end
            """,
            """
            module Elixir.A from a.ex
              def g/4: (a, b = 2, c, d) -> {a, b, c, d}
              def g/2: (x0, x1) -> super(x0, x1, 3, 4)
              def g/3: (x0, x1, x2) -> super(x0, x1, x2, 4)
              def h/2: (a, b) -> {a, b}
              def h/1: (x1) -> super(x1, z = 1; z)
            """,
        )

    /** Before 1.14 the variables are numbered from 0 whatever the defaults bind. */
    fun testADefaultArityBefore114NumbersFromZero() =
        assertClauses(
            """
            defmodule A do
              def h(a, b \\ (z = 1; z)), do: {a, b}
            end
            """,
            """
            module Elixir.A from a.ex
              def h/2: (a, b) -> {a, b}
              def h/1: (x0) -> super(x0, z = 1; z)
            """,
            version = "1.13.4",
        )

    /**
     * A kept quote in a definition that took `@file` keeps that file, which isn't ported, so B stops at it and loads no
     * clauses. The next definition is in the module's file again.
     */
    fun testAKeptQuoteUnderFileStops() =
        assertClauses(
            """
            defmodule A do
              @file "x.ex"
              def f, do: 1
              defmacro n, do: quote(location: :keep, do: v)
            end

            defmodule B do
              @file "x.ex"
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            """
            module Elixir.A from a.ex
              def f/0: () -> 1
              defmacro n/0: () -> quote(location: :keep, do: v) quoted {:{}, [], [:v, [keep: {"/project/a.ex", 4}], A]}
            """,
        )

    /** `@file` naming the module's own file doesn't move the definition, so its kept quote keeps that file. */
    fun testAKeptQuoteUnderFileNamingItsOwnFileKeepsIt() =
        assertClauses(
            """
            defmodule A do
              @file "/project/a.ex"
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            """
            module Elixir.A from a.ex
              defmacro m/0: () -> quote(location: :keep, do: v) quoted {:{}, [], [:v, [keep: {"/project/a.ex", 3}], A]}
            """,
        )

    /** A relative `@file` is made absolute as `filename:absname/1` makes it, against the directory the file compiles in. */
    fun testAKeptQuoteUnderARelativeFileNamingItsOwnFileKeepsIt() =
        assertClauses(
            """
            defmodule A do
              @file "./a.ex"
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            """
            module Elixir.A from a.ex
              defmacro m/0: () -> quote(location: :keep, do: v) quoted {:{}, [], [:v, [keep: {"/project/a.ex", 3}], A]}
            """,
            directories = { "/project" },
        )

    /** `filename:absname/1` drops an absolute path's `.` names and repeated separators. */
    fun testAKeptQuoteUnderAnUnnormalisedFileNamingItsOwnFileKeepsIt() =
        assertClauses(
            """
            defmodule A do
              @file "/project/.//a.ex"
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            """
            module Elixir.A from a.ex
              defmacro m/0: () -> quote(location: :keep, do: v) quoted {:{}, [], [:v, [keep: {"/project/a.ex", 3}], A]}
            """,
        )

    /** `filename:absname/1` keeps `..`, so a path through it names another file and moves the definition. */
    fun testAFileThroughAParentNamesAnotherFile() =
        assertClauses(
            """
            defmodule A do
              @file "/project/b/../a.ex"
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            "",
        )

    /** A drive-letter path is absolute wherever the plugin runs, as file paths are on Windows. */
    fun testAKeptQuoteUnderADriveLetterFileNamingItsOwnFileKeepsIt() =
        assertClauses(
            """
            defmodule A do
              @file "C:/project/a.ex"
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            """
            module Elixir.A from a.ex
              defmacro m/0: () -> quote(location: :keep, do: v) quoted {:{}, [], [:v, [keep: {"C:/project/a.ex", 3}], A]}
            """,
            file = "C:/project/a.ex",
        )

    /** On Windows `filename:absname/1` keeps a `//` root, so a file on a network share names itself. */
    fun testAKeptQuoteUnderAUncFileNamingItsOwnFileKeepsIt() =
        assertClauses(
            """
            defmodule A do
              @file "//server/share/a.ex"
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            """
            module Elixir.A from a.ex
              defmacro m/0: () -> quote(location: :keep, do: v) quoted {:{}, [], [:v, [keep: {"//server/share/a.ex", 3}], A]}
            """,
            file = "//server/share/a.ex",
        )

    /** On Windows `filename:absname/1` lowercases a drive letter, so either case names the same file. */
    fun testAKeptQuoteUnderALowercaseDriveLetterFileNamingItsOwnFileKeepsIt() =
        assertClauses(
            """
            defmodule A do
              @file "c:/project/a.ex"
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            """
            module Elixir.A from a.ex
              defmacro m/0: () -> quote(location: :keep, do: v) quoted {:{}, [], [:v, [keep: {"C:/project/a.ex", 3}], A]}
            """,
            file = "C:/project/a.ex",
        )

    /** `filename:absname/1` drops a `.` only before a separator, so a path ending in one names another file. */
    fun testAFileEndingInADotNamesAnotherFile() =
        assertClauses(
            """
            defmodule A do
              @file "/project/a.ex/."
              defmacro m, do: quote(location: :keep, do: v)
            end
            """,
            "",
        )

    fun testAHeadHasNoClause() =
        assertClauses(
            """
            defmodule A do
              def k(a, b)
              def k(a, 1), do: a
            end
            """,
            """
            module Elixir.A from a.ex
              def k/2: (a, 1) -> a
            """,
        )

    fun testABodyWithMoreThanDoIsATry() =
        assertClauses(
            """
            defmodule A do
              def r(x) do
                x
              rescue
                _ -> :error
              end
            end
            """,
            """
            module Elixir.A from a.ex
              def r/1: (x) -> try(do
                x
              rescue
                _ -> :error
              end)
            """,
        )

    fun testAQuoteIsItsEscapedBlock() =
        assertClauses(
            """
            defmodule A do
              defmacro m(a), do: quote(do: unquote(a))
              defmacro v, do: quote(do: x)
            end
            """,
            """
            module Elixir.A from a.ex
              defmacro m/1: (a) -> quote(do: unquote(a)) quoted {{:., [line: 2], [:elixir_quote, :unquote]}, [line: 2], [{:a, [line: 2], nil}]}
              defmacro v/0: () -> quote(do: x) quoted {:{}, [], [:x, [], A]}
            """,
        )

    /** Each call a `quote` escapes is at that call, and what the escape adds is at the `quote`. */
    fun testAnEscapedCallIsAtTheCallItQuotes() {
        val source = "defmodule A do\n  defmacro c, do: quote(do: f(g(1)))\nend"
        val clauses = loaded(source).single()
        val body = clauses.definitions.values.single().clauses!!.single().body
        val block = clauses.quotes.of(body!!) as ElixirAst.Tuple
        val (_, _, arguments) = block.elements
        val inner = (arguments as ElixirAst.ListNode).elements.single() as ElixirAst.Tuple
        val text = { node: ElixirAst -> node.meta.origin.substring(source) }

        assertEquals(
            listOf("f(g(1))", "quote(do: f(g(1)))", "g(1)", "quote(do: f(g(1)))"),
            listOf(text(block), text(block.elements[0]), text(inner), text(inner.elements[1])),
        )
    }

    fun testADefinitionRedefinedAfterDefoverridableHasOnlyItsNewClauses() =
        assertClauses(
            """
            defmodule A do
              def f(x), do: x
              defoverridable f: 1
              def f(x), do: x + 1
            end
            """,
            """
            module Elixir.A from a.ex
              def f/1: (x) -> x + 1
            """,
        )

    fun testAnOverridableDefinitionNotRedefinedKeepsItsClauses() =
        assertClauses(
            """
            defmodule A do
              def f(x), do: x
              defoverridable f: 1
              def g, do: 1
            end
            """,
            """
            module Elixir.A from a.ex
              def g/0: () -> 1
              def f/1: (x) -> x
            """,
        )

    fun testSuperKeepsTheOverriddenClausesUnderItsHiddenName() =
        assertClauses(
            """
            defmodule A do
              def f(x), do: x
              defoverridable f: 1
              def f(x), do: super(x)
            end
            """,
            """
            module Elixir.A from a.ex
              defp f (overridable 1)/1: (x) -> x
              def f/1: (x) -> super(x)
            """,
        )

    /** Elixir may not define a definition in a branch the evaluator can't select, so what the module defines isn't read. */
    fun testAModuleWithADefinitionInABranchTheEvaluatorCannotSelectHasNoClauses() =
        assertClauses(
            """
            defmodule A do
              if (receive do after 0 -> true end) do
                def f, do: 1
              end

              def g, do: 2
            end
            """,
            "",
        )

    fun testAModuleThatDoesNotLoadHasNoClauses() =
        assertClauses(
            """
            defmodule C do
              def h, do: 5
            end

            defmodule A do
              def f, do: 1
              require U
              U.f()
            end

            defmodule B do
              def g, do: 2
              defp g, do: 3
            end
            """,
            """
            module Elixir.C from a.ex
              def h/0: () -> 5
            """,
        )

    fun testANestedModuleHasItsOwnClausesAndQuotes() =
        assertClauses(
            """
            defmodule A do
              defmodule B do
                defmacro m, do: quote(do: :b)
              end

              defmacro m, do: quote(do: :a)
            end
            """,
            """
            module Elixir.A.B from a.ex
              defmacro m/0: () -> quote(do: :b) quoted :b
            module Elixir.A from a.ex
              defmacro m/0: () -> quote(do: :a) quoted :a
            """,
        )

    /** The clauses each module of [code] loads with, as [render] shows them. */
    private fun assertClauses(
        code: String,
        expected: String,
        version: String = VERSION,
        file: String = FILE,
        directories: CompileDirectories = CompileDirectories.UNKNOWN,
    ) {
        val source = code.trimIndent()
        val rendered = loaded(source, version, file, directories).joinToString("\n") { render(it, source) }

        assertEquals(expected.trimIndent(), rendered)
    }

    /** The clauses each module of [source] loads with at [version], expanded as [file], compiled where [directories] says. */
    private fun loaded(
        source: String,
        version: String = VERSION,
        file: String = FILE,
        directories: CompileDirectories = CompileDirectories.UNKNOWN,
    ): List<ModuleClauses> {
        val level = ElixirLanguageLevel.of(version)
        val loaded = mutableListOf<ModuleClauses>()
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun clausesLoaded(module: String, clauses: ModuleClauses?) {
                clauses?.let { loaded += it }
            }
        }

        Expander.expandFile(
            lower(source, level), Env.empty(level, kernel), level, exports, structs, observer, file = file,
            directories = directories, virtualFile = LightVirtualFile("a.ex"),
        )

        return loaded
    }

    private fun render(clauses: ModuleClauses, source: String): String {
        lateinit var text: (ElixirAst) -> String
        // A node the expander built, such as a default arity's call, has no text of its own.
        text = { node: ElixirAst ->
            when {
                !node.meta.built -> node.meta.origin.substring(source)
                node is ElixirAst.Call ->
                    text(node.callee) + node.arguments?.joinToString(", ", "(", ")", transform = text).orEmpty()
                node is ElixirAst.Literal.Atom -> node.name
                else -> inspect(node.toOtp())
            }
        }
        val body = { node: ElixirAst ->
            if (node is ElixirAst.Call && (node.callee as? ElixirAst.Literal.Atom)?.name == "try" && text(node).startsWith("def")) {
                "try(${text(node.arguments!!.single())})"
            } else {
                text(node)
            }
        }
        val quoted = { node: ElixirAst ->
            quotes(node).mapNotNull { clauses.quotes.of(it) }.takeIf { it.isNotEmpty() }
                ?.joinToString(", ", " quoted ") { inspect(it.toOtp()) }
                .orEmpty()
        }

        return "module ${clauses.module} from ${clauses.file?.name}\n" +
            clauses.definitions.entries.joinToString("\n") { (nameArity, definition) ->
                val described = "  ${definition.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity}"

                definition.clauses?.joinToString("; ", "$described: ") { clause ->
                    val guards = clause.guards.takeIf { it.isNotEmpty() }?.joinToString(", ", " when [", "]", transform = text)
                        .orEmpty()

                    "(${clause.args.joinToString(", ", transform = text)})$guards -> ${clause.body?.let(body) ?: "stopped"}${clause.body?.let(quoted).orEmpty()}"
                } ?: "$described unknown"
            }
    }

    /** Each `quote` call in [node], in source order. */
    private fun quotes(node: ElixirAst): List<ElixirAst> =
        when (node) {
            is ElixirAst.Call ->
                buildList {
                    if (isCall(node, "quote", 1)) add(node)
                    addAll(quotes(node.callee))
                    node.arguments?.forEach { addAll(quotes(it)) }
                }
            is ElixirAst.Alias -> node.segments.flatMap(::quotes)
            is ElixirAst.ListNode -> node.elements.flatMap(::quotes)
            is ElixirAst.Tuple -> node.elements.flatMap(::quotes)
            is ElixirAst.Block -> node.expressions.flatMap(::quotes)
            is ElixirAst.Literal, is ElixirAst.Placeholder, is ElixirAst.Value -> emptyList()
        }

    private companion object {
        const val VERSION = "1.20.4"
        const val FILE = "/project/a.ex"
    }
}
