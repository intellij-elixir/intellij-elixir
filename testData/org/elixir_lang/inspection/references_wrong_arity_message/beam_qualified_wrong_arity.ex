defmodule BeamQualifiedWrongArity do
  def caller do
    :queue.new(1)
  end
end
