config :app, k: (
    case nil do
      x when x and true -> :a
      _ -> :b
    end
  ), k1: 1
