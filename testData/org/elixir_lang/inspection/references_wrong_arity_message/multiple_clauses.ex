defmodule MultipleClauses do
  def snoc(a, b), do: [a | b]
  def snoc(a, b, c, d), do: [a, b, c | d]

  def caller do
    snoc()
  end
end
