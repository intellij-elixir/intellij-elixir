config :app, k: 0

try do
  config :app, k: 5
  _ = (for {_, v} when v <- System.get_env(), do: 1)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
