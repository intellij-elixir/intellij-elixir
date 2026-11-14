defmodule A do
  @r is_list(Code.require_file("../support/h.ex", __DIR__))
  @w H.v()
  def r, do: {@r, @w}
end
