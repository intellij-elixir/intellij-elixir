defmodule MyApp.Imported do
  import Application

  def imported, do: get_env(:my_app, :imported)

  @compiled compile_env(:my_app, :imported_compile_env)

  def compiled, do: @compiled
end
