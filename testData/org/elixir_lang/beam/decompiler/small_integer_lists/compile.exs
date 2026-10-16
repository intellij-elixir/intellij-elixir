# Compiles the source file to `<output>/ebin` and writes Elixir's `Macro.to_string` of every function body of every
# module to `<output>/oracle/<Module>.<function>`.
[source, output] = System.argv()

ebin = Path.join(output, "ebin")
oracle = Path.join(output, "oracle")
File.mkdir_p!(ebin)
File.mkdir_p!(oracle)

source
|> Code.compile_file()
|> Enum.each(fn {module, binary} ->
  File.write!(Path.join(ebin, "#{module}.beam"), binary)

  {:ok, {^module, [debug_info: {:debug_info_v1, backend, data}]}} = :beam_lib.chunks(binary, [:debug_info])
  {:ok, %{definitions: definitions}} = backend.debug_info(:elixir_v1, module, data, [])

  for {{name, _arity}, _kind, _meta, [{_clause_meta, _arguments, _guards, body}]} <- definitions do
    File.write!(Path.join(oracle, "#{inspect(module)}.#{name}"), Macro.to_string(body))
  end
end)
