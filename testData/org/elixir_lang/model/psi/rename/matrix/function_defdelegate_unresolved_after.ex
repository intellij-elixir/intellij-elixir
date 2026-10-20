defmodule Facade do
  @spec fresh(term) :: term
  defdelegate fresh(x), to: Missing, as: :run

  def go(x), do: fresh(x)

  def capture, do: &fresh/1
end

defmodule FacadeClient do
  def call(x), do: Facade.fresh(x)
end
