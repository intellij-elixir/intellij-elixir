config :app, k: (
    cond do
      is_binary(System.get_env("X")) and System.get_env("X") != "" -> :a
      true -> :b
    end
  ), k1: 1
