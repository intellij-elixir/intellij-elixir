defmodule UsesMissingType do
  def render(assigns) do
    ~H"""
    <%= @spec convert(Other.<error descr="Type 'Other.missing/0' is not defined">missing</error>()) :: :ok %>
    """
  end
end
