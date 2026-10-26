defmodule MyApp.Imported do
  import Application, only: [get_env: 2]

  def get_env(:my_app, :greeting), do: nil
end
