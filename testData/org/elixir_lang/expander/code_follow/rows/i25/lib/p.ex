defmodule P do
  @v H.v()
  @e function_exported?(H, :v, 0)
  def r, do: {@v, @e}
end
