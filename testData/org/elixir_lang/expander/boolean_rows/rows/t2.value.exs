b = is_binary(System.get_env("X"))

try do
  _ = "v" <> inspect(b)
  config :app, k: 1
rescue
  _ -> config :app, k: 2
end

config :app, k1: 1
