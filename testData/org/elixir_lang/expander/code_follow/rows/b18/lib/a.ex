defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  {v, _} = Code.eval_string("import H; v()")
  @v v
  def r, do: @v
end
