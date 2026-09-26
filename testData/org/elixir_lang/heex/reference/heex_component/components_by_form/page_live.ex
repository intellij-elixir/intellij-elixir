defmodule MyAppWeb.PageLive do
  require EEx

  def button(assigns), do: assigns
  defp private_component(assigns), do: assigns
  defdelegate delegated_component(assigns), to: MyAppWeb.Components, as: :card
  EEx.function_from_string(:def, :rendered_component, "", [:assigns])
  defmacro macro_component(assigns), do: assigns
  defguard guard_component(assigns) when is_map(assigns)
  def two_arguments(assigns, other), do: {assigns, other}
end
