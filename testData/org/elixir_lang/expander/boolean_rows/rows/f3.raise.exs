config :app, k: 0

try do
  config :app, k: 5
  _ = (for x <- [System.get_env("X")], is_binary(x) and x != "", do: x)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
