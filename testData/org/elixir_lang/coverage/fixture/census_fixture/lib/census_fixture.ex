defmodule CensusFixture do
  use CensusDep

  # A macro of this module, which no summary models
  defmacrop twice(x), do: quote(do: unquote(x) * 2)

  def wrapped(x), do: CensusDep.wrap(x)
  def doubled(x), do: twice(x)
end
