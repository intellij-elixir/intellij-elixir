Code.eval_file("support/h.ex")
x = H.v()
defmodule R do
  @v x
  def r, do: @v
end
