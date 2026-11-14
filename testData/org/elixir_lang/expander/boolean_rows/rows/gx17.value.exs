config :app, k: (
    v = System.get_env("X")
    case 0 do
      _ when is_boolean(is_bitstring(<<v::bitstring>>)) -> :a
      _ -> :b
    end
  ), k1: 1
