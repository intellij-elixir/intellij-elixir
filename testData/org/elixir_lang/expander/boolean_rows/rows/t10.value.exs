s = for x <- ["a"], System.get_env("X"), into: "", do: x

try do
  _ = "v" <> s
  config :app, k: 1
rescue
  _ -> config :app, k: 2
end

config :app, k1: 1
