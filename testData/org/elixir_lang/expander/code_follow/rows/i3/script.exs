Code.require_file("support/h.ex", __DIR__)
defmodule R do
  @v H.v()
  def r, do: @v
end
