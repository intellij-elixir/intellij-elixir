config :app, k: (
    case System.get_env("X") do
      false -> :a
      true -> :b
    end
  ), k1: 1
