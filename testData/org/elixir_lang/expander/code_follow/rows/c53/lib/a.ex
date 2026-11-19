defmodule A do
  @v is_nil(Code.require_file("../support/h.exs", __DIR__))
  def r, do: @v
end
