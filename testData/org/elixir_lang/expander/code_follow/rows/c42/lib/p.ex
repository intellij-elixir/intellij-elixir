defmodule P do
  {l, _} = Code.eval_string("\n__ENV__.line", [], line: 2_147_483_647)
  @l l
  def r, do: @l
end
