defmodule P do
  {l, _} = Code.eval_string("__ENV__.line", [], line: 0)
  @l l
  def r, do: @l
end
