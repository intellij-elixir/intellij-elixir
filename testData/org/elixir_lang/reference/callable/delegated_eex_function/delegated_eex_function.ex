defmodule DelegatedEExTemplate do
  require EEx

  EEx.function_from_string(:def, :render, "<%= a %>", @args)
end

defmodule DelegatedEExFunction do
  defdelegate render(a), to: DelegatedEExTemplate

  def caller, do: ren<caret>der(1, 2)
end
