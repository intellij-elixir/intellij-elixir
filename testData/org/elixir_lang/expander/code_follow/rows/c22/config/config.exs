import Config
Code.eval_quoted(quote do
  import Config
  config :p, a: :from_quoted
end)
