# Writes the `.beam` fixtures into `ebin/` beside this script. Run from the repository root:
#
#     mise exec -- elixir testData/org/elixir_lang/model/psi/function_arity_keyword_pair/import_key/generate.exs
#
# The checked-in beams were compiled with Elixir 1.20.4 on Erlang/OTP 29, with debug info, so they decompile from
# their Elixir debug info.
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
"""

for {module, binary} <- Code.compile_string(source) do
  File.write!(Path.join(directory, "#{module}.beam"), binary)
end
