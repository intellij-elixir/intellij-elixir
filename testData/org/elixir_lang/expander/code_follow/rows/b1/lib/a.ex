defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  @v H.v()
  def r, do: @v
end
