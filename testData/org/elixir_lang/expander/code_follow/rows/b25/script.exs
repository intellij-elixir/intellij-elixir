x = Code.compile_file("support/h.ex") |> Enum.map(&elem(&1, 0))
defmodule R do
  @v x
  def r, do: @v
end
