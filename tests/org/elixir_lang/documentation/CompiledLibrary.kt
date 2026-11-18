package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import junit.framework.TestCase.assertEquals
import org.elixir_lang.Module
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.expander.elixirString
import org.elixir_lang.intellij_elixir.Quoter
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * One build of a compiled library's module: `Compiled` renamed to [name], with its debug info or, as a Docs-only
 * dependency ships, without, with its `Docs` chunk or without, and that chunk naming [docsLanguage] as the language
 * that compiled it.
 */
internal class CompiledBuild(
    val name: String,
    val debugInfo: Boolean = true,
    val docsLanguage: String = "elixir",
    val docs: Boolean = true,
)

/**
 * Compiles [source] with the leg's Elixir once for each of [builds], its module `Compiled` (and any module named under
 * it) renamed to the build's name, an alias or an atom such as `:compiled`, and adds the built modules' `.beam`s to the
 * project as one library, whose directory it returns, with each build's renamed [source] as the library's source where
 * [sources].
 */
internal fun CodeInsightTestFixture.addCompiledLibrary(source: String, vararg builds: CompiledBuild, sources: Boolean = false): VirtualFile {
    val compiled = Quoter.compile(
        """
        compile = fn name, debug_info, docs, docs_language ->
          [{module, binary} | _] =
            ${elixirString(source)}
            |> String.replace("Compiled", name)
            |> Code.compile_string()
            |> Enum.filter(fn {module, _} -> inspect(module) == name end)

          :code.delete(module)
          :code.purge(module)

          if debug_info and docs and docs_language == :elixir do
            binary
          else
            {:ok, _, chunks} = :beam_lib.all_chunks(binary)

            {:ok, rebuilt} =
              chunks
              |> Enum.reject(fn {id, _} -> (not debug_info and id == ~c"Dbgi") or (not docs and id == ~c"Docs") end)
              |> Enum.map(fn
                {~c"Docs", docs} ->
                  {~c"Docs", docs |> :erlang.binary_to_term() |> put_elem(2, docs_language) |> :erlang.term_to_binary()}

                chunk ->
                  chunk
              end)
              |> :beam_lib.build_module()

            rebuilt
          end
        end

        IntellijElixir.Quoter.Probe.send(__ENV__, [${builds.joinToString { "compile.(\"${it.name}\", ${it.debugInfo}, ${it.docs}, :${it.docsLanguage})" }}])
        """.trimIndent(),
        60.seconds,
    )

    assertEquals("compile ${compiled.diagnostics}", OtpErlangAtom("ok"), compiled.status)

    val directory = FileUtil.createTempDirectory("compiled_library", null)
    val binaries = (compiled.messages.single() as OtpErlangList).elements().map { (it as OtpErlangBinary).binaryValue() }

    builds.zip(binaries).forEach { (build, bytes) -> File(directory, "${Module.atom(build.name)!!}.beam").writeBytes(bytes) }

    fun vfsRoot(ioDirectory: File): VirtualFile {
        VfsRootAccess.allowRootAccess(testRootDisposable, ioDirectory.path)

        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioDirectory)!!
    }

    val root = vfsRoot(directory)
    val sourceRoots = if (sources) {
        val sourceDirectory = FileUtil.createTempDirectory("compiled_library_source", null)
        builds.forEach { File(sourceDirectory, "${Module.atom(it.name)!!}.ex").writeText(source.replace("Compiled", it.name)) }

        listOf(vfsRoot(sourceDirectory))
    } else {
        emptyList()
    }
    BeamLibraryFixture.addLibrary(project, module, "compiled-library", listOf(root), sourceRoots)
    // Let the library's roots change and refresh finish before a gesture's background read waits on them.
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    return root
}

/** `o/2`'s defaults generate `o/1`, and `def o(a, b)` overrides `o/2`: `Docs` records only `o(a, b \\ 1)`. */
internal val OV_FULL_LIB = """
    defmodule Compiled do
      @doc "o original"
      def o(a, b \\ 1), do: {a, b}

      defoverridable o: 2

      @doc "o override"
      def o(a, b), do: {:override, a, b}
    end
""".trimIndent()

/** `o/2`'s defaults generate `o/1`, and `def o(a)` is written at that arity once `o/2` is overridable. */
internal val OV_FULL_GEN = """
    defmodule OvFullGen do
      @doc "o original"
      def o(a, b \\ 1), do: {a, b}

      defoverridable o: 2

      @doc "o written"
      def o(a), do: {:written, a}
    end
""".trimIndent()

/** [OV_FULL_GEN], as `Compiled`. */
internal val OV_FULL_GEN_LIB = OV_FULL_GEN.replace("defmodule OvFullGen do", "defmodule Compiled do")

/** `f/1..2`, and a macro `f/3` whose quote writes `def f(x)`: compiled, its clause is the place debug info gives that head. */
internal val SAME_NAME_MACRO_LIB = """
    defmodule Compiled do
      def f(a, b \\ 1), do: {a, b}

      defmacro f(n, o, p) do
        quote do
          def f(x), do: {x, unquote(n), unquote(o), unquote(p)}
        end
      end
    end
""".trimIndent()

/**
 * `f/1..2`, and three macros whose names Elixir writes only quoted, so their decompiled clauses unquote them: `build f/0`,
 * whose quote writes `def f(x)`, `build g/1`, whose quote writes `def g(x)`, and `build m/1`, whose quote writes
 * `defmacro m(x)`. Compiled, each macro's clause is the place debug info gives the head its quote writes.
 */
internal val UNQUOTED_NAME_MACRO_LIB = """
    defmodule Compiled do
      def f(a, b \\ 1), do: {a, b}

      defmacro unquote(:"build f")() do
        quote do
          def f(x), do: x
        end
      end

      defmacro unquote(:"build g")(n) do
        quote do
          def g(x), do: {x, unquote(n)}
        end
      end

      defmacro unquote(:"build m")(n) do
        quote do
          defmacro m(x), do: {x, unquote(n)}
        end
      end
    end
""".trimIndent()

/**
 * `Placed`, which calls [UNQUOTED_NAME_MACRO_LIB], compiled as [module], at its [macro] with [arguments], and whose
 * `calls(y)` is [call].
 */
internal fun unquotedNameMacroCaller(module: String, macro: String, call: String, arguments: String = ""): String =
    """
    defmodule Placed do
      require $module
      $module."$macro"($arguments)

      def calls(y), do: $call
    end
    """.trimIndent()

/** [SAME_NAME_MACRO_LIB]'s source, as `SameName`. */
internal val SAME_NAME_MACRO_SOURCE = SAME_NAME_MACRO_LIB.replace("defmodule Compiled do", "defmodule SameName do")

/**
 * `f/2`, whose default adds `f/1`, and a `__using__` whose quote defines `f/1` in the module that uses it: compiled, a
 * module that does `use Compiled` defines `f/1` at a head the macro's clause places.
 */
internal val PLACED_LIB = """
    defmodule Compiled do
      def f(a, b \\ 1), do: {a, b}

      defmacro __using__(_) do
        quote do
          def f(x), do: x
        end
      end
    end
""".trimIndent()
