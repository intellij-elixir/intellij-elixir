package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.util.system.OS
import org.elixir_lang.FIXED_STACK
import org.elixir_lang.expander.interpreter.holdsUnknown
import org.elixir_lang.expander.interpreter.otpOf
import org.elixir_lang.lowering.inspect

/**
 * `Module.create/3` run as a module body, a function or the file's top level reaches it, against what Elixir's
 * `Code.compile_string/2` of the same file raises, loads and answers.
 */
class ModuleCreateTest : SdkProbeTestCase() {
    fun testAModuleBodysEnvCreatesAModule() =
        assertAsElixir(
            """
            defmodule Created.BodyEnv do
              Module.create(__MODULE__.Made, quote(do: def(f, do: :ok)), __ENV__)
              def g, do: :g
            end
            """,
            "BodyEnv", "BodyEnv.Made",
        )

    fun testTheFilesEnvCreatesAModule() =
        assertAsElixir(
            """
            Module.create(Created.FileEnv.Made, quote(do: def(f, do: :ok)), __ENV__)

            defmodule Created.FileEnv do
              def g, do: :g
            end
            """,
            "FileEnv", "FileEnv.Made",
        )

    fun testACreateInAFunctionIsNotRun() =
        assertAsElixir(
            """
            defmodule Created.InDef do
              def go, do: Module.create(Created.InDef.Made, quote(do: def(f, do: :ok)), __ENV__)
            end
            """,
            "InDef", "InDef.Made",
        )

    fun testKeywordOptionsCreateAModule() =
        assertAsElixir(
            """
            defmodule Created.Keyword do
              Module.create(Created.Keyword.Made, quote(do: def(f, do: :ok)), line: 3, file: "keyword.ex")
              def g, do: :g
            end
            """,
            "Keyword", "Keyword.Made",
        )

    fun testKeywordOptionsWithoutAFileRaise() =
        assertAsElixir(
            """
            defmodule Created.KeywordNoFile do
              Module.create(Created.KeywordNoFile.Made, quote(do: def(f, do: :ok)), line: 3)
              def g, do: :g
            end
            """,
            "KeywordNoFile", "KeywordNoFile.Made",
        )

    /**
     * A `%Macro.Env{}`'s imports and aliases reach the body. Its `Ch` is built from data: a quoted `Ch` carries the
     * quoting env's `alias:` metadata, so it would expand without the created env's aliases.
     */
    fun testAnEnvKeepsItsImportsAndAliases() =
        assertAsElixir(
            """
            defmodule Created.EnvKeeps do
              import Enum, only: [count: 1]
              alias String.Chars, as: Ch
              Module.create(Created.EnvKeeps.Made, quote(do: (def f(x), do: count(x); @h unquote({:__aliases__, [], [:Ch]}); def h, do: @h)), __ENV__)
              def g, do: :g
            end
            """,
            "EnvKeeps", "EnvKeeps.Made",
            readers = readersOf("EnvKeeps.Made", "h"),
        )

    /** The keyword form's env starts from `elixir_env:new()`, without the caller's imports. */
    fun testKeywordOptionsDropTheCallersImports() =
        assertAsElixir(
            """
            defmodule Created.KeywordDrops do
              import Enum, only: [count: 1]
              Module.create(Created.KeywordDrops.Made, quote(do: def(f(x), do: count(x))), line: 1, file: "k.ex")
              def g, do: :g
            end
            """,
            "KeywordDrops", "KeywordDrops.Made",
        )

    fun testAnEnvsImportReachesABodyBuiltFromData() =
        assertAsElixir(
            """
            defmodule Created.EnvHasImport do
              import Enum, only: [count: 1]
              call = {:count, [], [{:x, [], nil}]}
              Module.create(Created.EnvHasImport.Made, quote(do: def(f(unquote({:x, [], nil})), do: unquote(call))), __ENV__)
              def g, do: :g
            end
            """,
            "EnvHasImport", "EnvHasImport.Made",
        )

    fun testKeywordOptionsLeaveABodyBuiltFromDataWithoutTheImport() =
        assertAsElixir(
            """
            defmodule Created.KeywordNoImport do
              import Enum, only: [count: 1]
              call = {:count, [], [{:x, [], nil}]}
              Module.create(Created.KeywordNoImport.Made, quote(do: def(f(unquote({:x, [], nil})), do: unquote(call))), line: 1, file: "k.ex")
              def g, do: :g
            end
            """,
            "KeywordNoImport", "KeywordNoImport.Made",
        )

    fun testTheCreatingBodyCallsTheCreatedModule() =
        assertAsElixir(
            """
            defmodule Created.LaterCalls do
              Module.create(Created.LaterCalls.Made, quote(do: def(f, do: :from_made)), __ENV__)
              @called Created.LaterCalls.Made.f()
              def called, do: @called
            end
            """,
            "LaterCalls", "LaterCalls.Made",
            readers = readersOf("LaterCalls", "called"),
        )

    fun testALaterModuleCallsACreatedMacro() =
        assertAsElixir(
            """
            Module.create(Created.SiblingMacro.Made, quote(do: defmacro(m, do: :from_macro)), __ENV__)

            defmodule Created.SiblingMacro do
              require Created.SiblingMacro.Made
              @value Created.SiblingMacro.Made.m()
              def value, do: @value
            end
            """,
            "SiblingMacro", "SiblingMacro.Made",
            readers = readersOf("SiblingMacro", "value"),
        )

    fun testABodySplicedFromAComprehension() =
        assertAsElixir(
            """
            defmodule Created.UnquoteList do
              names = [:a, :b, :c]

              defs =
                for name <- names do
                  quote do
                    def unquote(name)(), do: unquote(name)
                  end
                end

              code = quote do
                unquote_splicing(defs)
                def total, do: unquote(length(names))
              end

              Module.create(Created.UnquoteList.Made, code, __ENV__)
            end
            """,
            "UnquoteList", "UnquoteList.Made",
        )

    fun testACreatedModulesBeforeCompileRuns() =
        assertAsElixir(
            """
            defmodule Created.Hook do
              defmacro __before_compile__(_env) do
                quote do
                  def hooked, do: :hooked
                end
              end
            end

            defmodule Created.BeforeCompile do
              Module.create(
                Created.BeforeCompile.Made,
                quote do
                  @before_compile Created.Hook
                  def f, do: :ok
                end,
                __ENV__
              )
            end
            """,
            "Hook", "BeforeCompile", "BeforeCompile.Made",
        )

    fun testACreatedModuleNestsADefmodule() =
        assertAsElixir(
            """
            defmodule Created.Nested do
              Module.create(
                Created.Nested.Made,
                quote do
                  defmodule Inner do
                    def i, do: :i
                  end

                  def f, do: Inner.i()
                end,
                __ENV__
              )
            end
            """,
            "Nested", "Nested.Made", "Nested.Made.Inner",
        )

    /** The file's modules and a module's nested ones are in the order Elixir compiles them, a created one where it runs. */
    fun testCreatedModulesAreInTheOrderElixirCompilesThem() {
        val token = harness.token()
        val source = """
            Module.create(Created.First, quote(do: def(f, do: :ok)), __ENV__)

            defmodule Created.Second do
              Module.create(Created.Second.Inner, quote(do: def(f, do: :ok)), __ENV__)

              defmodule Later do
              end
            end
            """
        val file = expandedFileOnAStack(written(source, token), FIXED_STACK, OS.CURRENT)
        val name = { result: ExpansionResult -> result.module.removePrefix("Elixir.$token.Created.") }

        assertEquals(
            listOf("First []", "Second [Second.Inner, Second.Later]"),
            file.modules.map { module -> "${name(module)} ${module.nested.map(name)}" },
        )
    }

    fun testAMacroCreatesAModuleFromItsBlock() =
        assertAsElixir(
            """
            defmodule Created.Maker do
              defmacro made(name, do: block) do
                quote do
                  Created.Maker.__made__(__MODULE__, unquote(name), unquote(Macro.escape(block)), __ENV__)
                end
              end

              def __made__(mod, name, block, env) do
                code =
                  quote do
                    def from_fixed, do: :fixed
                    unquote(block)
                  end

                Module.create(Module.concat(mod, name), code, env)
              end
            end

            defmodule Created.FromMacro do
              require Created.Maker

              Created.Maker.made Inner do
                def from_block, do: :block
              end
            end
            """,
            "Maker", "FromMacro", "FromMacro.Inner",
        )

    /**
     * A `@before_compile` callback creates a module from data with the call form
     * `Module.create(name, code, line: env.line, file: env.file)`.
     */
    fun testABeforeCompileCallbackCreatesAModuleWithTheCallersLineAndFile() =
        assertAsElixir(
            """
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
                made = Created.Definer.define(env, defs)

                quote do
                  def __made__, do: unquote(made)
                end
              end

              def define(env, defs) do
                code = quote do: (unquote_splicing(defs))
                name = Module.concat(env.module, Helpers)
                Module.create(name, code, line: env.line, file: env.file)
                name
              end
            end

            defmodule Created.FromBeforeCompile do
              use Created.Definer
              @names :a
              @names :b
            end
            """,
            "Definer", "FromBeforeCompile", "FromBeforeCompile.Helpers",
        )

    /** The tuple's name is known; its `binary` and the value of the body's last expression aren't. */
    fun testTheReturnedTuplesNameIsKnown() =
        assertAsElixir(
            """
            defmodule Created.ReturnRead do
              {:module, name, binary, last} = Module.create(Created.ReturnRead.Made, quote(do: (def(f, do: :ok); :the_last)), __ENV__)
              @made name
              @binary_is is_binary(binary)
              @last last
              def made, do: @made
              def binary_is, do: @binary_is
              def last, do: @last
            end
            """,
            "ReturnRead", "ReturnRead.Made",
            readers = readersOf("ReturnRead", "made", "binary_is", "last"),
            unknown = setOf("binary_is", "last"),
        )

    /** Elixir expands the enclosing body before it runs it, so the `import` raises before the module exists. */
    fun testAnImportInTheCreatingBodyRaises() =
        assertAsElixir(
            """
            defmodule Created.LaterSees do
              Module.create(Created.LaterSees.Made, quote do
                def f, do: :from_made
                defmacro m, do: :from_macro
              end, __ENV__)

              import Created.LaterSees.Made
              @value m()
              @called Created.LaterSees.Made.f()
              def g, do: f()
              def value, do: @value
              def called, do: @called
            end
            """,
            "LaterSees", "LaterSees.Made",
        )

    fun testALaterModuleImportsACreatedModule() =
        assertAsElixir(
            """
            Module.create(Created.SiblingSees.Made, quote(do: def(f, do: :ok)), __ENV__)

            defmodule Created.SiblingSees do
              import Created.SiblingSees.Made
              def g, do: f()
            end
            """,
            "SiblingSees", "SiblingSees.Made",
        )

    fun testTheCreatingBodysVariablesAndAttributesSurvive() =
        assertAsElixir(
            """
            defmodule Created.AroundSurvives do
              before = :before
              @attr :attr
              Module.create(Created.AroundSurvives.Made, quote(do: def(f, do: :ok)), __ENV__)
              @after_it before
              def attr, do: @attr
              def after_it, do: @after_it
              def g, do: :g
            end
            """,
            "AroundSurvives", "AroundSurvives.Made",
            readers = readersOf("AroundSurvives", "attr", "after_it"),
        )

    fun testARaiseInTheBodyIsRaised() =
        assertAsElixir(
            """
            defmodule Created.RaiseInBody.Before do
              def b, do: :b
            end

            defmodule Created.RaiseInBody do
              Module.create(Created.RaiseInBody.Made, quote(do: raise("in the body")), __ENV__)
              def g, do: :g
            end

            defmodule Created.RaiseInBody.After do
              def a, do: :a
            end
            """,
            "RaiseInBody.Before", "RaiseInBody", "RaiseInBody.Made", "RaiseInBody.After",
        )

    fun testARescuedRaiseLeavesNoModule() =
        assertAsElixir(
            """
            defmodule Created.RescuedRaise do
              value =
                try do
                  Module.create(Created.RescuedRaise.Made, quote(do: raise("in the body")), __ENV__)
                rescue
                  RuntimeError -> :rescued
                end

              @value value
              def value, do: @value
            end
            """,
            "RescuedRaise", "RescuedRaise.Made",
            readers = readersOf("RescuedRaise", "value"),
        )

    /** A created module's error is its own: the module that rescues the raise it ends in compiles. */
    fun testARescuedCompileErrorLeavesTheCreatingModuleCompiled() =
        assertAsElixir(
            """
            defmodule Created.TaintRescued do
              try do
                Module.create(Created.TaintRescued.Made, quote(do: def(f, do: count(1))), line: 1, file: "t.ex")
              rescue
                CompileError -> :ok
              end

              def g, do: :g
            end
            """,
            "TaintRescued", "TaintRescued.Made",
        )

    /** A created module's raise is its exception's, whose other fields aren't known, so a rescue reads them unknown. */
    fun testARescuedRaisesFieldsAreUnknown() =
        assertAsElixir(
            """
            defmodule Created.Fields do
              compiling =
                try do
                  Module.create(__MODULE__, quote(do: def(f, do: :ok)), __ENV__)
                rescue
                  e in CompileError -> e.description
                end

              tainted =
                try do
                  Module.create(Created.Fields.Tainted, quote(do: def(f, do: count(1))), line: 1, file: "t.ex")
                rescue
                  e in CompileError -> e.description
                end

              raised =
                try do
                  Module.create(Created.Fields.Raised, quote(do: raise("in the body")), __ENV__)
                rescue
                  e in RuntimeError -> e.message
                end

              nil_name =
                try do
                  Module.create(nil, quote(do: def(f, do: :ok)), __ENV__)
                rescue
                  e in CompileError -> e.description
                  e in ArgumentError -> e.message
                end

              @compiling compiling
              @tainted tainted
              @raised raised
              @nil_name nil_name
              def compiling, do: @compiling
              def tainted, do: @tainted
              def raised, do: @raised
              def nil_name, do: @nil_name
            end
            """,
            "Fields", "Fields.Tainted", "Fields.Raised",
            readers = readersOf("Fields", "compiling", "tainted", "raised", "nil_name"),
            unknown = setOf("compiling", "tainted", "raised", "nil_name"),
        )

    /** A macro that creates a module as the body around it expands, and rescues its raise, gives its fallback. */
    fun testAMacroRescuesACreatedRaiseAsTheBodyExpands() =
        assertAsElixir(
            """
            defmodule Created.MacroRescue.Maker do
              defmacro made do
                try do
                  Module.create(Created.MacroRescue.Made, quote(do: raise("in the body")), __ENV__)
                  :created
                rescue
                  RuntimeError -> :rescued
                end
              end
            end

            defmodule Created.MacroRescue do
              require Created.MacroRescue.Maker

              @value Created.MacroRescue.Maker.made()
              def value, do: @value
            end
            """,
            "MacroRescue.Maker", "MacroRescue", "MacroRescue.Made",
            readers = readersOf("MacroRescue", "value"),
        )

    /** A macro that creates a module as a function's body expands, and rescues its raise, leaves the function defined. */
    fun testAMacroRescuesACreatedRaiseAsAFunctionsBodyExpands() =
        assertAsElixir(
            """
            defmodule Created.DefRescue.Maker do
              defmacro made do
                try do
                  Module.create(Created.DefRescue.Made, quote(do: raise("in the body")), __ENV__)
                  :created
                rescue
                  RuntimeError -> :rescued
                end
              end
            end

            defmodule Created.DefRescue do
              require Created.DefRescue.Maker

              def value, do: Created.DefRescue.Maker.made()
            end
            """,
            "DefRescue.Maker", "DefRescue", "DefRescue.Made",
        )

    /**
     * A created module's `@after_compile` callback isn't followed, and it may raise once the module is loaded, so a
     * rescue around the create may run: the macro stops, Elixir's `:rescued` not being known.
     */
    fun testARescueAroundACreatedModulesAfterCompileMayRun() =
        assertExpanded(
            """
            defmodule Created.AfterCompileRescue.Hook do
              def __after_compile__(_env, _bytecode), do: raise("in the callback")
            end

            defmodule Created.AfterCompileRescue.Maker do
              defmacro made do
                try do
                  Module.create(
                    Created.AfterCompileRescue.Made,
                    quote do
                      @after_compile Created.AfterCompileRescue.Hook
                      def f, do: :f
                    end,
                    __ENV__
                  )

                  :created
                rescue
                  RuntimeError -> :rescued
                end
              end
            end

            defmodule Created.AfterCompileRescue do
              require Created.AfterCompileRescue.Maker

              @value Created.AfterCompileRescue.Maker.made()
              def value, do: @value
            end
            """,
            listOf("AfterCompileRescue", "AfterCompileRescue.Made"),
            "raises nothing\nAfterCompileRescue stopped Created.AfterCompileRescue.Maker.made/0 functions: ; macros: \n" +
                "AfterCompileRescue.Made compiled functions: f/0; macros: \nAfterCompileRescue.value absent",
            readersOf("AfterCompileRescue", "value"),
        )

    /**
     * A created body that ran on a raise assumed not to happen may have raised, so a rescue around the create may run:
     * the macro stops, where it gave `:created` and Elixir `:rescued`.
     */
    fun testARescueAroundACreatedBodysAssumedRaiseMayRun() =
        assertExpanded(
            """
            defmodule Created.AssumedRescue.Maker do
              defmacro made do
                try do
                  Module.create(
                    Created.AssumedRescue.Made,
                    quote do
                      @key Application.fetch_env!(:created_assumed_rescue, :key)
                      def f, do: :f
                    end,
                    __ENV__
                  )

                  :created
                rescue
                  ArgumentError -> :rescued
                end
              end
            end

            defmodule Created.AssumedRescue do
              require Created.AssumedRescue.Maker

              @value Created.AssumedRescue.Maker.made()
              def value, do: @value
            end
            """,
            listOf("AssumedRescue"),
            "raises nothing\nAssumedRescue stopped Created.AssumedRescue.Maker.made/0 functions: ; macros: \n" +
                "AssumedRescue.value absent",
            readersOf("AssumedRescue", "value"),
        )

    /** A module a created body compiled before it raised stays compiled where the raise is rescued. */
    fun testAModuleACreatedBodyCompiledSurvivesItsRaise() =
        assertAsElixir(
            """
            defmodule Created.ChildSurvives do
              value =
                try do
                  Module.create(
                    Created.ChildSurvives.Made,
                    quote do
                      defmodule Child do
                        @c :c
                        def c, do: @c
                      end

                      raise "after the child"
                    end,
                    __ENV__
                  )
                rescue
                  RuntimeError -> :rescued
                end

              @value value
              def value, do: @value
            end
            """,
            "ChildSurvives", "ChildSurvives.Made", "ChildSurvives.Made.Child",
            readers = readersOf("ChildSurvives", "value") + readersOf("ChildSurvives.Made.Child", "c"),
        )

    fun testCreatingTheModuleBeingCompiledRaises() =
        assertAsElixir(
            """
            defmodule Created.NameCompiling do
              Module.create(__MODULE__, quote(do: def(f, do: :ok)), __ENV__)
              def g, do: :g
            end
            """,
            "NameCompiling",
        )

    fun testTheLaterDefinitionOfANameWins() =
        assertAsElixir(
            """
            defmodule Created.NameDefined.Made do
              def first, do: :first
            end

            defmodule Created.NameDefined do
              Module.create(Created.NameDefined.Made, quote(do: def(second, do: :second)), __ENV__)
              def g, do: :g
            end
            """,
            "NameDefined.Made", "NameDefined",
        )

    fun testANameThatIsNotAnAtomRaisesAFunctionClauseError() =
        assertAsElixir(
            """
            defmodule Created.InvalidName do
              Module.create("not an atom", quote(do: def(f, do: :ok)), __ENV__)
              def g, do: :g
            end
            """,
            "InvalidName",
        )

    /** `invalid_module_name`: a `CompileError` up to 1.14, an `ArgumentError` from 1.15. */
    fun testANilNameRaisesByVersion() =
        assertAsElixir(
            """
            defmodule Created.NilName do
              Module.create(nil, quote(do: def(f, do: :ok)), __ENV__)
              def g, do: :g
            end
            """,
            "NilName",
        )

    fun testABooleanNameRaises() = assertAsElixir("Module.create(true, quote(do: def(f, do: :ok)), __ENV__)\n")

    /** A name with a slash compiles before 1.13 and raises from it. */
    fun testASlashNameRaisesByVersion() =
        assertAsElixir("Module.create(:\"Elixir.Created.Slash/Name\", quote(do: def(f, do: :ok)), __ENV__)\n", "Slash/Name")

    fun testAReservedNameRaises() = assertAsElixir("Module.create(Elixir.Any, quote(do: def(f, do: :ok)), __ENV__)\n")

    fun testAnUpdatedEnvsLineReachesTheBody() =
        assertAsElixir(
            """
            defmodule Created.EnvUpdated do
              env = %{__ENV__ | line: 77}
              Module.create(Created.EnvUpdated.Made, quote(do: (@line __ENV__.line; def line, do: @line)), env)
              def g, do: :g
            end
            """,
            "EnvUpdated", "EnvUpdated.Made",
            readers = readersOf("EnvUpdated.Made", "line"),
        )

    /** The keyword form's `:file` is the created body's `__ENV__.file`, `__DIR__` and `@file`'s. */
    fun testAGivenFileIsTheBodysFile() =
        assertAsElixir(
            """
            defmodule Created.FileGiven do
              Module.create(
                Created.FileGiven.Made,
                quote(do: (@file_is __ENV__.file; @dir_is __DIR__; def file_is, do: @file_is; def dir_is, do: @dir_is)),
                line: 1,
                file: "/elsewhere/given.ex"
              )
            end
            """,
            "FileGiven", "FileGiven.Made",
            readers = readersOf("FileGiven.Made", "file_is", "dir_is"),
        )

    /**
     * An `@file` naming the given `:file` keeps a created definition in that file, where its `__ENV__.file` is it, where
     * the path is absolute: on Windows, `/elsewhere/given.ex` takes the working directory's drive, so the definition
     * moves, and its `__ENV__.file` is unknown, as a moved definition's is.
     */
    fun testAnAtFileOfTheGivenFileKeepsTheDefinitionThere() =
        assertFileIsAsElixir(
            "FileKept",
            """quote(do: (@file "/elsewhere/given.ex"; def file_is, do: __ENV__.file))""",
            "/elsewhere/given.ex",
            moved = OS.CURRENT == OS.Windows,
        )

    /**
     * An `@file` naming a given `:file` with a drive is absolute only on Windows: elsewhere it is relative to the working
     * directory, so the definition moves, and its `__ENV__.file` is unknown, as a moved definition's is.
     */
    fun testAnAtFileOfAGivenFileWithADriveKeepsTheDefinitionOnlyOnWindows() =
        assertFileIsAsElixir(
            "FileDrive",
            """quote(do: (@file "c:/elsewhere/given.ex"; def file_is, do: __ENV__.file))""",
            "c:/elsewhere/given.ex",
            moved = OS.CURRENT != OS.Windows,
        )

    /**
     * A `keep:` naming a given `:file` with a drive is absolute only on Windows, as an `@file` is: elsewhere the
     * definition moves, and its `__ENV__.file` is unknown, as a moved definition's is.
     */
    fun testAKeepOfAGivenFileWithADriveKeepsTheDefinitionOnlyOnWindows() =
        assertFileIsAsElixir(
            "KeepDrive",
            """quote(file: "c:/elsewhere/given.ex", do: def(file_is, do: __ENV__.file))""",
            "c:/elsewhere/given.ex",
            moved = OS.CURRENT != OS.Windows,
        )

    /** `__DIR__` of a given `:file` with a drive is its directory on every OS, as `filename:dirname/1` reads no pathtype. */
    fun testADirOfAGivenFileWithADriveIsItsDirectory() =
        assertAsElixir(
            """
            defmodule Created.DirDrive do
              Module.create(
                Created.DirDrive.Made,
                quote(do: (@dir_is __DIR__; def dir_is, do: @dir_is)),
                line: 1,
                file: "c:/elsewhere/given.ex"
              )
            end
            """,
            "DirDrive", "DirDrive.Made",
            readers = readersOf("DirDrive.Made", "dir_is"),
        )

    /**
     * The given `:file` is compared as given, so an `@file` of the same unnormalised path moves the definition, whose
     * `__ENV__.file` is then unknown, as a moved definition's is.
     */
    fun testAnAtFileOfAnUnnormalisedGivenFileMovesTheDefinition() =
        assertFileIsAsElixir(
            "FileRaw",
            """quote(do: (@file "/elsewhere/./given.ex"; def file_is, do: __ENV__.file))""",
            "/elsewhere/./given.ex",
            moved = true,
        )

    /** A `Macro.Env`'s `file` is the created body's, though it names another file. */
    fun testAnEnvsFileIsTheBodysFile() =
        assertAsElixir(
            """
            defmodule Created.EnvFile do
              env = %{__ENV__ | file: "/other/place.ex"}
              Module.create(Created.EnvFile.Made, quote(do: (@file_is __ENV__.file; @dir_is __DIR__; def file_is, do: @file_is; def dir_is, do: @dir_is)), env)
            end
            """,
            "EnvFile", "EnvFile.Made",
            readers = readersOf("EnvFile.Made", "file_is", "dir_is"),
        )

    /** `generated: true` reaches each node of the body's metadata from 1.14, and is ignored before it. */
    fun testGeneratedReachesTheBody() =
        assertAsElixir(
            """
            defmodule Created.GenProbe do
              defmacro generated(x), do: Keyword.get(elem(x, 1), :generated, false)
            end

            defmodule Created.Generated do
              Module.create(
                Created.Generated.Made,
                quote(do: (require Created.GenProbe; @gen Created.GenProbe.generated(y); def gen, do: @gen)),
                line: 3,
                file: "g.ex",
                generated: true
              )
            end
            """,
            "Generated", "Generated.Made",
            readers = readersOf("Generated.Made", "gen"),
        )

    /** Elixir raises `KeyError` from the created body, which the expander has only as a kind. */
    fun testABodyWhoseRaiseIsNotAnExceptionsDeclines() =
        assertExpanded(
            """
            defmodule Created.UnknownRaise do
              Module.create(Created.UnknownRaise.Made, quote(do: %URI{nope: 1}), __ENV__)
              def g, do: :g
            end
            """,
            listOf("UnknownRaise", "UnknownRaise.Made"),
            "raises nothing\nUnknownRaise compiled functions: g/0; macros: \nUnknownRaise.Made absent",
        )

    fun testAnUnknownNameDeclines() =
        assertExpanded(
            """
            defmodule Created.UnknownName do
              name = Module.concat([System.get_env("CREATE_NAME") || "Created.UnknownName.Made"])
              Module.create(name, quote(do: def(f, do: :ok)), __ENV__)
              def g, do: :g
            end
            """,
            listOf("UnknownName", "UnknownName.Made"),
            "raises nothing\nUnknownName compiled functions: g/0; macros: \nUnknownName.Made absent",
        )

    fun testAnUnknownBodyDeclines() =
        assertExpanded(
            """
            defmodule Created.UnknownBody do
              name = System.get_env("CREATE_BODY") || "f"
              code = Code.string_to_quoted!("def #{name}, do: :ok")
              Module.create(Created.UnknownBody.Made, code, __ENV__)
              def g, do: :g
            end
            """,
            listOf("UnknownBody", "UnknownBody.Made"),
            "raises nothing\nUnknownBody compiled functions: g/0; macros: \nUnknownBody.Made absent",
        )

    /** The created module stops where its body declines, keeping what it defined before. */
    fun testADeclineInsideTheBodyStopsBoth() =
        assertExpanded(
            """
            defmodule Created.DeclinedInside do
              Module.create(
                Created.DeclinedInside.Made,
                quote unquote: false do
                  name = System.get_env("CREATE_INNER") || "f"
                  def first, do: :first
                  def unquote(String.to_atom(name))(), do: :ok
                  def last_one, do: :last
                end,
                __ENV__
              )
              def g, do: :g
            end
            """,
            listOf("DeclinedInside", "DeclinedInside.Made"),
            "raises nothing\nDeclinedInside stopped defmodule/2 functions: g/0; macros: \n" +
                "DeclinedInside.Made stopped unquote/1 functions: first/0, last_one/0; macros: ",
        )

    /** A function [module] defines whose value Elixir gives, against the attribute of the same name it returns. */
    private data class Reader(val module: String, val function: String)

    /** A [Reader] of [module] for each of [functions]. */
    private fun readersOf(module: String, vararg functions: String): List<Reader> = functions.map { Reader(module, it) }

    /**
     * [name]'s `Made`, created from [quoted] with `:file` [file], and the `__ENV__.file` its `file_is/0` gives, as
     * [name]'s `User` reads it, against Elixir's: unknown where the definition [moved], as a moved one's is.
     */
    private fun assertFileIsAsElixir(name: String, quoted: String, file: String, moved: Boolean) =
        assertAsElixir(
            """
            defmodule Created.$name do
              Module.create(
                Created.$name.Made,
                $quoted,
                line: 1,
                file: "$file"
              )
            end

            defmodule Created.$name.User do
              @file_is Created.$name.Made.file_is()
              def file_is, do: @file_is
            end
            """,
            name, "$name.Made", "$name.User",
            readers = readersOf("$name.User", "file_is"),
            unknown = if (moved) setOf("file_is") else emptySet(),
        )

    /**
     * [source], with `Created` under a fresh token, as the expander ends it against Elixir's compile: what raises, each of
     * [modules] compiled or absent, and each of [readers]' values, but those named in [unknown], which the expander
     * leaves unknown.
     */
    private fun assertAsElixir(source: String, vararg modules: String, readers: List<Reader> = emptyList(), unknown: Set<String> = emptySet()) {
        val token = harness.token()
        val written = written(source, token)
        val names = modules.map { "Elixir.$token.Created.$it" }
        val atoms = names.joinToString(", ", "[", "]") { "String.to_atom(${elixirString(it)})" }
        val compiled = harness.compileSource(
            """
            raised =
              try do
                Code.compile_string(${elixirString(written)}, "/s.ex")
                nil
              rescue
                e -> e.__struct__
              end

            modules =
              for name <- $atoms do
                if Code.ensure_loaded?(name) do
                  %{functions: name.__info__(:functions), macros: name.__info__(:macros)}
                end
              end

            reads =
              for {module, function} <- ${readers.joinToString(", ", "[", "]") { "{String.to_atom(${elixirString("Elixir.$token.Created.${it.module}")}), :${it.function}}" }} do
                if Code.ensure_loaded?(module), do: inspect(apply(module, function, [])), else: "absent"
              end

            for {name, _} <- :code.all_loaded(), String.starts_with?(Atom.to_string(name), ${elixirString("Elixir.$token.")}) do
              :code.delete(name)
              :code.purge(name)
            end

            IntellijElixir.Quoter.Probe.send(__ENV__, {raised, modules, reads})
            """.trimIndent(),
        )

        assertEquals("the oracle's compile: ${compiled.diagnostics}", OtpErlangAtom("ok"), compiled.status)

        val (raised, infos, reads) = (compiled.messages.single() as OtpErlangTuple).elements()
        val expected = listOf("raises " + if (raised == OtpErlangAtom("nil")) "nothing" else (raised as OtpErlangAtom).atomValue()) +
            modules.zip((infos as OtpErlangList).elements()) { module, info ->
                "$module " + if (info == OtpErlangAtom("nil")) "absent" else "compiled ${exported(info as OtpErlangMap)}"
            } +
            readers.zip((reads as OtpErlangList).elements()) { reader, read ->
                "${reader.module}.${reader.function} " +
                    if (reader.function in unknown) "unknown" else utf8(read)
            }

        assertEquals(expected.joinToString("\n").replace("$token.", ""), expanded(written, token, modules.toList(), readers))
    }

    /** [source], with `Created` under a fresh token, as the expander ends it, against [expected]. */
    private fun assertExpanded(source: String, modules: List<String>, expected: String, readers: List<Reader> = emptyList()) =
        assertEquals(expected, expanded(source, modules, readers))

    /** [source], with `Created` under a fresh token, as [expanded] gives it. */
    private fun expanded(source: String, modules: List<String>, readers: List<Reader>): String {
        val token = harness.token()

        return expanded(written(source, token), token, modules, readers)
    }

    /** [source] with `Created` under [token]. */
    private fun written(source: String, token: String): String = source.trimIndent().replace("Created.", "$token.Created.")

    /**
     * [written], whose `Created` is under [token], as the expander ends it: what the file raises, or that it stopped, each
     * of [modules], and each of [readers]' attribute values, with the token taken out.
     */
    private fun expanded(written: String, token: String, modules: List<String>, readers: List<Reader>): String {
        val prefix = "Elixir.$token.Created."
        val file = expandedFileOnAStack(written, FIXED_STACK, OS.CURRENT)
        val byName = file.modules.withNested().associateBy { it.module }
        val raise = when (val top = file.top) {
            is Expansion.Error -> rendered(top.kind)
            is Expansion.Unported, is Expansion.Opaque -> "nothing known, as it stopped"
            else -> "nothing"
        }
        val lines = listOf("raises $raise") +
            modules.map { module ->
                val result = byName["$prefix$module"]

                // A module that raised defines nothing.
                "$module " +
                    if (result == null || result.ended.raises) "absent" else "${ending(result)} ${stored(result)}"
            } +
            readers.map { reader ->
                val result = byName["$prefix${reader.module}"]
                val value = result?.attributes?.final?.get(reader.function)

                "${reader.module}.${reader.function} " + when (value) {
                    null -> "absent"
                    is AttributeValue.Known ->
                        otpOf(value.term).let { if (holdsUnknown(it)) "unknown" else inspect(it) }
                    is AttributeValue.Held -> inspect(value.value)
                    AttributeValue.Unknown -> "unknown"
                }
            }

        return lines.joinToString("\n").replace("$token.", "")
    }

    /** The exception an error of [kind] raises, as its module. */
    private fun rendered(kind: String?): String = Expansion.Error.exception(kind, legLevel()) ?: "an exception that isn't known"
}
