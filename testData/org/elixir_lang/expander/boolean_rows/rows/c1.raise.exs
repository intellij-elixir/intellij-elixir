config :app, k: 0

defmodule H do
  def g(false), do: :b
  def g(true), do: :a
end

try do
  config :app, k: 5
  _ = (H.g(is_binary(System.get_env("X"))))
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
