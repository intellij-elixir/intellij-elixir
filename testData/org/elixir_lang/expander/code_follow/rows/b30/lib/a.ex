defmodule A do
  @v H.v()
  Code.require_file("../support/h.ex", __DIR__)
  def r, do: @v
end
