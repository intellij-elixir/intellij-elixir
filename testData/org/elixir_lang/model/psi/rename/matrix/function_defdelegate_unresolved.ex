defmodule Facade do
  @spec run(term) :: term
  defdelegate run(x), to: Missing

  def go(x), do: run(x)

  def capture, do: &run/1
end

defmodule FacadeClient do
  def call(x), do: Facade.run(x)
end
