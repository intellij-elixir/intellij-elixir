defmodule R do
  @v is_list(Code.require_file("support/h.ex", __DIR__))
  def r, do: @v
end
