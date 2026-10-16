# A function at three arities, one of them with a default, built twice: `EveryArity` as compiled, and
# `EveryArityDocsOnly` without its debug info, as a Docs-only dependency ships. `EveryArityHidden` and
# `EveryArityHiddenDocsOnly` are the same pair for a function with a `@doc false` arity and a private one.
#
# Run from the repository root, naming the directory to write into; the checked-in beams were compiled with
# Elixir 1.20.4 on Erlang/OTP 29:
#
#     mise exec -- elixir testData/org/elixir_lang/beam/every_arity/build.exs testData/org/elixir_lang/beam/every_arity/ebin
sources = [
  {"""
   defmodule Compiled do
     @moduledoc "Every arity."

     @doc "Three."
     def push(a, b, c), do: {a, b, c}

     @doc "One or two."
     def push(a, b \\\\ nil), do: {a, b}

     @doc "Other."
     def other(a), do: a
   end
   """, ["EveryArity", "EveryArityDocsOnly"]},
  {"""
   defmodule Compiled do
     @moduledoc "Visible and hidden."

     @doc "Visible one."
     def push(a), do: a

     @doc false
     def push(a, b), do: {a, b}

     @doc "Visible three."
     def push(a, b, c), do: {a, b, c}

     defp push(a, b, c, d), do: {a, b, c, d}

     def calls(a), do: push(a, a, a, a)
   end
   """, ["EveryArityHidden", "EveryArityHiddenDocsOnly"]}
]

for {source, [with_debug_info, docs_only]} <- sources,
    {name, strip?} <- [{with_debug_info, false}, {docs_only, true}] do
  [{module, binary}] = Code.compile_string(String.replace(source, "Compiled", name))
  {:ok, _, chunks} = :beam_lib.all_chunks(binary)

  chunks =
    if strip?, do: Enum.reject(chunks, fn {id, _} -> id == ~c"Dbgi" end), else: chunks

  {:ok, built} = :beam_lib.build_module(chunks)
  File.write!(Path.join(hd(System.argv()), "#{module}.beam"), built)
end
