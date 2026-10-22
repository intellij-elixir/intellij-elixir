defmodule MyAppWeb.PageLive do
  defexception [:message]

  def local_probe(assigns), do: message(assigns)
end
