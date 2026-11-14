config :app, k: 0

try do
  for v when v <- (if System.get_env("X"), do: [true, "s"], else: []), do: config(:app, k: 1)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
