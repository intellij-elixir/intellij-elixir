package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.declaration.preferred
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import java.io.File
import java.util.concurrent.Callable

/** What [ExpanderSource] answers a use with, in a project whose module has the leg's SDK. */
class ExpanderSourceHeavyTest : HeavyTestCase() {
    override fun setUp() {
        super.setUp()
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testVia() {
        val file = file(
            """
            defmodule Target do
              def f(x), do: x
            end

            defmodule Viaing do
              import Target
              def g, do: f(1)
              defdelegate h(x), to: Target, as: :f
            end
            """
        )

        assertEquals("Target.f/1 IMPORT VALID via [import Target] line 2", candidates(file, "do: f(1)", "f(1)"))
        assertEquals(
            "Kernel.defdelegate/2 IMPLICIT_IMPORT VALID via []",
            candidates(file, "defdelegate h(x)", "defdelegate h(x), to: Target, as: :f"),
        )
    }

    fun testDelegationTargetReaches() {
        val file = file(
            """
            defmodule D do
              defdelegate keys(m), to: Map
              def own(m), do: D.keys(m)
            end

            defmodule User do
              import D
              def go(m), do: keys(m)
            end
            """
        )

        assertEquals(
            """
            D.keys/1 OWN VALID via [] line 2
            Map.keys/1 DELEGATION_TARGET VALID via [defdelegate keys(m), to: Map]
            """.trimIndent(),
            candidates(file, "D.keys(m)", "D.keys(m)"),
        )
        assertEquals(
            """
            D.keys/1 IMPORT VALID via [import D] line 2
            Map.keys/1 UNHELD_DELEGATION_TARGET VALID via [import D | defdelegate keys(m), to: Map]
            """.trimIndent(),
            candidates(file, "do: keys(m)", "keys(m)"),
        )
    }

    /** An `import` that narrows a `defdelegate` head leaves the head calling its target at the head's largest arity. */
    fun testANarrowedDelegationReachesItsTargetAtTheHeadsArity() {
        val file = file(
            """
            defmodule T do
              def f(a, b), do: {a, b}
            end

            defmodule D do
              defdelegate f(a, b \\ 1), to: T
            end

            defmodule U do
              import D, only: [f: 1]
              def go, do: f(1)
            end
            """
        )

        assertEquals(
            """
            D.f/1 IMPORT VALID via [import D, only: [f: 1]] line 6
            T.f/2 UNHELD_DELEGATION_TARGET VALID via [import D, only: [f: 1] | defdelegate f(a, b \\ 1), to: T] line 2
            """.trimIndent(),
            candidates(file, "do: f(1)", "f(1)"),
        )
    }

    /** A delegation the use's arity doesn't fit is still followed, and its targets don't fit either. */
    fun testAnUnfitDelegationIsFollowed() {
        val file = file(
            """
            defmodule D do
              defdelegate keys(m), to: Map
            end

            defmodule C do
              def go, do: D.keys(1, 2)
            end
            """
        )

        assertEquals(
            """
            D.keys/1 OWN WRONG_ARITY via [] line 2
            Map.keys/1 DELEGATION_TARGET WRONG_ARITY via [defdelegate keys(m), to: Map]
            """.trimIndent(),
            candidates(file, "D.keys(1, 2)", "D.keys(1, 2)"),
        )
    }

    /** An `import` that admits arities apart gives one candidate per run of them. */
    fun testAnImportOfAritiesApartGivesOneCandidatePerRun() {
        val file = file(
            """
            defmodule Pad do
              def pad(a, b \\ 1, c \\ 2), do: {a, b, c}
            end

            defmodule Padder do
              import Pad, only: [pad: 1, pad: 3]
              def go, do: pad(1)
            end
            """
        )

        assertEquals(
            """
            Pad.pad/1 IMPORT VALID via [import Pad, only: [pad: 1, pad: 3]] line 2
            Pad.pad/3 IMPORT WRONG_ARITY via [import Pad, only: [pad: 1, pad: 3]] line 2
            """.trimIndent(),
            candidates(file, "do: pad(1)", "pad(1)"),
        )
    }

    /** A delegation imported at arities apart is followed once, valid where any of its runs is. */
    fun testADelegationImportedAtAritiesApartReachesItsTargetOnce() {
        val file = file(
            """
            defmodule T do
              def pad(a, b, c), do: {a, b, c}
            end

            defmodule D do
              defdelegate pad(a, b \\ 1, c \\ 2), to: T
            end

            defmodule P do
              import D, only: [pad: 1, pad: 3]
              def go, do: pad(1)
              def go3, do: pad(1, 2, 3)
            end
            """
        )

        assertEquals(
            """
            D.pad/1 IMPORT VALID via [import D, only: [pad: 1, pad: 3]] line 6
            D.pad/3 IMPORT WRONG_ARITY via [import D, only: [pad: 1, pad: 3]] line 6
            T.pad/3 UNHELD_DELEGATION_TARGET VALID via [import D, only: [pad: 1, pad: 3] | defdelegate pad(a, b \\ 1, c \\ 2), to: T] line 2
            """.trimIndent(),
            candidates(file, "do: pad(1)", "pad(1)"),
        )
        assertEquals(
            """
            D.pad/1 IMPORT WRONG_ARITY via [import D, only: [pad: 1, pad: 3]] line 6
            D.pad/3 IMPORT VALID via [import D, only: [pad: 1, pad: 3]] line 6
            T.pad/3 UNHELD_DELEGATION_TARGET VALID via [import D, only: [pad: 1, pad: 3] | defdelegate pad(a, b \\ 1, c \\ 2), to: T] line 2
            """.trimIndent(),
            candidates(file, "pad(1, 2, 3)", "pad(1, 2, 3)"),
        )
    }

    fun testEachArityOfAnImportedDelegationListReachesItsOwnTarget() {
        val file = file(
            """
            defmodule T do
              def f(x), do: x
              def f(x, y), do: {x, y}
            end

            defmodule D do
              defdelegate [f(x), f(x, y)], to: T
            end

            defmodule P do
              import D
              def go, do: f(1, 2)
            end
            """
        )

        assertEquals(
            """
            D.f/1 IMPORT WRONG_ARITY via [import D] line 7
            T.f/1 UNHELD_DELEGATION_TARGET WRONG_ARITY via [import D | defdelegate [f(x), f(x, y)], to: T] line 2
            T.f/2 UNHELD_DELEGATION_TARGET WRONG_ARITY via [import D | defdelegate [f(x), f(x, y)], to: T] line 3
            D.f/2 IMPORT VALID via [import D] line 7
            T.f/1 UNHELD_DELEGATION_TARGET WRONG_ARITY via [import D | defdelegate [f(x), f(x, y)], to: T] line 2
            T.f/2 UNHELD_DELEGATION_TARGET VALID via [import D | defdelegate [f(x), f(x, y)], to: T] line 3
            """.trimIndent(),
            candidates(file, "f(1, 2)", "f(1, 2)"),
        )
    }

    /**
     * A module defined twice in one file is its last definition, as Elixir loads it, nested or not, and its own where a
     * use is.
     */
    fun testARedefinedModuleIsItsLastDefinition() {
        val file = file(
            """
            defmodule A do
              def old, do: :old
            end

            defmodule A do
              def new, do: :new
              def local, do: new()
            end

            defmodule B do
              defmodule Nested do
                def nested, do: :nested
              end
            end

            defmodule B.Nested do
              def top, do: :top
            end

            defmodule C do
              def go, do: A.new()
              def stale, do: A.old()
              def top, do: B.Nested.top()
              def nested, do: B.Nested.nested()
            end
            """
        )

        assertEquals(
            listOf(
                "A.new/0 OWN VALID via [] line 6",
                "",
                "A.new/0 OWN VALID via [] line 6",
                "B.Nested.top/0 OWN VALID via [] line 17",
                "",
            ),
            listOf(
                candidates(file, "A.new()", "A.new()"),
                candidates(file, "A.old()", "A.old()"),
                candidates(file, "do: new()", "new()"),
                candidates(file, "B.Nested.top()", "B.Nested.top()"),
                candidates(file, "B.Nested.nested()", "B.Nested.nested()"),
            ),
        )
    }

    /** The private definition `super` saves an overridden public one as stays private. */
    fun testASavedOverriddenDefinitionIsPrivate() {
        val file = file(
            """
            defmodule O do
              def f(x), do: x
              defoverridable f: 1
              def f(x), do: super(x)
            end

            defmodule Q do
              def go, do: O."f (overridable 1)"(1)
            end
            """
        )

        assertEquals(
            "O.f (overridable 1)/1 OWN PRIVATE via [] line 2",
            candidates(file, "O.\"f (overridable 1)\"(1)", "O.\"f (overridable 1)\"(1)"),
        )
    }

    /** Overriding a definition with defaults at its own arity leaves the arities its defaults added, as Elixir runs them. */
    fun testADefaultArityOutlivesAnOverrideOfItsDefinition() {
        val file = file(
            """
            defmodule Over do
              def f(x, y \\ 1), do: {x, y}
              defoverridable f: 2
              def f(x, y), do: {:new, x, y}
              def own, do: f(1)
            end

            defmodule OverUser do
              import Over, only: [f: 1]
              def imported, do: f(5)
              def remote, do: Over.f(6)
            end
            """
        )

        assertEquals(
            """
            Over.f/1 OWN VALID via [] line 2
            Over.f/2 OWN WRONG_ARITY via [] line 4
            """.trimIndent(),
            candidates(file, "do: f(1)", "f(1)"),
        )
        assertEquals("Over.f/1 IMPORT VALID via [import Over, only: [f: 1]] line 2", candidates(file, "do: f(5)", "f(5)"))
        assertEquals(
            """
            Over.f/1 OWN VALID via [] line 2
            Over.f/2 OWN WRONG_ARITY via [] line 4
            """.trimIndent(),
            candidates(file, "Over.f(6)", "Over.f(6)"),
        )
        assertEquals(
            """
            f/1 line 2
            f/2 line 4
            """.trimIndent(),
            renderVisible(visible(file, "do: f(1)", "f(1)").filter { it.lookupName == "f" }),
        )
    }

    /**
     * A module body that stops, raising or at what isn't ported, answers what it defined before the stop. Elixir loads
     * no module whose body raised, nor one with an error in a function, so another module reaches none of either.
     */
    fun testAStoppedModuleBodyAnswersWhatItDefinedBeforeTheStop() {
        val file = file(
            """
            defmodule M do
              defmacro m, do: receive(do: (x -> x), after: (0 -> :ok))
            end

            defmodule Stopped do
              require M
              def target(a), do: a
              def before_stop, do: target(2)
              M.m()
              def after_stop, do: target(3)
            end

            defmodule Raised do
              def target(a), do: a
              def before_error, do: target(0)
              1.f()
              def after_error, do: target(1)
            end
            """
        )
        // Elixir abandons a file at a module that raises, so each is last in its file.
        val tainted = file(
            """
            defmodule Tainted do
              def target(a), do: a
              def own, do: target(6)
              def g, do: __STACKTRACE__
            end
            """
        )
        val caller = file(
            """
            defmodule Caller do
              def stopped, do: Stopped.target(4)
              def raised, do: Raised.target(5)
              def tainted, do: Tainted.target(7)
            end
            """
        )

        assertEquals(mapOf("M" to "Compiled", "Stopped" to "Stopped", "Raised" to "Raised"), ended(file))
        // Before 1.15 the error in a function raises where it is.
        assertEquals(
            mapOf("Tainted" to if (FUNCTION_ERRORS_CONTINUE.isSufficient(legLevel())) "Tainted" else "Raised"),
            ended(tainted),
        )
        assertEquals(
            listOf(
                "Stopped.target/1 OWN VALID via [] line 7",
                "Stopped.target/1 OWN VALID via [] line 7",
                "Raised.target/1 OWN VALID via [] line 14",
                "Raised.target/1 OWN VALID via [] line 14",
                "Stopped.target/1 OWN VALID via [] line 7",
                "",
                "Tainted.target/1 OWN VALID via [] line 2",
                "",
            ),
            listOf(
                candidates(file, "target(2)", "target(2)"),
                candidates(file, "target(3)", "target(3)"),
                candidates(file, "target(0)", "target(0)"),
                candidates(file, "target(1)", "target(1)"),
                candidates(caller, "Stopped.target(4)", "Stopped.target(4)"),
                candidates(caller, "Raised.target(5)", "Raised.target(5)"),
                candidates(tainted, "target(6)", "target(6)"),
                candidates(caller, "Tainted.target(7)", "Tainted.target(7)"),
            ),
        )
    }

    /** A qualified `unquote` names no definition, so each of the module's is OPAQUE where the arity fits. */
    fun testAQualifiedUnquoteReachesEveryNameOpaquely() {
        val file = file(
            """
            defmodule Q do
              def f(a), do: a
              def g(a, b), do: {a, b}
            end

            defmodule Unquoter do
              defmacro go(name), do: quote(do: Q.unquote(name)(1))
            end
            """
        )

        assertEquals(
            """
            Q.f/1 OWN OPAQUE via [] line 2
            Q.g/2 OWN WRONG_ARITY via [] line 3
            """.trimIndent(),
            candidates(file, "Q.unquote(name)(1)", "Q.unquote(name)(1)"),
        )
    }

    /** A piped value is one more argument: `:x |> Left.unquote(name)(1)` calls with two. */
    fun testAPipedArgumentCountsInANamelessUsesArity() {
        val file = file(
            """
            defmodule A do
              def zero, do: 0
              def one(a), do: a
              def two(a, b), do: {a, b}
            end

            defmodule U do
              defmacro bare(name), do: quote(do: :x |> A.unquote(name))
              defmacro one(name), do: quote(do: :x |> A.unquote(name)(1))
            end
            """
        )

        assertEquals(
            listOf(
                "A.one/1 OWN OPAQUE via [] line 3\nA.two/2 OWN WRONG_ARITY via [] line 4\nA.zero/0 OWN WRONG_ARITY via [] line 2",
                "A.one/1 OWN WRONG_ARITY via [] line 3\nA.two/2 OWN OPAQUE via [] line 4\nA.zero/0 OWN WRONG_ARITY via [] line 2",
            ),
            listOf("bare" to "A.unquote(name)", "one" to "A.unquote(name)(1)").map { (macro, call) ->
                candidates(file, "defmacro $macro(", call).lines().filter { it.startsWith("A.") }.sorted().joinToString("\n")
            },
        )
    }

    /**
     * A `do` block is one more argument to the argument list it follows: `Left.unquote(name) do ... end` is a call to
     * `unquote/2`, which Elixir does not unquote, and `Left.unquote(name)(1) do ... end` calls with two.
     */
    fun testADoBlockCountsInTheArgumentListItFollows() {
        val file = file(
            """
            defmodule A do
              def zero, do: 0
              def one(a), do: a
              def two(a, b), do: {a, b}
            end

            defmodule U do
              defmacro bare(name) do
                quote do
                  A.unquote(name) do :ok end
                end
              end

              defmacro one(name) do
                quote do
                  A.unquote(name)(1) do :ok end
                end
              end
            end
            """
        )

        assertEquals(
            listOf(
                "",
                "A.one/1 OWN WRONG_ARITY via [] line 3\nA.two/2 OWN OPAQUE via [] line 4\nA.zero/0 OWN WRONG_ARITY via [] line 2",
            ),
            listOf("bare" to "A.unquote(name) do :ok end", "one" to "A.unquote(name)(1) do :ok end").map { (macro, call) ->
                candidates(file, "defmacro $macro(", call).lines().filter { it.startsWith("A.") }.sorted().joinToString("\n")
            },
        )
    }

    /** `Left.unquote(name)(args)` calls with `args`, so `unquote`'s own argument is not counted. */
    fun testANamelessUsesArityIsItsSecondArgumentList() {
        val file = file(
            """
            defmodule A do
              def zero, do: 0
              def one(a), do: a
              def two(a, b), do: {a, b}
            end

            defmodule U do
              defmacro bare(name), do: quote(do: A.unquote(name))
              defmacro empty(name), do: quote(do: A.unquote(name)())
              defmacro pair(name), do: quote(do: A.unquote(name)(1, 2))
            end
            """
        )

        assertEquals(
            listOf(
                "A.one/1 OWN WRONG_ARITY via [] line 3\nA.two/2 OWN WRONG_ARITY via [] line 4\nA.zero/0 OWN OPAQUE via [] line 2",
                "A.one/1 OWN WRONG_ARITY via [] line 3\nA.two/2 OWN WRONG_ARITY via [] line 4\nA.zero/0 OWN OPAQUE via [] line 2",
                "A.one/1 OWN WRONG_ARITY via [] line 3\nA.two/2 OWN OPAQUE via [] line 4\nA.zero/0 OWN WRONG_ARITY via [] line 2",
            ),
            listOf("bare" to "A.unquote(name)", "empty" to "A.unquote(name)()", "pair" to "A.unquote(name)(1, 2)").map { (macro, call) ->
                candidates(file, "defmacro $macro(", call).lines().filter { it.startsWith("A.") }.sorted().joinToString("\n")
            },
        )
    }

    /** Each `defdelegate` head a nameless use crosses must take the arity reaching it, and passes on its largest. */
    fun testANamelessUseFitsADelegationThroughTheHeadsCrossed() {
        val file = file(
            """
            defmodule T do
              def f(a, b), do: {a, b}
              def h(a, b \\ 1), do: {a, b}
            end

            defmodule D do
              defdelegate f(a, b \\ 1), to: T
              defdelegate one(a), to: T, as: :f
              defdelegate two(a, b), to: T, as: :h

              defmacro go(name), do: quote(do: D.unquote(name)(1))
            end

            defmodule C do
              defdelegate g(a), to: D, as: :f

              defmacro go(name), do: quote(do: C.unquote(name)(1))
            end
            """
        )

        assertEquals(
            listOf(
                """
                T.f/2 DELEGATION_TARGET OPAQUE via [defdelegate f(a, b \\ 1), to: T] line 2
                T.f/2 DELEGATION_TARGET WRONG_ARITY via [defdelegate one(a), to: T, as: :f] line 2
                T.h/1..2 DELEGATION_TARGET WRONG_ARITY via [defdelegate two(a, b), to: T, as: :h] line 3
                """.trimIndent(),
                """T.f/2 DELEGATION_TARGET OPAQUE via [defdelegate g(a), to: D, as: :f | defdelegate f(a, b \\ 1), to: T] line 2""",
            ),
            listOf("D.unquote(name)(1)", "C.unquote(name)(1)").map { call ->
                candidates(file, call, call).lines().filter { it.startsWith("T.") }.sorted().joinToString("\n")
            },
        )
    }

    /** An `import` that leaves out the use's arity leaves the head, and what it delegates to, a wrong arity. */
    fun testANamedUseAnImportLeavesOutIsAWrongArityThroughADelegation() {
        val file = file(
            """
            defmodule T do
              def pair(a, b), do: {a, b}
            end

            defmodule D do
              defdelegate pair(a, b \\ 1), to: T
            end

            defmodule U do
              import D, only: [pair: 2]

              def uses, do: pair(1)
            end
            """
        )

        assertEquals(
            """
            D.pair/2 IMPORT WRONG_ARITY via [import D, only: [pair: 2]] line 6
            T.pair/2 UNHELD_DELEGATION_TARGET WRONG_ARITY via [import D, only: [pair: 2] | defdelegate pair(a, b \\ 1), to: T] line 2
            """.trimIndent(),
            candidates(file, "def uses", "pair(1)").lines().filter { it.startsWith("D.") || it.startsWith("T.") }.sorted().joinToString("\n"),
        )
    }

    fun testAnExplicitKernelImportIsAnImport() {
        val file = file(
            """
            defmodule K do
              import Kernel, except: [max: 2]
              def m, do: min(1, 2)
            end

            defprotocol Pr do
              @doc "a" <> "b"
              def f(x)
            end
            """
        )

        assertEquals(
            "Kernel.min/2 IMPORT VALID via [import Kernel, except: [max: 2]]",
            candidates(file, "min(1, 2)", "min(1, 2)"),
        )
        assertEquals("Kernel.<>/2 IMPLICIT_IMPORT VALID via []", candidates(file, "\"a\" <> \"b\"", "\"a\" <> \"b\""))
    }

    fun testAnOuterImportIsSeenFromANestedModule() {
        val file = file(
            """
            defmodule Target do
              def f(x), do: x
            end

            defmodule Outer do
              import Target

              defmodule Inner do
                def g, do: f(1)
              end
            end
            """
        )

        assertEquals("Target.f/1 IMPORT VALID via [import Target] line 2", candidates(file, "do: f(1)", "f(1)"))
    }

    fun testAQualifiedCallToAPrivateDefinitionIsPrivate() {
        val file = file(
            """
            defmodule P do
              defp priv(x), do: x
              def own(x), do: __MODULE__.priv(x)
            end

            defmodule Q do
              def other(x), do: P.priv(x)
            end
            """
        )

        assertEquals("P.priv/1 OWN PRIVATE via [] line 2", candidates(file, "__MODULE__.priv(x)", "__MODULE__.priv(x)"))
        assertEquals("P.priv/1 OWN PRIVATE via [] line 2", candidates(file, "P.priv(x)", "P.priv(x)"))
    }

    /** A qualified call's module is the one its receiver expanded to, whatever form the receiver takes. */
    fun testAReceiverIsTheModuleItExpandsTo() {
        val file = file(
            """
            defmodule Foo.Bar do
              def f(x), do: x
            end

            defmodule Foo do
              defmodule Inner do
                def f(x), do: x
              end

              alias Foo.Bar, as: B

              def by_alias(x), do: B.f(x)
              def by_module(x), do: __MODULE__.Inner.f(x)
              def by_atom(x), do: :"Elixir.Foo.Bar".f(x)
              def by_parentheses(x), do: (__MODULE__).Inner.f(x)
              def by_pipe(x), do: x |> B.f()
              defmacro by_quote(x), do: quote(do: B.f(unquote(x)))
            end
            """
        )

        assertEquals("Foo.Bar.f/1 OWN VALID via [] line 2", candidates(file, "B.f(x)", "B.f(x)"))
        assertEquals("Foo.Inner.f/1 OWN VALID via [] line 7", candidates(file, "__MODULE__.Inner.f(x)", "__MODULE__.Inner.f(x)"))
        assertEquals("Foo.Bar.f/1 OWN VALID via [] line 2", candidates(file, ":\"Elixir.Foo.Bar\".f(x)", ":\"Elixir.Foo.Bar\".f(x)"))
        assertEquals(
            "Foo.Inner.f/1 OWN VALID via [] line 7",
            candidates(file, "(__MODULE__).Inner.f(x)", "(__MODULE__).Inner.f(x)"),
        )
        assertEquals("Foo.Bar.f/1 OWN VALID via [] line 2", candidates(file, "x |> B.f()", "B.f()"))
        assertEquals("Foo.Bar.f/1 OWN VALID via [] line 2", candidates(file, "B.f(unquote(x))", "B.f(unquote(x))"))
    }

    /**
     * A receiver is the module the file's expansion found at it, which the env alone doesn't give, or a quoted call's,
     * as `quote` expands it; where the expansion stopped before it, what it expands to in its env.
     */
    fun testAReceiverIsWhatTheFilesExpansionFound() {
        val file = file(
            """
            defmodule Foo.Bar do
              def f(x), do: x
            end

            defmodule Foo do
              alias Foo.Bar, as: B
              @mod Foo.Bar

              def by_attribute(x), do: @mod.f(x)

              def by_body_alias(x) do
                alias Foo.Bar, as: C
                C.f(x)
              end

              defmacro by_quote(x), do: quote(do: B.f(unquote(x)))

              defmodule Nested do
                def f(x), do: x
                def by_nested_module(x), do: __MODULE__.f(x)
              end

              def after_error(x) do
                1.f()
                B.f(x)
                @mod.f(x)
              end
            end
            """
        )

        assertEquals("Foo.Bar.f/1 OWN VALID via [] line 2", candidates(file, "do: @mod.f(x)", "@mod.f(x)"))
        assertEquals("Foo.Bar.f/1 OWN VALID via [] line 2", candidates(file, "C.f(x)", "C.f(x)"))
        assertEquals("Foo.Bar.f/1 OWN VALID via [] line 2", candidates(file, "B.f(unquote(x))", "B.f(unquote(x))"))
        assertEquals("Foo.Bar.f/1 OWN VALID via [] line 2", candidates(file, "B.f(x)", "B.f(x)"))
        assertEquals("", candidates(file, "    @mod.f(x)", "@mod.f(x)"))
        assertEquals("Foo.Nested.f/1 OWN VALID via [] line 19", candidates(file, "__MODULE__.f(x)", "__MODULE__.f(x)"))
    }

    /**
     * A node after a stop keeps the directives the statements before the stop left in its block, but not one a nested
     * scope made. A module that raises ends its file, so each stop is in a file of its own.
     */
    fun testANodeAfterAStopKeepsTheDirectivesBeforeIt() {
        file(
            """
            defmodule Dir.Target do
              def f(x), do: x
              def g(x), do: x
            end
            """
        )
        val byAlias = file(
            """
            defmodule ByAlias do
              def go(x) do
                alias Dir.Target, as: D
                fn -> alias Dir.Target, as: F end
                1.f()
                D.f(x)
                F.f(x)
              end
            end
            """
        )
        val byImport = file(
            """
            defmodule ByImport do
              def go(x) do
                import Dir.Target
                1.f()
                g(x)
              end
            end
            """
        )
        val inBody = file(
            """
            defmodule InBody do
              alias Dir.Target, as: E
              1.f()
              def late(x), do: E.f(x)
            end
            """
        )

        assertEquals("Dir.Target.f/1 OWN VALID via [] line 2", candidates(byAlias, "D.f(x)", "D.f(x)"))
        assertEquals("", candidates(byAlias, "F.f(x)", "F.f(x)"))
        assertEquals("Dir.Target.g/1 IMPORT VALID via [import Dir.Target] line 3", candidates(byImport, "g(x)", "g(x)"))
        assertEquals("Dir.Target.f/1 OWN VALID via [] line 2", candidates(inBody, "E.f(x)", "E.f(x)"))
    }

    /**
     * A qualifier that is a macro call is not the receiver of a call the macro builds. In a module body an attribute is
     * read by `Module.__get_attribute__`, known only when the body runs, so it names no module, even one that defines
     * the function.
     */
    fun testAMacroCallQualifierIsNotTheReceiverOfTheCallItBuilds() {
        val file = file(
            """
            defmodule Foo.Bar do
              def concat(list), do: list
            end

            defmodule Foo do
              @mod Foo.Bar
              @joined @mod.concat([A])

              def g(x, y), do: to_string(x).to_string(y)
            end
            """
        )

        assertEquals(
            listOf("", ""),
            listOf(
                candidates(file, "@mod.concat([A])", "@mod.concat([A])"),
                candidates(file, "to_string(x).to_string(y)", "to_string(x).to_string(y)"),
            ),
        )
    }

    fun testOnlyThePreferenceStepDropsACandidate() {
        val file = file(
            """
            defmodule Pinger do
              def ping(pid), do: GenServer.call(pid, :ping)
            end
            """
        )
        val use = use(file, "GenServer.call(pid, :ping)", "GenServer.call(pid, :ping)")
        val found = read { ExpanderSource.candidates(use, false) }

        assertEquals(
            """
            GenServer.call/2 OWN VALID via []
            GenServer.call/3 OWN WRONG_ARITY via []
            """.trimIndent(),
            render(found),
        )
        assertEquals("GenServer.call/2 OWN VALID via []", render(read { preferred(use, found, false) }.map { it.found }))
    }

    /** The special forms, at every arity Elixir takes them at, which `Kernel.SpecialForms` declares fewer of. */
    fun testSpecialFormsAreImplicitImports() {
        val file = file(
            """
            defmodule Target do
              def f(x), do: x
            end

            defmodule Forms do
              require Logger, as: L
              require Integer
              alias Foo.Baz
              import Target, only: [f: 1]
              import Bitwise
              def q, do: quote([line: 1], do: :x)

              def q1 do
                quote do
                  :y
                end
              end

              def comprehension, do: for(x <- [1], do: x)
              def withs, do: with({:ok, x} <- {:ok, 1}, do: x)
            end

            defmodule Super do
              def g(x), do: x
              defoverridable g: 1
              def g(x), do: super(x)
            end
            """
        )

        assertEquals(
            "Kernel.SpecialForms.require/1..2 IMPLICIT_IMPORT VALID via []",
            candidates(file, "require Logger", "require Logger, as: L"),
        )
        assertEquals("Kernel.SpecialForms.require/1..2 IMPLICIT_IMPORT VALID via []", candidates(file, "require Integer", "require Integer"))
        assertEquals("Kernel.SpecialForms.alias/1..2 IMPLICIT_IMPORT VALID via []", candidates(file, "alias Foo.Baz", "alias Foo.Baz"))
        assertEquals(
            "Kernel.SpecialForms.import/1..2 IMPLICIT_IMPORT VALID via []",
            candidates(file, "import Target, only", "import Target, only: [f: 1]"),
        )
        assertEquals("Kernel.SpecialForms.import/1..2 IMPLICIT_IMPORT VALID via []", candidates(file, "import Bitwise", "import Bitwise"))
        assertEquals(
            "Kernel.SpecialForms.quote/1..2 IMPLICIT_IMPORT VALID via []",
            candidates(file, "quote([line: 1], do: :x)", "quote([line: 1], do: :x)"),
        )
        assertEquals(
            "Kernel.SpecialForms.quote/1..2 IMPLICIT_IMPORT VALID via []",
            candidates(file, "quote do", "quote do\n      :y\n    end"),
        )
        assertEquals(
            "Kernel.SpecialForms.for/0+ IMPLICIT_IMPORT VALID via []",
            candidates(file, "for(x <- [1], do: x)", "for(x <- [1], do: x)"),
        )
        assertEquals(
            "Kernel.SpecialForms.with/0+ IMPLICIT_IMPORT VALID via []",
            candidates(file, "with({:ok, x} <- {:ok, 1}, do: x)", "with({:ok, x} <- {:ok, 1}, do: x)"),
        )
        assertEquals("Kernel.SpecialForms.super/0+ IMPLICIT_IMPORT VALID via []", candidates(file, "super(x)", "super(x)"))
    }

    fun testAnOnlyArityNarrowsTheRange() {
        val file = file(
            """
            defmodule Pad do
              def pad(a, b \\ 1), do: {a, b}
            end

            defmodule PadUser do
              import Pad, only: [pad: 1]
              def one, do: pad(1)
              def two, do: pad(1, 2)
            end
            """
        )

        assertEquals("Pad.pad/1 IMPORT VALID via [import Pad, only: [pad: 1]] line 2", candidates(file, "do: pad(1)", "pad(1)"))
        assertEquals(
            "Pad.pad/1 IMPORT WRONG_ARITY via [import Pad, only: [pad: 1]] line 2",
            candidates(file, "do: pad(1, 2)", "pad(1, 2)"),
        )
    }

    fun testADefmoduleCallsCandidates() {
        val file = file(
            """
            defmodule M do
            end
            """
        )

        assertEquals("Kernel.defmodule/2 IMPLICIT_IMPORT VALID via []", candidates(file, "defmodule M", "defmodule M do\nend"))
    }

    fun testAnOnlyListWithAnAliasIsNoImport() {
        val file = file(
            """
            defmodule Foo do
              def g(x), do: x
            end

            defmodule Baz do
              import Foo, only: [g: 1]
              def h, do: g(1)
            end

            defmodule Bar do
              import Foo, only: [Foo, g: 1]
              def h, do: g(1)
            end
            """
        )

        assertEquals("Foo.g/1 IMPORT VALID via [import Foo, only: [g: 1]] line 2", candidates(file, "do: g(1)\nend\n\ndefmodule Bar", "g(1)"))
        assertEquals("", candidates(file, "do: g(1)\nend\n", "g(1)", last = true))
    }

    fun testEveryHeadIsACandidate() {
        val file = file(
            """
            defmodule Multi do
              def multi(:a), do: 1
              def multi(:b), do: 2
              def go, do: multi(:a)
            end
            """
        )

        assertEquals(
            """
            Multi.multi/1 OWN VALID via [] line 2
            Multi.multi/1 OWN VALID via [] line 3
            """.trimIndent(),
            candidates(file, "do: multi(:a)", "multi(:a)"),
        )
    }

    fun testADelegationCycleStopsAtTheFirstRepeat() {
        val file = file(
            """
            defmodule CycleA do
              defdelegate f(x), to: CycleB
            end

            defmodule CycleB do
              defdelegate f(x), to: CycleA
            end

            defmodule RevisitA do
              defdelegate f(x), to: RevisitB, as: :g
              def h(x), do: {:h, x}
            end

            defmodule RevisitB do
              defdelegate g(x), to: RevisitA, as: :h
            end

            defmodule Caller do
              def cycle, do: CycleA.f(1)
              def revisit, do: RevisitA.f(1)
            end
            """
        )

        assertEquals(
            """
            CycleA.f/1 OWN VALID via [] line 2
            CycleB.f/1 DELEGATION_TARGET VALID via [defdelegate f(x), to: CycleB] line 6
            """.trimIndent(),
            candidates(file, "CycleA.f(1)", "CycleA.f(1)"),
        )
        assertEquals(
            """
            RevisitA.f/1 OWN VALID via [] line 10
            RevisitB.g/1 DELEGATION_TARGET VALID via [defdelegate f(x), to: RevisitB, as: :g] line 15
            RevisitA.h/1 DELEGATION_TARGET VALID via [defdelegate f(x), to: RevisitB, as: :g | defdelegate g(x), to: RevisitA, as: :h] line 11
            """.trimIndent(),
            candidates(file, "RevisitA.f(1)", "RevisitA.f(1)"),
        )
    }

    /**
     * Two modules that delegate each arity of one name to each other. A hop follows on only through the head it calls,
     * so the candidates grow with the square of the heads, not with every order of them.
     */
    fun testADelegationCycleFollowsOnlyTheHeadEachHopCalls() {
        val sizes = 1..5
        val file = file(
            buildString {
                for (n in sizes) {
                    for ((from, to) in listOf("A" to "B", "B" to "A")) {
                        appendLine("defmodule Cycle$from$n do")
                        for (k in 1..n) appendLine("  defdelegate f(${(1..k).joinToString { "a$it" }}), to: Cycle$to$n")
                        appendLine("end")
                    }
                    appendLine("defmodule CycleCaller$n do")
                    appendLine("  def go, do: CycleA$n.f(1)")
                    appendLine("end")
                }
            }
        )

        assertEquals(
            sizes.map { 2 * it * it },
            sizes.map { n -> read { ExpanderSource.candidates(use(file, "CycleA$n.f(1)", "CycleA$n.f(1)"), false) }.size },
        )
        assertEquals(
            """
            CycleA2.f/1 OWN VALID via [] line 11
            CycleB2.f/1 DELEGATION_TARGET VALID via [defdelegate f(a1), to: CycleB2] line 15
            CycleA2.f/2 DELEGATION_TARGET WRONG_ARITY via [defdelegate f(a1), to: CycleB2 | defdelegate f(a1), to: CycleA2] line 12
            CycleB2.f/2 DELEGATION_TARGET WRONG_ARITY via [defdelegate f(a1), to: CycleB2] line 16
            CycleA2.f/2 OWN WRONG_ARITY via [] line 12
            CycleB2.f/1 DELEGATION_TARGET WRONG_ARITY via [defdelegate f(a1, a2), to: CycleB2] line 15
            CycleB2.f/2 DELEGATION_TARGET WRONG_ARITY via [defdelegate f(a1, a2), to: CycleB2] line 16
            CycleA2.f/1 DELEGATION_TARGET WRONG_ARITY via [defdelegate f(a1, a2), to: CycleB2 | defdelegate f(a1, a2), to: CycleA2] line 11
            """.trimIndent(),
            candidates(file, "CycleA2.f(1)", "CycleA2.f(1)"),
        )
    }

    /** The heads a list of them in one `defdelegate` gives are each crossed once, not once between them. */
    fun testEachHeadOfADelegationListIsItsOwnRepeat() {
        val file = file(
            """
            defmodule ListA do
              defdelegate [f(x), g(x)], to: ListB
            end

            defmodule ListB do
              defdelegate f(x), to: ListA, as: :g
            end

            defmodule ListCaller do
              def go, do: ListA.f(1)
            end

            defmodule ArityA do
              defdelegate [f(x), f(x, y)], to: ArityB
            end

            defmodule ArityB do
              defdelegate f(x, y \\ 1), to: ArityA
            end

            defmodule ArityCaller do
              def go, do: ArityA.f(1)
            end
            """
        )

        assertEquals(
            """
            ListA.f/1 OWN VALID via [] line 2
            ListB.f/1 DELEGATION_TARGET VALID via [defdelegate [f(x), g(x)], to: ListB] line 6
            ListA.g/1 DELEGATION_TARGET VALID via [defdelegate [f(x), g(x)], to: ListB | defdelegate f(x), to: ListA, as: :g] line 2
            """.trimIndent(),
            candidates(file, "ListA.f(1)", "ListA.f(1)"),
        )
        assertEquals(
            """
            ArityA.f/1 OWN VALID via [] line 14
            ArityB.f/1..2 DELEGATION_TARGET VALID via [defdelegate [f(x), f(x, y)], to: ArityB] line 18
            ArityA.f/2 DELEGATION_TARGET VALID via [defdelegate [f(x), f(x, y)], to: ArityB | defdelegate f(x, y \\ 1), to: ArityA] line 14
            ArityA.f/2 OWN WRONG_ARITY via [] line 14
            ArityB.f/1..2 DELEGATION_TARGET WRONG_ARITY via [defdelegate [f(x), f(x, y)], to: ArityB] line 18
            ArityA.f/1 DELEGATION_TARGET WRONG_ARITY via [defdelegate [f(x), f(x, y)], to: ArityB | defdelegate f(x, y \\ 1), to: ArityA] line 14
            """.trimIndent(),
            candidates(file, "ArityA.f(1)", "ArityA.f(1)"),
        )
    }

    /**
     * A remote call reaches what the compilers add to a compiled module too: `__info__/1`, `module_info/0,1`, and a
     * behaviour's `behaviour_info/1`.
     */
    fun testARemoteCallReachesWhatTheCompilersAdd() {
        val file = file(
            """
            defmodule Caller do
              def info, do: Map.__info__(:functions)
              def all, do: Map.module_info()
              def callbacks, do: GenServer.behaviour_info(:callbacks)
              def none, do: Map.behaviour_info(:callbacks)
            end
            """
        )

        assertEquals(
            listOf(
                "Map.__info__/1 OWN VALID via []",
                "Map.module_info/0 OWN VALID via []\nMap.module_info/1 OWN WRONG_ARITY via []",
                "GenServer.behaviour_info/1 OWN VALID via []",
                "",
            ),
            listOf(
                candidates(file, "Map.__info__(:functions)", "Map.__info__(:functions)"),
                candidates(file, "Map.module_info()", "Map.module_info()"),
                candidates(file, "GenServer.behaviour_info(:callbacks)", "GenServer.behaviour_info(:callbacks)"),
                candidates(file, "Map.behaviour_info(:callbacks)", "Map.behaviour_info(:callbacks)"),
            ),
        )
    }

    /**
     * A copy of a file, as completion makes of the one being edited, is read as itself, not as the file: a qualified
     * use reaches its modules as an unqualified one does, and a module later in it is still compiling.
     */
    fun testACopyOfAFileIsReadAsItself() {
        val original = file(
            """
            defmodule Edited do
              def old, do: 1
              def unqualified, do: old()
              def qualified, do: __MODULE__.old()
            end

            defmodule Later do
              defmacro m, do: 1
            end

            defmodule Early do
              require Later
            end
            """
        )
        val copy = copyOf(
            original,
            """
            defmodule Edited do
              def new, do: 1
              def unqualified, do: new()
              def qualified, do: __MODULE__.new()
            end

            defmodule Early do
              require Later
            end

            defmodule Later do
              defmacro m, do: 1
            end
            """
        )

        assertEquals(
            listOf(
                mapOf("Edited" to "Compiled", "Later" to "Compiled", "Early" to "Compiled"),
                mapOf("Edited" to "Compiled", "Early" to "Raised"),
                "Edited.new/0 OWN VALID via [] line 2",
                "Edited.new/0 OWN VALID via [] line 2",
            ),
            listOf(
                ended(original),
                ended(copy),
                candidates(copy, "do: new()", "new()"),
                candidates(copy, "__MODULE__.new()", "__MODULE__.new()"),
            ),
        )
    }

    fun testVisible() {
        val file = file(
            """
            defmodule Target do
              def v_imported(x), do: x
              def v_imported(x, y), do: {x, y}
              def v_unimported(x), do: x
              defp v_target_private, do: :p
            end

            defmodule Visibler do
              import Target, only: [v_imported: 1]
              def v_own(a \\ 1), do: a
              defp v_private, do: :p
              def v_multi(1), do: 1
              def v_multi(2), do: 2
              def caller, do: v_own()
              def remote, do: Target.v_imported(1)
            end
            """
        )

        val unqualified = visible(file, "do: v_own()", "v_own()")
        assertEquals(
            """
            v_imported/1 line 2
            v_multi/1 line 12
            v_multi/1 line 13
            v_own/0..1 line 10
            v_private/0 line 11
            """.trimIndent(),
            renderVisible(unqualified.filter { it.lookupName.startsWith("v_") }),
        )
        assertTrue("Kernel's defmodule", unqualified.any { it.lookupName == "defmodule" })
        assertTrue("Kernel.SpecialForms' quote", unqualified.any { it.lookupName == "quote" })

        assertEquals(
            """
            v_imported/1 line 2
            v_unimported/1 line 4
            """.trimIndent(),
            renderVisible(visible(file, "Target.v_imported(1)", "Target.v_imported(1)")),
        )
    }

    /** After a qualifier, as completion offers them: one of each name, at its function head where it has one. */
    fun testAQualifiedPositionSeesEachNameOnce() {
        val file = file(
            """
            defmodule Multi do
              def m_clauses(1), do: 1
              def m_clauses(2), do: 2
              def m_headed(a \\ 1)
              def m_headed(1), do: 1
              def m_headed(a), do: a
              defdelegate m_delegated(m), to: Map, as: :keys
              defdelegate m_both(m), to: Map, as: :keys
              def m_both(a, b), do: {a, b}
              def m_late(a, b), do: {a, b}
              def m_late(a)
              def m_late(a), do: a
              defdelegate [m_list_a(m), m_list_b(m)], to: Map
            end

            defmodule Asker do
              def go, do: Multi.m_clauses(1)
            end
            """
        )

        assertEquals(
            """
            m_both/2 line 9
            m_clauses/1 line 2
            m_delegated/1 line 7
            m_headed/0..1 line 4
            m_late/1 line 11
            m_list_a/1 line 13
            m_list_b/1 line 13
            """.trimIndent(),
            renderVisible(visible(file, "Multi.m_clauses(1)", "Multi.m_clauses(1)").filter { it.lookupName.startsWith("m_") }),
        )
    }

    /** A remote capture's receiver is the module the file's expansion found at it, as a call's is. */
    fun testACapturesReceiverIsWhatTheFilesExpansionFound() {
        val file = file(
            """
            defmodule Foo.Bar do
              def f(x), do: x
            end

            defmodule Foo do
              @mod Foo.Bar

              def by_capture, do: &@mod.f/1
            end
            """
        )

        assertEquals("f/1 line 2", renderVisible(visible(file, "&@mod.f/1", "@mod.f")))
    }

    /** How each module [file] defines at its top ended, by name. */
    private fun ended(file: PsiFile): Map<String, String> =
        read {
            val inputs = (SdkInputs.getInstance(project).inputsFor(file) as SdkInputs.Answer.Ready).inputs

            ProjectModules.getInstance(project).expansion(file as ElixirFile, inputs).modules
                .associate { it.module.removePrefix("Elixir.") to it.ended::class.simpleName!! }
        }

    /** A copy of [original] holding [text], as completion makes of the file being edited. */
    @Suppress("SameParameterValue")
    private fun copyOf(original: PsiFile, text: String): PsiFile =
        read {
            PsiFileFactory.getInstance(project)
                .createFileFromText(original.name, original.language, text.trimIndent() + "\n", false, false)
                .also { (it as PsiFileImpl).originalFile = original }
        }

    /** `lib/source.ex` holding [text], in a source root of the test's module. */
    private fun file(text: String): PsiFile {
        val directory = createTempDir("source")
        FileUtil.writeToFile(File(directory, "lib/source.ex"), text.trimIndent() + "\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findChild("lib")!!)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return PsiManager.getInstance(project).findFile(root.findFileByRelativePath("lib/source.ex")!!)!!
    }

    /** The candidates [ExpanderSource] gives the use [call] in [context], rendered one per line. */
    private fun candidates(file: PsiFile, context: String, call: String, last: Boolean = false): String {
        val use = use(file, context, call, last)

        return render(read { ExpanderSource.candidates(use, false) })
    }

    /** The use that is the call [call], found in the only occurrence of [context], or its last with [last]. */
    private fun use(file: PsiFile, context: String, call: String, last: Boolean = false): Use.OfCall {
        val text = file.text
        val at = if (last) text.lastIndexOf(context) else text.indexOf(context)
        assertTrue("`$context` in the file", at >= 0)
        if (!last) assertEquals("`$context` once in the file", at, text.lastIndexOf(context))
        val offset = text.indexOf(call, at)

        return read {
            val found = generateSequence(file.findElementAt(offset)) { it.parent }
                .filterIsInstance<Call>()
                .first { it.textRange.startOffset == offset && it.text == call }

            Use.of(found)!!
        }
    }

    private fun render(found: List<Found>): String =
        read {
            found.joinToString("\n") { found ->
                val declaration = found.candidate.declaration
                val arity = arity(declaration.arity)
                val via = found.via.joinToString(" | ") { it.text.lines().first() }
                val line = when (declaration.declared) {
                    is Declared.Source -> " line ${lineOf(found.element)}"
                    is Declared.Compiled -> ""
                }

                "${found.declaringModuleName()}.${declaration.name}/$arity ${found.candidate.reach} " +
                    "${found.candidate.applicability} via [$via]$line"
            }
        }

    /** What [ExpanderSource] makes visible at the call [call] in [context]. */
    private fun visible(file: PsiFile, context: String, call: String): List<Visible> {
        val position = use(file, context, call).entrance

        return read { ExpanderSource.visible(position) }
    }

    /** The 1-based line of [element] in its own file. */
    private fun lineOf(element: PsiElement): Int =
        StringUtil.offsetToLineNumber(element.containingFile.text, element.textRange.startOffset) + 1

    /** [visible], one per line in name order, each with its arity and the line that declares it. */
    private fun renderVisible(visible: List<Visible>): String =
        read {
            visible
                .map { visible ->
                    "${visible.lookupName}/${arity(visible.declaration!!.arity)} line ${lineOf(visible.element)}"
                }
                .sorted()
                .joinToString("\n")
        }

    private fun arity(arity: ArityKnowledge): String =
        when (arity) {
            is ArityKnowledge.Exact -> "${arity.arity}"
            is ArityKnowledge.Range -> "${arity.minimum}..${arity.maximum}"
            is ArityKnowledge.Open -> "${arity.minimum}+"
            ArityKnowledge.Unknown -> "?"
        }

    private fun <T> read(block: () -> T): T = ReadAction.nonBlocking(Callable { block() }).executeSynchronously()
}
