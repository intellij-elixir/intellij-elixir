defmodule DecomposedIdentifier do
  def snoć(a, b), do: [a | b]

  def caller(a, b) do
    snoć(a, b)
  end
end
