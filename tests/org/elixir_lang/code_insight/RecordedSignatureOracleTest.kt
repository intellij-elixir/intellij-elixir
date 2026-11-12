package org.elixir_lang.code_insight

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.ProbeHarness
import org.elixir_lang.expander.elixirString
import org.elixir_lang.expander.legLevel
import org.elixir_lang.expander.utf8
import org.elixir_lang.expander.withEex
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import kotlin.time.Duration.Companion.seconds

/**
 * [RecordedSignature] against what the leg's Elixir records: each method's modules are compiled by the leg's Elixir,
 * and each named function's `Docs` signature is compared with what the port gives from the same source.
 */
class RecordedSignatureOracleTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    override fun setUp() {
        super.setUp()
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, legLevel())
    }

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } finally {
            super.tearDown()
        }
    }

    fun testEveryKindOfArgument() = assertRecorded(
        """
        @x 5
        def f(%{} = m, [h | t], 1, :a, "s", 1.0, true, @x), do: {m, h, t}
        """,
        "f/8"
    )

    fun testDefaultKeepsItsText() = assertRecorded(
        """
        def split(string, pattern \\ " ", options \\ []), do: {string, pattern, options}
        def d(%{} \\ %{}), do: :ok
        """,
        "split/3", "d/1"
    )

    fun testDefaultExpandsAnAttribute() = assertRecorded(
        """
        @x 5
        def m2(%mod{}, var!(v), @x, [a], b \\ @x), do: {mod, v, a, b}
        """,
        "m2/5"
    )

    fun testASignedNumberAttributeIsItsNumber() = assertRecorded(
        """
        @x -1
        @y -1.5
        @z +2
        def f(@x, @y, @z), do: :ok
        """,
        "f/3"
    )

    fun testAnAttributeNamingAModuleIsAnAtom() = assertRecorded(
        """
        @m __MODULE__
        @p Point
        def f(@m, @p), do: :ok
        """,
        "f/2"
    )

    fun testAStructAttributeIsAMap() = assertRecorded(
        """
        @s %Point{}
        def f(@s), do: :ok
        """,
        "f/1"
    )

    fun testATupleAttributeIsAnArgument() = assertRecorded(
        """
        @t {1, 2}
        def f(@t), do: :ok
        """,
        "f/1"
    )

    fun testAStructOfAnAttributeNamingTheModule() = assertRecorded(
        """
        defstruct [:a]
        @m __MODULE__
        def f(%@m{}), do: :ok
        """,
        "f/1"
    )

    fun testADefaultPrintsAnAttributeAsElixirPrintsItsValue() = assertRecorded(
        """
        @x -1
        @m __MODULE__
        @p Point
        @n 0x10
        @l [1, :a, "b"]
        @s "a\"b"
        @k [a: 1, b: "x"]
        @t {1, 2, 3}
        @j [1, 2]
        @e []
        def g(a \\ @x, b \\ @m, c \\ @p, d \\ @n, e \\ @l, f \\ @s, h \\ @k, i \\ @t, j \\ @j, k \\ @e), do: {a, b, c, d, e, f, h, i, j, k}
        """,
        "g/10"
    )

    /** `__MODULE__.Inner` in `:foo` is `:"Elixir.foo.Inner"`, which `simplify_module_name/1` names by its last segment. */
    fun testAStructNestedInAnErlangNamedModule() = assertRecorded(
        """
        @i __MODULE__.Inner
        def f(%__MODULE__.Inner{}, %@i{}, a \\ @i), do: a
        """,
        true,
        "f/3"
    )

    fun testMatchNamesByItsLeftVariable() = assertRecorded(
        """
        def g(a = %Point{}, {x, y}), do: {a, x, y}
        """,
        "g/2"
    )

    fun testPlainVariables() = assertRecorded("def k(x, x2, arg), do: {x, x2, arg}", "k/3")

    fun testStructNamesByItsAlias() = assertRecorded(
        """
        defstruct [:a]

        def connected?(%Socket{transport_pid: pid}), do: pid
        def renamed(%Sock{}), do: :ok
        def own(%__MODULE__{}), do: :ok
        def nested(%__MODULE__.Socket{}), do: :ok
        """,
        "connected?/1", "renamed/1", "own/1", "nested/1"
    )

    fun testStructWithAVariableName() = assertRecorded("def s(%mod{}), do: mod", "s/1")

    fun testStructOfAnErlangModule() = assertRecorded(
        """
        def f(%:erlang_struct{} = v), do: v
        def g(%:erlang_struct{}), do: :ok
        """,
        "f/1", "g/1"
    )

    fun testRepeatedGuessesAreNumbered() = assertRecorded(
        """
        def m1(%Socket{}, %Socket{}, %{}, %{}, 1, 2, _, _, {}, []), do: :ok
        """,
        "m1/10"
    )

    fun testVarBang() = assertRecorded("def vb(var!(v)), do: v", "vb/1")

    fun testEverythingElseIsArg() = assertRecorded(
        """
        def e({a, b}, [_ | _] = l, <<x>>, y), do: {a, b, l, x, y}
        """,
        "e/4"
    )

    fun testUnderscoredNames() = assertRecorded("def h(_ignored, _), do: :ok", "h/2")

    fun testOlderDefaultIsKept() = assertRecorded(
        """
        def o(a \\ 1)
        def o(x), do: x
        """,
        "o/1"
    )

    fun testNewerDefaultIsDropped() = assertRecorded(
        """
        def w(%{} = m, k) when is_map(m), do: {m, k}
        def w(x, k \\ 2), do: {x, k}
        """,
        "w/2"
    )

    fun testExplicitNameBeatsAGuess() = assertRecorded(
        """
        def m3(_a), do: :ok
        def m3(b), do: b

        def m4(a, b), do: {a, b}
        def m4(c, d), do: {c, d}

        def m6(x, _), do: x
        def m6(_, y), do: y
        """,
        "m3/1", "m4/2", "m6/2"
    )

    fun testSameGuessKeepsTheName() = assertRecorded(
        """
        def map_every([], nth, _fun), do: nth
        def map_every(list, nth, fun), do: {list, nth, fun}
        """,
        "map_every/3"
    )

    fun testDifferentGuessesGiveArg() = assertRecorded(
        """
        def dg(1, x), do: x
        def dg(:a, x), do: x
        """,
        "dg/2"
    )

    fun testDefaultsMakeAnotherKey() = assertRecorded(
        """
        def g2(a, b), do: {a, b}
        def g2(a, b \\ 1, c \\ 2), do: {a, b, c}
        """,
        "g2/2", "g2/3"
    )

    fun testBodilessHeadIsMerged() = assertRecorded(
        """
        def bh(a, b \\ 1)
        def bh(x, y), do: {x, y}
        """,
        "bh/2"
    )

    fun testSpecialFormNames() = assertRecorded(
        """
        defmacro unquote(:__aliases__)(args), do: args
        defmacro unquote(:__block__)(args), do: args
        defmacro unquote(:fn)(clauses), do: clauses
        defmacro unquote(:__ENV__)(), do: :ok
        defmacro unquote(:%{})(args), do: args
        defmacro unquote(:%)(struct, map), do: {struct, map}
        """,
        "__aliases__/1", "__block__/1", "fn/1", "__ENV__/0", "%{}/1", "%/2"
    )

    fun testStructFunctionIsTheStruct() = assertRecorded("defstruct [:a]", "__struct__/0")

    fun testOperatorNamesPrintInfix() = assertRecorded(
        """
        import Kernel, except: [+: 2, |>: 2]

        def a + b, do: {a, b}
        def left |> right, do: {left, right}
        """,
        "+/2", "|>/2"
    )

    fun testUnaryOperator() = assertRecorded(
        """
        import Kernel, except: [-: 1]

        def -a, do: a
        """,
        "-/1"
    )

    fun testNameThatIsNoIdentifier() = assertRecorded("def unquote(:\"weird name\")(a), do: a", "weird name/1")

    fun testGuardIsDropped() = assertRecorded("def w(a) when is_integer(a), do: a", "w/1")

    fun testDefguard() = assertRecorded("defguard is_pos(x) when x > 0", "is_pos/1")

    fun testDelegationWithADefault() = assertRecorded("defdelegate go(x, y \\\\ 1), to: Target, as: :run", "go/2")

    fun testExceptionWithAMessage() = assertRecorded("defexception [:message]", "exception/1", "message/1")

    fun testExceptionWithoutAMessage() = assertRecorded("defexception [:reason]", "exception/1", "message/1")

    fun testEexFunctionFrom() = assertRecorded(
        """
        require EEx

        EEx.function_from_string(:def, :page, "<%= a %><%= b %>", [:a, :b])
        """,
        "page/2"
    )

    fun testEexUnderscoredName() = assertRecorded(
        """
        require EEx

        EEx.function_from_string(:def, :pu, "<%= b %>", [:_a, :b])
        EEx.function_from_string(:def, :pv, "", [:_, :_])
        """,
        "pu/2", "pv/2"
    )

    /** The macro evaluates its argument names, so an attribute names the parameter by the atom it holds. */
    fun testEexAttributeName() = assertRecorded(
        """
        require EEx

        @extra :extra
        EEx.function_from_string(:def, :render, "<%= extra %>", [:assigns, @extra])
        """,
        "render/2"
    )

    fun testADefaultReadsTheLastWriteOfAnAttributeRegisteredWithoutAccumulating() = assertRecorded(
        """
        Module.register_attribute(__MODULE__, :opts, accumulate: false)
        @opts :first
        @opts :second
        def g(a \\ @opts), do: a
        Module.register_attribute(__MODULE__, :kept, persist: true)
        @kept :first
        @kept :second
        def h(a \\ @kept), do: a
        """,
        "g/1",
        "h/1"
    )

    fun testCallbackRecordsNoSignature() = assertRecorded("@callback cb(term) :: term", "cb/1")

    /**
     * [body] as the leg's Elixir compiles it into a module, beside a `Socket` struct, also aliased `Sock`, a `Point`
     * one and an Erlang-named one, which [body] writes `:erlang_struct`: each of [nameArities] gives the `Docs` signature, or none where Elixir
     * records none.
     */
    private fun assertRecorded(body: String, vararg nameArities: String) = assertRecorded(body, false, *nameArities)

    /** [body] in the module `:erlang_struct` names, whose `Inner` is a struct too, where [inErlangModule]. */
    private fun assertRecorded(body: String, inErlangModule: Boolean, vararg nameArities: String) {
        val harness = ProbeHarness({ error("not parsed") })
        val token = harness.token()
        val elixirModule = "RecordedSignatureOracle$token"
        val erlangStruct = "recorded_signature_oracle_${token.lowercase()}"
        val indented = body.trimIndent().replace(":erlang_struct", ":$erlangStruct").prependIndent("  ")
        val source = """
            defmodule $elixirModule.Socket do
              defstruct [:transport_pid]
            end

            defmodule $elixirModule.Point do
              defstruct [:x]
            end

            defmodule :"Elixir.$erlangStruct.Inner" do
              defstruct [:a]
            end

        """.trimIndent() + "\n" +
            "defmodule :$erlangStruct do\n  defstruct [:a]\n" + (if (inErlangModule) "$indented\n" else "") + "end\n\n" +
            """
            defmodule $elixirModule do
              alias $elixirModule.Socket
              alias $elixirModule.Socket, as: Sock
              alias $elixirModule.Point

            """.trimIndent() + "\n" + (if (inErlangModule) "" else "$indented\n") + "end\n"
        val module = if (inErlangModule) ":$erlangStruct" else elixirModule
        val recorded = harness.withEex { docsSignatures(source) }.getValue(module)

        myFixture.copyFileToProject("eex.ex")
        val file = myFixture.configureByText("oracle.ex", source)

        assertEquals(
            nameArities.associateWith { recorded[it] },
            nameArities.associateWith { recordedIn(file, module, it) }
        )
    }

    /** Each module [source] compiles to, by its inspected name, with each function's first `Docs` signature. */
    private fun docsSignatures(source: String): Map<String, Map<String, String?>> {
        val compiled = Quoter.compile(
            """
            docs =
              for {module, binary} <- Code.compile_string(${elixirString(source)}) do
                :code.delete(module)
                :code.purge(module)
                {:ok, {_, [{_, chunk}]}} = :beam_lib.chunks(binary, [String.to_charlist("Docs")])
                {:docs_v1, _, _, _, _, _, entries} = :erlang.binary_to_term(chunk)

                {inspect(module), for({{_, name, arity}, _, signature, _, _} <- entries, do: {Atom.to_string(name), arity, signature})}
              end

            IntellijElixir.Quoter.Probe.send(__ENV__, docs)
            """.trimIndent(),
            60.seconds
        )

        assertEquals("compile ${compiled.diagnostics}", OtpErlangAtom("ok"), compiled.status)

        return (compiled.messages.single() as OtpErlangList).associate { module ->
            val (name, entries) = (module as OtpErlangTuple).elements()

            utf8(name) to (entries as OtpErlangList).associate { entry ->
                val (function, arity, signature) = (entry as OtpErlangTuple).elements()

                "${utf8(function)}/${(arity as OtpErlangLong).intValue()}" to
                    (signature as OtpErlangList).elements().firstOrNull()?.let(::utf8)
            }
        }
    }
}
