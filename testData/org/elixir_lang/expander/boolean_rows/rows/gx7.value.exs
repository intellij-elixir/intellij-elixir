config :app, k: (
    b = is_binary(System.get_env("X"))
    case 0 do
      _ when not b -> :a
      _ -> :b
    end
  ), k1: 1
