# Writes the `.beam` fixtures into `ebin/` beside this script. Run from the repository root:
#
#     mise exec -- elixir testData/org/elixir_lang/beam/generated_arguments/generate.exs
#
# The checked-in beams were compiled with Elixir 1.20.4 on Erlang/OTP 29.
directory = Path.join(Path.dirname(__ENV__.file), "ebin")

modules = [
  {[debug_info: false, docs: false],
   """
   defmodule NoNames do
     def f(a, b), do: {a, b}
     defmacro m(x), do: x
   end
   """},
  {[debug_info: false, docs: true],
   """
   defmodule DocsDefaults do
     @doc "snoc"
     defmacro snoc(q, x \\\\ nil), do: {q, x}

     @doc "f"
     def f(a \\\\ 1, b \\\\ 2, c), do: {a, b, c}

     # `g/1` fills both defaults and calls `g/3`, although `g/2` is the next arity up.
     @doc "Takes two."
     def g(a, b), do: {a, b}

     @doc "Takes three."
     def g(a, b \\\\ 1, c \\\\ 2), do: {a, b, c}
   end
   """}
]

File.mkdir_p!(directory)

for {options, source} <- modules do
  Code.compiler_options(options)

  for {module, binary} <- Code.compile_string(source) do
    File.write!(Path.join(directory, "#{module}.beam"), binary)
  end
end
