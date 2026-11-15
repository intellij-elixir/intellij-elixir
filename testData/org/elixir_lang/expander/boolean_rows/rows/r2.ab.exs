config :app, k: 0

try do
  b = is_binary(System.get_env("X"))
  for {_, v} when b and v != "" <- System.get_env(), do: config(:app, k: 1)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
