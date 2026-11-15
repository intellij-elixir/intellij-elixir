config :app, k: (for x when hd(x) == 1 <- [nil, [1]], do: x), k1: 1
