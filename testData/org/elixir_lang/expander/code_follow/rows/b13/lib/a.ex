defmodule A do
  Code.eval_quoted(quote(do: def(g, do: 1)), [], __ENV__)
end
