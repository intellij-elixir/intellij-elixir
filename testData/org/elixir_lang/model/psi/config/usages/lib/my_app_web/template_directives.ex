defmodule MyAppWeb.TemplateDirectives do
  def aliased_away(assigns) do
    ~E"""
    <% alias MyApp.Fake, as: Application %>
    <%= Application.get_env(:my_app, :own_aliased_away) %>
    """
  end

  def aliased(assigns) do
    ~E"""
    <% alias Application, as: App %>
    <%= App.get_env(:my_app, :own_aliased) %>
    """
  end

  def imported(assigns) do
    ~E"""
    <% import Application %>
    <%= get_env(:my_app, :own_imported) %>
    """
  end

  def parenthesised_away(assigns) do
    ~E"""
    <% alias MyApp.Fake, as: Application %>
    <%= (Application).get_env(:my_app, :own_parenthesised_away) %>
    """
  end
end
