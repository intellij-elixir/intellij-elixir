defmodule A do
  @attr 1
  @ spaced 2
  @fun &String.upcase/1
  def f, do: [@attr, @fun.("a"), @attr.field]
end
