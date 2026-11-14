config :app, k: 0
for x when is_binary(x) when true <- [System.get_env("X")], do: config(:app, k: 7)
config :app, k1: 1
