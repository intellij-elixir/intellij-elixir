defmodule A do
  _ = Enum.map([], fn _ -> defmodule W do def f, do: :ok end end)
  @v Code.ensure_loaded?(A.W)
  def r, do: @v
end
