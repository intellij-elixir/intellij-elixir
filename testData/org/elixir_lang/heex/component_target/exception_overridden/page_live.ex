defmodule MyAppWeb.PageLive do
  import MyAppWeb.CoreComponents

  def local_probe(assigns), do: message(assigns)
  def remote_probe(assigns), do: MyAppWeb.CoreComponents.message(assigns)
end
