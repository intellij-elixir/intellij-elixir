defmodule A do
  Code.require_file("../support/a.exs", __DIR__)
  @v 1
  def r, do: @v
end
