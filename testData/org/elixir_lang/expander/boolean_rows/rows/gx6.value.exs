config :app, k: (for v when is_boolean(is_map_key(v, :a)) <- [System.get_env("X")], do: v), k1: 1
