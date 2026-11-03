defmodule PhoenixComponentFixtureWeb.CoreComponents do
  use Phoenix.Component

  attr :label, :string, required: true

  def badge(assigns) do
    ~H"""
    <span class="badge"><%= @label %></span>
    """
  end
end
