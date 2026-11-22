# Writes the `.beam` fixtures into `ebin/` beside this script. Run from the repository root:
#
#     mise exec -- elixir testData/org/elixir_lang/model/psi/function_arity_keyword_pair/import_key/generate.exs
#
# The checked-in beams were compiled with Elixir 1.20.4 on Erlang/OTP 29. `KeyBeam` keeps its debug info, so it
# decompiles from it; `KeyDefaults` has its debug info stripped, so it decompiles from its `Docs` signatures, which write
# its head with defaults.
directory = Path.join(Path.dirname(__ENV__.file), "ebin")
File.mkdir_p!(directory)

source = """
defmodule KeyBeam do
  defp f(q), do: q
  def g(q), do: f(q)
  def pub(q), do: q
  defmacrop mp(x), do: x
  def h(x), do: mp(x)
end

defmodule KeyDefaults do
  def snoc(a \\\\ nil, b \\\\ nil)
  def snoc(a, b) when is_nil(a), do: {a, b}
  def snoc(a, b), do: {a, b}
end
"""

for {module, binary} <- Code.compile_string(source) do
  written =
    if module == KeyDefaults do
      {:ok, _name, chunks} = :beam_lib.all_chunks(binary)
      {:ok, stripped} = :beam_lib.build_module(Enum.reject(chunks, fn {id, _} -> id == ~c"Dbgi" end))
      stripped
    else
      binary
    end

  File.write!(Path.join(directory, "#{module}.beam"), written)
end
