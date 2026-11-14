import Config
{v, _} = Code.eval_string("x + 1", x: 1)
config :p, a: v
