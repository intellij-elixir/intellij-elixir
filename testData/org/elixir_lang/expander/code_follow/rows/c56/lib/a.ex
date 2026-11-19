defmodule A do
  Application.put_env(:p, :k, :mine)
  Code.require_file("../support/w.exs", __DIR__)
  @v Application.get_env(:p, :seen)
  def r, do: @v
end
