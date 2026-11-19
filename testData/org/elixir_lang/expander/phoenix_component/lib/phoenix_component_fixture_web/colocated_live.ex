defmodule PhoenixComponentFixtureWeb.ColocatedLive do
  use PhoenixComponentFixtureWeb, :live_view

  def mount(_params, _session, socket), do: {:ok, socket}
end
