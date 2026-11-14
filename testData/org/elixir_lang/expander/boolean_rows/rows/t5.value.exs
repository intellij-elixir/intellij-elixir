s = if System.get_env("X"), do: "a", else: "b"

try do
  _ = "v" <> s
  config :app, k: 1
rescue
  _ -> config :app, k: 2
end

config :app, k1: 1
