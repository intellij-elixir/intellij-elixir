defmodule TwoSpellings do
  def snoć(a, b) when is_list(b), do: [a | b]
  def snoć(a, b), do: [a, b]

  def caller do
    snoć()
  end
end
