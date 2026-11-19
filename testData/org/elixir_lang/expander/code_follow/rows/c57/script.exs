defmodule R do
  Application.put_env(:p, :k, :mine)
  Code.ensure_compiled!(W)
  @v Application.get_env(:p, :k)
  def r, do: @v
end
