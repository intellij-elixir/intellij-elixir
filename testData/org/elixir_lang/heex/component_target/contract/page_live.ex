defmodule MyAppWeb.PageLive do
  import MyAppWeb.CoreComponents

  def local_probe(assigns), do: button(assigns)
  def remote_probe(assigns), do: MyAppWeb.CoreComponents.button(assigns)
end
