config :app, k: (
    (fn
      false -> :b
      true -> :a
    end).(is_binary(System.get_env("X")))
  ), k1: 1
