defmodule B do
  Code.ensure_compiled!(A)
  @v H.v()
  def r, do: @v
end
