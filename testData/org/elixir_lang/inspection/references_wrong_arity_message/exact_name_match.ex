defmodule ExactNameMatch do
  def snoc(a, b), do: [a | b]
  def snoc_all(a, b, c), do: [a, b | c]

  def caller do
    snoc()
  end
end
