if System.get_env("X"), do: config(:app, s: "a"), else: config(:app, s: "b")

try do
  _ = "v" <> Config.read_config(:app)[:s]
  config :app, k: 1
rescue
  _ -> config :app, k: 2
end

config :app, k1: 1
