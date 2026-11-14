config :app, k: (
    case is_binary(System.get_env("X")) do
      false -> :a
      true -> :b
    end
  ), k1: 1
