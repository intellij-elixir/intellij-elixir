defmodule BTest do
  @v if(Code.require_file("../support/h.ex", __DIR__), do: true, else: false)
  def r, do: @v
end
IO.inspect(BTest.r(), label: "ANSWER")
