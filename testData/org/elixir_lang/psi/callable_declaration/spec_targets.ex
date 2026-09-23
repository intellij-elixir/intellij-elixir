defmodule SpecTargets do
  require EEx

  def usage, do: :ok

  @spec greet(term) :: String.t()
  EEx.function_from_string(:def, :greet, "<%= name %>", [:name])

  defexception [:message]
  @spec message(t) :: String.t()
end
