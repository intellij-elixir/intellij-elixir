defmodule Contract do
  @callback f(term) :: term
  def f(a), do: a

  def run, do: f(1)
end
