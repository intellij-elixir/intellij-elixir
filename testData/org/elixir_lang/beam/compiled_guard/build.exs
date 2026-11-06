# Compiled guards, one module for each place a guard's `.beam` can say what it is: `Guards`, with Docs and debug info;
# `HiddenGuards`, under `@moduledoc false`; and `UndocumentedGuards`, compiled with docs off, so only its debug info
# has the body `defguard` leaves.
#
# Run from the repository root, naming the directory to write into; the checked-in beams were compiled with
# Elixir 1.20.4 on Erlang/OTP 29:
#
#     mise exec -- elixir testData/org/elixir_lang/beam/compiled_guard/build.exs testData/org/elixir_lang/beam/compiled_guard/ebin
sources = [
  {"""
   defmodule Guards do
     @moduledoc "Guards."

     @doc "Documented."
     defguard documented(term) when is_integer(term)

     @doc false
     defguard hidden(term) when is_atom(term)

     @doc "A macro."
     defmacro plain(term), do: term

     @doc guard: true
     defmacro defaulted(term, _other \\\\ nil), do: term
   end
   """, true},
  {"""
   defmodule HiddenGuards do
     @moduledoc false

     defguard hidden_module(term) when is_atom(term)
   end
   """, true},
  {"""
   defmodule UndocumentedGuards do
     @moduledoc "Not kept."

     @doc "Not kept."
     defguard undocumented(term) when is_integer(term)

     defguard undocumented_defaulted(term, other \\\\ 1) when term == other
   end
   """, false}
]

for {source, docs} <- sources do
  Code.put_compiler_option(:docs, docs)
  [{module, binary}] = Code.compile_string(source)
  File.write!(Path.join(hd(System.argv()), "#{module}.beam"), binary)
end
