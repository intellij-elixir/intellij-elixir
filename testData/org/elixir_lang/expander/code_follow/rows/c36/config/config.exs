import Config
{_, b} = Code.eval_string("1", x: 2, y: 1, a: 3)
config :p, a: b
