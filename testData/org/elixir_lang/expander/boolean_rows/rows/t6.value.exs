r =
  try do
    if System.get_env("X") do
      raise "a"
    else
      raise "a"
    end
  rescue
    _ -> __STACKTRACE__
  end

config :app, k: r, k1: 1
