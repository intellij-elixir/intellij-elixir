config :app, k: 0

try do
  config :app, k: 5
  _ = (not is_binary(System.get_env("X")))
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
