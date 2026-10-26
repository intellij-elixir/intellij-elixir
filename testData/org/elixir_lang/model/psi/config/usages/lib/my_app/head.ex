defmodule MyApp.Head do
  import Application, only: [get_env: 2, fetch_env: 2]

  def get_env(:my_app, :defined_head), do: nil

  defp fetch_env(:my_app, :guarded_head) when true, do: nil
end
