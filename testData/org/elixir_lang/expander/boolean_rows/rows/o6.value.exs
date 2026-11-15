config :app, k: 0

try do
  for v when is_binary(v) when 1 <- (for s <- String.split(System.get_env("X", "t"), ","), do: s != "t" && s), do: config(:app, k: 7)
rescue
  _ -> :ok
end

config :app, k1: 1
