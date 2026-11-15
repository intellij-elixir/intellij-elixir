config :app, k: 0
for x <- [1], System.get_env("X", ""), do: config(:app, k: x)
config :app, k1: 1
