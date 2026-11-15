defmodule A do
  {v, _} = Code.eval_string("1 +")
  @v v
  def r, do: @v
end
