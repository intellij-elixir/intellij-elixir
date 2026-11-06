# A module that writes its own `behaviour_info/1`, as a module with no `@callback` may, and calls it, so its `.beam`'s
# debug info lists it among the module's definitions.
#
# Run from the repository root, naming the directory to write into; the checked-in beam was compiled with
# Elixir 1.20.4 on Erlang/OTP 29:
#
#     mise exec -- elixir testData/org/elixir_lang/beam/compiler_output/build.exs testData/org/elixir_lang/beam/compiler_output/ebin
source = """
defmodule OwnBehaviourInfo do
  def behaviour_info(:callbacks), do: [run: 0]
  def behaviour_info(_), do: :undefined

  def callbacks, do: behaviour_info(:callbacks)
end
"""

[{module, binary}] = Code.compile_string(source)
File.write!(Path.join(hd(System.argv()), "#{module}.beam"), binary)
