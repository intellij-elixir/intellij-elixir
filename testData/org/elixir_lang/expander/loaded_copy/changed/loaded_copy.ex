# The `.beam` beside this is `elixirc loaded_copy.ex -o .` of it: `LoadedCopy.Dep` rebuilt with other exports.
defmodule LoadedCopy.Dep do
  def g, do: 1
end
