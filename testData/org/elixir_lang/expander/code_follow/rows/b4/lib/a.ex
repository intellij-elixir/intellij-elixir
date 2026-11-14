defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  import H
  def r, do: v()
end
