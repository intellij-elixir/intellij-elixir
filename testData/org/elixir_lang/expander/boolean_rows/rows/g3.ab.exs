config :app, k: 0

try do
  for {_, v} when not is_binary(v) <- System.get_env(), do: config(:app, k: 1)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
