defmodule MyAppWeb.PageLive do
  def button(assigns), do: assigns

  if Code.ensure_loaded?(Kernel) do
    def gated(assigns), do: assigns
  end
end
