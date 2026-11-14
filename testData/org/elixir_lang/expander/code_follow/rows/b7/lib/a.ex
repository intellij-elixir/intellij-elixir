defmodule A do
  Code.require_file("h2.ex", __DIR__)
  @v H2.v()
  def r, do: @v
end
