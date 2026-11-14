config :app, k: 0

try do
  config :app, k: 5
  _ = :maps.get(is_binary(System.get_env("X")), %{true => 1})
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
