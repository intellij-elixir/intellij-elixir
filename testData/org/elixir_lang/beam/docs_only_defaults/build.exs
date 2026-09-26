# A module with docs but no debug info, as a Docs-only dependency ships: two shapes of defaults.
source = """
defmodule DocsOnlyDefaults do
  @moduledoc "Docs only."

  @doc "Appends an element."
  def snoc(q, x \\\\ nil), do: {q, x}

  @doc "Joins two, both optional."
  def pair(a \\\\ nil, b \\\\ nil)
  def pair(a, b), do: {a, b}
end
"""

[{module, binary}] = Code.compile_string(source)
{:ok, _, chunks} = :beam_lib.all_chunks(binary)
{:ok, stripped} = :beam_lib.build_module(Enum.reject(chunks, fn {id, _} -> id == ~c"Dbgi" end))
File.write!(Path.join(hd(System.argv()), "#{module}.beam"), stripped)
