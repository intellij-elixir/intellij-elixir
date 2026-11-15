config :app, k: 0

try do
  config :app, k: 5
  _ =
    (
      (case System.get_env("X") do
        nil -> true
        _ -> false
      end) and true
    )
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
