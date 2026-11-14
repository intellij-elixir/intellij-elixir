config :app, k: (for x <- [System.get_env("X")], is_binary(x) and x != "", do: x), k1: 1
