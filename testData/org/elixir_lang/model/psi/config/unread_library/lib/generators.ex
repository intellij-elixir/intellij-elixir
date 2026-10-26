defmodule LibraryApp.Generators do
  def generators(otp_app), do: Application.get_env(otp_app, :generators)
end
