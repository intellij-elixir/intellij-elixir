defmodule NormalizedQuickDoc do
  @doc "Snocs an element onto a list."
  def snoć(a, b), do: [a | b]

  def caller do
    sn<caret>oć(1)
  end
end
