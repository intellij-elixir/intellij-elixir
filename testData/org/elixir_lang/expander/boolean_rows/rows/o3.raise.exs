config :app, k: 0

try do
  config :app, k: 5
  for {a, b} when a when hd(b) <- [{is_binary(System.get_env("X")), 1}], do: a
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
