config :app, k: 0

try do
  config :app, k: 5
  _ =
    (
      cond do
        System.get_env("X") == "y" -> :a
      end
    )
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
