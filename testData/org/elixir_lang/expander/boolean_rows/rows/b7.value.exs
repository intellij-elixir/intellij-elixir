config :app, k: (
    cond do
      System.get_env("X") == "y" -> :a
    end
  ), k1: 1
