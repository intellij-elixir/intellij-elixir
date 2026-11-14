defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  def r do
    require H
    H.m()
  end
end
