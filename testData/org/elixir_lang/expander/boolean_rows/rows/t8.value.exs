b = is_binary(System.get_env("X"))

try do
  :maps.get(:k, b)
rescue
  BadMapError -> config :app, k: 1
end

config :app, k1: 1
