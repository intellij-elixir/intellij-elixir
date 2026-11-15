config :app, k: (
    case nil do
      x when x or true -> :a
      _ -> :b
    end
  ), k1: 1
