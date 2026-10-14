defmodule UsagesImplementationDefaultsBehaviour do
  @callback ru<caret>n(term) :: term
end

defmodule UsagesImplementationDefaultsImpl do
  @behaviour UsagesImplementationDefaultsBehaviour

  @impl true
  def run(x, option \\ nil)
  def run(x, :a), do: x
  def run(x, option), do: {x, option}

  def twice(x), do: run(x, :a)
end
