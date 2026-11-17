defmodule A do
  Application.put_env(:p, :k, :mine)
  (fn -> if System.get_env("X"), do: Code.ensure_compiled(Enum) end).()
  @v Application.get_env(:p, :k)
  def r, do: @v
end
