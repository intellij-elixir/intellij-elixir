defmodule A do
  Code.eval_file("../support/w.exs", __DIR__)
  @v Application.get_env(:p, :k)
  def r, do: @v
end
