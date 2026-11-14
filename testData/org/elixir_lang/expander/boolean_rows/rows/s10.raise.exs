config :app, k: 0

try do
  config :app, k: 5
  _ = (:erlang.is_map_key(System.get_env("X"), %{}))
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
