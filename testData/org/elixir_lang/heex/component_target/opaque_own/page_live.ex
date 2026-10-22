defmodule MyAppWeb.PageLive do
  require EEx

  @args [:assigns]
  EEx.function_from_string(:def, :button, "<%= @x %>", @args)

  def local_probe(assigns), do: button(assigns)
end
