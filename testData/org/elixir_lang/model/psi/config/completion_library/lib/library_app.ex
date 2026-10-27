defmodule LibraryApp do
  def buffer_size, do: Application.get_env(:library_app, :buffer_size)
end
