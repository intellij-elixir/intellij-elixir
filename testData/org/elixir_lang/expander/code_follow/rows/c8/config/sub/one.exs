import Config
config :p, a: __DIR__ |> Path.basename() |> String.to_atom()
