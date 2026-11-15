defmodule A do
  @r Code.require_file("../support/h.ex", __DIR__) |> Enum.map(&elem(&1, 0))
  @c Code.compile_file("../support/h.ex", __DIR__) |> Enum.map(&elem(&1, 0))
  def r, do: {@r, @c}
end
