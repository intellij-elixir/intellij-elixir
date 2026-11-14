defmodule A do
  Code.require_file("../support/r.exs", __DIR__)
  @v 1
  def r, do: @v
end
