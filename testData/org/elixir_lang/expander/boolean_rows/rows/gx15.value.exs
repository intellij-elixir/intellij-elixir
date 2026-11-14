config :app, k: (
    v = System.get_env("X")
    case 0 do
      _ when is_binary(<<v::binary>>) -> :a
      _ -> :b
    end
  ), k1: 1
