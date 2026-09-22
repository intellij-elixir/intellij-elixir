defmodule ExactBeamOverSourcePrefix do
  import :queue

  def lenx(a), do: a

  def caller(q) do
    len(q, 1)
  end
end
