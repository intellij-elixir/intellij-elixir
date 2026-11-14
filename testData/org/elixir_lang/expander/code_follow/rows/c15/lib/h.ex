defmodule H do
  @x Application.get_env(:p, :a)
  def v, do: @x
end
