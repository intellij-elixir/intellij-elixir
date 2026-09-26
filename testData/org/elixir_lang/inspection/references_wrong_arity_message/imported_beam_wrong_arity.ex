defmodule ImportedBeamWrongArity do
  import :queue

  def caller(q) do
    len(q, 1)
  end
end
