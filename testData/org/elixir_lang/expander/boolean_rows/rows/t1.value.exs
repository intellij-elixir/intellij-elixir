b = is_binary(System.get_env("X"))

r =
  try do
    :maps.get(:k, b)
  catch
    :error, reason -> reason
  end

config :app, k: r, k1: 1
