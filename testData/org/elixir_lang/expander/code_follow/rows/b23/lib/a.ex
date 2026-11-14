defmodule A do
  x = 1
  {v, b} = Code.eval_string("y = 2; y")
  @r {x, v, b}
  def r, do: @r
end
