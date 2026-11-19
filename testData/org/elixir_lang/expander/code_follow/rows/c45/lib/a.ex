defmodule A do
  _ = Enum.map([], fn _ -> defmodule B do Code.require_file("../support/w.exs", __DIR__) end end)
  @v Code.ensure_loaded?(W)
  def r, do: @v
end
