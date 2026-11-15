config :app, k: (if is_binary(System.get_env("X")) and System.get_env("X") != "", do: :a, else: :b), k1: 1
