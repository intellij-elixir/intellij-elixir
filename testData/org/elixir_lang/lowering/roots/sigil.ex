defmodule T do
  def render(assigns) do
    ~H"""
    <div><%= if a, do: :b, else: {:c, 1} %></div>
    """
  end
end
