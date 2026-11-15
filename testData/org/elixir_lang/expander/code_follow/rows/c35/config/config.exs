import Config
{_, b} = Code.eval_string("Foo", x: 2, y: 1, a: 3)
config :p, a: b
