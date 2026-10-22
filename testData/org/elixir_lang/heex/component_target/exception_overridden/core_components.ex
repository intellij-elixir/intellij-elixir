defmodule MyAppWeb.CoreComponents do
  defexception [:message]

  def message(assigns), do: assigns
end
