defmodule Views do
  require EEx
  require Mix.Generator

  def plain(x), do: x
  EEx.function_from_string(:def, :greet, "<%= name %>", [:name])
  Mix.Generator.embed_text(:banner, "Banner")
  defexception [:message]
  @callback hook() :: :ok
end

defmodule User do
  import Views

  def usage do
    {plain(1), greet("x"), banner_text(), message(%{}), hook()}
  end
end
