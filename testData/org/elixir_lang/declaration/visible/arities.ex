defmodule Arities do
  def pick(a, b), do: {a, b}
  def pick(a), do: a
  def pad(a), do: a
  def padded(a, b), do: {a, b}
end
