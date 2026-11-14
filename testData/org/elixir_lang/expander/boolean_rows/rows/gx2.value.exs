config :app, k: (
    b = is_binary(System.get_env("X"))
    case 0 do
      _ when :erlang.map_get(b, %{true => 1}) == 1 -> :a
      _ -> :b
    end
  ), k1: 1
