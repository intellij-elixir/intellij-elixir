defmodule MyAppWeb.Templates do
  def brace(assigns), do: ~H"<p>{Application.get_env(:my_app, :heex_brace)}</p>"
  def tag(assigns), do: ~H"<p><%= Application.get_env(:my_app, :heex_tag) %></p>"
  def e(assigns), do: ~E"<p><%= Application.get_env(:my_app, :e_sigil) %></p>"
  def l(assigns), do: ~L"<p><%= Application.get_env(:my_app, :l_sigil) %></p>"

  def heredoc(assigns) do
    ~H"""
    <p>
      {Application.fetch_env!(:my_app, :heredoc_template)}
    </p>
    """
  end

  def banner(assigns) do
    ~H"""
    <%= if Application.get_env(:my_app,
             :banner) do %>
      <p>Banner</p>
    <% end %>
    """
  end

  def other(assigns), do: ~H"<p>{Application.get_env(:other_app, :heex_brace)}</p>"

  def mailer(assigns) do
    alias MyApp.Mailer

    ~H"<p>{Application.get_env(:my_app, Mailer)}</p>"
  end

  def layout(assigns), do: ~H"<p>{Application.get_env(:my_app, __MODULE__)}</p>"

  def crypto(assigns) do
    ~E"""
    <% alias :crypto, as: Crypto %>
    <%= Application.get_env(:my_app, Crypto) %>
    """
  end

  def aliased(assigns) do
    alias Application, as: App

    ~H"<p>{App.get_env(:my_app, :template_aliased)}</p>"
  end

  def aliased_away(assigns) do
    alias MyApp.Fake, as: Application

    ~H"<p>{Application.get_env(:my_app, :template_aliased_away)}</p>"
  end

  def imported(assigns) do
    import Application

    ~H"<p>{get_env(:my_app, :template_imported)}</p>"
  end
end
