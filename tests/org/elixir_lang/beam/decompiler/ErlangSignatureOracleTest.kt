package org.elixir_lang.beam.decompiler

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.beam.psi.stubs.CallDefinitionStub
import org.elixir_lang.beam.psi.stubs.callDefinitionStubs
import org.elixir_lang.code.sanitizeErlangVariableName
import org.elixir_lang.expander.elixirString
import org.elixir_lang.expander.failure
import org.elixir_lang.expander.stdlibEbin
import org.elixir_lang.expander.utf8
import org.elixir_lang.intellij_elixir.Quoter
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The stub's names for each exported function of the leg's `stdlib` against the names its `Docs` chunk records, on
 * OTP 27 and later, which embed that chunk. A function whose `-doc` opens with a line naming its parameters is left
 * out: the compiler strips `-doc` from the abstract code the stub reads, so that line is read from the module's
 * source. A function with a `-callback` of its name and arity is left out too, on an OTP whose `beam_doc` names the
 * function from that callback rather than its own spec.
 */
class ErlangSignatureOracleTest : PlatformTestCase() {
    fun testEachStdlibFunctionTheDocsChunkNamesIsNamedTheSameByItsStub() {
        val compiled = Quoter.compile(chunkNames(stdlibEbin().parentFile), 300.seconds)
        assertEquals("compile: ${compiled.failure}", OtpErlangAtom("ok"), compiled.status)

        val sent = compiled.messages.single()
        if (sent == OtpErlangAtom("no_embedded_docs")) return

        val (ebin, modules) = (sent as OtpErlangTuple).elements()
        val mismatches = mutableListOf<String>()
        var compared = 0

        for (module in (modules as OtpErlangList).elements()) {
            val (moduleName, functions) = (module as OtpErlangTuple).elements()
            val stubs = exportedStubs(File(utf8(ebin), "${utf8(moduleName)}.beam"))

            for (function in (functions as OtpErlangList).elements()) {
                val (name, arity, names) = (function as OtpErlangTuple).elements()
                val nameArity = "${utf8(moduleName)}:${utf8(name)}/${(arity as OtpErlangLong).intValue()}"
                val expected = (names as OtpErlangList).elements().map { sanitizeErlangVariableName(utf8(it)) }
                val stub = stubs.singleOrNull {
                    it.name == utf8(name) && it.callDefinitionClauseHeadArity() == arity.intValue()
                }

                if (stub == null) {
                    mismatches += "$nameArity: no exported stub"
                } else {
                    compared++

                    if (stub.parameters() != expected) {
                        mismatches += "$nameArity: chunk=$expected stub=${stub.parameters()}"
                    }
                }
            }
        }

        assertEmpty("stub names that differ from the Docs chunk's", mismatches)
        assertTrue("no stdlib function's Docs entry names its parameters", compared > 0)
    }

    private fun exportedStubs(beam: File): List<CallDefinitionStub<*>> =
        callDefinitionStubs(BeamFileImpl.buildFileStub(beam.readBytes(), beam.name) ?: error("no stub built for $beam"))
            .filter { it.isExported }

    private companion object {
        /**
         * `:no_embedded_docs` below OTP 27, else `{ebin, [{module, [{name, arity, names}]}]}` for each function whose
         * `Docs` signature is a call, so not `name/arity`, and whose `-doc` does not open with one, as `beam_doc`'s
         * first strategy reads it. Where `beam_doc` names a function from a same-name `-callback`, such a function is
         * left out.
         */
        fun chunkNames(stdlib: File) = """
            otp = :erlang.system_info(:otp_release) |> List.to_integer()

            result =
              if otp < 27 do
                :no_embedded_docs
              else
                lib = ${elixirString(stdlib.path)}
                src = Path.join(lib, "src")
                ebin = Path.join(lib, "ebin")

                if not File.dir?(src), do: raise("the leg's stdlib ships no source: #{src}")

                includes = [String.to_charlist(Path.join(lib, "include")), String.to_charlist(src)]

                callback_names_function? =
                  (
                    forms =
                      for form <- [
                            ~S|-module(signature_probe).|,
                            ~S|-moduledoc "Probe.".|,
                            ~S|-export([f/1]).|,
                            ~S|-callback f(Callback :: term()) -> ok.|,
                            ~S|f(Own) -> Own.|
                          ] do
                        {:ok, tokens, _} = :erl_scan.string(String.to_charlist(form))
                        {:ok, abstract} = :erl_parse.parse_form(tokens)
                        abstract
                      end

                    {:ok, _, binary} = :compile.forms(forms)
                    {:ok, {_, [{_, chunk}]}} = :beam_lib.chunks(binary, [String.to_charlist("Docs")])
                    {:docs_v1, _, _, _, _, _, entries} = :erlang.binary_to_term(chunk)

                    Enum.any?(entries, &match?({{:function, :f, 1}, _, ["f(Callback)"], _, _}, &1))
                  )

                call = fn text ->
                  with {:ok, tokens, _} <- :erl_scan.string(String.to_charlist(text <> ".")),
                       {:ok, [{:call, _, {:atom, _, name}, arguments}]} <- :erl_parse.parse_exprs(tokens) do
                    {name, arguments}
                  else
                    _ -> nil
                  end
                end

                doc_line? = fn doc, name, arity ->
                  [first | _] = doc |> IO.chardata_to_string() |> String.trim() |> String.split("\n", parts: 2)

                  match?({^name, arguments} when length(arguments) == arity, call.(first))
                end

                named_by_doc_line = fn module ->
                  path = String.to_charlist(Path.join(src, module <> ".erl"))
                  {:ok, forms} = :epp.parse_file(path, includes: includes)

                  {_, named} =
                    Enum.reduce(forms, {nil, MapSet.new()}, fn
                      {:attribute, _, :doc, doc}, {_, named} when is_list(doc) or is_binary(doc) -> {doc, named}
                      {:attribute, _, :doc, meta}, state when is_map(meta) -> state
                      {:attribute, _, :doc, _}, {_, named} -> {nil, named}
                      {:function, _, name, arity, _}, {doc, named} ->
                        {nil, if(doc && doc_line?.(doc, name, arity), do: MapSet.put(named, {name, arity}), else: named)}
                      {:attribute, _, kind, _}, {_, named} when kind in [:type, :opaque, :nominal, :callback] -> {nil, named}
                      _, state -> state
                    end)

                  named
                end

                modules =
                  # A listing, not `Path.wildcard`: a glob reads each `\` in a Windows path as an escape.
                  for name <- Enum.sort(File.ls!(ebin)),
                      String.ends_with?(name, ".beam"),
                      {:ok, {_, [{_, chunk}]}} <- [:beam_lib.chunks(String.to_charlist(Path.join(ebin, name)), [String.to_charlist("Docs")])] do
                    module = Path.rootname(name)
                    {:docs_v1, _, _, _, _, _, entries} = :erlang.binary_to_term(chunk)
                    by_doc_line = named_by_doc_line.(module)
                    callbacks =
                      for {{:callback, name, arity}, _, _, _, _} <- entries, into: MapSet.new(), do: {name, arity}

                    functions =
                      for {{:function, name, arity}, _, [signature | _], _, _} <- entries,
                          not MapSet.member?(by_doc_line, {name, arity}),
                          not (callback_names_function? and MapSet.member?(callbacks, {name, arity})),
                          {^name, arguments} <- [call.(signature)] do
                        {Atom.to_string(name), arity, for({:var, _, var} <- arguments, do: Atom.to_string(var))}
                      end

                    {module, functions}
                  end

                {ebin, modules}
              end

            IntellijElixir.Quoter.Probe.send(__ENV__, result)
        """.trimIndent()
    }
}
