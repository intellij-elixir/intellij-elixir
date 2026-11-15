config :app, k: 0

try do
  config :app, k: 5
  _ =
    (
      defmodule M do
        if is_binary(System.get_env("X")) and System.get_env("X") != "", do: def(f, do: 1)
      end
    )
  config :app, k: 0
rescue
  _ -> :ok
end

config :app, k1: 1
