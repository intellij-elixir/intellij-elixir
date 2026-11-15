config :app, k: (for x when is_nil(x) or x == "" <- [System.get_env("X")], do: x), k1: 1
