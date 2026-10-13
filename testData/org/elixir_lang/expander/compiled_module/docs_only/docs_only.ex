# The `.beam` beside this is `elixirc --no-debug-info docs_only.ex -o .` of it: `Docs`, but no debug info.
defmodule DocsOnly do
  def f(a, b \\ 1), do: {a, b}
end
