defmodule A do
  Code.require_file("../support/w.exs", __DIR__)
  @v Application.get_env(:p, :k)
  def r, do: @v
end
