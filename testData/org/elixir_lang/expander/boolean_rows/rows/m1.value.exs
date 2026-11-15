defmodule M do
  if is_binary(System.get_env("X")) and System.get_env("X") != "", do: def(f, do: 1)
end

config :app, k: (function_exported?(M, :f, 0)), k1: 1
