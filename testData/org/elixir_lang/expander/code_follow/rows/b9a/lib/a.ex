defmodule A do
  @c Code.compile_file("../support/h.ex", __DIR__) |> Enum.map(&elem(&1, 0))
  @r Code.require_file("../support/h.ex", __DIR__) |> Enum.map(&elem(&1, 0))
  def r, do: {@c, @r}
end
