config :app, k: (
    v = System.get_env("X")
    case 0 do
      _ when :erlang.map_get(is_binary(v), %{true => 1}) == 1 -> :a
      _ -> :b
    end
  ), k1: 1
