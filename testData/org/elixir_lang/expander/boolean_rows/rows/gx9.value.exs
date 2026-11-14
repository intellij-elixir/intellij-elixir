config :app, k: (
    v = System.get_env("X")
    (fn
      w when is_boolean(is_map_key(w, :a)) -> :a
      _ -> :b
    end).(v)
  ), k1: 1
