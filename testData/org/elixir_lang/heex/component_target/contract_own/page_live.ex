defmodule MyAppWeb.PageLive do
  @callback button(map) :: term

  def button(assigns), do: assigns

  def local_probe(assigns), do: button(assigns)
end
