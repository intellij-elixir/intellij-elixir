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
 * it) renamed to the build's name, and adds the built modules' `.beam`s to the project as one library, whose directory
 * it returns.
 */
internal fun CodeInsightTestFixture.addCompiledLibrary(source: String, vararg builds: CompiledBuild): VirtualFile {
    val compiled = Quoter.compile(
        """
        compile = fn name, debug_info, docs, docs_language ->
          [{module, binary} | _] =
            ${elixirString(source)}
            |> String.replace("Compiled", name)
            |> Code.compile_string()
            |> Enum.filter(fn {module, _} -> module == Module.concat([name]) end)

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

    builds.zip(binaries).forEach { (build, bytes) -> File(directory, "Elixir.${build.name}.beam").writeBytes(bytes) }
    VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)
    val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
    BeamLibraryFixture.addLibrary(project, module, "compiled-library", listOf(root))
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
