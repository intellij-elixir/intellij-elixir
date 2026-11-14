defmodule A do
  {v, _} = Code.eval_string("1 + 1", :notlist)
  @v v
  def r, do: @v
end
