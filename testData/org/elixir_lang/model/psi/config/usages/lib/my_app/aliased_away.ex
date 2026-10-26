defmodule MyApp.AliasedAway do
  alias MyApp.Fake, as: Application

  def aliased_away, do: Application.get_env(:my_app, :aliased_away)
end
