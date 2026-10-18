# Writes the `.beam` of a module whose name is an atom that has to be quoted, to `ebin/` beside this script. Run from
# the repository root:
#
#     mise exec -- elixir testData/org/elixir_lang/model/psi/atom/quoted_module/generate.exs
#
# The checked-in beam was compiled with Elixir 1.20.4 on Erlang/OTP 29.
directory = Path.join(Path.dirname(__ENV__.file), "ebin")
File.mkdir_p!(directory)

for {module, binary} <- Code.compile_string("defmodule :\"a-b\" do\n  def f, do: 1\nend\n") do
  File.write!(Path.join(directory, "#{module}.beam"), binary)
end
