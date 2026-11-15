import Config
{v, _} = Code.eval_quoted(quote(do: var!(x) + 1), x: 1)
config :p, a: v
