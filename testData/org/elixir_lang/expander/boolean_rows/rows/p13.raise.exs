config :app, k: 0

try do
  config :app, k: 5
  _ = ((:erlang.xor(is_binary(System.get_env("X")), true)) and true)
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
