defmodule MyApp.Repo do
  def config, do: Application.get_env(:my_app, __MODULE__)
  def config(otp_app), do: Application.get_env(otp_app, __MODULE__)
end
