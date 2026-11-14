defmodule A do
  {v, _} = Code.eval_file("../support/d.exs", __DIR__)
  @v v
  def r, do: @v
end
