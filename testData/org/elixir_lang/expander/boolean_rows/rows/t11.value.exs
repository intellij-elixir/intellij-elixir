b = is_binary(System.get_env("X"))

try do
  :maps.get(:k, b)
catch
  :error, {:badmap, _} -> config :app, k: 1
end

config :app, k1: 1
