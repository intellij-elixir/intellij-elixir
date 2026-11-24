defmodule MyApp.RepoTest do
  use MyApp.ConnCase, async: true

  def render(assigns) do
    ~H"""
    <p><%= Repo.insert!(%{}) %></p>
    """
  end
end
