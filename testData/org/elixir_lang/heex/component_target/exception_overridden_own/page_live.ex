defmodule MyAppWeb.PageLive do
  defexception [:message]

  def message(assigns), do: assigns

  def local_probe(assigns), do: message(assigns)
end
