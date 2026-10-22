defmodule MyAppWeb.CoreComponents do
  @callback button(map) :: term

  def button(assigns), do: assigns
end
