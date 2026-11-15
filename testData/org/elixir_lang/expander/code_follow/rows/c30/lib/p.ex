defmodule P do
  @r Code.require_file("../support/h.ex", __DIR__)
  @v H.v()
  def r, do: {@r, @v}
end
