defmodule MyAppWeb.PageLive do
  import MyAppWeb.CoreComponents
  import MyAppWeb.CoreComponents, only: [button: 2]

  def local_probe(assigns), do: button(assigns)
  def remote_probe(assigns), do: MyAppWeb.CoreComponents.button(assigns)
end
