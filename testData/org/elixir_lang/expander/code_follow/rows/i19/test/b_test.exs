defmodule BTest do
  @v is_list(Code.require_file("../support/h.ex", __DIR__))
  @w H.v()
  def r, do: {@v, @w}
end
IO.inspect(BTest.r(), label: "ANSWER")
