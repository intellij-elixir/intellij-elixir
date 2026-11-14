defmodule A do
  @x 1
  {v, _} = Code.eval_string("Module.get_attribute(A, :x)")
  @v v
  def r, do: @v
end
