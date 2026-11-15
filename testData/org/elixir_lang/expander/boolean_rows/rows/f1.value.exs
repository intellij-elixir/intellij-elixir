config :app, k: (for x when is_binary(x) <- [System.get_env("X")], do: x), k1: 1
