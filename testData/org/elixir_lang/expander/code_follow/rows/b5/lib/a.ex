defmodule A do
  @r Code.require_file("../support/h.ex", __DIR__)
  def r, do: @r != nil
end
