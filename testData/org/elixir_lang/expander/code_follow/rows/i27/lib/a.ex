defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  def r, do: H.v()
end
