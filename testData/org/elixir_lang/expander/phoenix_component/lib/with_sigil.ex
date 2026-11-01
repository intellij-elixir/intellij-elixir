defmodule PhoenixComponentFixture.WithSigil do
  use Phoenix.Component

  attr :label, :string, required: true

  def sigiled(assigns) do
    ~H"""
    <span><%= @label %></span>
    """
  end
end
