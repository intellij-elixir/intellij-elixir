config :app, k: (
    v = System.get_env("X")
    case 0 do
      _ when elem({is_map_key(v, :a), 1}, 1) == 1 -> :a
      _ -> :b
    end
  ), k1: 1
