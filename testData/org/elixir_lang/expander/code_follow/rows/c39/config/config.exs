import Config
{_, b} = Code.eval_string("v1", Enum.map(1..33, &{:"v#{&1}", &1}))
config :p, a: b
