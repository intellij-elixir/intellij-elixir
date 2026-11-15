defmodule P do
  @k Application.get_env(:dep, :key)
  @o Application.get_env(:dep, :other_key)
  @a Application.get_env(:p, :a)
  def r, do: {@k, @o, @a}
end
