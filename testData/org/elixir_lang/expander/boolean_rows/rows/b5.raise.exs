config :app, k: 0

try do
  config :app, k: 5
  _ = (unless is_nil(System.get_env("X")) or System.get_env("X") == "", do: :a, else: :b)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
