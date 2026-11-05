# The `.beam` files beside this are `elixirc phoenix_component.ex -o .` of it.
defmodule Phoenix.Component do
  def assign(socket_or_assigns, key, value)

  def assign(%{assigns: _} = socket, key, value), do: {socket, key, value}

  def assign(assigns, key, value) when is_map(assigns), do: Map.put(assigns, key, value)

  def connected?(%{transport_pid: pid}), do: pid != nil

  defmacro slot(name), do: name

  defguard is_assigns(term) when is_map(term)

  defp hidden(x), do: x

  def visible(x), do: hidden(x)

  defdelegate render(template), to: Phoenix.Component.Renderer

  for name <- [:generated] do
    def unquote(name)(), do: unquote(name)
  end

  defmodule Nested do
    def nested(x), do: x
  end
end

defmodule Phoenix.Component.Renderer do
  def render(template), do: template
end

defmodule Phoenix.Component.Error do
  defexception [:message]
end

defmodule Phoenix.Component.OwnMessage do
  defexception [:reason]

  def message(%{reason: reason}), do: reason
end
