defmodule Caller do
  alias MyNamespace.Referenced

  def render(assigns) do
    ~H"""
    <p><%= Referenced.changeset(%{}) %></p>
    """
  end
end
