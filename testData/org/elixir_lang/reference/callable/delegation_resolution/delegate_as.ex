defmodule AsImpl do
  def fetch!(x), do: x
end

defmodule AsDelegate do
  defdelegate fetch(x), to: AsImpl, as: :fetch!

  def caller, do: fe<caret>tch(1)
end
