defmodule UnknownArity do
  require EEx

  def usage, do: render(1)

  @args [:a]
  EEx.function_from_string(:def, :render, "<%= a %>", @args)
  @spec render(term) :: String.t()
end
