defmodule A do
  x = Code.require_file(:atom)
  @v x
  def r, do: @v
end
