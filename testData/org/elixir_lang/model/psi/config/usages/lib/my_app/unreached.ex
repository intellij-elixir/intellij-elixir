defmodule MyApp.Unreached do
  alias Application, as: App

  def unreached do
    1.f()
    App.get_env(:my_app, :unreached_alias)
    Application.get_env(:my_app, :unreached_written)
    Elixir.Application.get_env(:my_app, :unreached_absolute)
    :"Elixir.Application".get_env(:my_app, :unreached_absolute)
  end
end
