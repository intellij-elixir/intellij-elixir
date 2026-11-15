config :app, k: (
    v = System.get_env("X")
    case 0 do
      _ when is_boolean(is_atom(is_map_key(v, :a))) -> :a
      _ -> :b
    end
  ), k1: 1
