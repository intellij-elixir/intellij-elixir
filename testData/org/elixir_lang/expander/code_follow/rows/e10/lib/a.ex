defmodule A do
  @v Code.require_file("sub.exs", Path.expand("../support", __DIR__)) |> Enum.map(&elem(&1, 0))
  def r, do: @v
end
