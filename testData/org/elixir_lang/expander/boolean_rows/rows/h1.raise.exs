config :app, k: 0

try do
  config :app, k: 5
  _ =
    (
      case is_binary(System.get_env("X")) do
        false -> :a
        true -> :b
      end
    )
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
