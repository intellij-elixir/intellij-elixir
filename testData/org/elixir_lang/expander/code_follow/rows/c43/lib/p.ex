defmodule P do
  @v Application.get_env(:p, :a) == Application.get_env(:p, :b)
  def r, do: @v
end
