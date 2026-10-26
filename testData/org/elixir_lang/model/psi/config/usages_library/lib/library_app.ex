defmodule LibraryApp do
  def timeout, do: Application.get_env(:library_app, :timeout)
end
