s = for _ <- System.get_env(), reduce: "", do: (_ -> "b")

try do
  _ = "v" <> s
  config :app, k: 1
rescue
  _ -> config :app, k: 2
end

config :app, k1: 1
