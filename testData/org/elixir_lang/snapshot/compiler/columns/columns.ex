defmodule Columns do
  def identifier(x́), do: String.length(x́)
  def decomposed(x), do: {"éé", String.length(x)}
  def opens_with_mark(x), do: {"́a", String.length(x)}
  def astral(x), do: {"😀", String.length(x)}
  def sigil(x), do: {~w(a b), String.length(x)}
  def unquote(:"with space")(x), do: String.length(x)
end
