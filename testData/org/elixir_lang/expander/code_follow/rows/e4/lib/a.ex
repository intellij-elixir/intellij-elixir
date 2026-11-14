defmodule A do
  Code.eval_file("nope.exs", __DIR__)
  @v 1
  def r, do: @v
end
