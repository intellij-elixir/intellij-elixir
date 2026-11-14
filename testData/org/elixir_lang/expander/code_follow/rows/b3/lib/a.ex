defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  require H
  def r, do: H.m()
end
