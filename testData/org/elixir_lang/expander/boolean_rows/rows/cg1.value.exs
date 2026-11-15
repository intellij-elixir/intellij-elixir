config :app, k: (
    case System.get_env("X") do
      x when is_binary(x) and x != "" -> :a
      _ -> :b
    end
  ), k1: 1
