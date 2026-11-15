defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  if H.v() == :from_h do
    def yes, do: 1
  end
end
