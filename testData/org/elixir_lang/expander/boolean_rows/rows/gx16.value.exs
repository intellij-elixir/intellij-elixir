config :app, k: (
    v = System.get_env("X")
    case 0 do
      _ when is_binary(<<is_map_key(v, :a)::binary>>) -> :a
      _ -> :b
    end
  ), k1: 1
