defmodule A do
  Code.require_file("nope.exs", __DIR__)
  @v 1
  def r, do: @v
end
