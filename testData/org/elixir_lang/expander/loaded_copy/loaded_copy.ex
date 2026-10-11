# The `.beam` files beside this are `elixirc loaded_copy.ex -o .` of it.
defmodule LoadedCopy.App do
  def f, do: 1
end

defmodule LoadedCopy.Dep do
  def f, do: 1
end

defprotocol LoadedCopy.Protocol do
  def p(t)
end
