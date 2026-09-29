defmodule DefaultArgumentInterval do
  def snoc(a, b, c \\ []), do: [a, b | c]

  def caller do
    snoc()
  end
end
