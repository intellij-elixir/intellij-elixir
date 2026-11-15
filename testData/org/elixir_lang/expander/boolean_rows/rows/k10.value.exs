config :app, k: (
    case nil do
      x when not x -> :a
      _ -> :b
    end
  ), k1: 1
