config :app, k: 0

try do
  config :app, k: 5
  _ =
    (
      case System.get_env("X") do
        x when is_binary(x) and x != "" -> :a
        _ -> :b
      end
    )
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
