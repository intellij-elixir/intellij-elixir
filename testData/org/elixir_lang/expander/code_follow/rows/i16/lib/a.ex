defmodule A do
  Code.ensure_compiled(C)
  @v C.x()
  def r, do: @v
end
