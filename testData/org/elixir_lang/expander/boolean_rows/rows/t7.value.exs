try do
  if System.get_env("X"), do: raise("a"), else: raise("b")
rescue
  RuntimeError -> config :app, k: 1
end

config :app, k1: 1
