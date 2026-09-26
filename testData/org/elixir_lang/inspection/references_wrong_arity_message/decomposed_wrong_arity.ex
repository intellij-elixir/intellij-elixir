defmodule DecomposedWrongArity do
  def snoć(a, b), do: [a | b]

  def caller do
    snoć()
  end
end
