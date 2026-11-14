first = Code.require_file("support/h.ex") |> Enum.map(&elem(&1, 0))
second = Code.require_file("support/h.ex")
third = Code.require_file("h.ex", Path.expand("support", __DIR__))
x = {first, second, third}
defmodule R do
  @v x
  def r, do: @v
end
