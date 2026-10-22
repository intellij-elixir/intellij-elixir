defmodule MyAppWeb.PageLive do
  def button(assigns), do: assigns

  defoverridable button: 1

  def button(assigns), do: {:overridden, assigns}

  def local_probe(assigns), do: button(assigns)
end
