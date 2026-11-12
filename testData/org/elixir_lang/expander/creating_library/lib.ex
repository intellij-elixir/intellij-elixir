# The `.beam` beside this is `elixirc lib.ex -o .` of it.
defmodule Lib do
  def make(name) do
    Module.create(name, quote(do: def(f, do: 1)), Macro.Env.location(__ENV__))
  end
end
