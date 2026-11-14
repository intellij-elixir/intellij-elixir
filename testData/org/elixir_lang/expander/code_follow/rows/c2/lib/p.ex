defmodule P do
  @a Application.get_env(:p, :a)
  @b Application.get_env(:p, :b)
  def r, do: {@a, @b}
end
