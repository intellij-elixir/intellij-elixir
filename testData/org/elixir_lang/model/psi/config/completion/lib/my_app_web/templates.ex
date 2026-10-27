defmodule MyAppWeb.Templates do
  def banner(assigns), do: ~H"<p>{Application.get_env(:my_app, :template_banner)}</p>"
  def theme(assigns), do: ~H"<p>{Application.get_env(:app_template, :theme)}</p>"

  def aliased_away(assigns) do
    ~E"""
    <% alias MyApp.Fake, as: Application %>
    <%= Application.get_env(:my_app, :template_undecided) %>
    """
  end
end
