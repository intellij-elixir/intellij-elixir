config :app, k: 0

try do
  config :app, k: 5
  _ = ((is_binary(System.get_env("X")) or is_nil(System.get_env("X"))) and true)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
