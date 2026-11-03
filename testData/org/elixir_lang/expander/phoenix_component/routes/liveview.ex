defmodule PhoenixComponentFixtureWeb.Routes.Liveview do
  use PhoenixComponentFixtureWeb, :live_view
  def f, do: ~p"/users"
  def render(assigns), do: assigns
end
