config :app, k: 0

try do
  for v when v <- (for s <- String.split("t," <> System.get_env("X", ""), ","), do: s == "t" || s), do: config(:app, k: 1)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
