import Config
{_, b} = Code.eval_string(":a", x: 1)
config :p, a: b
