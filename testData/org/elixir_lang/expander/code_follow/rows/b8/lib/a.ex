defmodule A do
  @first Code.require_file("../support/m.ex", __DIR__) |> Enum.map(&elem(&1, 0))
  @second Code.require_file("../support/m.ex", __DIR__)
  @compiled Code.compile_file("../support/m.ex", __DIR__) |> Enum.map(&elem(&1, 0))
  @afterc Code.require_file("../support/m.ex", __DIR__)
  {v, b} = Code.eval_file("../support/m.ex", __DIR__)
  @ev {v, b}
  def r, do: {@first, @second, @compiled, @afterc, @ev}
end
