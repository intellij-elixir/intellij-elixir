config :app, k: 0

try do
  config :app, k: 5
  _ = ((:lists.member(System.get_env("X"), ["y"])) and true)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
