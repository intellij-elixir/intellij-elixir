import Config
{_, b} = Code.eval_string("x", x: 2, y: 1, a: 3)
config :p, a: b
