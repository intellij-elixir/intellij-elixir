config :app, k: 0

defmodule H2 do
  def g(false, _), do: :b
  def g(true, false), do: :a
  def g(true, true), do: :c
end

try do
  config :app, k: 5
  _ = (H2.g(is_binary(System.get_env("X")), System.get_env("X") == "y"))
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
