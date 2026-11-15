config :app, k: 0

try do
  config :app, k: 5
  _ =
    (
      x = if System.get_env("Y"), do: true, else: is_binary(System.get_env("X"))
      x and true
    )
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
