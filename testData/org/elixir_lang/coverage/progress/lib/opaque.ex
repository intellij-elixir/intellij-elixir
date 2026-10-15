defmodule Progress.Opaque do
  require Progress.Macros
  def g, do: 1
  Progress.Macros.wrap(1)
end
