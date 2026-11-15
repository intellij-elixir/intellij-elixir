config :app, k: 0

try do
  config :app, k: 5
  _ = ((:lists.keymember(System.get_env("X"), 1, [])) and true)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
