config :app, k: (unless is_nil(System.get_env("X")) or System.get_env("X") == "", do: :a, else: :b), k1: 1
