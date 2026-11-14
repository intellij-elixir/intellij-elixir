import Config
{_, b} = Code.eval_string("var!(z, Foo) = 1; y = 2; :ok")
config :p, a: b
