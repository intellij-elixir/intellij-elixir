defmodule MyAppWeb.CoreComponents do
  def button(assigns), do: assigns

  defoverridable button: 1

  def button(assigns), do: {:overridden, assigns}
end
