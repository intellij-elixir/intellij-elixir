defmodule C do
  Code.ensure_compiled(B)
  @v H.v()
  def r, do: @v
end
