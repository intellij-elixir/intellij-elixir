x = Code.eval_string("x + 1", x: 1)
defmodule R do
  @v x
  def r, do: @v
end
