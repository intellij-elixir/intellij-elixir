defmodule SingleArityMismatch do
  def snoc(a, b), do: [a | b]

  def caller do
    snoc()
  end
end
