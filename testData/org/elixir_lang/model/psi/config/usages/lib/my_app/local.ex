defmodule MyApp.Local do
  def local, do: get_env(:my_app, :local)

  defp get_env(_app, _key), do: nil
end
